package com.sn.lib.gui;

import java.util.Arrays;
import java.util.List;

/**
 * Pure helper resolving an ASCII mask into chest slot indexes for menus built by code.
 *
 * <p>Rows are read top to bottom over a 9-column chest grid: the cell at row {@code i},
 * column {@code j} is slot {@code i * 9 + j}. At most 6 rows are read (extra rows are
 * ignored) and at most 9 characters per row (extra characters are ignored). A null or
 * empty row counts as a row of empty cells and still occupies its row index; a null row
 * list yields an empty array. The space character is ALWAYS an empty cell:
 * {@code slots(' ', ...)} returns an empty array even when the mask contains spaces.
 * The result holds every cell where {@code key} appears, in ascending row-major order
 * (each cell is unique, so duplicates are impossible); a key that never appears yields
 * an empty array.</p>
 *
 * <p>Typical usage:</p>
 * <pre>
 * session.bindPaged("tpl", data,
 *         GuiMask.slots('d', "         ", " ddddddd ", " ddddddd "), mapper);
 * session.bind(GuiMask.slots('x', rows)[0], template);
 * </pre>
 */
public final class GuiMask {

    private static final int MAX_ROWS = 6;
    private static final int MAX_COLUMNS = 9;

    private GuiMask() {
    }

    /** Varargs variant of {@link #slots(char, List)}. */
    public static int[] slots(char key, String... rows) {
        return slots(key, rows == null ? null : Arrays.asList(rows));
    }

    /**
     * Slots of every mask cell holding {@code key}, in ascending row-major order over
     * the 6x9 chest grid; empty when the key never appears, the key is a space or the
     * row list is null.
     */
    public static int[] slots(char key, List<String> rows) {
        if (rows == null || key == ' ') {
            return new int[0];
        }
        int[] found = new int[MAX_ROWS * MAX_COLUMNS];
        int count = 0;
        int rowLimit = Math.min(rows.size(), MAX_ROWS);
        for (int row = 0; row < rowLimit; row++) {
            String line = rows.get(row);
            if (line == null) {
                continue;
            }
            int columnLimit = Math.min(line.length(), MAX_COLUMNS);
            for (int column = 0; column < columnLimit; column++) {
                if (line.charAt(column) == key) {
                    found[count++] = row * 9 + column;
                }
            }
        }
        return Arrays.copyOf(found, count);
    }
}
