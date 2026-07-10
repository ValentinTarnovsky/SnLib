package com.sn.lib.item.internal;

import java.util.ArrayList;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.block.Action;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryMoveItemEvent;
import org.bukkit.event.inventory.InventoryType;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerRespawnEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.event.EquipMethod;
import com.sn.lib.event.SnArmourEquipEvent;
import com.sn.lib.item.ItemDef;
import com.sn.lib.item.ItemRegistry;
import com.sn.lib.item.ObtainMode;
import com.sn.lib.util.InvUtil;

/**
 * Single shared listener owned by SnLib enforcing the locked mode of registered items
 * (EdToolsArmors 2.0 pattern): a locked piece is pinned to its slot and cannot leave it
 * through any of the seven extraction vectors (inventory click, drag, manual equip via
 * interact, hand swap, drop, death drops, hopper move). Inscribed in the ListenerHub;
 * the registerEvents call happens UNIQUELY in the SnLibPlugin bootstrap.
 *
 * <p>Also listens to {@link SnArmourEquipEvent} to block foreign equips of
 * {@link ObtainMode#COMMAND_ONLY} items: only {@link ItemRegistry#apply} (which marks
 * the change as programmatic for a one-tick window) may equip them.</p>
 *
 * <p>Hot-path contract: every handler quick-exits in layers through
 * {@link ItemPropertyListener#match} (null/air, hasItemMeta, PDC tag, registry).
 * Keep-on-death of unlocked items is enforced by the property listener; the death
 * vector here covers locked pieces, which never enter circulation through drops.</p>
 */
public final class LockedItemListener implements Listener {

    /** Programmatic equipment change: the slot touched and the tick it happened. */
    private record ApplyMark(EquipmentSlot slot, int tick) {
    }

    /** Server-wide static justified: transient one-tick marks, not per-consumer data. */
    private static final Map<UUID, ApplyMark> PROGRAMMATIC = new ConcurrentHashMap<>();

    /** Raw slots 5-8 are the armour slots of the own-inventory (CRAFTING) view. */
    private static final int ARMOR_RAW_FIRST = 5;
    private static final int ARMOR_RAW_LAST = 8;

    /** Transient stash bounded by dead players awaiting respawn, not per-consumer data. */
    private final Map<UUID, List<ItemStack>> keptOnDeath = new ConcurrentHashMap<>();

    /** Marks the player's slot as changed programmatically for the current tick window. */
    public static void markProgrammatic(UUID uuid, EquipmentSlot slot) {
        PROGRAMMATIC.put(uuid, new ApplyMark(slot, Bukkit.getCurrentTick()));
    }

    /**
     * Whether the slot change matches a live programmatic mark. Marks expire after one
     * tick: the primary armour source echoes a programmatic setItem on the following
     * tick, so the window covers both the synthetic event and its echo.
     */
    static boolean isProgrammatic(UUID uuid, EquipmentSlot slot) {
        ApplyMark mark = PROGRAMMATIC.get(uuid);
        if (mark == null) {
            return false;
        }
        if (Bukkit.getCurrentTick() - mark.tick() > 1) {
            PROGRAMMATIC.remove(uuid, mark);
            return false;
        }
        return mark.slot() == slot;
    }

    /** Vector 1: locked on cursor or under the click (armour slots included) never moves. */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryClick(InventoryClickEvent event) {
        if (!(event.getWhoClicked() instanceof Player player)) {
            return;
        }
        if (isLocked(event.getCurrentItem()) || isLocked(event.getCursor())
                || deniesHotbar(event, player) || deniesManualEquip(event)) {
            event.setCancelled(true);
        }
    }

    /** Vector 2: drags of a locked piece, or of a no-manual-equip piece into slots 5-8. */
    @EventHandler(ignoreCancelled = true)
    public void onInventoryDrag(InventoryDragEvent event) {
        ItemPropertyListener.Match match = ItemPropertyListener.match(event.getOldCursor());
        if (match == null) {
            return;
        }
        ItemDef def = match.def();
        if (def.locked()) {
            event.setCancelled(true);
            return;
        }
        if (!def.noManualEquip()
                || event.getInventory().getType() != InventoryType.CRAFTING) {
            return;
        }
        for (int rawSlot : event.getRawSlots()) {
            if (rawSlot >= ARMOR_RAW_FIRST && rawSlot <= ARMOR_RAW_LAST) {
                event.setCancelled(true);
                return;
            }
        }
    }

    /** Vector 3: right-click manual equip of a no-manual-equip or locked armour piece. */
    @EventHandler
    public void onInteract(PlayerInteractEvent event) {
        if (event.getAction() != Action.RIGHT_CLICK_AIR
                && event.getAction() != Action.RIGHT_CLICK_BLOCK) {
            return;
        }
        ItemStack item = event.getItem();
        ItemPropertyListener.Match match = ItemPropertyListener.match(item);
        if (match == null) {
            return;
        }
        ItemDef def = match.def();
        if ((def.noManualEquip() || def.locked()) && isArmourPiece(item)) {
            event.setUseItemInHand(Event.Result.DENY);
        }
    }

    /** Vector 4: hand swaps involving a locked piece in either hand. */
    @EventHandler(ignoreCancelled = true)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        if (isLocked(event.getMainHandItem()) || isLocked(event.getOffHandItem())) {
            event.setCancelled(true);
        }
    }

    /** Vector 5: no-drop and locked pieces stay in the inventory. */
    @EventHandler(ignoreCancelled = true)
    public void onDrop(PlayerDropItemEvent event) {
        ItemPropertyListener.Match match =
                ItemPropertyListener.match(event.getItemDrop().getItemStack());
        if (match != null && (match.def().locked() || match.def().noDrop())) {
            event.setCancelled(true);
        }
    }

    /** Vector 6: locked pieces are pulled out of the death drops. */
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
            ItemPropertyListener.Match match = ItemPropertyListener.match(stack);
            if (match == null || !match.def().locked()) {
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

    /** Vector 6, second half: the pulled locked pieces come back on respawn. */
    @EventHandler(priority = EventPriority.MONITOR)
    public void onRespawn(PlayerRespawnEvent event) {
        List<ItemStack> kept = keptOnDeath.remove(event.getPlayer().getUniqueId());
        if (kept != null) {
            InvUtil.giveItems(event.getPlayer(), kept.toArray(new ItemStack[0]));
        }
    }

    /** Vector 7: hoppers and any container-to-container move of a locked piece. */
    @EventHandler(ignoreCancelled = true)
    public void onHopperMove(InventoryMoveItemEvent event) {
        ItemStack stack = event.getItem();
        if (!stack.hasItemMeta()) {
            return;
        }
        ItemPropertyListener.Match match = ItemPropertyListener.match(stack);
        if (match != null && match.def().locked()) {
            event.setCancelled(true);
        }
    }

    /**
     * Blocks foreign equips of COMMAND_ONLY pieces (only {@link ItemRegistry#apply} may
     * equip them) and dispenser equips of locked or no-manual-equip pieces, the one
     * binding cancellable source of the synthesized event.
     */
    @EventHandler(ignoreCancelled = true)
    public void onArmourEquip(SnArmourEquipEvent event) {
        ItemPropertyListener.Match match = ItemPropertyListener.match(event.getNewPiece());
        if (match == null || isProgrammatic(event.getPlayer().getUniqueId(), event.getSlot())) {
            return;
        }
        ItemDef def = match.def();
        if (def.obtainVia() == ObtainMode.COMMAND_ONLY
                || (event.getMethod() == EquipMethod.DISPENSER
                        && (def.locked() || def.noManualEquip()))) {
            event.setCancelled(true);
        }
    }

    /** NUMBER_KEY resolves through getHotbarButton, SWAP_OFFHAND through the offhand item. */
    private static boolean deniesHotbar(InventoryClickEvent event, Player player) {
        ClickType click = event.getClick();
        if (click == ClickType.NUMBER_KEY) {
            int button = event.getHotbarButton();
            if (button < 0) {
                return false;
            }
            ItemStack hotbar = player.getInventory().getItem(button);
            return isLocked(hotbar)
                    || (event.getSlotType() == InventoryType.SlotType.ARMOR
                            && noManualEquip(hotbar));
        }
        if (click == ClickType.SWAP_OFFHAND) {
            return isLocked(player.getInventory().getItemInOffHand());
        }
        return false;
    }

    /** Cursor drop onto an armour slot, or shift-click equip in the own-inventory view. */
    private static boolean deniesManualEquip(InventoryClickEvent event) {
        if (event.getSlotType() == InventoryType.SlotType.ARMOR
                && noManualEquip(event.getCursor())) {
            return true;
        }
        return event.isShiftClick()
                && event.getInventory().getType() == InventoryType.CRAFTING
                && isArmourPiece(event.getCurrentItem())
                && noManualEquip(event.getCurrentItem());
    }

    private static boolean isLocked(@Nullable ItemStack stack) {
        ItemPropertyListener.Match match = ItemPropertyListener.match(stack);
        return match != null && match.def().locked();
    }

    private static boolean noManualEquip(@Nullable ItemStack stack) {
        ItemPropertyListener.Match match = ItemPropertyListener.match(stack);
        return match != null && match.def().noManualEquip();
    }

    /**
     * Matches equippable pieces by Material name suffix (Material treated as open: name
     * checks, never switch/EnumSet over its constants).
     */
    private static boolean isArmourPiece(@Nullable ItemStack item) {
        if (item == null || item.getType().isAir()) {
            return false;
        }
        String name = item.getType().name();
        return name.endsWith("_HELMET") || name.endsWith("_CHESTPLATE")
                || name.endsWith("_LEGGINGS") || name.endsWith("_BOOTS")
                || name.endsWith("_HEAD") || name.endsWith("_SKULL")
                || name.equals("ELYTRA") || name.equals("CARVED_PUMPKIN");
    }
}
