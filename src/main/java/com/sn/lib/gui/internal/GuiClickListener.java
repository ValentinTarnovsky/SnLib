package com.sn.lib.gui.internal;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.DragType;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.gui.GuiSession;
import com.sn.lib.gui.ItemOffer;
import com.sn.lib.gui.SnGuiHolder;

/**
 * Single shared click listener owned by SnLib for every library GUI, across all
 * consumers. Inscribed in the ListenerHub; the registerEvents call happens UNIQUELY in
 * the SnLibPlugin bootstrap.
 *
 * <p>Identification is ALWAYS {@code holder instanceof SnGuiHolder}, never by title. The
 * routing table itself is the pure {@link OfferRouting}; this class only reads the event,
 * asks it what to do and does it. Every click over a CELL OF THE MENU is cancelled, and
 * {@code COLLECT_TO_CURSOR} (double-click stacking) is cancelled UNCONDITIONALLY before
 * anything else, closing the double-click extraction vector. Top-inventory clicks then
 * dispatch to the per-viewer {@link GuiSession}, which resolves the effective item and runs
 * its action lists.</p>
 *
 * <p>Since 1.28.0 the same chokepoint also routes ITEM OFFERS. A cell the menu declared
 * {@code input: true} reports the stack the viewer aimed at it - by cursor click, by drag,
 * or by shift-click from a player inventory the menu declared {@code player-inventory:
 * open} - to {@link GuiSession#handleOffer}. The event is cancelled FIRST in every one of
 * those paths, which is precisely what returns the stack to the player: SnLib reads the
 * offer and never moves it. A menu that declares neither key behaves exactly as it did
 * before 1.28.0, down to which events get cancelled.</p>
 *
 * <p>On a natural close the session tears down (registry removal plus task handle
 * cancellation) unless it is transitioning between inventories of a page change or
 * recreation, guarded per viewer by {@link GuiSession#transitioningPage()}.</p>
 */
public final class GuiClickListener implements Listener {

    /** Clicks over a library GUI: routed by {@link OfferRouting#click}. */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onClick(InventoryClickEvent event) {
        if (!(event.getInventory().getHolder() instanceof SnGuiHolder holder)) {
            return;
        }
        GuiSession session = holder.session();
        int rawSlot = event.getRawSlot();
        OfferRouting.Zone zone = OfferRouting.zone(rawSlot, event.getInventory().getSize());
        ItemStack cursor = event.getCursor();
        ItemStack clicked = event.getCurrentItem();
        OfferRouting.Decision decision = OfferRouting.click(session.playerInventory(), zone,
                event.getAction(), event.getClick(),
                zone == OfferRouting.Zone.TOP && session.isInputSlot(rawSlot),
                empty(cursor), empty(clicked));
        if (decision == OfferRouting.Decision.PASS_THROUGH) {
            return;
        }
        // Cancelled BEFORE any dispatch, on every path: that is what hands the offered
        // stack back to the player and what keeps a rendered stack off the cursor.
        event.setCancelled(true);
        switch (decision) {
            case CANCEL_AND_CLICK -> session.handleClick(rawSlot, event.getClick());
            case OFFER_CURSOR -> session.handleOffer(new ItemOffer(session.viewer(),
                    ItemOffer.Kind.CURSOR, rawSlot, -1, cursor, event.getClick()));
            // getSlot() on a bottom click is the index inside the PLAYER inventory, which
            // is the one a consumer writes the remainder of a deposit back to.
            case OFFER_SHIFT -> session.handleOffer(new ItemOffer(session.viewer(),
                    ItemOffer.Kind.SHIFT_CLICK, -1, event.getSlot(), clicked, event.getClick()));
            default -> {
            }
        }
    }

    /**
     * Drags over a library GUI: routed by {@link OfferRouting#drag} over how many cells of
     * the MENU the drag covers. A drag that never leaves the player's own inventory passes
     * through under {@code player-inventory: open}; one that covers exactly one input cell
     * is an offer; everything else is cancelled, as before 1.28.0.
     */
    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onDrag(InventoryDragEvent event) {
        if (!(event.getInventory().getHolder() instanceof SnGuiHolder holder)) {
            return;
        }
        GuiSession session = holder.session();
        int topSize = event.getInventory().getSize();
        int topSlots = 0;
        int target = -1;
        for (int raw : event.getRawSlots()) {
            if (raw >= 0 && raw < topSize) {
                topSlots++;
                target = raw;
            }
        }
        ItemStack dragged = event.getOldCursor();
        OfferRouting.Decision decision = OfferRouting.drag(session.playerInventory(), topSlots,
                topSlots == 1 && session.isInputSlot(target), empty(dragged));
        if (decision == OfferRouting.Decision.PASS_THROUGH) {
            return;
        }
        // A cancelled drag restores the PRE-drag cursor, so the offered stack is intact
        // and untouched by the time the handler sees its copy.
        event.setCancelled(true);
        if (decision == OfferRouting.Decision.OFFER_DRAG) {
            session.handleOffer(new ItemOffer(session.viewer(), ItemOffer.Kind.DRAG, target, -1,
                    dragged, dragButton(event.getType())));
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

    /**
     * Mouse button behind a drag, so a DRAG offer carries the same information a CURSOR
     * offer does: a single-item drag is the right button, an even spread the left one.
     * Resolved by comparison rather than a switch, keeping the enum open across versions.
     */
    private static ClickType dragButton(DragType type) {
        return type == DragType.SINGLE ? ClickType.RIGHT : ClickType.LEFT;
    }

    /** Null-safe emptiness of a stack; the API returns AIR or null for "nothing" alike. */
    private static boolean empty(@Nullable ItemStack stack) {
        return stack == null || stack.getType().isAir();
    }
}
