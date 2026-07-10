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

    private static List<String> fixture(String name) throws IOException {
        try (InputStream in = YamlUpdaterTest.class.getResourceAsStream("/yml/" + name)) {
            if (in == null) {
                throw new IOException("Fixture no encontrado: " + name);
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
