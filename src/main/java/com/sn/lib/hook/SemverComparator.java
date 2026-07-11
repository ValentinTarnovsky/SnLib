package com.sn.lib.hook;

import java.util.Comparator;

import org.jetbrains.annotations.Nullable;

/**
 * Pure version comparator for plugin version gates, with semver pre-release precedence.
 *
 * <p>Build metadata starting at the first {@code '+'} is ignored (semver item 10). The
 * release core is compared segment by segment as numbers (any digit count per segment,
 * so {@code 1.10 > 1.9}); missing trailing segments count as 0 ({@code 1.2 == 1.2.0}).
 * When the cores tie, a version WITH a pre-release qualifier (everything after the
 * first {@code '-'}) PRECEDES the bare release ({@code 2.0.0-SNAPSHOT < 2.0.0}). Two
 * pre-releases compare by their dot-separated identifiers left to right: numeric
 * identifiers compare numerically, a numeric identifier is lower than an alphanumeric
 * one, alphanumeric identifiers compare in ASCII order, and when all shared identifiers
 * tie the version with MORE identifiers is newer ({@code 1.0.0-alpha < 1.0.0-alpha.1}).
 * No Bukkit dependency.</p>
 */
public final class SemverComparator implements Comparator<String> {

    @Override
    public int compare(String left, String right) {
        return compareVersions(left, right);
    }

    /**
     * Negative when {@code left} is older than {@code right}, zero when equivalent,
     * positive when newer. Applies the pre-release precedence ladder described in the
     * class javadoc; build metadata ({@code +...}) is ignored on both sides.
     */
    public static int compareVersions(String left, String right) {
        String a = stripBuildMetadata(left);
        String b = stripBuildMetadata(right);
        int core = compareCores(parseCore(a), parseCore(b));
        if (core != 0) {
            return core;
        }
        String preA = preRelease(a);
        String preB = preRelease(b);
        if (preA == null && preB == null) {
            return 0;
        }
        if (preA == null) {
            return 1;
        }
        if (preB == null) {
            return -1;
        }
        return comparePreRelease(preA, preB);
    }

    /** Everything from the first {@code '+'} is build metadata and never affects order. */
    private static String stripBuildMetadata(@Nullable String version) {
        String out = version == null ? "" : version.trim();
        int plus = out.indexOf('+');
        return plus >= 0 ? out.substring(0, plus) : out;
    }

    /**
     * Pre-release qualifier after the first {@code '-'}; null when absent. An empty
     * qualifier ({@code "1.0.0-"}) leniently counts as no qualifier at all.
     */
    private static @Nullable String preRelease(String version) {
        int dash = version.indexOf('-');
        if (dash < 0 || dash == version.length() - 1) {
            return null;
        }
        return version.substring(dash + 1);
    }

    private static int compareCores(int[] a, int[] b) {
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

    private static int[] parseCore(String version) {
        String core = version;
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

    /** Dot-separated identifiers left to right; with all shared ties, more identifiers wins. */
    private static int comparePreRelease(String left, String right) {
        String[] a = left.split("\\.");
        String[] b = right.split("\\.");
        int shared = Math.min(a.length, b.length);
        for (int i = 0; i < shared; i++) {
            int cmp = compareIdentifier(a[i], b[i]);
            if (cmp != 0) {
                return cmp;
            }
        }
        return Integer.compare(a.length, b.length);
    }

    /** Numeric vs numeric compares numerically; numeric < alphanumeric; else ASCII order. */
    private static int compareIdentifier(String left, String right) {
        boolean leftNumeric = isAllDigits(left);
        boolean rightNumeric = isAllDigits(right);
        if (leftNumeric && rightNumeric) {
            return compareNumeric(left, right);
        }
        if (leftNumeric) {
            return -1;
        }
        if (rightNumeric) {
            return 1;
        }
        return left.compareTo(right);
    }

    private static boolean isAllDigits(String identifier) {
        if (identifier.isEmpty()) {
            return false;
        }
        for (int i = 0; i < identifier.length(); i++) {
            char c = identifier.charAt(i);
            if (c < '0' || c > '9') {
                return false;
            }
        }
        return true;
    }

    /** Overflow-safe: compares by length after dropping leading zeros, then lexicographically. */
    private static int compareNumeric(String left, String right) {
        String a = stripLeadingZeros(left);
        String b = stripLeadingZeros(right);
        if (a.length() != b.length()) {
            return Integer.compare(a.length(), b.length());
        }
        return a.compareTo(b);
    }

    private static String stripLeadingZeros(String digits) {
        int start = 0;
        while (start < digits.length() - 1 && digits.charAt(start) == '0') {
            start++;
        }
        return digits.substring(start);
    }
}
