package com.sn.lib;

import java.time.Duration;
import java.time.ZoneId;
import java.time.ZonedDateTime;

import org.junit.jupiter.api.Test;

import com.sn.lib.cron.CronExpr;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

class CronNextRunTest {

    private static final ZoneId UTC = ZoneId.of("UTC");
    private static final ZoneId NEW_YORK = ZoneId.of("America/New_York");

    private static ZonedDateTime at(int year, int month, int day, int hour, int minute, ZoneId zone) {
        return ZonedDateTime.of(year, month, day, hour, minute, 0, 0, zone);
    }

    @Test
    void stepFieldMatchesNextMultiple() {
        CronExpr expr = CronExpr.parse("*/15 * * * *");
        assertEquals(at(2026, 1, 10, 10, 15, UTC), expr.nextRun(at(2026, 1, 10, 10, 7, UTC)));
        assertEquals(at(2026, 1, 10, 11, 0, UTC), expr.nextRun(at(2026, 1, 10, 10, 45, UTC)));
    }

    @Test
    void listAndRangeFieldsCombine() {
        CronExpr expr = CronExpr.parse("0,30 9-17 * * *");
        assertEquals(at(2026, 1, 10, 9, 0, UTC), expr.nextRun(at(2026, 1, 10, 8, 10, UTC)));
        assertEquals(at(2026, 1, 10, 9, 30, UTC), expr.nextRun(at(2026, 1, 10, 9, 10, UTC)));
        assertEquals(at(2026, 1, 11, 9, 0, UTC), expr.nextRun(at(2026, 1, 10, 17, 45, UTC)));
    }

    @Test
    void dayOfWeekFieldWaitsForMatchingDay() {
        CronExpr expr = CronExpr.parse("0 12 * * 1");
        // 2026-01-09 is a Friday; the next Monday is 2026-01-12.
        assertEquals(at(2026, 1, 12, 12, 0, UTC), expr.nextRun(at(2026, 1, 9, 15, 0, UTC)));
    }

    @Test
    void sundayMatchesBothZeroAndSeven() {
        // 2026-01-11 is a Sunday.
        ZonedDateTime from = at(2026, 1, 9, 15, 0, UTC);
        assertEquals(at(2026, 1, 11, 6, 0, UTC), CronExpr.parse("0 6 * * 0").nextRun(from));
        assertEquals(at(2026, 1, 11, 6, 0, UTC), CronExpr.parse("0 6 * * 7").nextRun(from));
    }

    @Test
    void dailyShortcutIsStrictlyAfterFrom() {
        CronExpr expr = CronExpr.parse("daily 04:00");
        assertEquals(at(2026, 1, 10, 4, 0, UTC), expr.nextRun(at(2026, 1, 10, 3, 59, UTC)));
        assertEquals(at(2026, 1, 11, 4, 0, UTC), expr.nextRun(at(2026, 1, 10, 4, 0, UTC)));
        assertEquals(at(2026, 1, 11, 4, 0, UTC), expr.nextRun(at(2026, 1, 10, 5, 0, UTC)));
    }

    @Test
    void hourlyShortcutMatchesEveryHour() {
        CronExpr expr = CronExpr.parse("hourly :30");
        assertEquals(at(2026, 1, 10, 10, 30, UTC), expr.nextRun(at(2026, 1, 10, 10, 15, UTC)));
        assertEquals(at(2026, 1, 10, 11, 30, UTC), expr.nextRun(at(2026, 1, 10, 10, 30, UTC)));
    }

    @Test
    void dayThirtyOneSkipsShorterMonths() {
        CronExpr expr = CronExpr.parse("0 0 31 * *");
        // April has 30 days: the next 31st is May 31st.
        assertEquals(at(2026, 5, 31, 0, 0, UTC), expr.nextRun(at(2026, 4, 1, 0, 0, UTC)));
    }

    @Test
    void februaryTwentyNinthWaitsForLeapYear() {
        CronExpr expr = CronExpr.parse("0 6 29 2 *");
        assertEquals(at(2028, 2, 29, 6, 0, UTC), expr.nextRun(at(2026, 3, 1, 0, 0, UTC)));
    }

    @Test
    void springForwardShortensTheRealDelay() {
        // US DST starts 2026-03-08: the 02:00-02:59 hour does not exist in New York.
        CronExpr expr = CronExpr.parse("0 8 * * *");
        ZonedDateTime from = at(2026, 3, 7, 12, 0, NEW_YORK);
        ZonedDateTime next = expr.nextRun(from);
        assertEquals(at(2026, 3, 8, 8, 0, NEW_YORK), next);
        assertEquals(Duration.ofHours(19), Duration.between(from, next));
    }

    @Test
    void fallBackLengthensTheRealDelay() {
        // US DST ends 2026-11-01: the 01:00-01:59 hour repeats in New York.
        CronExpr expr = CronExpr.parse("0 8 * * *");
        ZonedDateTime from = at(2026, 10, 31, 12, 0, NEW_YORK);
        ZonedDateTime next = expr.nextRun(from);
        assertEquals(at(2026, 11, 1, 8, 0, NEW_YORK), next);
        assertEquals(Duration.ofHours(21), Duration.between(from, next));
    }

    @Test
    void wallClockErasedByDstGapSkipsToNextDay() {
        // 02:30 does not exist on 2026-03-08 in New York; the run lands on March 9th.
        CronExpr expr = CronExpr.parse("daily 02:30");
        assertEquals(at(2026, 3, 9, 2, 30, NEW_YORK),
                expr.nextRun(at(2026, 3, 8, 0, 0, NEW_YORK)));
    }

    @Test
    void invalidExpressionsThrow() {
        assertThrows(IllegalArgumentException.class, () -> CronExpr.parse("61 * * * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpr.parse("* * *"));
        assertThrows(IllegalArgumentException.class, () -> CronExpr.parse("a b c d e"));
        assertThrows(IllegalArgumentException.class, () -> CronExpr.parse("daily 25:00"));
        assertThrows(IllegalArgumentException.class, () -> CronExpr.parse("hourly :75"));
        assertThrows(IllegalArgumentException.class, () -> CronExpr.parse(""));
        assertThrows(IllegalArgumentException.class, () -> CronExpr.parse("*/0 * * * *"));
    }

    @Test
    void impossibleDateNeverMatches() {
        CronExpr expr = CronExpr.parse("0 0 31 2 *");
        assertThrows(IllegalStateException.class,
                () -> expr.nextRun(at(2026, 1, 1, 0, 0, UTC)));
    }
}
