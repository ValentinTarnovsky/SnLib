package com.sn.lib.yml.internal;

import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

/**
 * Names of the backups {@code YamlUpdater} writes next to a managed file before merging it:
 * {@code old-<base>-<yyyyMMdd-HHmmss>.yml}. The pruning of those backups and every lister of a
 * folder of managed files build their patterns on the same {@link #STAMP}, so the two rules
 * never drift apart. Internal: not part of the public API.
 */
public final class MergeBackups {

    /** Regex of the stamp and extension closing every backup name: {@code yyyyMMdd-HHmmss.yml}. */
    public static final String STAMP = "\\d{8}-\\d{6}\\.yml";

    /** Any backup, whatever its base, ignoring case. */
    private static final Pattern ANY = Pattern.compile("(?i)old-.+-" + STAMP);

    private MergeBackups() {
    }

    /**
     * Whether a file name is a merge backup ({@code old-<base>-<yyyyMMdd-HHmmss>.yml}, ignoring
     * case), so a lister of a folder of managed files skips it. A file whose name only starts
     * with {@code old} ({@code old-town.yml}, {@code oldies.yml}) is not one.
     */
    public static boolean isMergeBackup(@Nullable String fileName) {
        return fileName != null && ANY.matcher(fileName).matches();
    }
}
