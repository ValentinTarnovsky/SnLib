package com.sn.lib.compat;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.bukkit.Bukkit;

/**
 * Server version detection, parsed once at class initialization.
 *
 * <p>Parses {@link Bukkit#getBukkitVersion()} (for example {@code 1.21.1-R0.1-SNAPSHOT}),
 * never {@code getVersion()}, whose free-form text varies per fork. An unparseable string
 * or an unknown forward version (1.22+) logs one WARN and reports full support: SnLib
 * tolerates newer servers and never hard-fails on version detection.</p>
 *
 * <p>Server-wide statics allowed by the SnLib contract: the server version is not
 * per-consumer data.</p>
 */
public final class SnVersion {

    /** Highest minor release line recognized by this build (target 1.21.8). */
    private static final int KNOWN_MAX_MINOR = 21;

    /** Parsed major version, or 1 when the version string could not be parsed. */
    public static final int MAJOR;

    /** Parsed minor version, or the target minor when the version string could not be parsed. */
    public static final int MINOR;

    /** Parsed patch version; 0 when absent, target patch when the string could not be parsed. */
    public static final int PATCH;

    /**
     * True when the running version is unknown (unparseable or 1.22+): {@link #supports}
     * then reports full support, forward tolerance instead of hard-fail.
     */
    private static final boolean ASSUME_TARGET;

    private static final boolean FOLIA = detectFolia();

    static {
        String raw = Bukkit.getBukkitVersion();
        Matcher matcher = Pattern.compile("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").matcher(raw);
        int major = 1;
        int minor = KNOWN_MAX_MINOR;
        int patch = 8;
        boolean assume = false;
        if (matcher.find()) {
            major = Integer.parseInt(matcher.group(1));
            minor = Integer.parseInt(matcher.group(2));
            patch = matcher.group(3) == null ? 0 : Integer.parseInt(matcher.group(3));
            if (major != 1 || minor > KNOWN_MAX_MINOR) {
                assume = true;
            }
        } else {
            assume = true;
        }
        if (assume) {
            Bukkit.getLogger().warning(
                    "[SnLib] '" + raw + "': unrecognized version, assuming compat target");
        }
        MAJOR = major;
        MINOR = minor;
        PATCH = patch;
        ASSUME_TARGET = assume;
    }

    private SnVersion() {
    }

    /** True when the server runs 1.{@code minor} or newer (always true on unknown versions). */
    public static boolean supports(int minor) {
        return ASSUME_TARGET || MINOR >= minor;
    }

    /** True when the server runs 1.{@code minor}.{@code patch} or newer (always true on unknown versions). */
    public static boolean supports(int minor, int patch) {
        return ASSUME_TARGET || MINOR > minor || (MINOR == minor && PATCH >= patch);
    }

    /** True when the server is Folia (RegionizedServer present), detected once and cached. */
    public static boolean isFolia() {
        return FOLIA;
    }

    private static boolean detectFolia() {
        try {
            Class.forName("io.papermc.paper.threadedregions.RegionizedServer");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
}
