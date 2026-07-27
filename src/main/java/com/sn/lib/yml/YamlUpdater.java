package com.sn.lib.yml;

import java.io.BufferedReader;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

/**
 * Line-based always-merge YAML updater. Inserts keys/sections present in the bundled
 * jar resource but missing from the on-disk file, preserving user values, comments,
 * list contents and any extra keys the user added. There is NO version marker key:
 * the resource is compared structurally against the disk file on every start.
 *
 * <p>Never deletes anything by default and never reformats existing lines; removal of
 * keys absent from the resource happens only when pruning is requested explicitly.
 * Indentation is assumed to use spaces and to be consistent between resource and disk
 * (both come from the same plugin baseline).</p>
 *
 * <p>User-owned sections: a {@code # sn:extensible} comment line inside the comment block
 * of a key declares that everything under that key is server-owner data rather than plugin
 * schema. Nothing below it is ever inserted or pruned, so an entry the owner deletes stays
 * deleted while entries they add keep surviving. The key itself remains schema: deleting the
 * whole section restores the block, an empty section ({@code points: {}}) is preserved as-is.
 * A {@code # sn:extensible-root} line in the file header applies the same rule to the
 * top-level keyset, for files whose every root key is an entry id.</p>
 *
 * <p>EITHER side may declare it, and the two only ever ADD protection (since 1.19.0):</p>
 * <ul>
 *   <li>The RESOURCE declaring it is the plugin author stating that those entries belong to
 *       the owner. It is binding: deleting the comment from the disk file does NOT switch the
 *       merge back on, so an owner cannot un-declare an author-owned section.</li>
 *   <li>The DISK file declaring it is the server owner freezing that subtree in their own
 *       file, for a section the author still manages. Keys the plugin would have inserted
 *       there are withheld and reported once per boot, naming the file and the key.</li>
 * </ul>
 *
 * <p>The flip side of freezing a section the author still owns is that schema keys added in
 * a later version never reach it. That is the owner's call to make, which is why it is
 * reported rather than silently honored.</p>
 *
 * <p>Master gate: the boolean {@code update-configs} is read by parsing the consumer
 * config straight from DISK before any merge; an absent key or file counts as
 * {@code true}. The consumer's own config file is EXEMPT from the gate (it is merged
 * always) so the {@code update-configs} key itself can arrive through a merge on the
 * first start after an upgrade.</p>
 *
 * <p>Backups: before writing a merge, the disk file is copied to
 * {@code old-<name>-<yyyyMMdd-HHmmss>.yml} next to it, keeping only the last 3. A disk
 * file that does not parse as YAML is moved to {@code <name>.backup-N} and reseeded
 * from the jar, never crashing the caller.</p>
 *
 * <p>Synchronous I/O by design: {@link #update} runs only inside onEnable and inside
 * the reload command, never during gameplay. This is the documented exception to the
 * async I/O rule.</p>
 */
public final class YamlUpdater {

    private static final DateTimeFormatter BACKUP_STAMP =
            DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss");
    private static final int BACKUPS_KEPT = 3;

    /**
     * Comment token declaring that the entries of the key below it belong to the server
     * owner: nothing under that key is ever inserted or pruned. Written on its own comment
     * line, either in the jar resource (the author declares the section owner-owned) or in
     * the disk file (the owner freezes that subtree in their own copy).
     */
    public static final String EXTENSIBLE_MARKER = "sn:extensible";

    /**
     * Comment token for files whose every top-level key is an entry id (an items file,
     * a catalogue). Recognized only in the file header, before the first key, on either
     * side like {@link #EXTENSIBLE_MARKER}.
     */
    public static final String EXTENSIBLE_ROOT_MARKER = "sn:extensible-root";

    private YamlUpdater() {
    }

    /**
     * Pure merge entry: returns a copy of {@code diskLines} with every block missing
     * from it (keys plus their attached comments) inserted at its anchored position,
     * right after the nearest preceding sibling shared with the resource, otherwise
     * before the nearest following shared sibling, otherwise at the end of the parent.
     * User values and extra keys are never touched, and nothing is inserted below a key
     * EITHER side marked {@code # sn:extensible}. No I/O, safe for plain unit tests.
     */
    public static List<String> merge(List<String> resourceLines, List<String> diskLines) {
        List<String> result = new ArrayList<>(diskLines);
        applyInsertions(result, planInsertions(resourceLines, result));
        return result;
    }

    /**
     * True when the text parses as YAML. Used to detect corrupt disk files before a
     * merge; pure, safe for plain unit tests.
     */
    public static boolean isParseable(String yamlText) {
        try {
            new YamlConfiguration().loadFromString(yamlText == null ? "" : yamlText);
            return true;
        } catch (InvalidConfigurationException ex) {
            return false;
        }
    }

    /**
     * Merges the bundled resource into the disk file, seeding it when absent and
     * backing it up when corrupt. The {@code update-configs} gate is read from the
     * {@code config.yml} of the plugin data folder; when {@code diskFile} IS that
     * config it is exempt from the gate and merges always.
     *
     * @param plugin       consumer plugin owning both the resource and the file
     * @param resourcePath path of the bundled resource inside the jar
     * @param diskFile     file under the plugin data folder to update
     * @param prune        when true, keys absent from the resource are REMOVED from
     *                     disk (opt-in; the default merge never deletes)
     */
    public static void update(JavaPlugin plugin, String resourcePath, File diskFile, boolean prune) {
        File gate = new File(plugin.getDataFolder(), "config.yml");
        boolean exempt = diskFile.getAbsolutePath().equals(gate.getAbsolutePath());
        update(plugin, resourcePath, diskFile, prune, gate, exempt);
    }

    /**
     * Gate-aware variant used by {@link YmlManager}, which knows the real config file
     * declared in the consumer spec.
     */
    static void update(JavaPlugin plugin, String resourcePath, File diskFile, boolean prune,
                       @Nullable File gateFile, boolean gateExempt) {
        List<String> resourceLines = readResource(plugin, resourcePath);
        if (resourceLines == null) {
            plugin.getLogger().warning("[update-configs] Resource " + resourcePath
                    + " absent from the jar; " + diskFile.getName() + " cannot be updated");
            return;
        }
        logMarkerWarnings(plugin.getLogger(), resourceLines, diskFile);
        try {
            if (!diskFile.exists()) {
                seed(diskFile, resourceLines);
                return;
            }
            String rawText = YamlPreprocessor.read(diskFile.toPath());
            if (!isParseable(YamlPreprocessor.preprocess(rawText).cleanText())) {
                File backup = backupCorrupt(diskFile);
                seed(diskFile, resourceLines);
                plugin.getLogger().warning("[update-configs] " + diskFile.getName()
                        + " does not parse as YAML: backed up to " + backup.getName()
                        + " and regenerated from the jar");
                return;
            }
            List<String> diskLines = new ArrayList<>(
                    Files.readAllLines(diskFile.toPath(), StandardCharsets.UTF_8));
            logDiskMarkerWarnings(plugin.getLogger(), resourceLines, diskLines, diskFile);
            Map<String, Integer> frozen = new LinkedHashMap<>();
            List<Insertion> insertions = planInsertions(resourceLines, diskLines, frozen);
            logFrozenByDiskMarker(plugin.getLogger(), diskFile, frozen);
            List<String> result = new ArrayList<>(diskLines);
            applyInsertions(result, insertions);
            if (prune) {
                result = prune(resourceLines, result);
            }
            if (result.equals(diskLines)) {
                return;
            }
            if (!gateExempt && !readUpdateConfigsGate(gateFile)) {
                if (insertions.isEmpty()) {
                    plugin.getLogger().warning("[update-configs] update-configs is false: "
                            + "prune pending in " + diskFile.getName());
                } else {
                    plugin.getLogger().warning("[update-configs] update-configs is false: "
                            + insertions.size() + " keys missing in " + diskFile.getName());
                }
                return;
            }
            backupBeforeMerge(diskFile);
            Files.write(diskFile.toPath(), result, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().severe("[update-configs] Failed updating "
                    + diskFile.getName() + ": " + ex.getMessage());
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("[update-configs] Parse error merging "
                    + resourcePath + " into " + diskFile.getName() + ": " + ex.getMessage());
        }
    }

    /**
     * Variant of {@link #update} whose reference lives in memory instead of the jar
     * (e.g. a translation merged against the DISK {@code messages_en.yml}). Same
     * semantics: seed when absent, backup-N plus reseed when corrupt, pre-merge backup
     * keep-last-3 and the {@code update-configs} gate read from {@code gateFile}
     * ({@code null} skips the gate).
     *
     * @return true when the disk file changed (seeded, reseeded or merged)
     */
    public static boolean updateFromLines(JavaPlugin plugin, List<String> referenceLines,
                                          File diskFile, @Nullable File gateFile) {
        return updateFromLines(plugin.getLogger(), referenceLines, diskFile, gateFile);
    }

    /**
     * Logger-based variant of {@link #updateFromLines(JavaPlugin, List, File, File)} for
     * callers that hold a reference in memory but no {@link JavaPlugin} handle (the gui
     * seeder reads its reference straight from the consumer jar). Same semantics: seed
     * when absent, backup-N plus reseed when corrupt, pre-merge backup keep-last-3 and the
     * {@code update-configs} gate read from {@code gateFile} ({@code null} skips the gate).
     *
     * @return true when the disk file changed (seeded, reseeded or merged)
     */
    public static boolean updateFromLines(Logger logger, List<String> referenceLines,
                                          File diskFile, @Nullable File gateFile) {
        logMarkerWarnings(logger, referenceLines, diskFile);
        try {
            if (!diskFile.exists()) {
                seed(diskFile, referenceLines);
                return true;
            }
            String rawText = YamlPreprocessor.read(diskFile.toPath());
            if (!isParseable(YamlPreprocessor.preprocess(rawText).cleanText())) {
                File backup = backupCorrupt(diskFile);
                seed(diskFile, referenceLines);
                logger.warning("[update-configs] " + diskFile.getName()
                        + " does not parse as YAML: backed up to " + backup.getName()
                        + " and regenerated from the reference");
                return true;
            }
            List<String> diskLines = new ArrayList<>(
                    Files.readAllLines(diskFile.toPath(), StandardCharsets.UTF_8));
            logDiskMarkerWarnings(logger, referenceLines, diskLines, diskFile);
            Map<String, Integer> frozen = new LinkedHashMap<>();
            List<Insertion> insertions = planInsertions(referenceLines, diskLines, frozen);
            // Report BEFORE the early return: a marker that withheld everything leaves no
            // insertions, and that is exactly the case worth reporting.
            logFrozenByDiskMarker(logger, diskFile, frozen);
            if (insertions.isEmpty()) {
                return false;
            }
            if (gateFile != null && !readUpdateConfigsGate(gateFile)) {
                logger.warning("[update-configs] update-configs is false: "
                        + insertions.size() + " keys missing in " + diskFile.getName());
                return false;
            }
            List<String> result = new ArrayList<>(diskLines);
            applyInsertions(result, insertions);
            if (result.equals(diskLines)) {
                return false;
            }
            backupBeforeMerge(diskFile);
            Files.write(diskFile.toPath(), result, StandardCharsets.UTF_8);
            return true;
        } catch (IOException ex) {
            logger.severe("[update-configs] Failed updating "
                    + diskFile.getName() + ": " + ex.getMessage());
            return false;
        } catch (RuntimeException ex) {
            logger.severe("[update-configs] Parse error merging reference into "
                    + diskFile.getName() + ": " + ex.getMessage());
            return false;
        }
    }

    /** Reports every misplaced extensible marker of a reference, one WARN per finding. */
    private static void logMarkerWarnings(Logger logger, List<String> referenceLines, File diskFile) {
        for (String warning : markerWarnings(referenceLines)) {
            logger.warning("[update-configs] " + diskFile.getName() + ": " + warning);
        }
    }

    /**
     * Reports the misplaced markers the OWNER typed into the disk file, so a marker that
     * protects nothing is as visible to them as it is to the plugin author. Findings the
     * reference already carries are dropped: the disk file was seeded from it, so reporting
     * both would log the same authoring mistake twice per file.
     */
    private static void logDiskMarkerWarnings(Logger logger, List<String> referenceLines,
                                              List<String> diskLines, File diskFile) {
        List<String> warnings = new ArrayList<>(markerWarnings(diskLines));
        warnings.removeAll(markerWarnings(referenceLines));
        for (String warning : warnings) {
            logger.warning("[update-configs] " + diskFile.getName() + ": " + warning);
        }
    }

    /**
     * Reports the owner-declared markers that actually withheld keys, one WARN per marker.
     * Silent when a marker kept nothing out, so this fires only while the file is genuinely
     * missing keys the plugin ships - the same cadence as the {@code update-configs} gate.
     */
    private static void logFrozenByDiskMarker(Logger logger, File diskFile,
                                              Map<String, Integer> frozen) {
        for (Map.Entry<String, Integer> entry : frozen.entrySet()) {
            String where = entry.getKey().isEmpty()
                    ? "the file header declares " + EXTENSIBLE_ROOT_MARKER
                    : "the file declares " + EXTENSIBLE_MARKER + " at '" + entry.getKey() + "'";
            logger.warning("[update-configs] " + diskFile.getName() + ": " + entry.getValue()
                    + " key(s) not inserted because " + where);
        }
    }

    /** Seeds the file from the jar resource only when it does not exist; never merges. */
    static void seedIfMissing(JavaPlugin plugin, String resourcePath, File diskFile) {
        if (diskFile.exists()) {
            return;
        }
        List<String> resourceLines = readResource(plugin, resourcePath);
        if (resourceLines == null) {
            plugin.getLogger().warning("[update-configs] Resource " + resourcePath
                    + " absent from the jar; " + diskFile.getName() + " cannot be seeded");
            return;
        }
        try {
            seed(diskFile, resourceLines);
        } catch (IOException ex) {
            plugin.getLogger().severe("[update-configs] Failed seeding "
                    + diskFile.getName() + ": " + ex.getMessage());
        }
    }

    /**
     * Reads the master gate from the config file on disk BEFORE any merge. Absent
     * file, absent key or unreadable content all count as {@code true}.
     */
    static boolean readUpdateConfigsGate(@Nullable File gateFile) {
        if (gateFile == null || !gateFile.exists()) {
            return true;
        }
        try {
            String raw = YamlPreprocessor.read(gateFile.toPath());
            YamlConfiguration cfg = new YamlConfiguration();
            cfg.loadFromString(YamlPreprocessor.preprocess(raw).cleanText());
            return cfg.getBoolean("update-configs", true);
        } catch (IOException | InvalidConfigurationException ex) {
            return true;
        }
    }

    // ------------------------------------------------------------------
    // Insertion planning
    // ------------------------------------------------------------------

    private static List<Insertion> planInsertions(List<String> resourceLines, List<String> diskLines) {
        return planInsertions(resourceLines, diskLines, new LinkedHashMap<>());
    }

    /**
     * Plans the insertions and records what an OWNER-declared marker kept out: {@code frozen}
     * maps the path of a {@code # sn:extensible} declared on DISK (the empty string for a
     * {@code # sn:extensible-root} header) to the number of resource blocks it withheld. A
     * marker declared in the resource is the documented contract and is never recorded, and a
     * disk marker that withholds nothing leaves no entry, so a steady-state boot reports
     * nothing.
     */
    private static List<Insertion> planInsertions(List<String> resourceLines, List<String> diskLines,
                                                  Map<String, Integer> frozen) {
        Node resourceRoot = parse(resourceLines);
        Node diskRoot = parse(diskLines);
        List<Insertion> insertions = new ArrayList<>();
        collectInsertions(resourceRoot, diskRoot, resourceLines, diskLines, insertions, "", null, frozen);
        insertions.sort(Comparator.comparingInt((Insertion i) -> i.position).reversed()
                .thenComparing(Comparator.comparingInt((Insertion i) -> i.sequence).reversed()));
        return insertions;
    }

    private static void applyInsertions(List<String> lines, List<Insertion> insertions) {
        for (Insertion ins : insertions) {
            int pos = Math.min(ins.position, lines.size());
            lines.addAll(pos, ins.lines);
        }
    }

    private static void collectInsertions(Node resource, Node disk, List<String> resourceLines,
                                          List<String> diskLines, List<Insertion> out,
                                          String path, @Nullable String frozenAt,
                                          Map<String, Integer> frozen) {
        if (resource.extensible) {
            // The AUTHOR declared these entries the owner's: what they deleted stays deleted.
            // Binding and silent - this is the contract the resource ships with.
            return;
        }
        if (frozenAt == null && disk.extensible) {
            // The OWNER declared it in their own file. Keep walking rather than returning, so
            // what the marker withholds can be counted and reported; the outermost marker owns
            // the report, which is the one the owner actually typed.
            frozenAt = path;
        }
        List<Node> rChildren = resource.children;
        for (int idx = 0; idx < rChildren.size(); idx++) {
            Node rChild = rChildren.get(idx);
            Node dChild = disk.findChild(rChild.key);
            if (dChild == null) {
                if (frozenAt != null) {
                    frozen.merge(frozenAt, 1, Integer::sum);
                    continue;
                }
                int insertAt = computeInsertPosition(resource, idx, disk, diskLines);
                List<String> block = new ArrayList<>();
                for (int li = rChild.blockStart; li <= rChild.blockEnd; li++) {
                    block.add(resourceLines.get(li));
                }
                out.add(new Insertion(insertAt, rChild.blockStart, block));
            } else {
                String childPath = path.isEmpty() ? rChild.key : path + "." + rChild.key;
                collectInsertions(rChild, dChild, resourceLines, diskLines, out,
                        childPath, frozenAt, frozen);
            }
        }
    }

    private static int computeInsertPosition(Node rParent, int rIdx, Node dParent, List<String> diskLines) {
        // Anchor to the nearest preceding sibling that exists both in the resource and
        // on disk: insert right after its block.
        for (int i = rIdx - 1; i >= 0; i--) {
            Node prevR = rParent.children.get(i);
            Node prevD = dParent.findChild(prevR.key);
            if (prevD != null) {
                return prevD.blockEnd + 1;
            }
        }
        // No preceding anchor: try the nearest following sibling and insert immediately
        // before its block (preserving its leading comments).
        for (int i = rIdx + 1; i < rParent.children.size(); i++) {
            Node nextR = rParent.children.get(i);
            Node nextD = dParent.findChild(nextR.key);
            if (nextD != null) {
                return nextD.blockStart;
            }
        }
        // No siblings on disk: fall back to end of parent / file.
        if (dParent.indent < 0) {
            return diskLines.size();
        }
        if (!dParent.children.isEmpty()) {
            Node last = dParent.children.get(dParent.children.size() - 1);
            return last.blockEnd + 1;
        }
        return dParent.blockEnd + 1;
    }

    // ------------------------------------------------------------------
    // Pruning (opt-in)
    // ------------------------------------------------------------------

    /**
     * Pure prune entry: returns a copy of {@code lines} with every block whose key path
     * does not exist in the resource removed, comments included. Opt-in only, via
     * {@code managedPruning}; the default merge never deletes user keys. Stops at any key
     * EITHER side marked {@code # sn:extensible}, whose entries are owner data rather
     * than stale keys. No I/O, safe for plain unit tests.
     */
    public static List<String> prune(List<String> resourceLines, List<String> lines) {
        Node resourceRoot = parse(resourceLines);
        Node diskRoot = parse(lines);
        List<int[]> removals = new ArrayList<>();
        collectRemovals(resourceRoot, diskRoot, removals);
        removals.sort(Comparator.comparingInt((int[] r) -> r[0]).reversed());
        List<String> out = new ArrayList<>(lines);
        for (int[] removal : removals) {
            int end = Math.min(removal[1], out.size() - 1);
            for (int li = end; li >= removal[0]; li--) {
                out.remove(li);
            }
        }
        return out;
    }

    private static void collectRemovals(Node resource, Node disk, List<int[]> out) {
        if (resource.extensible || disk.extensible) {
            // Owner-owned entries are not "stale keys": pruning stops at the marker, whether
            // the author declared it in the resource or the owner declared it on disk.
            return;
        }
        for (Node dChild : disk.children) {
            Node rChild = resource.findChild(dChild.key);
            if (rChild == null) {
                out.add(new int[]{dChild.blockStart, dChild.blockEnd});
            } else {
                collectRemovals(rChild, dChild, out);
            }
        }
    }

    // ------------------------------------------------------------------
    // Backups and seeding
    // ------------------------------------------------------------------

    private static void seed(File diskFile, List<String> resourceLines) throws IOException {
        Path parent = diskFile.toPath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(diskFile.toPath(), resourceLines, StandardCharsets.UTF_8);
    }

    private static void backupBeforeMerge(File diskFile) throws IOException {
        String base = stripYmlExtension(diskFile.getName());
        File dir = diskFile.getParentFile() != null ? diskFile.getParentFile() : new File(".");
        File backup = new File(dir, "old-" + base + "-" + BACKUP_STAMP.format(LocalDateTime.now()) + ".yml");
        Files.copy(diskFile.toPath(), backup.toPath(), StandardCopyOption.REPLACE_EXISTING);
        pruneOldBackups(dir, "old-" + base + "-");
    }

    private static void pruneOldBackups(File dir, String prefix) {
        // Exact match old-<base>-<yyyyMMdd-HHmmss>.yml: a loose prefix would mix in the
        // backups of another file whose name extends the base (config vs config-extra).
        java.util.regex.Pattern stamped = java.util.regex.Pattern.compile(
                java.util.regex.Pattern.quote(prefix) + "\\d{8}-\\d{6}\\.yml");
        File[] backups = dir.listFiles((d, name) -> stamped.matcher(name).matches());
        if (backups == null || backups.length <= BACKUPS_KEPT) {
            return;
        }
        Arrays.sort(backups, Comparator.comparing(File::getName));
        for (int i = 0; i < backups.length - BACKUPS_KEPT; i++) {
            try {
                Files.deleteIfExists(backups[i].toPath());
            } catch (IOException ignored) {
                // A leftover backup never blocks the merge.
            }
        }
    }

    private static File backupCorrupt(File diskFile) throws IOException {
        File dir = diskFile.getParentFile() != null ? diskFile.getParentFile() : new File(".");
        int n = 1;
        File backup = new File(dir, diskFile.getName() + ".backup-" + n);
        while (backup.exists()) {
            n++;
            backup = new File(dir, diskFile.getName() + ".backup-" + n);
        }
        Files.move(diskFile.toPath(), backup.toPath());
        return backup;
    }

    private static String stripYmlExtension(String name) {
        return name.endsWith(".yml") ? name.substring(0, name.length() - 4) : name;
    }

    // ------------------------------------------------------------------
    // Parser
    // ------------------------------------------------------------------

    private static Node parse(List<String> lines) {
        Node root = new Node();
        root.indent = -1;
        root.extensible = hasMarker(lines, 0, firstKeyLine(lines), EXTENSIBLE_ROOT_MARKER);
        Deque<Node> stack = new ArrayDeque<>();
        stack.push(root);
        int pendingCommentStart = -1;
        for (int i = 0; i < lines.size(); i++) {
            String line = lines.get(i);
            String trimmed = line.trim();
            if (trimmed.isEmpty() || trimmed.startsWith("#")) {
                if (pendingCommentStart < 0) {
                    pendingCommentStart = i;
                }
                continue;
            }
            int indent = leadingSpaces(line);
            if (trimmed.startsWith("- ") || trimmed.equals("-")) {
                // Part of the current node's list value. Comments above it stay attached
                // to whatever they originally led to (typically the parent key).
                pendingCommentStart = -1;
                continue;
            }
            int colonIdx = findUnquotedColon(trimmed);
            if (colonIdx < 0) {
                // Continuation line of a multi-line scalar (folded/literal block).
                pendingCommentStart = -1;
                continue;
            }
            String key = trimmed.substring(0, colonIdx).trim();
            int boundary = pendingCommentStart >= 0 ? pendingCommentStart : i;
            while (stack.peek() != root && stack.peek().indent >= indent) {
                Node closing = stack.pop();
                closing.blockEnd = boundary - 1;
            }
            Node parent = stack.peek();
            Node node = new Node();
            node.key = key;
            node.indent = indent;
            node.keyLine = i;
            node.blockStart = pendingCommentStart >= 0 ? pendingCommentStart : i;
            node.inlineValue = trimmed.substring(colonIdx + 1).trim();
            node.extensible = hasMarker(lines, node.blockStart, i, EXTENSIBLE_MARKER);
            parent.children.add(node);
            stack.push(node);
            pendingCommentStart = -1;
        }
        int endBoundary = pendingCommentStart >= 0 ? pendingCommentStart : lines.size();
        while (stack.peek() != root) {
            Node closing = stack.pop();
            closing.blockEnd = endBoundary - 1;
        }
        return root;
    }

    /** Index of the first line that is neither blank nor a comment; {@code size} when none. */
    private static int firstKeyLine(List<String> lines) {
        for (int i = 0; i < lines.size(); i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.isEmpty() && !trimmed.startsWith("#")) {
                return i;
            }
        }
        return lines.size();
    }

    /**
     * True when one of the comment lines in {@code [from, toExclusive)} is exactly the
     * given marker. The token must own its line: leading {@code #} and surrounding
     * whitespace are stripped and the remainder has to match, case-insensitively.
     */
    private static boolean hasMarker(List<String> lines, int from, int toExclusive, String marker) {
        int end = Math.min(toExclusive, lines.size());
        for (int i = Math.max(0, from); i < end; i++) {
            String trimmed = lines.get(i).trim();
            if (!trimmed.startsWith("#")) {
                continue;
            }
            int j = 0;
            while (j < trimmed.length() && (trimmed.charAt(j) == '#' || trimmed.charAt(j) == ' ')) {
                j++;
            }
            if (trimmed.substring(j).trim().equalsIgnoreCase(marker)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Lists the misplaced {@code sn:extensible} markers of a yml: a marker declares that the
     * ENTRIES of a section belong to the server owner, so putting it on a key that holds a
     * plain value protects nothing and is always a mistake. An empty section
     * ({@code cores: {}}, a section with no entries yet) is legitimate and never reported.
     * Applies to a jar resource and to a disk file alike, since either may declare a marker.
     * Pure, safe for plain unit tests.
     *
     * @param lines the yml to lint, resource or disk file
     * @return one human-readable line per misplaced marker, empty when the file is fine
     */
    public static List<String> markerWarnings(List<String> lines) {
        List<String> out = new ArrayList<>();
        collectMarkerWarnings(parse(lines), "", out);
        return out;
    }

    private static void collectMarkerWarnings(Node node, String path, List<String> out) {
        for (Node child : node.children) {
            String childPath = path.isEmpty() ? child.key : path + "." + child.key;
            if (child.extensible && holdsPlainValue(child.inlineValue)) {
                out.add("'" + childPath + "' is marked " + EXTENSIBLE_MARKER
                        + " but holds a value; the marker only protects the entries of a section");
            }
            collectMarkerWarnings(child, childPath, out);
        }
    }

    /** True for a key written as {@code key: value}, excluding the empty map/list forms. */
    private static boolean holdsPlainValue(String inlineValue) {
        return inlineValue != null && !inlineValue.isEmpty()
                && !"{}".equals(inlineValue) && !"[]".equals(inlineValue);
    }

    private static int leadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') {
            n++;
        }
        return n;
    }

    /**
     * Strips one pair of balanced quotes wrapping the whole key ({@code 'foo'} or
     * {@code "foo"} become {@code foo}); anything else is returned untouched. Used only
     * for comparison: inserted lines always keep the textual form of the resource.
     */
    private static String unquoteKey(String key) {
        if (key.length() >= 2) {
            char first = key.charAt(0);
            char last = key.charAt(key.length() - 1);
            if (first == last && (first == '\'' || first == '"')) {
                return key.substring(1, key.length() - 1);
            }
        }
        return key;
    }

    private static int findUnquotedColon(String s) {
        char quote = 0;
        for (int j = 0; j < s.length(); j++) {
            char c = s.charAt(j);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '"' || c == '\'') {
                quote = c;
                continue;
            }
            if (c == '#') {
                return -1;
            }
            if (c == ':') {
                if (j == s.length() - 1 || Character.isWhitespace(s.charAt(j + 1))) {
                    return j;
                }
            }
        }
        return -1;
    }

    // ------------------------------------------------------------------
    // Resource read
    // ------------------------------------------------------------------

    private static @Nullable List<String> readResource(JavaPlugin plugin, String resourcePath) {
        try (InputStream in = plugin.getResource(resourcePath)) {
            if (in == null) {
                return null;
            }
            List<String> lines = new ArrayList<>();
            try (BufferedReader r = new BufferedReader(new InputStreamReader(in, StandardCharsets.UTF_8))) {
                String line;
                while ((line = r.readLine()) != null) {
                    lines.add(line);
                }
            }
            return lines;
        } catch (IOException ex) {
            plugin.getLogger().severe("[update-configs] Failed reading resource "
                    + resourcePath + ": " + ex.getMessage());
            return null;
        }
    }

    // ------------------------------------------------------------------
    // Internal types
    // ------------------------------------------------------------------

    private static final class Node {
        String key;
        int indent;
        int keyLine;
        int blockStart;
        int blockEnd;
        String inlineValue = "";
        boolean extensible;
        final List<Node> children = new ArrayList<>();

        Node findChild(String k) {
            if (k == null) {
                return null;
            }
            String wanted = unquoteKey(k);
            for (Node c : children) {
                if (wanted.equals(unquoteKey(c.key))) {
                    return c;
                }
            }
            return null;
        }
    }

    private static final class Insertion {
        final int position;
        final int sequence;
        final List<String> lines;

        Insertion(int position, int sequence, List<String> lines) {
            this.position = position;
            this.sequence = sequence;
            this.lines = lines;
        }
    }
}
