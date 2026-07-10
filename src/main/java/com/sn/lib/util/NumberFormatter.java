package com.sn.lib.util;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * Abbreviated number formatting with suffixes K/M/B/T/Qa/Qi (pure, no Bukkit).
 *
 * <p>{@link #format(double)} scales by powers of 1000 and keeps up to two
 * decimals with trailing zeros stripped ({@code 1500 -> "1.5K"}).
 * {@link #parseFormatted(String)} is the tolerant inverse: suffixes are
 * case-insensitive and both comma and dot are accepted as decimal or grouping
 * separators (a single comma followed by exactly three digits counts as
 * grouping, so {@code "1,500" -> 1500} while {@code "1,5" -> 1.5}).</p>
 */
public final class NumberFormatter {

    private static final String[] SUFFIXES = {"", "K", "M", "B", "T", "Qa", "Qi"};
    private static final BigDecimal THOUSAND = BigDecimal.valueOf(1_000);

    private NumberFormatter() {
    }

    /** Formats {@code value} with the largest fitting suffix and up to two decimals. */
    public static String format(double value) {
        if (!Double.isFinite(value)) {
            return String.valueOf(value);
        }
        double abs = Math.abs(value);
        int index = 0;
        while (abs >= 1_000D && index < SUFFIXES.length - 1) {
            abs /= 1_000D;
            index++;
        }
        BigDecimal scaled = BigDecimal.valueOf(abs).setScale(2, RoundingMode.HALF_UP);
        if (scaled.compareTo(THOUSAND) >= 0 && index < SUFFIXES.length - 1) {
            scaled = scaled.movePointLeft(3).setScale(2, RoundingMode.HALF_UP);
            index++;
        }
        String number = scaled.stripTrailingZeros().toPlainString();
        return (value < 0 ? "-" : "") + number + SUFFIXES[index];
    }

    /**
     * Parses an abbreviated number back to its double value.
     *
     * @throws NumberFormatException on null, empty, unknown suffix or unparseable number
     */
    public static double parseFormatted(String text) {
        if (text == null) {
            throw new NumberFormatException("null");
        }
        String trimmed = text.trim();
        if (trimmed.isEmpty()) {
            throw new NumberFormatException("empty input");
        }
        int end = trimmed.length();
        while (end > 0 && Character.isLetter(trimmed.charAt(end - 1))) {
            end--;
        }
        double multiplier = multiplierFor(trimmed.substring(end));
        String number = normalizeSeparators(trimmed.substring(0, end).trim());
        return Double.parseDouble(number) * multiplier;
    }

    private static double multiplierFor(String suffix) {
        if (suffix.isEmpty()) {
            return 1D;
        }
        for (int i = 1; i < SUFFIXES.length; i++) {
            if (SUFFIXES[i].equalsIgnoreCase(suffix)) {
                return Math.pow(1_000D, i);
            }
        }
        throw new NumberFormatException("Unknown suffix '" + suffix + "'");
    }

    /** Resolves mixed comma/dot input to a canonical dot-decimal string. */
    private static String normalizeSeparators(String number) {
        String out = number.replace(" ", "");
        int lastComma = out.lastIndexOf(',');
        int lastDot = out.lastIndexOf('.');
        if (lastComma >= 0 && lastDot >= 0) {
            if (lastDot > lastComma) {
                out = out.replace(",", "");
            } else {
                out = out.replace(".", "").replace(',', '.');
            }
        } else if (lastComma >= 0) {
            boolean grouping = out.indexOf(',') != lastComma
                    || out.length() - lastComma - 1 == 3;
            out = grouping ? out.replace(",", "") : out.replace(',', '.');
        }
        int firstDot = out.indexOf('.');
        int finalDot = out.lastIndexOf('.');
        if (firstDot != finalDot) {
            out = out.substring(0, finalDot).replace(".", "") + out.substring(finalDot);
        }
        return out;
    }
}
