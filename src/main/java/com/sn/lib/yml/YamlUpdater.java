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
import java.util.List;

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

    private YamlUpdater() {
    }

    /**
     * Pure merge entry: returns a copy of {@code diskLines} with every block missing
     * from it (keys plus their attached comments) inserted at its anchored position,
     * right after the nearest preceding sibling shared with the resource, otherwise
     * before the nearest following shared sibling, otherwise at the end of the parent.
     * User values and extra keys are never touched. No I/O, safe for plain unit tests.
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
            plugin.getLogger().warning("[update-configs] Recurso " + resourcePath
                    + " ausente del jar; " + diskFile.getName() + " no se puede actualizar");
            return;
        }
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
                        + " no parsea como YAML: respaldado en " + backup.getName()
                        + " y regenerado desde el jar");
                return;
            }
            List<String> diskLines = new ArrayList<>(
                    Files.readAllLines(diskFile.toPath(), StandardCharsets.UTF_8));
            List<Insertion> insertions = planInsertions(resourceLines, diskLines);
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
                    plugin.getLogger().warning("[update-configs] update-configs esta en false: "
                            + "prune pendiente en " + diskFile.getName());
                } else {
                    plugin.getLogger().warning("[update-configs] update-configs esta en false: "
                            + "faltan " + insertions.size() + " keys en " + diskFile.getName());
                }
                return;
            }
            backupBeforeMerge(diskFile);
            Files.write(diskFile.toPath(), result, StandardCharsets.UTF_8);
        } catch (IOException ex) {
            plugin.getLogger().severe("[update-configs] Fallo actualizando "
                    + diskFile.getName() + ": " + ex.getMessage());
        } catch (RuntimeException ex) {
            plugin.getLogger().severe("[update-configs] Error de parseo mergeando "
                    + resourcePath + " en " + diskFile.getName() + ": " + ex.getMessage());
        }
    }

    /** Seeds the file from the jar resource only when it does not exist; never merges. */
    static void seedIfMissing(JavaPlugin plugin, String resourcePath, File diskFile) {
        if (diskFile.exists()) {
            return;
        }
        List<String> resourceLines = readResource(plugin, resourcePath);
        if (resourceLines == null) {
            plugin.getLogger().warning("[update-configs] Recurso " + resourcePath
                    + " ausente del jar; " + diskFile.getName() + " no se puede seedear");
            return;
        }
        try {
            seed(diskFile, resourceLines);
        } catch (IOException ex) {
            plugin.getLogger().severe("[update-configs] Fallo seedeando "
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
        Node resourceRoot = parse(resourceLines);
        Node diskRoot = parse(diskLines);
        List<Insertion> insertions = new ArrayList<>();
        collectInsertions(resourceRoot, diskRoot, resourceLines, diskLines, insertions);
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
                                          List<String> diskLines, List<Insertion> out) {
        List<Node> rChildren = resource.children;
        for (int idx = 0; idx < rChildren.size(); idx++) {
            Node rChild = rChildren.get(idx);
            Node dChild = disk.findChild(rChild.key);
            if (dChild == null) {
                int insertAt = computeInsertPosition(resource, idx, disk, diskLines);
                List<String> block = new ArrayList<>();
                for (int li = rChild.blockStart; li <= rChild.blockEnd; li++) {
                    block.add(resourceLines.get(li));
                }
                out.add(new Insertion(insertAt, rChild.blockStart, block));
            } else {
                collectInsertions(rChild, dChild, resourceLines, diskLines, out);
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
     * Pure prune: returns a copy of {@code lines} with every block whose key path does
     * not exist in the resource removed, comments included. Opt-in only, via
     * {@code managedPruning}; the default merge never deletes user keys.
     */
    static List<String> prune(List<String> resourceLines, List<String> lines) {
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
        // Match exacto old-<base>-<yyyyMMdd-HHmmss>.yml: un prefijo suelto mezclaria los
        // backups de otro archivo cuyo nombre extiende la base (config vs config-extra).
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

    private static int leadingSpaces(String s) {
        int n = 0;
        while (n < s.length() && s.charAt(n) == ' ') {
            n++;
        }
        return n;
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
            plugin.getLogger().severe("[update-configs] Fallo leyendo el recurso "
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
        final List<Node> children = new ArrayList<>();

        Node findChild(String k) {
            if (k == null) {
                return null;
            }
            for (Node c : children) {
                if (k.equals(c.key)) {
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
