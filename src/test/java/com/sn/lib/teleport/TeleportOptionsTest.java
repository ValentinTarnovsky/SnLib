package com.sn.lib.teleport;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure coverage of {@link TeleportOptions} resolution: warmup clamping and tick conversion,
 * cooldown wiring, the per-call message-key overrides (the cancel bookkeeping decision) and
 * the two convenience factories, without a live server.
 */
class TeleportOptionsTest {

    @Test
    void defaultsAreAPlainWarmupTeleport() {
        TeleportOptions opts = TeleportOptions.builder().build();
        assertEquals(0, opts.warmupSeconds());
        assertEquals(0L, opts.warmupTicks());
        assertNull(opts.cooldownCategory());
        assertEquals(0, opts.cooldownSeconds());
        assertEquals(TeleportOptions.DEFAULT_WARMUP_KEY, opts.warmupKey());
        assertEquals(TeleportOptions.DEFAULT_CANCELLED_MOVE_KEY, opts.cancelledMoveKey());
        assertEquals(TeleportOptions.DEFAULT_CANCELLED_DAMAGE_KEY, opts.cancelledDamageKey());
        assertFalse(opts.silent());
        assertNull(opts.onComplete());
    }

    @Test
    void warmupConvertsSecondsToTicks() {
        assertEquals(100L, TeleportOptions.builder().warmupSeconds(5).build().warmupTicks());
        assertEquals(20L, TeleportOptions.warmup(1).warmupTicks());
    }

    @Test
    void negativeWarmupClampsToZero() {
        TeleportOptions opts = TeleportOptions.builder().warmupSeconds(-9).build();
        assertEquals(0, opts.warmupSeconds());
        assertEquals(0L, opts.warmupTicks());
    }

    @Test
    void cooldownStoresCategoryAndSeconds() {
        TeleportOptions opts = TeleportOptions.builder().cooldown("home", 30).build();
        assertEquals("home", opts.cooldownCategory());
        assertEquals(30, opts.cooldownSeconds());
    }

    @Test
    void negativeCooldownSecondsClampToZero() {
        TeleportOptions opts = TeleportOptions.builder().cooldown("home", -3).build();
        assertEquals("home", opts.cooldownCategory());
        assertEquals(0, opts.cooldownSeconds());
    }

    @Test
    void messageKeyOverridesReplaceDefaults() {
        TeleportOptions opts = TeleportOptions.builder()
                .warmupKey("home.warmup")
                .cancelledMoveKey("home.moved")
                .cancelledDamageKey("home.hurt")
                .build();
        assertEquals("home.warmup", opts.warmupKey());
        assertEquals("home.moved", opts.cancelledMoveKey());
        assertEquals("home.hurt", opts.cancelledDamageKey());
    }

    @Test
    void nullMessageKeyOverrideKeepsDefault() {
        TeleportOptions opts = TeleportOptions.builder()
                .warmupKey(null)
                .cancelledMoveKey(null)
                .cancelledDamageKey(null)
                .build();
        assertEquals(TeleportOptions.DEFAULT_WARMUP_KEY, opts.warmupKey());
        assertEquals(TeleportOptions.DEFAULT_CANCELLED_MOVE_KEY, opts.cancelledMoveKey());
        assertEquals(TeleportOptions.DEFAULT_CANCELLED_DAMAGE_KEY, opts.cancelledDamageKey());
    }

    @Test
    void silentAndCallbackAreCarried() {
        TeleportOptions opts = TeleportOptions.builder()
                .silent(true)
                .onComplete(player -> { })
                .build();
        assertTrue(opts.silent());
        assertNotNull(opts.onComplete());
    }

    @Test
    void instantIsSharedAndHasNoWarmup() {
        assertSame(TeleportOptions.instant(), TeleportOptions.instant());
        assertEquals(0, TeleportOptions.instant().warmupSeconds());
        assertNull(TeleportOptions.instant().cooldownCategory());
    }

    @Test
    void builderReuseSnapshotsIndependently() {
        TeleportOptions.Builder builder = TeleportOptions.builder().warmupSeconds(3);
        TeleportOptions first = builder.build();
        builder.warmupSeconds(9);
        TeleportOptions second = builder.build();
        assertEquals(3, first.warmupSeconds());
        assertEquals(9, second.warmupSeconds());
    }
}
