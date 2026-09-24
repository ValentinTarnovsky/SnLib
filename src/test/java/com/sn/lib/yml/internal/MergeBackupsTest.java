package com.sn.lib.yml.internal;

import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The backup names {@code YamlUpdater} writes before a merge, as the menu listers and the
 * backup pruning recognize them. No server is needed.
 */
class MergeBackupsTest {

    @Test
    void aStampedBackupIsRecognized() {
        assertTrue(MergeBackups.isMergeBackup("old-visibility-20260924-101500.yml"));
        assertTrue(MergeBackups.isMergeBackup("old-my-menu-20260924-101500.yml"));
    }

    @Test
    void aBackupIsRecognizedWhateverItsCase() {
        assertTrue(MergeBackups.isMergeBackup("OLD-Visibility-20260924-101500.YML"));
    }

    @Test
    void aMenuWhoseNameOnlyStartsWithOldIsNotABackup() {
        assertFalse(MergeBackups.isMergeBackup("old-town.yml"));
        assertFalse(MergeBackups.isMergeBackup("oldies.yml"));
        assertFalse(MergeBackups.isMergeBackup("old-visibility-2026.yml"));
        assertFalse(MergeBackups.isMergeBackup("visibility.yml"));
        assertFalse(MergeBackups.isMergeBackup("old--20260924-101500.yml"));
        assertFalse(MergeBackups.isMergeBackup("old-visibility-20260924-101500.yml.bak"));
        assertFalse(MergeBackups.isMergeBackup(null));
    }

    @Test
    void thePruningPatternBuiltOnTheStampMatchesOnlyTheBackupsOfItsOwnFile() {
        // YamlUpdater.pruneOldBackups: the quoted old-<base>- prefix plus the shared stamp.
        Pattern config = Pattern.compile(Pattern.quote("old-config-") + MergeBackups.STAMP);
        assertTrue(config.matcher("old-config-20260924-101500.yml").matches());
        // A loose prefix would mix in the backups of config-extra.yml.
        assertFalse(config.matcher("old-config-extra-20260924-101500.yml").matches());
        assertFalse(config.matcher("old-config-2026.yml").matches());
        // Written in lowercase by the merge, pruned as written.
        assertFalse(config.matcher("OLD-config-20260924-101500.yml").matches());
    }
}
