package com.sn.lib.gui.internal;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryAction;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;

import com.sn.lib.gui.GuiSession;
import com.sn.lib.gui.SnGuiHolder;

/**
 * Single shared click listener owned by SnLib for every library GUI, across all
 * consumers. Inscribed in the ListenerHub; the registerEvents call happens UNIQUELY in
 * the SnLibPlugin bootstrap.
 *
 * <p>Identification is ALWAYS {@code holder instanceof SnGuiHolder}, never by title.
 * Every click and drag over a library GUI is cancelled; {@code COLLECT_TO_CURSOR}
 * (double-click stacking) is cancelled UNCONDITIONALLY before anything else, closing the
 * double-click extraction vector. Top-inventory clicks then dispatch to the per-viewer
 * {@link GuiSession}, which resolves the effective item and runs its action lists.</p>
 *
 * <p>On a natural close the session tears down (registry removal plus task handle
 * cancellation) unless it is transitioning between inventories of a page change or
 * recreation, guarded per viewer by {@link GuiSession#transitioningPage()}.</p>
 */
public final class GuiClickListener implements Listener {

    /** Clicks over a library GUI: cancel everything, then dispatch top clicks. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SnGuiHolder holder)) {
            return;
        }
        if (event.getAction() == InventoryAction.COLLECT_TO_CURSOR) {
            event.setCancelled(true);
            return;
        }
        event.setCancelled(true);
        int rawSlot = event.getRawSlot();
        if (rawSlot < 0 || rawSlot >= event.getInventory().getSize()) {
            return;
        }
        holder.session().handleClick(rawSlot, event.getClick());
    }

    /** Drags over a library GUI are always cancelled. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (event.getInventory().getHolder() instanceof SnGuiHolder) {
            event.setCancelled(true);
        }
    }

    /** Natural close: tears the session down unless it is swapping inventories. */
    @EventHandler
    public void onClose(InventoryCloseEvent event) {
        if (!(event.getInventory().getHolder() instanceof SnGuiHolder holder)) {
            return;
        }
        GuiSession session = holder.session();
        if (!session.transitioningPage()) {
            session.handleClose();
        }
    }
}
