package com.sn.lib.action;

import java.util.EnumSet;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickGuardTest {

    @Test
    void rightClickOnlyExcludesShiftAndDouble() {
        assertTrue(ActionEngine.matchesExactClickGuard("right-click-only", ClickType.RIGHT));
        assertFalse(ActionEngine.matchesExactClickGuard("right-click-only", ClickType.SHIFT_RIGHT));
        assertFalse(ActionEngine.matchesExactClickGuard("right-click-only", ClickType.DOUBLE_CLICK));
        assertFalse(ActionEngine.matchesExactClickGuard("right-click-only", ClickType.CREATIVE));
    }

    @Test
    void leftClickOnlyExcludesShiftDoubleAndCreative() {
        assertTrue(ActionEngine.matchesExactClickGuard("left-click-only", ClickType.LEFT));
        assertFalse(ActionEngine.matchesExactClickGuard("left-click-only", ClickType.SHIFT_LEFT));
        assertFalse(ActionEngine.matchesExactClickGuard("left-click-only", ClickType.DOUBLE_CLICK));
        assertFalse(ActionEngine.matchesExactClickGuard("left-click-only", ClickType.CREATIVE));
    }

    @Test
    void inclusiveGuardsKeepLegacySemantics() {
        assertTrue(ActionEngine.matchesExactClickGuard("right-click", ClickType.RIGHT));
        assertTrue(ActionEngine.matchesExactClickGuard("right-click", ClickType.SHIFT_RIGHT));
        assertTrue(ActionEngine.matchesExactClickGuard("left-click", ClickType.LEFT));
        assertTrue(ActionEngine.matchesExactClickGuard("left-click", ClickType.SHIFT_LEFT));
        assertTrue(ActionEngine.matchesExactClickGuard("left-click", ClickType.DOUBLE_CLICK));
        assertTrue(ActionEngine.matchesExactClickGuard("left-click", ClickType.CREATIVE));
    }

    @Test
    void sugarGuardsMatchExactly() {
        assertTrue(ActionEngine.matchesExactClickGuard("middle-click", ClickType.MIDDLE));
        assertFalse(ActionEngine.matchesExactClickGuard("middle-click", ClickType.LEFT));
        assertTrue(ActionEngine.matchesExactClickGuard("double-click", ClickType.DOUBLE_CLICK));
        assertFalse(ActionEngine.matchesExactClickGuard("double-click", ClickType.LEFT));
        assertTrue(ActionEngine.matchesExactClickGuard("drop-click", ClickType.DROP));
        assertFalse(ActionEngine.matchesExactClickGuard("drop-click", ClickType.CONTROL_DROP));
        assertTrue(ActionEngine.matchesExactClickGuard("number-key", ClickType.NUMBER_KEY));
        assertFalse(ActionEngine.matchesExactClickGuard("number-key", ClickType.MIDDLE));
        assertTrue(ActionEngine.matchesExactClickGuard("swap-offhand", ClickType.SWAP_OFFHAND));
        assertFalse(ActionEngine.matchesExactClickGuard("swap-offhand", ClickType.DROP));
    }

    @Test
    void shiftGuardsUnchanged() {
        assertTrue(ActionEngine.matchesExactClickGuard("shift-right-click", ClickType.SHIFT_RIGHT));
        assertFalse(ActionEngine.matchesExactClickGuard("shift-right-click", ClickType.RIGHT));
        assertTrue(ActionEngine.matchesExactClickGuard("shift-left-click", ClickType.SHIFT_LEFT));
        assertFalse(ActionEngine.matchesExactClickGuard("shift-left-click", ClickType.LEFT));
    }

    @Test
    void parseClickTypesAcceptsCaseInsensitiveAndDashes() {
        assertEquals(EnumSet.of(ClickType.RIGHT), ActionEngine.parseClickTypes("right"));
        assertEquals(EnumSet.of(ClickType.NUMBER_KEY), ActionEngine.parseClickTypes("number-key"));
        assertEquals(EnumSet.of(ClickType.MIDDLE, ClickType.DROP),
                ActionEngine.parseClickTypes("MIDDLE,DROP"));
        assertEquals(EnumSet.of(ClickType.SWAP_OFFHAND),
                ActionEngine.parseClickTypes("swap_offhand"));
    }

    @Test
    void parseClickTypesRejectsInvalidWholesale() {
        assertNull(ActionEngine.parseClickTypes("RIGTH"));
        assertNull(ActionEngine.parseClickTypes(""));
        assertNull(ActionEngine.parseClickTypes("RIGHT,,LEFT"));
        assertNull(ActionEngine.parseClickTypes("RIGHT,NOPE"));
    }
}
