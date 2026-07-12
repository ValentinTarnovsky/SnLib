package com.sn.lib.gui.internal;

import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.Event;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.entity.ItemSpawnEvent;
import org.bukkit.event.entity.PlayerDeathEvent;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.event.inventory.InventoryOpenEvent;
import org.bukkit.event.player.PlayerDropItemEvent;
import org.bukkit.event.player.PlayerInteractEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerSwapHandItemsEvent;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataContainer;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.gui.GuiManager;
import com.sn.lib.gui.SnGuiHolder;

/**
 * Single shared anti-theft listener owned by SnLib (generalization of the EdToolsArmors
 * protection): any stack stamped with the GUI marker PDC key {@code snlib_gui_item}
 * (payload {@code "<guiId>:<slot>"}, namespaced per owner plugin, so detection scans the
 * PDC keys by key name across every namespace) is a rendered GUI item and must NEVER
 * circulate outside a library GUI. Inscribed in the ListenerHub; the registerEvents call
 * happens UNIQUELY in the SnLibPlugin bootstrap.
 *
 * <p>Seven extraction vectors: inventory click (current item outside a library GUI,
 * cursor, NUMBER_KEY hotbar swap, SWAP_OFFHAND), drag, interact use, hand swap, drop
 * (the drop entity is removed, never cancelled), death drops and the {@code
 * ItemSpawnEvent} catch-all. Marked stacks are deleted on sight, not returned.</p>
 *
 * <p>Hot-path contract (this listener sees every inventory event of the server): every
 * check quick-exits in layers, null/air first, then {@code hasItemMeta()}, then the PDC
 * scan. {@code ItemSpawnEvent} filters by {@code hasItemMeta()} before anything else.
 * An optional reactive sweep (off by default) additionally purges marked stacks when an
 * inventory opens or a player joins.</p>
 */
public final class GuiProtectionListener implements Listener {

    /** Server-wide static justified: opt-in sweep toggle, not per-consumer data. */
    private static volatile boolean reactiveSweep;

    /** Enables or disables the reactive sweep on inventory open and player join. */
    public static void reactiveSweep(boolean enabled) {
        reactiveSweep = enabled;
    }

    /** Vector 1: click over a marked stack anywhere outside a library GUI deletes it. */
    @EventHandler(priority = EventPriority.HIGH)
    public void onInventoryClick(InventoryClickEvent event) {
        boolean acted = false;
        if (marked(event.getCurrentItem()) && !insideGui(event.getClickedInventory())) {
            event.setCurrentItem(null);
            acted = true;
        }
        if (marked(event.getCursor())) {
            event.getWhoClicked().setItemOnCursor(null);
            acted = true;
        }
        if (event.getWhoClicked() instanceof Player player) {
            if (event.getClick() == ClickType.NUMBER_KEY) {
                int button = event.getHotbarButton();
                if (button >= 0 && marked(player.getInventory().getItem(button))) {
                    player.getInventory().setItem(button, null);
                    acted = true;
                }
            } else if (event.getClick() == ClickType.SWAP_OFFHAND
                    && marked(player.getInventory().getItemInOffHand())) {
                player.getInventory().setItemInOffHand(null);
                acted = true;
            }
        }
        if (acted) {
            event.setCancelled(true);
        }
    }

    /** Vector 2: drags of a marked stack are cancelled. */
    @EventHandler
    public void onInventoryDrag(InventoryDragEvent event) {
        if (marked(event.getOldCursor())) {
            event.setCancelled(true);
        }
    }

    /** Vector 3: using a marked stack clears the hand and denies the interaction. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInteract(PlayerInteractEvent event) {
        if (!marked(event.getItem())) {
            return;
        }
        EquipmentSlot hand = event.getHand();
        if (hand != null) {
            event.getPlayer().getInventory().setItem(hand, null);
        }
        // Both results on DENY are equivalent to cancelling the interaction event.
        event.setUseItemInHand(Event.Result.DENY);
        event.setUseInteractedBlock(Event.Result.DENY);
    }

    /** Vector 4: hand swaps involving a marked stack cancel and delete it. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onSwapHands(PlayerSwapHandItemsEvent event) {
        boolean toMain = marked(event.getMainHandItem());
        boolean toOff = marked(event.getOffHandItem());
        if (!toMain && !toOff) {
            return;
        }
        event.setCancelled(true);
        // The item headed to the main hand lives in the current offhand, and vice versa.
        if (toMain) {
            event.getPlayer().getInventory().setItemInOffHand(null);
        }
        if (toOff) {
            event.getPlayer().getInventory().setItemInMainHand(null);
        }
    }

    /** Vector 5: dropped marked stacks vanish; the drop is NOT cancelled. */
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrop(PlayerDropItemEvent event) {
        if (marked(event.getItemDrop().getItemStack())) {
            event.getItemDrop().remove();
        }
    }

    /** Vector 6: marked stacks never reach the death drops. */
    @EventHandler
    public void onDeath(PlayerDeathEvent event) {
        if (!event.getDrops().isEmpty()) {
            event.getDrops().removeIf(GuiProtectionListener::marked);
        }
    }

    /** Vector 7, catch-all: a marked item entity is removed the moment it spawns. */
    @EventHandler
    public void onItemSpawn(ItemSpawnEvent event) {
        ItemStack stack = event.getEntity().getItemStack();
        if (!stack.hasItemMeta()) {
            return;
        }
        if (marked(stack)) {
            event.getEntity().remove();
        }
    }

    /** Reactive sweep (flag): opening a non-library inventory purges marked stacks. */
    @EventHandler
    public void onInventoryOpen(InventoryOpenEvent event) {
        if (!reactiveSweep || event.getInventory().getHolder() instanceof SnGuiHolder) {
            return;
        }
        sweep(event.getInventory());
    }

    /** Reactive sweep (flag): a joining player's inventory is purged of marked stacks. */
    @EventHandler
    public void onJoin(PlayerJoinEvent event) {
        if (reactiveSweep) {
            sweep(event.getPlayer().getInventory());
        }
    }

    private static void sweep(Inventory inventory) {
        ItemStack[] contents = inventory.getContents();
        for (int slot = 0; slot < contents.length; slot++) {
            if (marked(contents[slot])) {
                inventory.setItem(slot, null);
            }
        }
    }

    private static boolean insideGui(@Nullable Inventory inventory) {
        return inventory != null && inventory.getHolder() instanceof SnGuiHolder;
    }

    /**
     * Layered quick-exit detection of the GUI marker: null/air, hasItemMeta, then a PDC
     * key scan matching {@code snlib_gui_item} under ANY namespace (the stamp is
     * namespaced per owner plugin, so a fixed-namespace lookup would miss it).
     */
    private static boolean marked(@Nullable ItemStack stack) {
        if (stack == null || stack.getType().isAir() || !stack.hasItemMeta()) {
            return false;
        }
        ItemMeta meta = stack.getItemMeta();
        if (meta == null) {
            return false;
        }
        PersistentDataContainer pdc = meta.getPersistentDataContainer();
        for (NamespacedKey key : pdc.getKeys()) {
            if (GuiManager.ITEM_TAG.equals(key.getKey())) {
                return true;
            }
        }
        return false;
    }
}
