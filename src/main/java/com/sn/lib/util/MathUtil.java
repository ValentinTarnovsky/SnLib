package com.sn.lib.util;

import java.util.Random;
import java.util.concurrent.ThreadLocalRandom;

/**
 * Math helpers: fair probabilistic rounding and roman numerals (pure, no Bukkit).
 *
 * <p>Fair rounding resolves the fractional part probabilistically so the
 * expected value of the result equals the input: {@code 2.3} yields 3 with 30%
 * probability and 2 otherwise.</p>
 */
public final class MathUtil {

    private static final int[] ROMAN_VALUES = {1_000, 900, 500, 400, 100, 90, 50, 40, 10, 9, 5, 4, 1};
    private static final String[] ROMAN_SYMBOLS = {"M", "CM", "D", "CD", "C", "XC", "L", "XL", "X", "IX", "V", "IV", "I"};

    private MathUtil() {
    }

    /** Fair rounding to int using {@link ThreadLocalRandom}. */
    public static int fairIntFromDouble(double value) {
        return fairIntFromDouble(value, ThreadLocalRandom.current());
    }

    /** Fair rounding to int weighted by the decimal part. */
    public static int fairIntFromDouble(double value, Random random) {
        int floor = (int) Math.floor(value);
        double fraction = value - floor;
        return fraction > 0D && random.nextDouble() < fraction ? floor + 1 : floor;
    }

    /** Fair rounding to long using {@link ThreadLocalRandom}. */
    public static long fairLongFromDouble(double value) {
        return fairLongFromDouble(value, ThreadLocalRandom.current());
    }

    /** Fair rounding to long weighted by the decimal part. */
    public static long fairLongFromDouble(double value, Random random) {
        long floor = (long) Math.floor(value);
        double fraction = value - floor;
        return fraction > 0D && random.nextDouble() < fraction ? floor + 1L : floor;
    }

    /**
     * Roman numeral for {@code number} in 1-3999.
     *
     * @throws IllegalArgumentException outside the supported range
     */
    public static String convertToRoman(int number) {
        if (number < 1 || number > 3_999) {
            throw new IllegalArgumentException("Roman numerals support 1-3999, got " + number);
        }
        StringBuilder out = new StringBuilder();
        int remaining = number;
        for (int i = 0; i < ROMAN_VALUES.length; i++) {
            while (remaining >= ROMAN_VALUES[i]) {
                remaining -= ROMAN_VALUES[i];
                out.append(ROMAN_SYMBOLS[i]);
            }
        }
        return out.toString();
    }
}
