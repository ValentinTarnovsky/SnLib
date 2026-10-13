package com.sn.lib.gui;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Cover of the {@code paged-flow:} arrow grid: the orders an owner draws, the gap skip,
 * and the rule that every broken drawing WARNs exactly once and keeps the row-major order
 * instead of breaking the menu.
 */
class PagedFlowTest {

    /** Two rows of 7 paged cells inside a frame: slots 10-16 and 19-25. */
    private static final List<String> LAYOUT = List.of(
            "#########",
            "#ddddddd#",
            "#ddddddd#");

    private static final int[] ROW_MAJOR = {10, 11, 12, 13, 14, 15, 16, 19, 20, 21, 22, 23, 24, 25};

    @Test
    void noFlowKeepsRowMajorSilently() {
        Warnings warnings = new Warnings();
        assertArrayEquals(ROW_MAJOR, PagedFlow.order(cells(LAYOUT), List.of(), warnings));
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    @Test
    void snakeWalksRightThenLeft() {
        Warnings warnings = new Warnings();
        int[] ordered = PagedFlow.order(cells(LAYOUT), List.of(
                "         ",
                " >>>>>>v ",
                " <<<<<<< "), warnings);

        assertArrayEquals(new int[] {10, 11, 12, 13, 14, 15, 16, 25, 24, 23, 22, 21, 20, 19},
                ordered);
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    @Test
    void everyRowRightToLeftTopDown() {
        // The turn on slot 10 enters row 2 where ITS arrows start (slot 25), not under it.
        assertFlow(List.of(
                "         ",
                " v<<<<<< ",
                " <<<<<<< "), 16, 15, 14, 13, 12, 11, 10, 25, 24, 23, 22, 21, 20, 19);
    }

    @Test
    void everyRowRightToLeftBottomUp() {
        assertFlow(List.of(
                "         ",
                " <<<<<<< ",
                " ^<<<<<< "), 25, 24, 23, 22, 21, 20, 19, 16, 15, 14, 13, 12, 11, 10);
    }

    @Test
    void everyRowLeftToRightBottomUp() {
        assertFlow(List.of(
                "         ",
                " >>>>>>> ",
                " >>>>>>^ "), 19, 20, 21, 22, 23, 24, 25, 10, 11, 12, 13, 14, 15, 16);
    }

    @Test
    void drawnDefaultMatchesRowMajor() {
        assertFlow(List.of(
                "         ",
                " >>>>>>v ",
                " >>>>>>> "), ROW_MAJOR);
    }

    @Test
    void columnsTopDownThenRight() {
        Warnings warnings = new Warnings();
        int[] ordered = PagedFlow.order(cells(List.of("ddd", "ddd", "ddd")), List.of(
                "vvv",
                "vvv",
                ">> "), warnings);

        assertArrayEquals(new int[] {0, 9, 18, 1, 10, 19, 2, 11, 20}, ordered);
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    @Test
    void columnsZigZag() {
        Warnings warnings = new Warnings();
        int[] ordered = PagedFlow.order(cells(List.of("ddd", "ddd", "ddd")), List.of(
                "v>v",
                "v^v",
                ">^ "), warnings);

        assertArrayEquals(new int[] {0, 9, 18, 19, 10, 1, 2, 11, 20}, ordered);
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    @Test
    void spiralInward() {
        Warnings warnings = new Warnings();
        int[] ordered = PagedFlow.order(cells(List.of("dddd", "dddd", "dddd", "dddd")),
                List.of(
                        ">>>v",
                        ">>vv",
                        "^ <v",
                        "^<<<"), warnings);

        assertArrayEquals(new int[] {0, 1, 2, 3, 12, 21, 30, 29, 28, 27, 18, 9, 10, 11, 20, 19},
                ordered);
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    @Test
    void arrowsSkipNonPagedCells() {
        Warnings warnings = new Warnings();
        int[] ordered = PagedFlow.order(cells(List.of("dd#dd")), List.of("<< <<"), warnings);

        // Slot 3 '<' jumps the filler on slot 2 and lands on slot 1.
        assertArrayEquals(new int[] {4, 3, 1, 0}, ordered);
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    @Test
    void missingRowReadsAsBlank() {
        Warnings warnings = new Warnings();
        // Row 2 is missing, so slots 9 and 10 read blank: 0 -> 1 -> 10 ends before slot 9.
        int[] ordered = PagedFlow.order(cells(List.of("dd", "dd")), List.of(">v"), warnings);

        assertArrayEquals(new int[] {0, 1, 9, 10}, ordered);
        assertEquals(1, warnings.messages.size(), () -> "warnings: " + warnings.messages);
    }

    @Test
    void notAnArrowFallsBack() {
        assertFallback(List.of("         ", " >>>x>>v ", " <<<<<<< "), "is not an arrow");
    }

    @Test
    void twoArrowsIntoOneCellFallBack() {
        assertFallback(List.of("         ", " >>>><<< ", " <<<<<<< "), "paged-flow ignored");
    }

    @Test
    void twoSeparateLinesFallBack() {
        assertFallback(List.of("         ", " >>>>>>> ", " <<<<<<< "), "is not on the path");
    }

    @Test
    void loopWithoutStartFallsBack() {
        Warnings warnings = new Warnings();
        int[] ordered = PagedFlow.order(cells(List.of("dd", "dd")), List.of(">v", "^<"),
                warnings);

        assertArrayEquals(new int[] {0, 1, 9, 10}, ordered);
        assertEquals(1, warnings.messages.size(), () -> "warnings: " + warnings.messages);
        assertTrue(warnings.messages.get(0).contains("loops"), warnings.messages.get(0));
    }

    @Test
    void unreachedIslandFallsBack() {
        Warnings warnings = new Warnings();
        // 0 -> 1 is the path; 9, 10, 19 and 18 loop among themselves and nothing reaches them.
        int[] ordered = PagedFlow.order(cells(List.of("dd", "dd", "dd")),
                List.of("> ", ">v", "^<"), warnings);

        assertArrayEquals(new int[] {0, 1, 9, 10, 18, 19}, ordered);
        assertEquals(1, warnings.messages.size(), () -> "warnings: " + warnings.messages);
        assertTrue(warnings.messages.get(0).contains("slot 9 is not on the path"),
                warnings.messages.get(0));
    }

    @Test
    void strayArrowWarnsButTheFlowStillApplies() {
        Warnings warnings = new Warnings();
        int[] ordered = PagedFlow.order(cells(LAYOUT), List.of(
                ">        ",
                " >>>>>>v ",
                " <<<<<<< "), warnings);

        assertArrayEquals(new int[] {10, 11, 12, 13, 14, 15, 16, 25, 24, 23, 22, 21, 20, 19},
                ordered);
        assertEquals(1, warnings.messages.size(), () -> "warnings: " + warnings.messages);
        assertTrue(warnings.messages.get(0).contains("slot 0"), warnings.messages.get(0));
    }

    @Test
    void flowWithoutPagedKeyWarnsAndIsIgnored() {
        Warnings warnings = new Warnings();
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("paged-flow", List.of(" >>>>>>v "));

        int[] result = GuiDef.parsePagedFlow(root, new int[0], true, warnings);

        assertEquals(0, result.length);
        assertEquals(1, warnings.messages.size(), () -> "warnings: " + warnings.messages);
        assertTrue(warnings.messages.get(0).contains("without a paged-key"),
                warnings.messages.get(0));
    }

    @Test
    void flowOnAnInvalidPagedKeyAddsNoSecondWarning() {
        Warnings warnings = new Warnings();
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("paged-key", "z");
        root.set("paged-flow", List.of(" >>>>>>v "));

        GuiDef.parsePagedFlow(root, new int[0], true, warnings);

        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    @Test
    void flowThroughGuiDefReordersPagedSlots() {
        Warnings warnings = new Warnings();
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("paged-flow", List.of("         ", " >>>>>>v ", " <<<<<<< "));

        int[] result = GuiDef.parsePagedFlow(root, cells(LAYOUT), true, warnings);

        assertEquals(25, result[7]);
        assertEquals(19, result[13]);
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    private static void assertFlow(List<String> flow, int... expected) {
        Warnings warnings = new Warnings();
        assertArrayEquals(expected, PagedFlow.order(cells(LAYOUT), flow, warnings));
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    private static void assertFallback(List<String> flow, String reason) {
        Warnings warnings = new Warnings();
        int[] ordered = PagedFlow.order(cells(LAYOUT), flow, warnings);

        assertArrayEquals(ROW_MAJOR, ordered);
        assertEquals(1, warnings.messages.size(), () -> "warnings: " + warnings.messages);
        assertTrue(warnings.messages.get(0).contains(reason), warnings.messages.get(0));
    }

    private static int[] cells(List<String> layout) {
        return GuiDef.layoutKeys(layout).get('d');
    }

    /** Capturing warning sink: the tests assert both the text AND that nothing fired. */
    private static final class Warnings implements java.util.function.Consumer<String> {

        private final List<String> messages = new ArrayList<>();

        @Override
        public void accept(String message) {
            messages.add(message);
        }
    }
}
