package com.sn.lib.teleport.internal;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure coverage of the move listener's block-delta quick exit ({@link
 * TeleportMoveListener#sameBlock} and {@link TeleportMoveListener#blockUnchanged}), the
 * math that decides whether a move cancels a warmup, without a live server.
 */
class TeleportMoveDeltaTest {

    @Test
    void identicalBlockCoordinatesAreSameBlock() {
        assertTrue(TeleportMoveListener.sameBlock(10, 64, -5, 10, 64, -5));
    }

    @Test
    void aChangeInAnyAxisBreaksSameBlock() {
        assertFalse(TeleportMoveListener.sameBlock(10, 64, -5, 11, 64, -5));
        assertFalse(TeleportMoveListener.sameBlock(10, 64, -5, 10, 65, -5));
        assertFalse(TeleportMoveListener.sameBlock(10, 64, -5, 10, 64, -4));
    }

    @Test
    void headRotationWithinABlockDoesNotChangeTheBlock() {
        // Same block, only sub-block position and (irrelevant) yaw/pitch differ.
        Location from = new Location(null, 0.20, 64.00, 0.80, 12.0f, 3.0f);
        Location to = new Location(null, 0.85, 64.40, 0.10, 178.0f, -9.0f);
        assertTrue(TeleportMoveListener.blockUnchanged(from, to));
    }

    @Test
    void crossingABlockBoundaryChangesTheBlock() {
        Location from = new Location(null, 0.90, 64.0, 0.0);
        Location to = new Location(null, 1.10, 64.0, 0.0);
        assertFalse(TeleportMoveListener.blockUnchanged(from, to));
    }

    @Test
    void crossingIntoNegativeCoordinatesFloorsCorrectly() {
        // 0.10 floors to block 0, -0.10 floors to block -1: a real block change.
        Location from = new Location(null, 0.10, 64.0, 0.0);
        Location to = new Location(null, -0.10, 64.0, 0.0);
        assertFalse(TeleportMoveListener.blockUnchanged(from, to));
    }

    @Test
    void nullDestinationCountsAsUnchanged() {
        Location from = new Location(null, 0.0, 64.0, 0.0);
        assertTrue(TeleportMoveListener.blockUnchanged(from, null));
    }
}
