package com.sn.lib;

import org.junit.jupiter.api.Test;

import com.sn.lib.util.NumberFormatter;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class NumberFormatterTest {

    @Test
    void formatsPlainNumbersBelowThousand() {
        assertEquals("0", NumberFormatter.format(0));
        assertEquals("999", NumberFormatter.format(999));
        assertEquals("12.35", NumberFormatter.format(12.345));
    }

    @Test
    void formatsEachSuffixMagnitude() {
        assertEquals("1.5K", NumberFormatter.format(1_500D));
        assertEquals("1M", NumberFormatter.format(1_000_000D));
        assertEquals("2.5B", NumberFormatter.format(2_500_000_000D));
        assertEquals("1T", NumberFormatter.format(1_000_000_000_000D));
        assertEquals("1Qa", NumberFormatter.format(1e15));
        assertEquals("1Qi", NumberFormatter.format(1e18));
    }

    @Test
    void formatsNegativesAndRoundsToTwoDecimals() {
        assertEquals("-1.5K", NumberFormatter.format(-1_500D));
        assertEquals("1.23M", NumberFormatter.format(1_234_567D));
    }

    @Test
    void promotesWhenRoundingReachesNextMagnitude() {
        assertEquals("1M", NumberFormatter.format(999_999D));
        assertEquals("1K", NumberFormatter.format(999.999D));
    }

    @Test
    void parsesSuffixedInputCaseInsensitively() {
        assertEquals(1_500D, NumberFormatter.parseFormatted("1.5K"));
        assertEquals(1_500D, NumberFormatter.parseFormatted("1.5k"));
        assertEquals(2_000_000D, NumberFormatter.parseFormatted("2m"));
        assertEquals(1e15, NumberFormatter.parseFormatted("1qa"));
        assertEquals(2.5e18, NumberFormatter.parseFormatted("2.5Qi"));
        assertEquals(-2.5e9, NumberFormatter.parseFormatted("-2.5B"));
    }

    @Test
    void toleratesCommaAndDotSeparators() {
        assertEquals(1_500D, NumberFormatter.parseFormatted("1,5K"));
        assertEquals(1.5D, NumberFormatter.parseFormatted("1,5"));
        assertEquals(1_500D, NumberFormatter.parseFormatted("1,500"));
        assertEquals(1_234_567.89D, NumberFormatter.parseFormatted("1,234,567.89"));
        assertEquals(1_234_567.89D, NumberFormatter.parseFormatted("1.234.567,89"));
        assertEquals(1_234D, NumberFormatter.parseFormatted("1234"));
    }

    @Test
    void rejectsGarbage() {
        assertThrows(NumberFormatException.class, () -> NumberFormatter.parseFormatted(null));
        assertThrows(NumberFormatException.class, () -> NumberFormatter.parseFormatted(""));
        assertThrows(NumberFormatException.class, () -> NumberFormatter.parseFormatted("abc"));
        assertThrows(NumberFormatException.class, () -> NumberFormatter.parseFormatted("1.5X"));
    }

    @Test
    void roundTripsWithinSuffixPrecision() {
        double[] samples = {
                0D, 1D, 999D, 1_500D, 12_345D, 999_999D, 1_234_567D,
                2_500_000_000D, 7.77e12, 1.234e15, 9.87e18, -42_000D, -1.5e9
        };
        for (double value : samples) {
            double parsed = NumberFormatter.parseFormatted(NumberFormatter.format(value));
            assertEquals(value, parsed, Math.abs(value) * 0.005 + 0.0001,
                    "round-trip failed for " + value);
        }
    }
}
