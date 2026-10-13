package com.sn.lib.gui;

import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.function.Consumer;

/**
 * Orders the {@code paged-key} cells along the arrow path the owner drew in
 * {@code paged-flow:} (pure, no Bukkit). The grid is the layout's 6x9 chest geometry
 * (cell row i, column j = slot i*9+j) and every paged cell carries the arrow that leads to
 * the NEXT entry: {@code >} right, {@code <} left, {@code ^} up, {@code v}/{@code V} down,
 * a blank meaning "no next cell".
 *
 * <p>An arrow leads to the first PAGED cell in its direction, skipping every other cell,
 * so a filler or a gap inside the paged area never breaks the path; with no paged cell
 * further along the line the cell is the end. A TURN (an arrow that leaves the axis the
 * path is travelling on, like the {@code v} at the end of a row) enters the line it turns
 * into where that line's own arrows start, then the path keeps travelling on the same
 * axis. That is what lets a drawing say "every row right to left, top down"
 * ({@code v<<<<<<} over {@code <<<<<<<}) while a snake ({@code >>>>>>v} over
 * {@code <<<<<<<}) still lands right under the turn, because there the row starts under
 * it; the same holds for columns joined by a {@code >} or {@code <} at their end.</p>
 *
 * <p>The start is the paged cell nothing points at whose walk covers the drawing: every
 * line a turn enters also starts on such a cell, so each one is walked and exactly one
 * must be a path through EVERY paged cell. Anything else WARNs once naming the first
 * offending slot and the cells keep their row-major order, so a broken drawing never
 * breaks the menu. An arrow on a non-paged cell does nothing and WARNs once, because it
 * almost always means the grid is shifted against the layout.</p>
 */
final class PagedFlow {

    private static final int ROWS = 6;
    private static final int COLUMNS = 9;
    private static final int GRID = ROWS * COLUMNS;

    private PagedFlow() {
    }

    /**
     * Returns {@code cells} in the order of the arrow path drawn in {@code flowRows}, or a
     * copy of {@code cells} unchanged (row-major) after a WARN when the drawing is not one
     * path through every cell. An empty {@code flowRows} returns the cells silently.
     */
    static int[] order(int[] cells, List<String> flowRows, Consumer<String> warn) {
        if (cells.length == 0 || flowRows.isEmpty()) {
            return cells.clone();
        }
        Set<Integer> paged = new HashSet<>();
        for (int slot : cells) {
            paged.add(slot);
        }
        noteStrayArrows(paged, flowRows, warn);
        Grid grid = new Grid();
        boolean[] targeted = new boolean[GRID];
        for (int slot : cells) {
            char arrow = charAt(flowRows, slot);
            if (arrow == ' ') {
                continue;
            }
            int[] step = step(arrow);
            if (step == null) {
                return fallback(cells, warn, "'" + arrow + "' at slot " + slot
                        + " is not an arrow (> < ^ v)");
            }
            grid.arrows[slot] = arrow;
            int target = follow(slot, step, paged);
            if (target < 0) {
                continue;
            }
            grid.nominal[slot] = target;
            targeted[target] = true;
            int[] incoming = horizontal(arrow) ? grid.fromLeftRight : grid.fromUpDown;
            if (incoming[target] < 0) {
                incoming[target] = slot;
            }
        }
        // The start is a cell no arrow points at, but so is the first cell of every line a
        // turn enters, so each candidate is walked and the one path through EVERY cell wins.
        int[] found = null;
        String failure = null;
        int bestCount = -1;
        for (int candidate : cells) {
            if (targeted[candidate]) {
                continue;
            }
            Walk walk = walk(candidate, cells, grid);
            if (walk.failure == null) {
                if (found != null) {
                    return fallback(cells, warn, "the flow reads as a full path from slot "
                            + found[0] + " and from slot " + candidate);
                }
                found = walk.order;
            } else if (walk.count > bestCount) {
                bestCount = walk.count;
                failure = walk.failure;
            }
        }
        if (found != null) {
            return found;
        }
        return fallback(cells, warn, failure != null ? failure
                : "the flow loops back on itself and has no start");
    }

    /**
     * Follows the arrows from {@code start}. A move along the axis the path is already
     * travelling goes to the cell the arrow points at; a TURN (an arrow on the other axis)
     * lands on the cell it points at and then backs up along the arrows of the travelling
     * axis that lead into it, to the first unvisited cell of the line it enters, and the
     * path keeps travelling on that axis.
     */
    private static Walk walk(int start, int[] cells, Grid grid) {
        int[] order = new int[cells.length];
        boolean[] visited = new boolean[GRID];
        int count = 0;
        boolean travellingHorizontally = horizontal(grid.arrows[start]);
        int slot = start;
        while (true) {
            if (visited[slot]) {
                return new Walk(order, count, "the path from slot " + start
                        + " runs back into slot " + slot);
            }
            // Every slot reached is a distinct paged cell, so count never outgrows order.
            visited[slot] = true;
            order[count++] = slot;
            int target = grid.nominal[slot];
            if (target < 0) {
                break;
            }
            if (horizontal(grid.arrows[slot]) != travellingHorizontally) {
                int[] back = travellingHorizontally ? grid.fromLeftRight : grid.fromUpDown;
                // Bounded: two arrows pointing at each other would otherwise back up forever.
                for (int steps = 0; steps < cells.length && back[target] >= 0
                        && !visited[back[target]]; steps++) {
                    target = back[target];
                }
            }
            slot = target;
        }
        for (int cell : cells) {
            if (!visited[cell]) {
                return new Walk(order, count, "slot " + cell + " is not on the path that"
                        + " starts at slot " + start);
            }
        }
        return new Walk(order, count, null);
    }

    /** WARNs once, naming the first slot, when an arrow sits on a cell that is not paged. */
    private static void noteStrayArrows(Set<Integer> paged, List<String> flowRows,
                                        Consumer<String> warn) {
        for (int row = 0; row < Math.min(ROWS, flowRows.size()); row++) {
            String line = flowRows.get(row);
            for (int column = 0; column < Math.min(COLUMNS, line.length()); column++) {
                int slot = row * COLUMNS + column;
                if (!paged.contains(slot) && step(line.charAt(column)) != null) {
                    warn.accept("paged-flow has an arrow at slot " + slot + ", which is not a"
                            + " paged-key cell; it is ignored (is the grid shifted against the"
                            + " layout?)");
                    return;
                }
            }
        }
    }

    /** The flow character of a slot; a missing row or a short row reads as blank. */
    private static char charAt(List<String> flowRows, int slot) {
        int row = slot / COLUMNS;
        int column = slot % COLUMNS;
        if (row >= flowRows.size()) {
            return ' ';
        }
        String line = flowRows.get(row);
        return column < line.length() ? line.charAt(column) : ' ';
    }

    /** Row and column delta of an arrow, or null when the character is not one. */
    private static int[] step(char arrow) {
        return switch (arrow) {
            case '>' -> new int[] {0, 1};
            case '<' -> new int[] {0, -1};
            case '^' -> new int[] {-1, 0};
            case 'v', 'V' -> new int[] {1, 0};
            default -> null;
        };
    }

    private static boolean horizontal(char arrow) {
        return arrow == '>' || arrow == '<';
    }

    /** The first paged cell from {@code slot} along {@code step}, or -1 past the grid edge. */
    private static int follow(int slot, int[] step, Set<Integer> paged) {
        int row = slot / COLUMNS + step[0];
        int column = slot % COLUMNS + step[1];
        while (row >= 0 && row < ROWS && column >= 0 && column < COLUMNS) {
            int candidate = row * COLUMNS + column;
            if (paged.contains(candidate)) {
                return candidate;
            }
            row += step[0];
            column += step[1];
        }
        return -1;
    }

    /** The arrow of every cell, where it points, and which arrow points into each cell. */
    private static final class Grid {

        private final char[] arrows = new char[GRID];
        private final int[] nominal = filled();
        private final int[] fromLeftRight = filled();
        private final int[] fromUpDown = filled();

        private static int[] filled() {
            int[] array = new int[GRID];
            Arrays.fill(array, -1);
            return array;
        }
    }

    /** One walk from a candidate start: the order so far and why it failed, if it did. */
    private record Walk(int[] order, int count, String failure) {
    }

    private static int[] fallback(int[] cells, Consumer<String> warn, String reason) {
        warn.accept("paged-flow ignored: " + reason + "; paging keeps the row-major order");
        return cells.clone();
    }
}
