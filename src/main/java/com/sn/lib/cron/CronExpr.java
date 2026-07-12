package com.sn.lib.cron;

import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.Locale;

/**
 * Parsed calendar expression: a subset of 5-field cron plus two shortcuts. Pure time
 * math over {@code java.time}, no Bukkit.
 *
 * <p>Supported syntax, fields in order {@code minute hour day-of-month month day-of-week}:</p>
 * <ul>
 *   <li>{@code *} (any), lists {@code 1,15}, ranges {@code 1-5} and steps {@code *&#47;10}
 *       (also over ranges, {@code 10-30/5}), combinable per field.</li>
 *   <li>Day of week 0-7 where both 0 and 7 mean Sunday. When day-of-month AND day-of-week
 *       are both restricted, a day matching EITHER runs (standard cron OR semantics).</li>
 *   <li>Shortcut {@code daily HH:mm} (time optional, default 00:00) and shortcut
 *       {@code hourly :mm} (minute optional, default :00).</li>
 * </ul>
 *
 * <p>{@link #nextRun} computes the next matching wall-clock instant STRICTLY after the
 * given time, iterating per field on {@link ZonedDateTime} so DST transitions and month
 * lengths resolve through the zone rules: a wall-clock time erased by a DST gap is
 * skipped to the next matching day, and a day absent from a month (the 31st in a 30-day
 * month, February 29th outside leap years) waits for the next month that has it.</p>
 */
public final class CronExpr {

    /** Bound on field adjustments per search; reached only by expressions that never match. */
    private static final int MAX_ADJUSTMENTS = 5000;

    private final String source;
    private final boolean[] minutes = new boolean[60];
    private final boolean[] hours = new boolean[24];
    private final boolean[] daysOfMonth = new boolean[32];
    private final boolean[] months = new boolean[13];
    private final boolean[] daysOfWeek = new boolean[7];
    private final boolean anyDayOfMonth;
    private final boolean anyDayOfWeek;

    private CronExpr(String source, String minute, String hour, String dayOfMonth,
            String month, String dayOfWeek) {
        this.source = source;
        fill(minutes, minute, 0, 59, "minute");
        fill(hours, hour, 0, 23, "hour");
        fill(daysOfMonth, dayOfMonth, 1, 31, "day of month");
        fill(months, month, 1, 12, "month");
        fillDayOfWeek(dayOfWeek);
        this.anyDayOfMonth = "*".equals(dayOfMonth);
        this.anyDayOfWeek = "*".equals(dayOfWeek);
    }

    /**
     * Parses a 5-field cron expression or one of the {@code daily}/{@code hourly}
     * shortcuts.
     *
     * @throws IllegalArgumentException when the expression is malformed or a value falls
     *         outside its field's range
     */
    public static CronExpr parse(String expr) {
        if (expr == null || expr.trim().isEmpty()) {
            throw new IllegalArgumentException("Empty cron expression");
        }
        String trimmed = expr.trim();
        String lower = trimmed.toLowerCase(Locale.ROOT);
        if (lower.equals("daily") || lower.startsWith("daily ")) {
            int[] time = parseClock(trimmed.substring(5).trim(), trimmed);
            return new CronExpr(trimmed, String.valueOf(time[1]), String.valueOf(time[0]),
                    "*", "*", "*");
        }
        if (lower.equals("hourly") || lower.startsWith("hourly ")) {
            int minute = parseHourlyMinute(trimmed.substring(6).trim(), trimmed);
            return new CronExpr(trimmed, String.valueOf(minute), "*", "*", "*", "*");
        }
        String[] fields = trimmed.split("\\s+");
        if (fields.length != 5) {
            throw new IllegalArgumentException("Cron expression '" + trimmed
                    + "': expected 5 fields (minute hour day month day-of-week), got "
                    + fields.length);
        }
        return new CronExpr(trimmed, fields[0], fields[1], fields[2], fields[3], fields[4]);
    }

    /**
     * Next matching instant STRICTLY after {@code from}, in {@code from}'s zone.
     *
     * @throws IllegalStateException when the expression never matches (for example
     *         February 31st)
     */
    public ZonedDateTime nextRun(ZonedDateTime from) {
        ZonedDateTime next = from.truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        for (int i = 0; i < MAX_ADJUSTMENTS; i++) {
            if (!months[next.getMonthValue()]) {
                next = next.toLocalDate().withDayOfMonth(1).plusMonths(1)
                        .atStartOfDay(next.getZone());
                continue;
            }
            if (!dayMatches(next)) {
                next = next.toLocalDate().plusDays(1).atStartOfDay(next.getZone());
                continue;
            }
            if (!hours[next.getHour()]) {
                next = next.plusHours(1).withMinute(0);
                continue;
            }
            if (!minutes[next.getMinute()]) {
                next = next.plusMinutes(1);
                continue;
            }
            return next;
        }
        throw new IllegalStateException(
                "Cron expression '" + source + "' has no reachable next run");
    }

    /** Original expression text this instance was parsed from. */
    @Override
    public String toString() {
        return source;
    }

    private boolean dayMatches(ZonedDateTime at) {
        boolean domOk = daysOfMonth[at.getDayOfMonth()];
        boolean dowOk = daysOfWeek[at.getDayOfWeek().getValue() % 7];
        if (!anyDayOfMonth && !anyDayOfWeek) {
            return domOk || dowOk;
        }
        if (!anyDayOfMonth) {
            return domOk;
        }
        if (!anyDayOfWeek) {
            return dowOk;
        }
        return true;
    }

    /** Fills one field from its spec: comma list of {@code *}, single, range, each with optional step. */
    private static void fill(boolean[] field, String spec, int min, int max, String name) {
        for (String atom : spec.split(",", -1)) {
            String base = atom;
            int step = 1;
            int slash = atom.indexOf('/');
            if (slash >= 0) {
                base = atom.substring(0, slash);
                step = parseInt(atom.substring(slash + 1), name, atom);
                if (step < 1) {
                    throw new IllegalArgumentException(
                            "Field " + name + " '" + atom + "': invalid step " + step);
                }
            }
            int lo;
            int hi;
            if (base.equals("*")) {
                lo = min;
                hi = max;
            } else {
                int dash = base.indexOf('-', 1);
                if (dash > 0) {
                    lo = parseInt(base.substring(0, dash), name, atom);
                    hi = parseInt(base.substring(dash + 1), name, atom);
                } else {
                    lo = parseInt(base, name, atom);
                    hi = slash >= 0 ? max : lo;
                }
            }
            if (lo < min || hi > max || lo > hi) {
                throw new IllegalArgumentException("Field " + name + " '" + atom
                        + "': out of range " + min + "-" + max);
            }
            for (int value = lo; value <= hi; value += step) {
                field[value] = true;
            }
        }
    }

    /** Day-of-week field over 0-7 collapsing 7 onto Sunday (0). */
    private void fillDayOfWeek(String spec) {
        boolean[] raw = new boolean[8];
        fill(raw, spec, 0, 7, "day of week");
        for (int value = 0; value <= 7; value++) {
            if (raw[value]) {
                daysOfWeek[value % 7] = true;
            }
        }
    }

    /** Parses {@code HH:mm} for the daily shortcut; empty means midnight. */
    private static int[] parseClock(String time, String expr) {
        if (time.isEmpty()) {
            return new int[] {0, 0};
        }
        int colon = time.indexOf(':');
        if (colon < 1) {
            throw new IllegalArgumentException(
                    "Daily shortcut '" + expr + "': expected an HH:mm time");
        }
        int hour = parseInt(time.substring(0, colon), "hour", expr);
        int minute = parseInt(time.substring(colon + 1), "minute", expr);
        if (hour < 0 || hour > 23 || minute < 0 || minute > 59) {
            throw new IllegalArgumentException(
                    "Daily shortcut '" + expr + "': time out of range");
        }
        return new int[] {hour, minute};
    }

    /** Parses {@code :mm} (or bare {@code mm}) for the hourly shortcut; empty means :00. */
    private static int parseHourlyMinute(String time, String expr) {
        String digits = time.startsWith(":") ? time.substring(1) : time;
        if (digits.isEmpty()) {
            return 0;
        }
        int minute = parseInt(digits, "minute", expr);
        if (minute < 0 || minute > 59) {
            throw new IllegalArgumentException(
                    "Hourly shortcut '" + expr + "': minute out of range");
        }
        return minute;
    }

    private static int parseInt(String raw, String name, String atom) {
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException(
                    "Field " + name + " '" + atom + "': '" + raw + "' is not a number");
        }
    }
}
