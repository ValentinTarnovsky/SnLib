package com.sn.lib.compat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The parsed shape of a {@code Bukkit.getBukkitVersion()} string and the whole
 * {@link SnVersion#supports} decision, kept apart from {@link SnVersion} so it can be
 * exercised without a running server (that class touches {@code Bukkit} in its static
 * initializer).
 *
 * <p>Two numbering schemes are recognized. The classic {@code 1.MINOR[.PATCH]} line
 * ({@code 1.21.1-R0.1-SNAPSHOT}) and Mojang's year-based numbering in force since 26.1
 * (March 2026), which Paper reports as {@code YEAR.DROP[.PATCH].build.N-status}
 * ({@code 26.2.build.2632-stable}, {@code 26.1.2.build.63-stable}). A year-based version
 * is newer than every 1.x, so it satisfies every {@code 1.MINOR} gate.</p>
 *
 * <p>Package-private on purpose: not part of the public API surface.</p>
 *
 * @param major  parsed major, or 1 when the string could not be parsed
 * @param minor  parsed minor, or the classic target minor (21) when unparseable
 * @param patch  parsed patch; 0 when absent, the classic target patch (8) when unparseable
 * @param parsed false when the string carried no {@code MAJOR.MINOR} pair at all
 */
record ParsedVersion(int major, int minor, int patch, boolean parsed) {

    private static final Pattern PATTERN = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?");

    /** Values reported when the string cannot be parsed: the last classic target. */
    private static final ParsedVersion UNPARSED = new ParsedVersion(1, 21, 8, false);

    /** Parses the first {@code MAJOR.MINOR[.PATCH]} group of {@code raw}, never throws. */
    static ParsedVersion parse(String raw) {
        if (raw == null) {
            return UNPARSED;
        }
        Matcher matcher = PATTERN.matcher(raw);
        if (!matcher.find()) {
            return UNPARSED;
        }
        int major = Integer.parseInt(matcher.group(1));
        int minor = Integer.parseInt(matcher.group(2));
        int patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
        return new ParsedVersion(major, minor, patch, true);
    }

    /** True on Mojang's year-based numbering (26.1+), which is newer than every 1.x. */
    boolean yearScheme() {
        return major > 1;
    }

    /**
     * True when this version is {@code 1.minor.patch} or newer. Always true on a
     * year-based version and on an unparseable string (forward tolerance, never hard-fail).
     */
    boolean atLeast(int minor, int patch) {
        if (!parsed || yearScheme()) {
            return true;
        }
        return this.minor > minor || (this.minor == minor && this.patch >= patch);
    }
}
