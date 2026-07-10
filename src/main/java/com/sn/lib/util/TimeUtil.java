package com.sn.lib.util;

/**
 * Duration parsing and humanizing helpers (pure, no Bukkit).
 *
 * <p>Parses compact duration strings such as {@code "1d 2h 30m 15s"} (units
 * {@code d/h/m/s}, plus {@code t} for ticks and {@code ms} for milliseconds;
 * bare numbers count as seconds, decimals allowed) and renders millisecond
 * durations back to text. Output labels are injectable via {@link Labels}
 * for i18n; {@link #ENGLISH} is the default.</p>
 */
public final class TimeUtil {

    private static final long MILLIS_PER_TICK = 50L;
    private static final long MILLIS_PER_SECOND = 1_000L;
    private static final long MILLIS_PER_MINUTE = 60_000L;
    private static final long MILLIS_PER_HOUR = 3_600_000L;
    private static final long MILLIS_PER_DAY = 86_400_000L;

    /** Time components rendered by {@link #humanize(long)} and {@link #humanizeShort(long)}. */
    public enum Unit {
        DAY, HOUR, MINUTE, SECOND
    }

    /** Injectable label provider for i18n of humanized durations. */
    public interface Labels {

        /** Long-form label for a unit, such as {@code "day"} or {@code "days"}. */
        String longLabel(Unit unit, boolean plural);

        /** Compact suffix for a unit, such as {@code "d"}. */
        String shortLabel(Unit unit);
    }

    /** Default English labels. */
    public static final Labels ENGLISH = new Labels() {
        @Override
        public String longLabel(Unit unit, boolean plural) {
            String base = switch (unit) {
                case DAY -> "day";
                case HOUR -> "hour";
                case MINUTE -> "minute";
                case SECOND -> "second";
            };
            return plural ? base + "s" : base;
        }

        @Override
        public String shortLabel(Unit unit) {
            return switch (unit) {
                case DAY -> "d";
                case HOUR -> "h";
                case MINUTE -> "m";
                case SECOND -> "s";
            };
        }
    };

    private TimeUtil() {
    }

    /** Parses a duration string to server ticks (20 per second). */
    public static long parseTicks(String text) {
        return parseMillis(text) / MILLIS_PER_TICK;
    }

    /**
     * Parses a duration string to milliseconds.
     *
     * <p>Reads every {@code <number><unit>} pair, tolerating spaces, commas and
     * full unit words ({@code "1 day 2 hours"}). Unknown units and garbage are
     * skipped; null or unparseable input yields 0.</p>
     */
    public static long parseMillis(String text) {
        if (text == null || text.isBlank()) {
            return 0L;
        }
        long total = 0L;
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (!Character.isDigit(c) && c != '.') {
                i++;
                continue;
            }
            int start = i;
            while (i < length && (Character.isDigit(text.charAt(i)) || text.charAt(i) == '.')) {
                i++;
            }
            double value;
            try {
                value = Double.parseDouble(text.substring(start, i));
            } catch (NumberFormatException ex) {
                continue;
            }
            while (i < length && text.charAt(i) == ' ') {
                i++;
            }
            int wordStart = i;
            while (i < length && Character.isLetter(text.charAt(i))) {
                i++;
            }
            long unitMillis = unitMillis(text.substring(wordStart, i));
            if (unitMillis > 0) {
                total += Math.round(value * unitMillis);
            }
        }
        return Math.max(0L, total);
    }

    /** Millis for a unit word; empty means seconds; unknown yields -1 (skipped). */
    private static long unitMillis(String word) {
        if (word.isEmpty()) {
            return MILLIS_PER_SECOND;
        }
        String lower = word.toLowerCase(java.util.Locale.ROOT);
        if (lower.startsWith("ms")) {
            return 1L;
        }
        return switch (lower.charAt(0)) {
            case 'd' -> MILLIS_PER_DAY;
            case 'h' -> MILLIS_PER_HOUR;
            case 'm' -> MILLIS_PER_MINUTE;
            case 's' -> MILLIS_PER_SECOND;
            case 't' -> MILLIS_PER_TICK;
            default -> -1L;
        };
    }

    /** Long-form English rendering, such as {@code "1 day 2 hours 30 minutes 15 seconds"}. */
    public static String humanize(long millis) {
        return humanize(millis, ENGLISH);
    }

    /** Long-form rendering with injectable labels; zero components are omitted. */
    public static String humanize(long millis, Labels labels) {
        StringBuilder out = new StringBuilder();
        long[] parts = split(millis);
        Unit[] units = Unit.values();
        for (int i = 0; i < units.length; i++) {
            if (parts[i] <= 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(parts[i]).append(' ').append(labels.longLabel(units[i], parts[i] != 1));
        }
        if (out.length() == 0) {
            return "0 " + labels.longLabel(Unit.SECOND, true);
        }
        return out.toString();
    }

    /** Compact English rendering, such as {@code "1d 2h 30m 15s"}. */
    public static String humanizeShort(long millis) {
        return humanizeShort(millis, ENGLISH);
    }

    /** Compact rendering with injectable labels; zero components are omitted. */
    public static String humanizeShort(long millis, Labels labels) {
        StringBuilder out = new StringBuilder();
        long[] parts = split(millis);
        Unit[] units = Unit.values();
        for (int i = 0; i < units.length; i++) {
            if (parts[i] <= 0) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            out.append(parts[i]).append(labels.shortLabel(units[i]));
        }
        if (out.length() == 0) {
            return "0" + labels.shortLabel(Unit.SECOND);
        }
        return out.toString();
    }

    /** Splits millis into whole {day, hour, minute, second} components. */
    private static long[] split(long millis) {
        long seconds = Math.max(0L, millis) / MILLIS_PER_SECOND;
        return new long[] {
                seconds / 86_400L,
                (seconds % 86_400L) / 3_600L,
                (seconds % 3_600L) / 60L,
                seconds % 60L
        };
    }
}
