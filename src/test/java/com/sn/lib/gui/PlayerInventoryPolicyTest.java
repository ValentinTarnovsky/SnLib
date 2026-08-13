package com.sn.lib.gui;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Lenient resolution of the menu-level {@code player-inventory:} key (1.28.0), the same
 * shape {@code inventory-type} has used since 1.0.0: a malformed value WARNs and falls back
 * instead of throwing, and the fallback is always the closed door.
 */
class PlayerInventoryPolicyTest {

    @Test
    void anAbsentValueIsTheLockedDefaultAndSaysNothing() {
        List<String> warns = new ArrayList<>();

        assertSame(PlayerInventoryPolicy.LOCKED, PlayerInventoryPolicy.resolve("", warns::add));
        assertSame(PlayerInventoryPolicy.LOCKED, PlayerInventoryPolicy.resolve(null, warns::add));
        assertSame(PlayerInventoryPolicy.LOCKED, PlayerInventoryPolicy.resolve("   ", warns::add));
        assertEquals(List.of(), warns);
    }

    @Test
    void bothPoliciesResolveWhateverTheCasingAndPadding() {
        List<String> warns = new ArrayList<>();

        assertSame(PlayerInventoryPolicy.OPEN, PlayerInventoryPolicy.resolve("open", warns::add));
        assertSame(PlayerInventoryPolicy.OPEN, PlayerInventoryPolicy.resolve("OPEN", warns::add));
        assertSame(PlayerInventoryPolicy.OPEN, PlayerInventoryPolicy.resolve(" Open ", warns::add));
        assertSame(PlayerInventoryPolicy.LOCKED,
                PlayerInventoryPolicy.resolve("locked", warns::add));
        assertSame(PlayerInventoryPolicy.LOCKED,
                PlayerInventoryPolicy.resolve("LOCKED", warns::add));
        assertEquals(List.of(), warns);
    }

    @Test
    void anUnknownValueWarnsNamingItAndKeepsThePlayerInventoryLocked() {
        List<String> warns = new ArrayList<>();

        assertSame(PlayerInventoryPolicy.LOCKED,
                PlayerInventoryPolicy.resolve("yes-please", warns::add));

        assertEquals(1, warns.size());
        assertTrue(warns.get(0).contains("yes-please"), warns.get(0));
        assertTrue(warns.get(0).contains("LOCKED"), warns.get(0));
    }
}
