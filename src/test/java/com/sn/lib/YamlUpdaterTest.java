package com.sn.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.sn.lib.yml.YamlUpdater;

/**
 * Golden test of the always-merge updater: missing keys land with their comments at
 * the anchored position, user values and extra keys stay untouched, and there is no
 * version marker key anywhere. All assertions compare {@code List<String>}.
 */
class YamlUpdaterTest {

    @Test
    void mergeMatchesGoldenExpected() throws IOException {
        List<String> merged = YamlUpdater.merge(fixture("merge-resource.yml"), fixture("merge-old.yml"));
        assertEquals(fixture("merge-expected.yml"), merged);
    }

    @Test
    void mergePreservesUserValuesAndExtraKeys() throws IOException {
        List<String> merged = YamlUpdater.merge(fixture("merge-resource.yml"), fixture("merge-old.yml"));
        assertTrue(merged.contains("  rows: 3"));
        assertTrue(merged.contains("  title: \"&bMy Custom Shop\""));
        assertTrue(merged.contains("  custom-flag: true"));
        assertTrue(merged.contains("  prefix: \"&7[MyShop]\""));
    }

    @Test
    void mergeInsertsNewKeysWithTheirComments() throws IOException {
        List<String> merged = YamlUpdater.merge(fixture("merge-resource.yml"), fixture("merge-old.yml"));
        int openSoundComment = merged.indexOf("  # NEW in this version: sound played on open.");
        int openSound = merged.indexOf("  open-sound: BLOCK_CHEST_OPEN");
        assertTrue(openSoundComment >= 0);
        assertEquals(openSoundComment + 1, openSound);
        int title = merged.indexOf("  title: \"&bMy Custom Shop\"");
        int customFlag = merged.indexOf("  custom-flag: true");
        assertTrue(title < openSoundComment && openSound < customFlag);
    }

    @Test
    void mergeInsertsWholeMissingSubsection() throws IOException {
        List<String> merged = YamlUpdater.merge(fixture("merge-resource.yml"), fixture("merge-old.yml"));
        int header = merged.indexOf("# NEW in this version: storage backend.");
        int section = merged.indexOf("storage:");
        int type = merged.indexOf("  type: sqlite");
        int prefix = merged.indexOf("  table-prefix: \"sn_\"");
        assertTrue(header >= 0 && section == header + 1);
        assertTrue(section < type && type < prefix);
        // The new subsection lands between its resource siblings: settings before, messages after.
        assertTrue(merged.indexOf("settings:") < section);
        assertTrue(section < merged.indexOf("messages:"));
    }

    @Test
    void pruneIsOptInAndRemovesOnlyKeysAbsentFromResource() throws IOException {
        List<String> resource = fixture("merge-resource.yml");
        List<String> merged = YamlUpdater.merge(resource, fixture("merge-old.yml"));
        // Default merge path: the user's extra key survives.
        assertTrue(merged.contains("  custom-flag: true"));
        List<String> pruned = YamlUpdater.prune(resource, merged);
        assertFalse(pruned.contains("  custom-flag: true"));
        assertFalse(pruned.contains("  # User-added key: must survive every merge untouched."));
        // Shared keys keep the user's values and the result still parses.
        assertTrue(pruned.contains("  rows: 3"));
        assertTrue(pruned.contains("  title: \"&bMy Custom Shop\""));
        assertTrue(pruned.contains("  prefix: \"&7[MyShop]\""));
        assertTrue(YamlUpdater.isParseable(String.join("\n", pruned)));
    }

    @Test
    void pruneIsANoOpWhenDiskMatchesResourceStructure() throws IOException {
        List<String> resource = fixture("merge-resource.yml");
        assertEquals(resource, YamlUpdater.prune(resource, resource));
    }

    @Test
    void mergeIsIdempotentOnUpToDateFile() throws IOException {
        List<String> expected = fixture("merge-expected.yml");
        assertEquals(expected, YamlUpdater.merge(fixture("merge-resource.yml"), expected));
    }

    @Test
    void mergedResultHasNoVersionMarkerAndStaysParseable() throws IOException {
        List<String> merged = YamlUpdater.merge(fixture("merge-resource.yml"), fixture("merge-old.yml"));
        assertTrue(merged.stream().noneMatch(line -> line.contains("config-version")));
        assertTrue(YamlUpdater.isParseable(String.join("\n", merged)));
    }

    @Test
    void corruptYamlIsDetectedAsUnparseable() throws IOException {
        assertFalse(YamlUpdater.isParseable(String.join("\n", fixture("corrupt.yml"))));
        assertTrue(YamlUpdater.isParseable(String.join("\n", fixture("merge-old.yml"))));
    }

    @Test
    void quotedAndUnquotedKeysCompareEqualOnMerge() {
        List<String> resource = List.of("foo: 1");
        List<String> disk = List.of("'foo': 2");
        assertEquals(disk, YamlUpdater.merge(resource, disk));
    }

    @Test
    void quotedResourceKeyInsertsWithItsTextualForm() {
        List<String> resource = List.of("foo: 1", "\"bar\": 3");
        List<String> disk = List.of("foo: 1");
        List<String> merged = YamlUpdater.merge(resource, disk);
        assertEquals(List.of("foo: 1", "\"bar\": 3"), merged);
    }

    @Test
    void pruneKeepsKeyWhenOnlyQuotingDiffers() {
        List<String> resource = List.of("foo:", "  bar: 1");
        List<String> disk = List.of("\"foo\":", "  bar: 1");
        assertEquals(disk, YamlUpdater.prune(resource, disk));
    }

    // ------------------------------------------------------------------
    // Owner-owned sections (# sn:extensible)
    // ------------------------------------------------------------------

    @Test
    void extensibleMergeMatchesGoldenExpected() throws IOException {
        List<String> merged = YamlUpdater.merge(fixture("extensible-resource.yml"),
                fixture("extensible-old.yml"));
        assertEquals(fixture("extensible-expected.yml"), merged);
    }

    @Test
    void entriesTheOwnerDeletedInAMarkedSectionStayDeleted() throws IOException {
        List<String> merged = YamlUpdater.merge(fixture("extensible-resource.yml"),
                fixture("extensible-old.yml"));
        // Whole entry deleted by the owner.
        assertFalse(merged.stream().anyMatch(line -> line.trim().equals("mobkills:")));
        // Deletion deeper in the subtree: one trigger of an entry the owner kept.
        assertFalse(merged.stream().anyMatch(line -> line.trim().equals("death: 1")));
        // What the owner added survives, as it already did before markers existed.
        assertTrue(merged.stream().anyMatch(line -> line.trim().equals("raids:")));
    }

    @Test
    void schemaKeysOutsideAMarkedSectionStillMerge() throws IOException {
        List<String> merged = YamlUpdater.merge(fixture("extensible-resource.yml"),
                fixture("extensible-old.yml"));
        int comment = merged.indexOf("  # NEW in this version: seconds between leaderboard refreshes.");
        int key = merged.indexOf("  refresh-seconds: 60");
        assertTrue(comment >= 0);
        assertEquals(comment + 1, key);
        assertTrue(merged.contains("  top-size: 25"));
    }

    @Test
    void withoutTheMarkerTheSameDeletionsAreRestored() throws IOException {
        List<String> unmarked = new ArrayList<>(fixture("extensible-resource.yml"));
        unmarked.removeIf(line -> line.trim().equalsIgnoreCase("# " + YamlUpdater.EXTENSIBLE_MARKER));
        List<String> merged = YamlUpdater.merge(unmarked, fixture("extensible-old.yml"));
        assertTrue(merged.stream().anyMatch(line -> line.trim().equals("mobkills:")));
        assertTrue(merged.stream().anyMatch(line -> line.trim().equals("death: 1")));
    }

    @Test
    void deletingTheMarkedSectionHeaderRestoresItWithItsEntries() throws IOException {
        List<String> merged = YamlUpdater.merge(fixture("extensible-resource.yml"),
                List.of("settings:", "  top-size: 10"));
        assertTrue(merged.contains("points:"));
        assertTrue(merged.stream().anyMatch(line -> line.trim().equals("mobkills:")));
    }

    @Test
    void anEmptyMarkedSectionIsLeftEmpty() throws IOException {
        List<String> merged = YamlUpdater.merge(fixture("extensible-resource.yml"),
                List.of("points: {}"));
        assertTrue(merged.contains("points: {}"));
        assertFalse(merged.stream().anyMatch(line -> line.trim().equals("kills:")));
    }

    @Test
    void pruneKeepsOwnerEntriesInsideAMarkedSection() throws IOException {
        List<String> resource = fixture("extensible-resource.yml");
        List<String> disk = fixture("extensible-old.yml");
        List<String> pruned = YamlUpdater.prune(resource, disk);
        assertTrue(pruned.stream().anyMatch(line -> line.trim().equals("raids:")));
        assertEquals(disk, pruned);
    }

    @Test
    void extensibleMergeIsIdempotentAndStaysParseable() throws IOException {
        List<String> expected = fixture("extensible-expected.yml");
        assertEquals(expected, YamlUpdater.merge(fixture("extensible-resource.yml"), expected));
        assertTrue(YamlUpdater.isParseable(String.join("\n", expected)));
    }

    @Test
    void rootMarkerFreezesTheWholeTopLevelKeyset() {
        List<String> resource = List.of(
                "# Items catalogue.",
                "# " + YamlUpdater.EXTENSIBLE_ROOT_MARKER,
                "",
                "sword:",
                "  material: DIAMOND_SWORD",
                "shield:",
                "  material: SHIELD");
        List<String> disk = List.of("sword:", "  material: NETHERITE_SWORD");
        assertEquals(disk, YamlUpdater.merge(resource, disk));
        assertEquals(disk, YamlUpdater.prune(resource, disk));
    }

    @Test
    void withoutTheRootMarkerTopLevelKeysStillMerge() {
        List<String> resource = List.of(
                "# Items catalogue.",
                "",
                "sword:",
                "  material: DIAMOND_SWORD",
                "shield:",
                "  material: SHIELD");
        List<String> merged = YamlUpdater.merge(resource, List.of("sword:", "  material: NETHERITE_SWORD"));
        assertTrue(merged.contains("shield:"));
        assertTrue(merged.contains("  material: NETHERITE_SWORD"));
    }

    @Test
    void markerWarningsFlagAMarkerPlacedOnAValue() {
        List<String> warnings = YamlUpdater.markerWarnings(
                List.of("# " + YamlUpdater.EXTENSIBLE_MARKER, "max-uses: 10"));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("max-uses"));
    }

    @Test
    void markerWarningsStayQuietOnRealSections() throws IOException {
        // An empty section is a legitimate "no entries yet" catalogue, not a mistake.
        assertTrue(YamlUpdater.markerWarnings(List.of(
                "# " + YamlUpdater.EXTENSIBLE_MARKER,
                "cores: {}",
                "# " + YamlUpdater.EXTENSIBLE_MARKER,
                "worlds:",
                "  overworld:",
                "    enabled: true")).isEmpty());
        assertTrue(YamlUpdater.markerWarnings(fixture("extensible-resource.yml")).isEmpty());
        assertTrue(YamlUpdater.markerWarnings(fixture("merge-resource.yml")).isEmpty());
    }

    private static List<String> fixture(String name) throws IOException {
        try (InputStream in = YamlUpdaterTest.class.getResourceAsStream("/yml/" + name)) {
            if (in == null) {
                throw new IOException("Fixture not found: " + name);
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = reader.readLine()) != null) {
                    lines.add(line);
                }
            }
            return lines;
        }
    }
}
