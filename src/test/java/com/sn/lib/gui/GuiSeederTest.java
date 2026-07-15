package com.sn.lib.gui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.logging.Handler;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Unit tests for the gui seeder: the jar-entry filter, the temp-jar enumeration and the
 * seed/merge decision (missing seeded, existing merged only when the {@code update-configs}
 * gate is open, non-yml and nested entries ignored, empty jar a no-op). The plugin logger
 * is replaced by a capturing {@link Logger} so the gate-closed WARN is asserted directly.
 */
class GuiSeederTest {

    // ------------------------------------------------------------------
    // Pure filtering
    // ------------------------------------------------------------------

    @Test
    void filterKeepsOnlyTopLevelGuisYml() {
        List<String> paths = GuiSeeder.guiResourcePaths(List.of(
                "guis/shop.yml",
                "guis/settings.yml",
                "guis/readme.txt",       // non-yml: ignored
                "guis/sub/deep.yml",     // nested: ignored
                "lang/messages_en.yml",  // not under guis/: ignored
                "config.yml",            // not under guis/: ignored
                "plugin.yml",
                "com/example/Main.class"));
        assertEquals(List.of("guis/settings.yml", "guis/shop.yml"), paths);
    }

    @Test
    void filterIgnoresTheFolderEntryItself() {
        assertTrue(GuiSeeder.guiResourcePaths(List.of("guis/", "guis")).isEmpty());
    }

    @Test
    void filterIsCaseInsensitiveOnPrefixAndSuffixAndNormalizesSeparators() {
        List<String> paths = GuiSeeder.guiResourcePaths(List.of(
                "GUIS/Shop.YML",
                "guis\\windows.yml"));
        assertEquals(List.of("GUIS/Shop.YML", "guis/windows.yml"), paths);
    }

    @Test
    void filterIsSortedAndDistinct() {
        List<String> paths = GuiSeeder.guiResourcePaths(List.of(
                "guis/b.yml", "guis/a.yml", "guis/b.yml"));
        assertEquals(List.of("guis/a.yml", "guis/b.yml"), paths);
    }

    // ------------------------------------------------------------------
    // Jar enumeration
    // ------------------------------------------------------------------

    @Test
    void enumerationReadsOnlyGuiYmlFromJar(@TempDir File dir) throws IOException {
        File jar = buildJar(new File(dir, "consumer.jar"), Map.of(
                "guis/shop.yml", "title: Shop\n",
                "guis/settings.yml", "title: Settings\n",
                "guis/notes.txt", "ignored\n",
                "guis/nested/deep.yml", "title: Deep\n",
                "lang/messages_en.yml", "greeting: hi\n"));
        assertEquals(List.of("guis/settings.yml", "guis/shop.yml"),
                GuiSeeder.guiResourcePaths(jar));
    }

    // ------------------------------------------------------------------
    // Seed / merge decision
    // ------------------------------------------------------------------

    @Test
    void missingFileIsSeededFromJar(@TempDir File dir) throws IOException {
        File jar = buildJar(new File(dir, "consumer.jar"), Map.of(
                "guis/shop.yml", "title: Shop\nrows: 3\n"));
        File dataFolder = new File(dir, "data");
        CapturingLogger log = new CapturingLogger();

        List<String> seeded = GuiSeeder.seed(jar, dataFolder, gate(dir, true), log.logger);

        assertEquals(List.of("guis/shop.yml"), seeded);
        File onDisk = new File(dataFolder, "guis/shop.yml");
        assertTrue(onDisk.isFile());
        assertEquals(List.of("title: Shop", "rows: 3"), readLines(onDisk));
        assertFalse(log.hasWarning(), "seeding a missing file must not WARN: " + log.messages());
    }

    @Test
    void existingFileIsMergedWhenGateOpen(@TempDir File dir) throws IOException {
        File jar = buildJar(new File(dir, "consumer.jar"), Map.of(
                "guis/shop.yml", "title: Shop\nrows: 3\nnew-key: added\n"));
        File dataFolder = new File(dir, "data");
        File onDisk = new File(dataFolder, "guis/shop.yml");
        writeLines(onDisk, "title: \"My Custom Shop\"", "rows: 6");

        GuiSeeder.seed(jar, dataFolder, gate(dir, true), new CapturingLogger().logger);

        List<String> merged = readLines(onDisk);
        assertTrue(merged.contains("new-key: added"), merged.toString());
        // User values survive the merge untouched.
        assertTrue(merged.contains("title: \"My Custom Shop\""), merged.toString());
        assertTrue(merged.contains("rows: 6"), merged.toString());
    }

    @Test
    void existingFileIsUntouchedAndWarnsWhenGateClosed(@TempDir File dir) throws IOException {
        File jar = buildJar(new File(dir, "consumer.jar"), Map.of(
                "guis/shop.yml", "title: Shop\nrows: 3\nnew-key: added\n"));
        File dataFolder = new File(dir, "data");
        File onDisk = new File(dataFolder, "guis/shop.yml");
        writeLines(onDisk, "title: \"My Custom Shop\"", "rows: 6");
        CapturingLogger log = new CapturingLogger();

        GuiSeeder.seed(jar, dataFolder, gate(dir, false), log.logger);

        assertEquals(List.of("title: \"My Custom Shop\"", "rows: 6"), readLines(onDisk));
        assertTrue(log.messages().stream().anyMatch(m -> m.contains("update-configs is false")),
                "gate-closed merge must WARN: " + log.messages());
    }

    @Test
    void nonYmlAndNestedAndNonGuiEntriesAreNotSeeded(@TempDir File dir) throws IOException {
        File jar = buildJar(new File(dir, "consumer.jar"), Map.of(
                "guis/shop.yml", "title: Shop\n",
                "guis/notes.txt", "ignored\n",
                "guis/nested/deep.yml", "title: Deep\n",
                "lang/messages_en.yml", "greeting: hi\n"));
        File dataFolder = new File(dir, "data");

        List<String> seeded = GuiSeeder.seed(jar, dataFolder, gate(dir, true),
                new CapturingLogger().logger);

        assertEquals(List.of("guis/shop.yml"), seeded);
        assertTrue(new File(dataFolder, "guis/shop.yml").isFile());
        assertFalse(new File(dataFolder, "guis/notes.txt").exists());
        assertFalse(new File(dataFolder, "guis/nested/deep.yml").exists());
        assertFalse(new File(dataFolder, "lang/messages_en.yml").exists());
    }

    @Test
    void emptyJarLeavesFolderEmptyAndDoesNotWarn(@TempDir File dir) throws IOException {
        File jar = buildJar(new File(dir, "consumer.jar"), Map.of(
                "config.yml", "update-configs: true\n",
                "plugin.yml", "name: X\n"));
        File dataFolder = new File(dir, "data");
        CapturingLogger log = new CapturingLogger();

        List<String> seeded = GuiSeeder.seed(jar, dataFolder, gate(dir, true), log.logger);

        assertTrue(seeded.isEmpty());
        assertFalse(new File(dataFolder, "guis").exists(),
                "no bundled menu means nothing is written under guis/");
        assertFalse(log.hasWarning(), "a jar without menus is a clean no-op: " + log.messages());
    }

    @Test
    void unreadableJarReturnsEmptyAndWarns(@TempDir File dir) {
        File missing = new File(dir, "does-not-exist.jar");
        File dataFolder = new File(dir, "data");
        CapturingLogger log = new CapturingLogger();

        List<String> seeded = GuiSeeder.seed(missing, dataFolder, gate(dir, true), log.logger);

        assertTrue(seeded.isEmpty());
        assertTrue(log.hasWarning(), "an unreadable jar must WARN");
    }

    // ------------------------------------------------------------------
    // Helpers
    // ------------------------------------------------------------------

    /** Writes a config gate file with the given {@code update-configs} value. */
    private static File gate(File dir, boolean value) {
        File config = new File(dir, "config.yml");
        try {
            Files.write(config.toPath(), List.of("update-configs: " + value),
                    StandardCharsets.UTF_8);
        } catch (IOException ex) {
            throw new AssertionError(ex);
        }
        return config;
    }

    private static File buildJar(File jar, Map<String, String> entries) throws IOException {
        try (JarOutputStream out = new JarOutputStream(Files.newOutputStream(jar.toPath()))) {
            for (Map.Entry<String, String> entry : entries.entrySet()) {
                out.putNextEntry(new JarEntry(entry.getKey()));
                out.write(entry.getValue().getBytes(StandardCharsets.UTF_8));
                out.closeEntry();
            }
        }
        return jar;
    }

    private static List<String> readLines(File file) throws IOException {
        return Files.readAllLines(file.toPath(), StandardCharsets.UTF_8);
    }

    private static void writeLines(File file, String... lines) throws IOException {
        Files.createDirectories(file.toPath().getParent());
        Files.write(file.toPath(), List.of(lines), StandardCharsets.UTF_8);
    }

    /** A named logger with an in-memory handler so the seeder's WARNs are assertable. */
    private static final class CapturingLogger {
        final Logger logger = Logger.getLogger("GuiSeederTest-" + System.nanoTime());
        private final List<LogRecord> records = new ArrayList<>();

        CapturingLogger() {
            logger.setUseParentHandlers(false);
            logger.setLevel(Level.ALL);
            logger.addHandler(new Handler() {
                @Override public void publish(LogRecord record) {
                    records.add(record);
                }

                @Override public void flush() {
                }

                @Override public void close() {
                }
            });
        }

        List<String> messages() {
            List<String> out = new ArrayList<>();
            for (LogRecord record : records) {
                out.add(record.getMessage());
            }
            return out;
        }

        boolean hasWarning() {
            return records.stream().anyMatch(r -> r.getLevel().intValue() >= Level.WARNING.intValue());
        }
    }
}
