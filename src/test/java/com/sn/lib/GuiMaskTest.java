package com.sn.lib;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sn.lib.gui.GuiMask;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;

class GuiMaskTest {

    @Test
    void keyInMultipleCellsReturnsAllSlotsRowMajor() {
        int[] slots = GuiMask.slots('d', "         ", " ddddddd ", " ddddddd ");
        assertArrayEquals(new int[] {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25},
                slots);
    }

    @Test
    void secondRowFirstColumnIsSlotNine() {
        assertArrayEquals(new int[] {9}, GuiMask.slots('x', "         ", "x"));
    }

    @Test
    void spaceKeyAlwaysReturnsEmpty() {
        assertArrayEquals(new int[0], GuiMask.slots(' ', "         ", " x ", "   "));
    }

    @Test
    void missingKeyReturnsEmpty() {
        assertArrayEquals(new int[0], GuiMask.slots('z', "abc", "def"));
    }

    @Test
    void rowsBeyondSixAreIgnored() {
        assertArrayEquals(new int[0],
                GuiMask.slots('x', "a", "a", "a", "a", "a", "a", "x"));
        assertArrayEquals(new int[] {45},
                GuiMask.slots('x', "a", "a", "a", "a", "a", "x", "x"));
    }

    @Test
    void columnsBeyondNineAreIgnored() {
        String row = "........x!";
        assertArrayEquals(new int[] {8}, GuiMask.slots('x', row));
        assertArrayEquals(new int[0], GuiMask.slots('!', row));
    }

    @Test
    void nullRowListReturnsEmpty() {
        assertArrayEquals(new int[0], GuiMask.slots('x', (List<String>) null));
        assertArrayEquals(new int[0], GuiMask.slots('x', (String[]) null));
    }

    @Test
    void nullAndEmptyRowsCountAsEmptyRows() {
        assertArrayEquals(new int[] {0, 18}, GuiMask.slots('x', Arrays.asList("x", null, "x")));
        assertArrayEquals(new int[] {9}, GuiMask.slots('x', List.of("", "x")));
    }

    @Test
    void varargsAndListOverloadsAgree() {
        assertArrayEquals(GuiMask.slots('d', List.of("a d", " dd")),
                GuiMask.slots('d', "a d", " dd"));
    }
}
