package com.sn.lib.gui.internal;

import org.bukkit.event.inventory.ClickType;
import org.bukkit.event.inventory.InventoryAction;
import org.junit.jupiter.api.Test;

import com.sn.lib.gui.PlayerInventoryPolicy;

import com.sn.lib.gui.internal.OfferRouting.Decision;
import com.sn.lib.gui.internal.OfferRouting.Zone;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Exhaustive cover of the item-offer routing table (1.28.0): what the shared click listener
 * does with every combination of policy, zone, click type, action and emptiness. The core is
 * pure by design precisely so this can be a full table instead of a sample - the listener
 * over it is a thin adapter with no branch of its own.
 *
 * <p>The load-bearing assertions are the ones that pin the OLD behaviour: a menu that
 * declares neither new key must route exactly as 1.27.0 did, for every click type, in every
 * zone. The fleet's anti-dupe guarantees rest on that, not on the new paths.</p>
 */
class OfferRoutingTest {

    private static final boolean INPUT = true;
    private static final boolean PLAIN = false;
    private static final boolean EMPTY = true;
    private static final boolean HELD = false;

    // ---------------------------------------------------------------- zones

    @Test
    void zoneSplitsTheViewAtTheTopInventorySize() {
        assertSame(Zone.TOP, OfferRouting.zone(0, 27));
        assertSame(Zone.TOP, OfferRouting.zone(26, 27));
        assertSame(Zone.BOTTOM, OfferRouting.zone(27, 27));
        assertSame(Zone.BOTTOM, OfferRouting.zone(62, 27));
        assertSame(Zone.OUTSIDE, OfferRouting.zone(-1, 27));
        assertSame(Zone.OUTSIDE, OfferRouting.zone(-999, 27));
    }

    // ------------------------------------------------- the pre-1.28.0 table

    @Test
    void aMenuThatOptedIntoNothingRoutesExactlyAsBefore() {
        for (ClickType click : ClickType.values()) {
            assertEquals(Decision.CANCEL_AND_CLICK,
                    click(PlayerInventoryPolicy.LOCKED, Zone.TOP, click, PLAIN, HELD),
                    "top click " + click);
            assertEquals(Decision.CANCEL_ONLY,
                    click(PlayerInventoryPolicy.LOCKED, Zone.BOTTOM, click, PLAIN, HELD),
                    "bottom click " + click);
            assertEquals(Decision.CANCEL_ONLY,
                    click(PlayerInventoryPolicy.LOCKED, Zone.OUTSIDE, click, PLAIN, HELD),
                    "outside click " + click);
        }
    }

    @Test
    void everyActionOverALockedMenuIsCancelledAndNeverPassesThrough() {
        for (InventoryAction action : InventoryAction.values()) {
            for (Zone zone : Zone.values()) {
                Decision decision = OfferRouting.click(PlayerInventoryPolicy.LOCKED, zone, action,
                        ClickType.LEFT, PLAIN, HELD, HELD);
                Decision expected = action == InventoryAction.COLLECT_TO_CURSOR
                        || zone != Zone.TOP ? Decision.CANCEL_ONLY : Decision.CANCEL_AND_CLICK;
                assertEquals(expected, decision, action + " in " + zone);
            }
        }
    }

    // ------------------------------------------------- COLLECT_TO_CURSOR

    @Test
    void collectToCursorIsCancelledUnconditionallyUnderBothPolicies() {
        for (PlayerInventoryPolicy policy : PlayerInventoryPolicy.values()) {
            for (Zone zone : Zone.values()) {
                assertEquals(Decision.CANCEL_ONLY,
                        OfferRouting.click(policy, zone, InventoryAction.COLLECT_TO_CURSOR,
                                ClickType.DOUBLE_CLICK, INPUT, HELD, HELD),
                        policy + " " + zone);
            }
        }
    }

    @Test
    void collectToCursorOutranksEvenAnOfferingClickOnAnInputCell() {
        assertEquals(Decision.CANCEL_ONLY,
                OfferRouting.click(PlayerInventoryPolicy.OPEN, Zone.TOP,
                        InventoryAction.COLLECT_TO_CURSOR, ClickType.LEFT, INPUT, HELD, HELD));
    }

    // -------------------------------------------------------- input cells

    @Test
    void anInputCellClickedWithAHeldStackOffersOnLeftAndRight() {
        for (PlayerInventoryPolicy policy : PlayerInventoryPolicy.values()) {
            assertEquals(Decision.OFFER_CURSOR,
                    click(policy, Zone.TOP, ClickType.LEFT, INPUT, HELD));
            assertEquals(Decision.OFFER_CURSOR,
                    click(policy, Zone.TOP, ClickType.RIGHT, INPUT, HELD));
        }
    }

    @Test
    void anInputCellClickedWithAnEmptyCursorIsAnOrdinaryClick() {
        assertEquals(Decision.CANCEL_AND_CLICK,
                click(PlayerInventoryPolicy.OPEN, Zone.TOP, ClickType.LEFT, INPUT, EMPTY));
        assertEquals(Decision.CANCEL_AND_CLICK,
                click(PlayerInventoryPolicy.OPEN, Zone.TOP, ClickType.RIGHT, INPUT, EMPTY));
    }

    @Test
    void onlyLeftAndRightOfferFromTheCursor() {
        for (ClickType click : ClickType.values()) {
            if (click == ClickType.LEFT || click == ClickType.RIGHT) {
                continue;
            }
            assertEquals(Decision.CANCEL_AND_CLICK,
                    click(PlayerInventoryPolicy.OPEN, Zone.TOP, click, INPUT, HELD),
                    "input cell clicked with " + click);
        }
    }

    @Test
    void aPlainCellNeverOffersHoweverFullTheCursorIs() {
        assertEquals(Decision.CANCEL_AND_CLICK,
                click(PlayerInventoryPolicy.OPEN, Zone.TOP, ClickType.LEFT, PLAIN, HELD));
        assertEquals(Decision.CANCEL_AND_CLICK,
                click(PlayerInventoryPolicy.OPEN, Zone.TOP, ClickType.RIGHT, PLAIN, HELD));
    }

    @Test
    void keyboardClicksOverTheMenuKeepTheirOldRouting() {
        for (ClickType click : new ClickType[] {ClickType.NUMBER_KEY, ClickType.DROP,
                ClickType.CONTROL_DROP, ClickType.SWAP_OFFHAND, ClickType.MIDDLE}) {
            assertEquals(Decision.CANCEL_AND_CLICK,
                    click(PlayerInventoryPolicy.OPEN, Zone.TOP, click, INPUT, HELD), click.name());
        }
    }

    // ------------------------------------------------ the player inventory

    @Test
    void anOpenPlayerInventoryPassesEveryNonShiftClickThrough() {
        for (ClickType click : ClickType.values()) {
            if (click == ClickType.SHIFT_LEFT || click == ClickType.SHIFT_RIGHT) {
                continue;
            }
            assertEquals(Decision.PASS_THROUGH,
                    click(PlayerInventoryPolicy.OPEN, Zone.BOTTOM, click, PLAIN, HELD),
                    "bottom click " + click);
        }
    }

    @Test
    void aShiftClickOverAnOpenPlayerInventoryOffersTheStackItAimed() {
        assertEquals(Decision.OFFER_SHIFT, OfferRouting.click(PlayerInventoryPolicy.OPEN,
                Zone.BOTTOM, InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_LEFT,
                PLAIN, HELD, HELD));
        assertEquals(Decision.OFFER_SHIFT, OfferRouting.click(PlayerInventoryPolicy.OPEN,
                Zone.BOTTOM, InventoryAction.MOVE_TO_OTHER_INVENTORY, ClickType.SHIFT_RIGHT,
                PLAIN, HELD, HELD));
    }

    @Test
    void aShiftClickOverAnEmptySlotPassesThroughInsteadOfOfferingNothing() {
        assertEquals(Decision.PASS_THROUGH, OfferRouting.click(PlayerInventoryPolicy.OPEN,
                Zone.BOTTOM, InventoryAction.NOTHING, ClickType.SHIFT_LEFT, PLAIN, HELD, EMPTY));
        assertEquals(Decision.PASS_THROUGH, OfferRouting.click(PlayerInventoryPolicy.OPEN,
                Zone.BOTTOM, InventoryAction.NOTHING, ClickType.SHIFT_RIGHT, PLAIN, HELD, EMPTY));
    }

    @Test
    void aLockedPlayerInventoryCancelsEveryClickIncludingShifts() {
        for (ClickType click : ClickType.values()) {
            assertEquals(Decision.CANCEL_ONLY,
                    click(PlayerInventoryPolicy.LOCKED, Zone.BOTTOM, click, PLAIN, HELD),
                    "bottom click " + click);
        }
    }

    @Test
    void clickingOutsideTheWindowStaysCancelledUnderBothPolicies() {
        for (PlayerInventoryPolicy policy : PlayerInventoryPolicy.values()) {
            for (ClickType click : ClickType.values()) {
                assertEquals(Decision.CANCEL_ONLY,
                        click(policy, Zone.OUTSIDE, click, PLAIN, HELD), policy + " " + click);
            }
        }
    }

    // --------------------------------------------------------------- drags

    @Test
    void aDragInsideAnOpenPlayerInventoryPassesThrough() {
        assertEquals(Decision.PASS_THROUGH,
                OfferRouting.drag(PlayerInventoryPolicy.OPEN, 0, PLAIN, HELD));
    }

    @Test
    void theSameDragIsCancelledWhileThePlayerInventoryIsLocked() {
        assertEquals(Decision.CANCEL_ONLY,
                OfferRouting.drag(PlayerInventoryPolicy.LOCKED, 0, PLAIN, HELD));
    }

    @Test
    void aDragOverExactlyOneInputCellOffersUnderBothPolicies() {
        for (PlayerInventoryPolicy policy : PlayerInventoryPolicy.values()) {
            assertEquals(Decision.OFFER_DRAG, OfferRouting.drag(policy, 1, INPUT, HELD),
                    policy.name());
        }
    }

    @Test
    void aDragOverOneCellThatIsNotAnInputCellIsCancelled() {
        assertEquals(Decision.CANCEL_ONLY,
                OfferRouting.drag(PlayerInventoryPolicy.OPEN, 1, PLAIN, HELD));
    }

    @Test
    void aDragSpreadOverTwoCellsIsCancelledEvenWhenBothAreInputCells() {
        assertEquals(Decision.CANCEL_ONLY,
                OfferRouting.drag(PlayerInventoryPolicy.OPEN, 2, INPUT, HELD));
        assertEquals(Decision.CANCEL_ONLY,
                OfferRouting.drag(PlayerInventoryPolicy.OPEN, 9, INPUT, HELD));
    }

    @Test
    void aDragWithNothingOnTheCursorNeverOffers() {
        assertEquals(Decision.CANCEL_ONLY,
                OfferRouting.drag(PlayerInventoryPolicy.OPEN, 1, INPUT, EMPTY));
    }

    /** Click with the action that click type would realistically carry outside a gather. */
    private static Decision click(PlayerInventoryPolicy policy, Zone zone, ClickType type,
                                  boolean inputSlot, boolean cursorEmpty) {
        return OfferRouting.click(policy, zone, InventoryAction.PICKUP_ALL, type, inputSlot,
                cursorEmpty, HELD);
    }
}
