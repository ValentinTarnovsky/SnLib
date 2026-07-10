package com.sn.lib.hook;

import java.util.Comparator;

/**
 * Pure numeric version comparator for plugin version gates.
 *
 * <p>Compares segment by segment as numbers (any digit count per segment, so
 * {@code 1.10 > 1.9}); missing trailing segments count as 0 ({@code 1.2 == 1.2.0});
 * qualifier suffixes starting at the first {@code '-'} (such as {@code -SNAPSHOT}) are
 * ignored. No Bukkit dependency.</p>
 */
public final class SemverComparator implements Comparator<String> {

    @Override
    public int compare(String left, String right) {
        return compareVersions(left, right);
    }

    /**
     * Negative when {@code left} is older than {@code right}, zero when equivalent,
     * positive when newer.
     */
    public static int compareVersions(String left, String right) {
        int[] a = parse(left);
        int[] b = parse(right);
        int length = Math.max(a.length, b.length);
        for (int i = 0; i < length; i++) {
            int x = i < a.length ? a[i] : 0;
            int y = i < b.length ? b[i] : 0;
            if (x != y) {
                return Integer.compare(x, y);
            }
        }
        return 0;
    }

    private static int[] parse(String version) {
        String core = version == null ? "" : version.trim();
        int dash = core.indexOf('-');
        if (dash >= 0) {
            core = core.substring(0, dash);
        }
        String[] segments = core.split("\\.");
        int[] out = new int[segments.length];
        for (int i = 0; i < segments.length; i++) {
            out[i] = leadingNumber(segments[i]);
        }
        return out;
    }

    /** Numeric prefix of a segment; a segment without a numeric prefix counts as 0. */
    private static int leadingNumber(String segment) {
        int value = 0;
        for (int i = 0; i < segment.length(); i++) {
            char c = segment.charAt(i);
            if (c < '0' || c > '9') {
                break;
            }
            value = value * 10 + (c - '0');
        }
        return value;
    }
}
