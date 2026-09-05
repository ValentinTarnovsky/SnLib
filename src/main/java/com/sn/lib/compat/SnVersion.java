package com.sn.lib.compat;

import org.bukkit.Bukkit;

/**
 * Server version detection, parsed once at class initialization.
 *
 * <p>Parses {@link Bukkit#getBukkitVersion()}, never {@code getVersion()}, whose free-form
 * text varies per fork. Both numbering schemes are recognized: the classic
 * {@code 1.20.4}-{@code 1.21.x} line ({@code 1.21.1-R0.1-SNAPSHOT}) and Mojang's year-based
 * numbering in force since 26.1 ({@code 26.2.build.2632-stable}), which is newer than every
 * 1.x and therefore passes every {@link #supports} gate. Only a string that carries no
 * version at all logs one WARN and reports full support: SnLib tolerates unknown servers
 * and never hard-fails on version detection.</p>
 *
 * <p>Server-wide statics allowed by the SnLib contract: the server version is not
 * per-consumer data.</p>
 */
public final class SnVersion {

    private static final ParsedVersion PARSED;

    /** Parsed major version (1 on the classic line, the year on 26.1+), or 1 when unparseable. */
    public static final int MAJOR;

    /** Parsed minor version (the drop on 26.1+), or 21 when the version string could not be parsed. */
    public static final int MINOR;

    /** Parsed patch version; 0 when absent, 8 when the string could not be parsed. */
    public static final int PATCH;

    private static final boolean FOLIA = detectFolia();

    static {
        String raw = Bukkit.getBukkitVersion();
        PARSED = ParsedVersion.parse(raw);
        if (!PARSED.parsed()) {
            Bukkit.getLogger().warning(
                    "[SnLib] '" + raw + "': unrecognized version, assuming compat target");
        }
        MAJOR = PARSED.major();
        MINOR = PARSED.minor();
        PATCH = PARSED.patch();
    }

    private SnVersion() {
    }

    /** True when the server runs 1.{@code minor} or newer (always true on 26.1+ and on unparseable strings). */
    public static boolean supports(int minor) {
        return PARSED.atLeast(minor, 0);
    }

    /** True when the server runs 1.{@code minor}.{@code patch} or newer (always true on 26.1+ and on unparseable strings). */
    public static boolean supports(int minor, int patch) {
        return PARSED.atLeast(minor, patch);
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
