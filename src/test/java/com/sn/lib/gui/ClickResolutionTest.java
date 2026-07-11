package com.sn.lib.gui;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickResolutionTest {

    @Test
    void shiftKeyMapsOnlyShiftClicks() {
        assertEquals(GuiItemDef.ClickKey.SHIFT_RIGHT, GuiItemDef.shiftKey(ClickType.SHIFT_RIGHT));
        assertEquals(GuiItemDef.ClickKey.SHIFT_LEFT, GuiItemDef.shiftKey(ClickType.SHIFT_LEFT));
        assertNull(GuiItemDef.shiftKey(ClickType.RIGHT));
        assertNull(GuiItemDef.shiftKey(ClickType.LEFT));
        assertNull(GuiItemDef.shiftKey(ClickType.MIDDLE));
        assertNull(GuiItemDef.shiftKey(ClickType.DOUBLE_CLICK));
        assertNull(GuiItemDef.shiftKey(null));
    }

    @Test
    void sideKeyGroupsRightFamily() {
        assertEquals(GuiItemDef.ClickKey.RIGHT, GuiItemDef.sideKey(ClickType.RIGHT));
        assertEquals(GuiItemDef.ClickKey.RIGHT, GuiItemDef.sideKey(ClickType.SHIFT_RIGHT));
    }

    @Test
    void sideKeyGroupsDoubleClickAndCreativeWithLeft() {
        assertEquals(GuiItemDef.ClickKey.LEFT, GuiItemDef.sideKey(ClickType.LEFT));
        assertEquals(GuiItemDef.ClickKey.LEFT, GuiItemDef.sideKey(ClickType.SHIFT_LEFT));
        assertEquals(GuiItemDef.ClickKey.LEFT, GuiItemDef.sideKey(ClickType.DOUBLE_CLICK));
        assertEquals(GuiItemDef.ClickKey.LEFT, GuiItemDef.sideKey(ClickType.CREATIVE));
    }

    @Test
    void sideKeyMapsMiddle() {
        assertEquals(GuiItemDef.ClickKey.MIDDLE, GuiItemDef.sideKey(ClickType.MIDDLE));
    }

    @Test
    void sideKeyNullForKeyboardAndUnknownClicks() {
        assertNull(GuiItemDef.sideKey(ClickType.NUMBER_KEY));
        assertNull(GuiItemDef.sideKey(ClickType.DROP));
        assertNull(GuiItemDef.sideKey(ClickType.CONTROL_DROP));
        assertNull(GuiItemDef.sideKey(ClickType.SWAP_OFFHAND));
        assertNull(GuiItemDef.sideKey(ClickType.UNKNOWN));
    }

    @Test
    void basicClickIsExactlyTheFourMouseClicks() {
        assertTrue(GuiItemDef.basicClick(ClickType.LEFT));
        assertTrue(GuiItemDef.basicClick(ClickType.RIGHT));
        assertTrue(GuiItemDef.basicClick(ClickType.SHIFT_LEFT));
        assertTrue(GuiItemDef.basicClick(ClickType.SHIFT_RIGHT));
        assertFalse(GuiItemDef.basicClick(ClickType.MIDDLE));
        assertFalse(GuiItemDef.basicClick(ClickType.DOUBLE_CLICK));
        assertFalse(GuiItemDef.basicClick(ClickType.NUMBER_KEY));
        assertFalse(GuiItemDef.basicClick(ClickType.DROP));
        assertFalse(GuiItemDef.basicClick(ClickType.CREATIVE));
        assertFalse(GuiItemDef.basicClick(ClickType.UNKNOWN));
        assertFalse(GuiItemDef.basicClick(null));
    }
}
