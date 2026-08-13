package com.sn.lib.gui.internal;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.gui.PlayerInventoryPolicy;

/**
 * The PURE decision core of {@link GuiClickListener} (1.28.0): given what the event says,
 * what should happen to it. Extracted so the whole routing table is exhaustively unit
 * testable without a server - the listener that consumes it is a thin adapter that only
 * reads the event, calls one of these two methods and applies the answer.
 *
 * <p>Two invariants live here, and everything else is a consequence of them:</p>
 * <ol>
 *   <li>{@code COLLECT_TO_CURSOR} (the double-click gather) is cancelled UNCONDITIONALLY,
 *       before anything else, under both policies and in every zone. It is the one action
 *       that can pull stacks out of the TOP inventory while the click that fires it lands
 *       on the bottom one.</li>
 *   <li>A click on a cell of the MENU is always cancelled - as a plain click, as an offer,
 *       it makes no difference. No rendered stack can therefore reach the cursor, which is
 *       what makes leaving the player's own inventory alone under
 *       {@link PlayerInventoryPolicy#OPEN} safe.</li>
 * </ol>
 *
 * <p>A menu that declares neither {@code player-inventory:} nor any {@code input: true}
 * cell routes to exactly what SnLib did before 1.28.0: cancel everything, dispatch top
 * clicks. That is the property the whole fleet depends on.</p>
 */
final class OfferRouting {

    /** Which inventory of the open view the event landed on. */
    enum Zone {
        /** A cell of the menu itself. */
        TOP,
        /** A slot of the viewer's own inventory. */
        BOTTOM,
        /** Outside the window entirely (the drop-outside raw slot). */
        OUTSIDE
    }

    /** What the listener must do with the event. */
    enum Decision {
        /** Leave the event alone: the viewer is using their own inventory. */
        PASS_THROUGH,
        /** Cancel and dispatch nothing. */
        CANCEL_ONLY,
        /** Cancel, then run the menu's normal click dispatch for the slot. */
        CANCEL_AND_CLICK,
        /** Cancel, then report the cursor stack as an offer on the clicked cell. */
        OFFER_CURSOR,
        /** Cancel, then report the shift-clicked player stack as an offer. */
        OFFER_SHIFT,
        /** Cancel, then report the dragged stack as an offer on the single covered cell. */
        OFFER_DRAG
    }

    private OfferRouting() {
    }

    /** Zone of a raw slot against the top inventory's size; negative raw slots are outside. */
    static Zone zone(int rawSlot, int topSize) {
        if (rawSlot < 0) {
            return Zone.OUTSIDE;
        }
        return rawSlot < topSize ? Zone.TOP : Zone.BOTTOM;
    }

    /**
     * Routing of one {@code InventoryClickEvent} over a library GUI.
     *
     * <p>TOP: an input cell clicked with a non-empty cursor by LEFT or RIGHT is an offer;
     * everything else is the pre-1.28.0 path (cancel, dispatch). An input cell clicked
     * with an EMPTY cursor therefore still runs its click actions, which is what lets one
     * cell both receive an item and act as a button - and offers are not actions, so they
     * never pass through the menu's {@code strict-clicks} gate.</p>
     *
     * <p>BOTTOM: under {@link PlayerInventoryPolicy#LOCKED} everything is cancelled with no
     * dispatch, byte-identical to before. Under {@link PlayerInventoryPolicy#OPEN} a shift
     * click over a non-empty stack is an offer (a shift-click aims INTO the menu) and
     * everything else passes through untouched - plain clicks, number keys, drops, offhand
     * swaps, middle clicks. A shift click over an EMPTY slot passes through too: vanilla
     * does nothing with it, and cancelling a no-op would only cost a client desync.</p>
     *
     * <p>OUTSIDE: cancelled under both policies, exactly as before 1.28.0.</p>
     */
    static Decision click(PlayerInventoryPolicy policy, Zone zone, @Nullable InventoryAction action,
                          @Nullable ClickType click, boolean inputSlot, boolean cursorEmpty,
                          boolean clickedEmpty) {
        if (action == InventoryAction.COLLECT_TO_CURSOR) {
            return Decision.CANCEL_ONLY;
        }
        if (zone == Zone.TOP) {
            boolean offering = inputSlot && !cursorEmpty
                    && (click == ClickType.LEFT || click == ClickType.RIGHT);
            return offering ? Decision.OFFER_CURSOR : Decision.CANCEL_AND_CLICK;
        }
        if (zone == Zone.OUTSIDE || policy != PlayerInventoryPolicy.OPEN) {
            return Decision.CANCEL_ONLY;
        }
        boolean shift = click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT;
        return shift && !clickedEmpty ? Decision.OFFER_SHIFT : Decision.PASS_THROUGH;
    }

    /**
     * Routing of one {@code InventoryDragEvent} over a library GUI, by how many cells of
     * the MENU the drag covers.
     *
     * <p>None, under {@link PlayerInventoryPolicy#OPEN}: the drag is entirely inside the
     * player's own inventory and passes through, which is what makes stack splitting work
     * again while a menu is open (before 1.28.0 it silently failed). Under
     * {@link PlayerInventoryPolicy#LOCKED} it is cancelled, as before.</p>
     *
     * <p>Exactly one, and it is an input cell with a non-empty dragged stack: an offer. The
     * cancel restores the pre-drag cursor, so the read-not-consume guarantee holds for a
     * drag exactly as it does for a click.</p>
     *
     * <p>Anything else - two or more cells of the menu, even when all of them are input
     * cells, or one cell that is not an input cell - is cancelled. A drag spread over
     * several input cells has no single target, and inventing a split would be SnLib
     * deciding how much of the stack each cell gets.</p>
     */
    static Decision drag(PlayerInventoryPolicy policy, int topSlots, boolean inputSlot,
                         boolean cursorEmpty) {
        if (topSlots <= 0) {
            return policy == PlayerInventoryPolicy.OPEN ? Decision.PASS_THROUGH
                    : Decision.CANCEL_ONLY;
        }
        if (topSlots == 1 && inputSlot && !cursorEmpty) {
            return Decision.OFFER_DRAG;
        }
        return Decision.CANCEL_ONLY;
    }
}
