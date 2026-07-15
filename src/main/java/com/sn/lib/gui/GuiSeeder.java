package com.sn.lib.gui;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.net.URISyntaxException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.Enumeration;
import java.util.List;
import java.util.Locale;
import java.util.TreeSet;
import java.util.jar.JarEntry;
import java.util.jar.JarFile;
import java.util.logging.Logger;

import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.yml.YamlUpdater;

/**
 * Seeds the bundled {@code guis/*.yml} of a consumer jar into its data folder before the
 * {@link GuiManager} lists the folder.
 *
 * <p>Bukkit cannot enumerate a resource directory through {@code getResource}, so the
 * seeder enumerates the CONSUMER plugin's own jar (resolved from the code source of the
 * plugin's main class, never SnLib's jar) and, for every top-level entry directly under
 * {@code guis/} ending in {@code .yml}, applies the managed semantics reused from
 * {@link YamlUpdater#updateFromLines(Logger, List, File, File)}: a missing file is seeded
 * from the jar, an existing file is always-merged through the same updater config and lang
 * use, gated by {@code update-configs}. Nested entries ({@code guis/sub/x.yml}) and
 * non-yml entries are ignored.</p>
 *
 * <p>The filtering ({@link #guiResourcePaths(Iterable)}) and the whole
 * {@link #seed(File, File, File, Logger)} pipeline take a jar {@link File} and a
 * {@link Logger} rather than a live {@link JavaPlugin}, so they run under plain unit tests
 * against a temp jar. Synchronous I/O by design: it runs only from {@link GuiManager#load()}
 * (onEnable and the reload flow), never during gameplay.</p>
 */
final class GuiSeeder {

    /** Folder that holds one menu per file, both as a jar resource prefix and on disk. */
    static final String GUIS_DIR = "guis";

    private static final String GUIS_PREFIX = GUIS_DIR + "/";
    private static final String YML_SUFFIX = ".yml";

    private GuiSeeder() {
    }

    /**
     * Filters jar entry names to the sorted, distinct list of top-level
     * {@code guis/<name>.yml} resource paths. Pure and testable: separators are normalized
     * to {@code /}, the {@code guis/} prefix and {@code .yml} suffix are matched
     * case-insensitively, and any entry that is nested below {@code guis/} or is the folder
     * entry itself is dropped.
     */
    static List<String> guiResourcePaths(Iterable<String> entryNames) {
        TreeSet<String> paths = new TreeSet<>();
        for (String raw : entryNames) {
            if (raw == null) {
                continue;
            }
            String name = raw.replace('\\', '/');
            String lower = name.toLowerCase(Locale.ROOT);
            if (!lower.startsWith(GUIS_PREFIX) || !lower.endsWith(YML_SUFFIX)) {
                continue;
            }
            String remainder = name.substring(GUIS_PREFIX.length());
            if (remainder.isEmpty() || remainder.indexOf('/') >= 0) {
                continue;
            }
            // Keep the entry name verbatim (case preserved): it must round-trip back to the
            // jar for reading and to the disk path for writing.
            paths.add(name);
        }
        return new ArrayList<>(paths);
    }

    /** Enumerates a jar file and returns its top-level {@code guis/*.yml} resource paths. */
    static List<String> guiResourcePaths(File jar) throws IOException {
        try (JarFile jarFile = new JarFile(jar)) {
            return guiResourcePaths(entryNames(jarFile));
        }
    }

    /**
     * Seeds/merges every bundled {@code guis/*.yml} of {@code jar} into
     * {@code dataFolder/guis/}. Returns the resource paths that were found in the jar (the
     * folder stays empty and the list is empty when the jar bundles no menu). Never throws:
     * a jar that cannot be read yields an empty list and one WARN.
     *
     * @param jar        consumer jar to enumerate
     * @param dataFolder consumer data folder the {@code guis/} tree is written under
     * @param gateFile   config file holding the {@code update-configs} gate, or null to
     *                   merge unconditionally
     * @param logger     consumer logger for the seeder and the reused updater
     */
    static List<String> seed(File jar, File dataFolder, @Nullable File gateFile, Logger logger) {
        try (JarFile jarFile = new JarFile(jar)) {
            List<String> paths = guiResourcePaths(entryNames(jarFile));
            for (String path : paths) {
                List<String> lines = readEntry(jarFile, path);
                if (lines == null) {
                    logger.warning("[gui] Could not read bundled resource " + path
                            + " from " + jar.getName() + "; it was not seeded");
                    continue;
                }
                YamlUpdater.updateFromLines(logger, lines, new File(dataFolder, path), gateFile);
            }
            return paths;
        } catch (IOException ex) {
            logger.warning("[gui] Could not read " + jar.getName()
                    + " to seed the guis/ folder: " + ex.getMessage());
            return List.of();
        }
    }

    /**
     * Resolves the jar of the CONSUMER plugin from the code source of its main class, so
     * the seeded resources always come from the consumer and never from SnLib's own jar.
     * Returns null when the location cannot be resolved (WARNed by the caller).
     */
    static @Nullable File consumerJar(JavaPlugin plugin) {
        ProtectionDomain domain = plugin.getClass().getProtectionDomain();
        if (domain == null) {
            return null;
        }
        CodeSource source = domain.getCodeSource();
        if (source == null || source.getLocation() == null) {
            return null;
        }
        URL location = source.getLocation();
        try {
            File file = new File(location.toURI());
            return file.isFile() ? file : null;
        } catch (URISyntaxException | IllegalArgumentException ex) {
            return null;
        }
    }

    private static List<String> entryNames(JarFile jarFile) {
        List<String> names = new ArrayList<>();
        Enumeration<JarEntry> entries = jarFile.entries();
        while (entries.hasMoreElements()) {
            JarEntry entry = entries.nextElement();
            if (!entry.isDirectory()) {
                names.add(entry.getName());
            }
        }
        return names;
    }

    private static @Nullable List<String> readEntry(JarFile jarFile, String path) {
        JarEntry entry = jarFile.getJarEntry(path);
        if (entry == null || entry.isDirectory()) {
            return null;
        }
        try (InputStream in = jarFile.getInputStream(entry);
             BufferedReader reader = new BufferedReader(
                     new InputStreamReader(in, StandardCharsets.UTF_8))) {
            List<String> lines = new ArrayList<>();
            String line;
            while ((line = reader.readLine()) != null) {
                lines.add(line);
            }
            return lines;
        } catch (IOException ex) {
            return null;
        }
    }
}
