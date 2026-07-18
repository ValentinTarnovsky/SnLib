package com.sn.lib.teleport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure coverage of the request state machine ({@link Teleports#evaluate}) and the
 * {@link TeleportResult} predicates, without a live server.
 */
class TeleportStateMachineTest {

    @Test
    void dedupWinsOverEverything() {
        // A pending teleport rejects a second request regardless of cooldown or warmup.
        assertEquals(TeleportResult.ALREADY_PENDING, Teleports.evaluate(true, false, 5));
        assertEquals(TeleportResult.ALREADY_PENDING, Teleports.evaluate(true, true, 5));
        assertEquals(TeleportResult.ALREADY_PENDING, Teleports.evaluate(true, false, 0));
    }

    @Test
    void cooldownRejectsWhenNotPending() {
        assertEquals(TeleportResult.ON_COOLDOWN, Teleports.evaluate(false, true, 5));
        assertEquals(TeleportResult.ON_COOLDOWN, Teleports.evaluate(false, true, 0));
    }

    @Test
    void zeroWarmupTeleportsImmediately() {
        assertEquals(TeleportResult.TELEPORTED, Teleports.evaluate(false, false, 0));
    }

    @Test
    void negativeWarmupIsTreatedAsInstant() {
        assertEquals(TeleportResult.TELEPORTED, Teleports.evaluate(false, false, -5));
    }

    @Test
    void positiveWarmupStartsWarmup() {
        assertEquals(TeleportResult.WARMUP_STARTED, Teleports.evaluate(false, false, 1));
        assertEquals(TeleportResult.WARMUP_STARTED, Teleports.evaluate(false, false, 300));
    }

    @Test
    void acceptedCoversDispatchedStatesOnly() {
        assertTrue(TeleportResult.WARMUP_STARTED.accepted());
        assertTrue(TeleportResult.TELEPORTED.accepted());
        assertFalse(TeleportResult.ALREADY_PENDING.accepted());
        assertFalse(TeleportResult.ON_COOLDOWN.accepted());
        assertFalse(TeleportResult.FAILED.accepted());
    }

    @Test
    void rejectedIsTheComplementOfAccepted() {
        for (TeleportResult result : TeleportResult.values()) {
            assertEquals(!result.accepted(), result.rejected(), result.name());
        }
    }
}
