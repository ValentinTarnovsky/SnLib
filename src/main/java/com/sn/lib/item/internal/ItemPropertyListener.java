package com.sn.lib.item.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.BlockPlaceEvent;
import org.bukkit.event.entity.EntityPickupItemEvent;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.SnLib;
import com.sn.lib.item.ItemDef;
import com.sn.lib.item.ItemRegistry;
import com.sn.lib.tenant.TenantRegistry;
import com.sn.lib.util.InvUtil;

/**
 * Single shared listener owned by SnLib that enforces the behaviour properties of
 * registered items (droppable/no-drop, moveable, placeable, tradeable, despawnable,
 * keep-on-death) and runs their pickup/drop action lists. Inscribed in the ListenerHub;
 * the registerEvents call happens UNIQUELY in the SnLibPlugin bootstrap.
 *
 * <p>Owner resolution is PDC-based: a created stack carries the owner-namespaced key
 * {@code snlib_item_id}, whose namespace maps back to the consumer plugin and its tracked
 * {@link ItemRegistry} in the multi-tenant registry below.</p>
 *
 * <p>Hot-path contract (this listener sees every inventory event of the server across all
 * consumers): every handler quick-exits in layers, null/air first, then
 * {@code hasItemMeta()}, then the PDC tag, then logic. {@code ItemSpawnEvent} filters by
 * {@code hasItemMeta()} before anything else.</p>
 */
public final class ItemPropertyListener implements Listener {

    /** Item registries by owner; the tenant sweeper drops the whole key on owner disable. */
    private static final TenantRegistry<ItemRegistry> REGISTRIES = new TenantRegistry<>();

    /** Transient stash bounded by dead players awaiting respawn, not per-consumer data. */
    private final Map<UUID, List<ItemStack>> keptOnDeath = new ConcurrentHashMap<>();

    /** Tracks a context registry so PDC tags can resolve back to their owner. */
    public static void track(JavaPlugin owner, ItemRegistry registry) {
        REGISTRIES.add(owner, registry);
    }

    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        Match match = match(event.getItemDrop().getItemStack());
        if (match == null) {
            return;
        }
        ItemDef def = match.def();
        if (!def.droppable() || def.noDrop()) {
            event.setCancelled(true);
            return;
        }
        runActions(match, event.getPlayer(), def.dropActions());
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        boolean merchantTop = event.getInventory().getType() == InventoryType.MERCHANT;
        if (denies(event.getCurrentItem(), event, merchantTop)
                || denies(event.getCursor(), event, merchantTop)
                || deniesHotbar(event, player, merchantTop)) {
            event.setCancelled(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        Match match = match(event.getOldCursor());
        if (match == null) {
            return;
        }
        ItemDef def = match.def();
        if (!def.moveable()) {
            event.setCancelled(true);
            return;
        }
        if (!def.tradeable() && event.getInventory().getType() == InventoryType.MERCHANT) {
            int topSize = event.getInventory().getSize();
            for (int rawSlot : event.getRawSlots()) {
                if (rawSlot < topSize) {
                    event.setCancelled(true);
                    return;
                }
            }
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onBlockPlace(BlockPlaceEvent event) {
        Match match = match(event.getItemInHand());
        if (match != null && !match.def().placeable()) {
            event.setCancelled(true);
        }
    }

    @EventHandler(priority = EventPriority.HIGH)
    public void onDeath(PlayerDeathEvent event) {
        if (event.getKeepInventory()) {
            return;
        }
        List<ItemStack> drops = event.getDrops();
        if (drops.isEmpty()) {
            return;
        }
        List<ItemStack> kept = null;
        Iterator<ItemStack> iterator = drops.iterator();
        while (iterator.hasNext()) {
            ItemStack stack = iterator.next();
            Match match = match(stack);
            if (match == null || !match.def().keepOnDeath()) {
                continue;
            }
            if (kept == null) {
                kept = new ArrayList<>();
            }
            kept.add(stack);
            iterator.remove();
        }
        if (kept != null) {
            keptOnDeath.merge(event.getEntity().getUniqueId(), kept, (existing, incoming) -> {
                existing.addAll(incoming);
                return existing;
            });
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        List<ItemStack> kept = keptOnDeath.remove(event.getPlayer().getUniqueId());
        if (kept != null) {
            InvUtil.giveItems(event.getPlayer(), kept.toArray(new ItemStack[0]));
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (!stack.hasItemMeta()) {
            return;
        }
        Match match = match(stack);
        if (match != null && !match.def().despawnable()) {
            event.getEntity().setUnlimitedLifetime(true);
        }
    }

    @EventHandler(ignoreCancelled = true)
    public void onPickup(EntityPickupItemEvent event) {
        Match match = match(event.getItem().getItemStack());
        if (match == null) {
            return;
        }
        if (!(event.getEntity() instanceof Player player)) {
            event.setCancelled(true);
            return;
        }
        runActions(match, player, match.def().pickupActions());
    }

    /** Registered item behind a stack: owner plugin, its registry, definition and id. */
    record Match(JavaPlugin owner, ItemRegistry registry, ItemDef def, String id) {
    }

    /** Layered quick-exit resolution: null/air, hasItemMeta, PDC tag, registry lookup. */
    static @Nullable Match match(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir() || !item.hasItemMeta()) {
            return null;
        }
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            if (!ItemRegistry.TAG_KEY.equals(key.getKey())) {
                continue;
            }
            String id = pdc.get(key, PersistentDataType.STRING);
            if (id == null) {
                continue;
            }
            Match resolved = resolve(key.getNamespace(), id);
            if (resolved != null) {
                return resolved;
            }
        }
        return null;
    }

    /** Maps a PDC namespace back to the tracked registry of the owner with that name. */
    private static @Nullable Match resolve(String namespace, String id) {
        Match[] out = new Match[1];
        REGISTRIES.forEachOwner((owner, registries) -> {
            if (out[0] != null || !(owner instanceof JavaPlugin javaPlugin)) {
                return;
            }
            if (!javaPlugin.getName().toLowerCase(Locale.ROOT).equals(namespace)) {
                return;
            }
            for (ItemRegistry registry : registries) {
                ItemDef def = registry.def(id);
                if (def != null) {
                    out[0] = new Match(javaPlugin, registry, def, id);
                    return;
                }
            }
        });
        return out[0];
    }

    /** Whether the stack's definition blocks this click (moveable or merchant trading). */
    private static boolean denies(@Nullable ItemStack stack, InventoryClickEvent event,
            boolean merchantTop) {
        Match match = match(stack);
        if (match == null) {
            return false;
        }
        ItemDef def = match.def();
        if (!def.moveable()) {
            return true;
        }
        if (merchantTop && !def.tradeable()) {
            Inventory clicked = event.getClickedInventory();
            if (clicked != null && clicked.getType() == InventoryType.MERCHANT) {
                return true;
            }
            return event.getAction() == InventoryAction.MOVE_TO_OTHER_INVENTORY;
        }
        return false;
    }

    /** NUMBER_KEY resolves through getHotbarButton, SWAP_OFFHAND through the offhand item. */
    private static boolean deniesHotbar(InventoryClickEvent event, Player player,
            boolean merchantTop) {
        ClickType click = event.getClick();
        if (click == ClickType.NUMBER_KEY) {
            int button = event.getHotbarButton();
            return button >= 0
                    && denies(player.getInventory().getItem(button), event, merchantTop);
        }
        if (click == ClickType.SWAP_OFFHAND) {
            return denies(player.getInventory().getItemInOffHand(), event, merchantTop);
        }
        return false;
    }

    private static void runActions(Match match, Player player, List<String> actions) {
        if (actions.isEmpty()) {
            return;
        }
        Sn ctx = SnLib.context(match.owner());
        if (ctx != null) {
            ctx.actions().run(player, actions);
        }
    }
}
