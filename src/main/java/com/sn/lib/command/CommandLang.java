package com.sn.lib.command;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.lang.SnLang;
import com.sn.lib.yml.YamlUpdater;

/**
 * Makes the generated help translatable: the description of every command and the visible
 * label of every argument are seeded into {@code lang/messages_en.yml} under a top-level
 * {@code commands} block and read back from the lang module, so a server owner edits them in
 * a file instead of needing the source.
 *
 * <p>Seeding runs after the consumer registered its roots (the command tree only exists
 * then) and again on every reload, through {@link SnCommands#applyLang()}. The values written
 * are exactly the ones declared in code, merged with {@link YamlUpdater#merge} so existing
 * values and comments are never overwritten - editing one is permanent, deleting one restores
 * the declared default on the next boot.</p>
 *
 * <p>Argument entries carry the VISIBLE label only. The identifier stays the name given to
 * {@code arg(name, ...)} and remains the {@code context.get(name)} key, so translating a
 * label never touches parsing; it changes the {@code <name>} hint in the usage line and in
 * tab completion, which stay consistent because both read the same {@link
 * RootCommand.Labels}.</p>
 *
 * <p>The block nests under reserved {@code subcommands} and {@code args} sections rather than
 * mapping node names directly, so a subcommand named {@code description}, {@code args} or
 * {@code subcommands} cannot collide with the structure.</p>
 */
final class CommandLang {

    /** Top-level key of the seeded block; free across every consumer of the fleet. */
    static final String ROOT_KEY = "commands";

    private static final String DESCRIPTION_KEY = "description";
    private static final String SUBCOMMANDS_KEY = "subcommands";
    private static final String ARGS_KEY = "args";
    private static final String LANG_FILE = "lang/messages_en.yml";
    private static final String INDENT = "  ";

    private CommandLang() {
    }

    /**
     * Seeds the descriptions and argument labels of {@code roots} into the language file and
     * applies the resolved values to the trees. Never throws: a failure degrades to the
     * declared defaults with one WARN, because a translation problem must not take a
     * consumer's commands down.
     */
    static void apply(Sn ctx, @Nullable SnLang lang, Collection<RootCommand> roots) {
        if (lang == null || roots.isEmpty()) {
            return;
        }
        try {
            if (seed(ctx, lang, roots)) {
                // Re-read so the keys just written are resolvable, and so the translation
                // files pick them up through the usual merge-from-English pass.
                lang.reload();
            }
            for (RootCommand root : roots) {
                applyTo(lang, root);
            }
        } catch (Throwable t) {
            ctx.plugin().getLogger().warning("Could not apply the command descriptions from "
                    + LANG_FILE + "; the values declared in code are used instead: " + t);
        }
    }

    // ------------------------------------------------------------------
    // Seeding
    // ------------------------------------------------------------------

    /** Merges the generated block into the language file; true when the file changed. */
    private static boolean seed(Sn ctx, SnLang lang, Collection<RootCommand> roots)
            throws IOException {
        File file = new File(ctx.plugin().getDataFolder(), LANG_FILE);
        List<String> disk = file.isFile()
                ? new ArrayList<>(Files.readAllLines(file.toPath(), StandardCharsets.UTF_8))
                : new ArrayList<>();
        List<String> merged = YamlUpdater.merge(block(roots), disk);
        if (merged.equals(disk)) {
            return false;
        }
        File parent = file.getParentFile();
        if (parent != null) {
            Files.createDirectories(parent.toPath());
        }
        Files.write(file.toPath(), merged, StandardCharsets.UTF_8);
        return true;
    }

    /** The whole {@code commands:} block, one entry per root, with its explanatory header. */
    private static List<String> block(Collection<RootCommand> roots) {
        List<String> out = new ArrayList<>();
        out.add("# ------------------------------------------------------------");
        out.add("#  Command descriptions shown in the generated help.");
        out.add("#  Seeded from the values this plugin declares; edit them freely,");
        out.add("#  they are never overwritten. Delete one to restore its default.");
        out.add("#  The 'args' entries are the VISIBLE labels of each argument");
        out.add("#  (<name>, [tag]) in the usage line and in tab completion; they do");
        out.add("#  not change the syntax, the order or how the command is typed.");
        out.add("# ------------------------------------------------------------");
        out.add(ROOT_KEY + ":");
        for (RootCommand root : roots) {
            out.addAll(rootLines(root.getName(), root.getDescription(), root.subs()));
        }
        return out;
    }

    /** Lines of ONE root inside the block, from its name down to its deepest leaf. */
    static List<String> rootLines(String name, String description, List<RootCommand.Sub> subs) {
        List<String> out = new ArrayList<>();
        out.add(INDENT + name + ":");
        out.add(INDENT.repeat(2) + DESCRIPTION_KEY + ": " + quote(description));
        appendNodes(out, subs, 2);
        return out;
    }

    /** Appends the {@code subcommands} section of {@code nodes} at {@code depth}, if any. */
    private static void appendNodes(List<String> out, List<RootCommand.Sub> nodes, int depth) {
        if (nodes.isEmpty()) {
            return;
        }
        out.add(INDENT.repeat(depth) + SUBCOMMANDS_KEY + ":");
        for (RootCommand.Sub sub : nodes) {
            out.add(INDENT.repeat(depth + 1) + sub.name + ":");
            out.add(INDENT.repeat(depth + 2) + DESCRIPTION_KEY + ": " + quote(sub.description));
            if (!sub.args.isEmpty()) {
                out.add(INDENT.repeat(depth + 2) + ARGS_KEY + ":");
                for (String identifier : sub.args.keySet()) {
                    out.add(INDENT.repeat(depth + 3) + identifier + ": " + quote(identifier));
                }
            }
            appendNodes(out, sub.children, depth + 2);
        }
    }

    /** Double-quoted YAML scalar; the only characters needing an escape here are {@code \} and {@code "}. */
    private static String quote(String value) {
        String body = value == null ? "" : value;
        return "\"" + body.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    // ------------------------------------------------------------------
    // Applying
    // ------------------------------------------------------------------

    /** Applies the resolved values to one tree, including the root's Bukkit description. */
    private static void applyTo(SnLang lang, RootCommand root) {
        apply(lang::rawOrNull, root.getName(), root.subs(), root::setDescription);
    }

    /**
     * Applies the values a {@code resolver} answers for the seeded keys to one tree: the
     * root description goes to {@code rootDescription}, and every node's labels are swapped
     * for the resolved description and argument labels. A key the resolver answers null (or
     * blank, for a label) for keeps the value declared in code, so a partially translated
     * file degrades entry by entry rather than all at once. The resolver seam keeps this
     * testable without a lang module.
     */
    static void apply(Function<String, String> resolver, String rootName,
            List<RootCommand.Sub> subs, Consumer<String> rootDescription) {
        String base = ROOT_KEY + "." + rootName;
        String description = resolver.apply(base + "." + DESCRIPTION_KEY);
        if (description != null && !description.isBlank()) {
            rootDescription.accept(description);
        }
        applyNodes(resolver, base, subs);
    }

    /** Recurses the tree, swapping each node's labels for the resolved values. */
    private static void applyNodes(Function<String, String> resolver, String base,
            List<RootCommand.Sub> nodes) {
        String prefix = base + "." + SUBCOMMANDS_KEY + ".";
        for (RootCommand.Sub sub : nodes) {
            String here = prefix + sub.name;
            String description = resolver.apply(here + "." + DESCRIPTION_KEY);
            sub.labels(new RootCommand.Labels(
                    description != null && !description.isBlank() ? description : sub.description,
                    argLabels(resolver, here, sub)));
            applyNodes(resolver, here, sub.children);
        }
    }

    /** Visible label per argument identifier; the identifier itself when the key is absent. */
    private static Map<String, String> argLabels(Function<String, String> resolver, String here,
            RootCommand.Sub sub) {
        Map<String, String> labels = new LinkedHashMap<>();
        String prefix = here + "." + ARGS_KEY + ".";
        for (String identifier : sub.args.keySet()) {
            String label = resolver.apply(prefix + identifier);
            labels.put(identifier, label != null && !label.isBlank() ? label : identifier);
        }
        return Map.copyOf(labels);
    }
}
