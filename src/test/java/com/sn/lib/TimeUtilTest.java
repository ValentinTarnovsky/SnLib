package com.sn.lib;

import org.junit.jupiter.api.Test;

import com.sn.lib.util.TimeUtil;

import static org.junit.jupiter.api.Assertions.assertEquals;

class TimeUtilTest {

    private static final long FULL = 86_400_000L + 2 * 3_600_000L + 30 * 60_000L + 15_000L;

    private static final TimeUtil.Labels SPANISH = new TimeUtil.Labels() {
        @Override
        public String longLabel(TimeUtil.Unit unit, boolean plural) {
            String base = switch (unit) {
                case DAY -> "dia";
                case HOUR -> "hora";
                case MINUTE -> "minuto";
                case SECOND -> "segundo";
            };
            return plural ? base + "s" : base;
        }

        @Override
        public String shortLabel(TimeUtil.Unit unit) {
            return switch (unit) {
                case DAY -> "d";
                case HOUR -> "h";
                case MINUTE -> "min";
                case SECOND -> "seg";
            };
        }
    };

    @Test
    void parsesCanonicalDurationString() {
        assertEquals(FULL, TimeUtil.parseMillis("1d 2h 30m 15s"));
        assertEquals(FULL / 50L, TimeUtil.parseTicks("1d 2h 30m 15s"));
    }

    @Test
    void parsesCompactAndSpacedVariants() {
        assertEquals(FULL, TimeUtil.parseMillis("1d2h30m15s"));
        assertEquals(FULL, TimeUtil.parseMillis("1 d 2 h 30 m 15 s"));
    }

    @Test
    void parsesFullUnitWords() {
        assertEquals(FULL, TimeUtil.parseMillis("1 day 2 hours 30 minutes 15 seconds"));
    }

    @Test
    void bareNumberCountsAsSeconds() {
        assertEquals(45_000L, TimeUtil.parseMillis("45"));
    }

    @Test
    void supportsDecimalsTicksAndMillis() {
        assertEquals(5_400_000L, TimeUtil.parseMillis("1.5h"));
        assertEquals(50L, TimeUtil.parseMillis("1t"));
        assertEquals(250L, TimeUtil.parseMillis("250ms"));
    }

    @Test
    void invalidInputYieldsZero() {
        assertEquals(0L, TimeUtil.parseMillis(null));
        assertEquals(0L, TimeUtil.parseMillis(""));
        assertEquals(0L, TimeUtil.parseMillis("garbage"));
        assertEquals(0L, TimeUtil.parseMillis("5x"));
    }

    @Test
    void humanizesLongForm() {
        assertEquals("1 day 2 hours 30 minutes 15 seconds", TimeUtil.humanize(FULL));
        assertEquals("1 second", TimeUtil.humanize(1_000L));
        assertEquals("2 minutes", TimeUtil.humanize(120_000L));
        assertEquals("0 seconds", TimeUtil.humanize(0L));
    }

    @Test
    void humanizesShortForm() {
        assertEquals("1d 2h 30m 15s", TimeUtil.humanizeShort(FULL));
        assertEquals("1m", TimeUtil.humanizeShort(60_000L));
        assertEquals("0s", TimeUtil.humanizeShort(0L));
    }

    @Test
    void labelsAreInjectableForI18n() {
        assertEquals("1 minuto 1 segundo", TimeUtil.humanize(61_000L, SPANISH));
        assertEquals("2 dias", TimeUtil.humanize(2 * 86_400_000L, SPANISH));
        assertEquals("1min 1seg", TimeUtil.humanizeShort(61_000L, SPANISH));
        assertEquals("0seg", TimeUtil.humanizeShort(0L, SPANISH));
    }

    @Test
    void shortFormRoundTripsThroughParse() {
        long[] samples = {FULL, 1_000L, 60_000L, 3_600_000L, 90_061_000L};
        for (long millis : samples) {
            assertEquals(millis, TimeUtil.parseMillis(TimeUtil.humanizeShort(millis)));
            assertEquals(millis, TimeUtil.parseMillis(TimeUtil.humanize(millis)));
        }
    }
}
