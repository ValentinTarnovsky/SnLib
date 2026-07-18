package com.sn.lib.teleport;

import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure coverage of the completion-callback gate ({@link Teleports#shouldRunOnComplete}): the
 * {@code onComplete} callback runs only after a genuinely successful teleport, never after one
 * another plugin vetoed nor one that completed exceptionally. No live server involved.
 */
class TeleportCompletionGateTest {

    private static final Consumer<Player> NOOP = player -> { };

    @Test
    void runsOnlyOnSuccessfulTeleport() {
        assertTrue(Teleports.shouldRunOnComplete(NOOP, Boolean.TRUE));
    }

    @Test
    void skipsVetoedTeleport() {
        // teleportAsync completed with success == false (a protection plugin cancelled it).
        assertFalse(Teleports.shouldRunOnComplete(NOOP, Boolean.FALSE));
    }

    @Test
    void skipsExceptionallyCompletedTeleport() {
        // teleportAsync completed exceptionally: success is null, the callback must not run.
        assertFalse(Teleports.shouldRunOnComplete(NOOP, null));
    }

    @Test
    void skipsWhenNoCallbackConfigured() {
        assertFalse(Teleports.shouldRunOnComplete(null, Boolean.TRUE));
        assertFalse(Teleports.shouldRunOnComplete(null, Boolean.FALSE));
        assertFalse(Teleports.shouldRunOnComplete(null, null));
    }
}
