package com.sn.lib.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Predicate;
import java.util.logging.Level;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;
import com.sn.lib.Sn;
import com.sn.lib.command.internal.BukkitCommandRegistry;
import com.sn.lib.lang.SnLang;
import com.sn.lib.reload.Registrable;
import com.sn.lib.text.SnText;

/**
 * Root of a command tree built through {@link SnCommands}: dispatches to its subcommands
 * with permission-first checks, argument-count validation against the generated usage,
 * typed parsing through each {@link Arg} and a generated help.
 *
 * <p>Subcommands nest: a subcommand that owns children (declared through
 * {@link SubCommandBuilder#sub(String, Consumer)}) is a GROUP that dispatches on the next
 * token among its children ({@code /clan admin disband <clan>}); a subcommand without
 * children is a LEAF that parses its positional arguments. A group carries its own optional
 * permission gating every child, and each leaf keeps its own; the effective check is every
 * permission on the path from the root down to the leaf. Leaf usage strings and the
 * generated help render the FULL path, and the help lists one entry per reachable leaf
 * rather than one per group.</p>
 *
 * <p>Permission inheritance: a subcommand without its own permission inherits the nearest
 * ancestor permission (a group or, ultimately, the root); a root without permission is
 * public. Tab completion and the generated help list only subcommands that are visible AND
 * whose permission chain the sender holds.</p>
 *
 * <p>Messages resolve through the context lang module when declared; without it the
 * shared {@code snlib.*} default templates bundled with the library render directly.</p>
 */
public final class RootCommand extends Command implements Registrable {

    /** Server-wide static justified: constant default templates mirroring snlib-messages.yml. */
    private static final Map<String, String> DEFAULT_MESSAGES = Map.ofEntries(
            Map.entry("snlib.no-permission", "&cYou do not have permission to use this command."),
            Map.entry("snlib.usage", "&cUsage: &f{usage}"),
            Map.entry("snlib.invalid-number", "&cInvalid number: &f{value}"),
            Map.entry("snlib.invalid-value", "&cInvalid value: &f{value}"),
            Map.entry("snlib.out-of-range", "&cValue must be between &f{min} &cand &f{max}&c: &f{value}"),
            Map.entry("snlib.player-not-found", "&cPlayer not found: &f{value}"),
            Map.entry("snlib.unknown-subcommand", "&cUnknown subcommand: &f{value}"),
            Map.entry("snlib.reload-done", "&aConfiguration reloaded."),
            Map.entry("snlib.help.header", "&8&m----------&r &e&l{plugin} &8&m----------"),
            Map.entry("snlib.help.entry", "&e{usage} &7{description}"),
            Map.entry("snlib.help.footer", "&7Page &f{page}&7/&f{total} &8- &7/{command} help <page>"));

    /** Entries per generated help page. */
    private static final int HELP_PAGE_SIZE = 10;

    private final Sn ctx;
    private final @Nullable SnLang lang;
    private final @Nullable String rootPermission;
    private final List<Sub> subs;
    private final @Nullable Consumer<RootContext> onEmpty;

    /**
     * Builds the tree, injecting the default subcommands where applicable: {@code reload}
     * and {@code help} unless defaults were opted out, {@code debug} when the spec
     * declared it. A consumer subcommand with the same name replaces the default.
     */
    RootCommand(Sn ctx, @Nullable SnLang lang, String name, List<String> aliases,
            String description, @Nullable String permission, List<Sub> declared,
            boolean withDefaults, boolean debugCommand, @Nullable Consumer<RootContext> onEmpty) {
        super(name.toLowerCase(Locale.ROOT), description,
                "/" + name.toLowerCase(Locale.ROOT), aliases);
        this.ctx = ctx;
        this.lang = lang;
        this.rootPermission = permission;
        this.onEmpty = onEmpty;
        if (permission != null) {
            setPermission(permission);
        }
        List<Sub> all = new ArrayList<>(declared);
        String adminBase = ctx.plugin().getName().toLowerCase(Locale.ROOT) + ".admin.";
        if (withDefaults) {
            if (!hasSub(all, "reload")) {
                all.add(Sub.of("reload", adminBase + "reload",
                        "Reloads the plugin configuration", context -> {
                            ctx.reloadAll();
                            send(context.sender(), "snlib.reload-done");
                        }));
            }
            if (!hasSub(all, "help")) {
                all.add(Sub.of("help", null, "Shows the available commands",
                        context -> sendHelp(context.sender(), pageFrom(context.raw(0)))));
            }
        }
        if (debugCommand && !hasSub(all, "debug")) {
            all.add(Sub.of("debug", adminBase + "debug",
                    "Toggles runtime debug output", context -> {
                        boolean on = ctx.debug().toggle();
                        context.sender().sendMessage(
                                SnText.color(on ? "&7Debug: &aON" : "&7Debug: &cOFF"));
                    }));
        }
        this.subs = List.copyOf(all);
    }

    /** Consumer plugin that owns this command tree. */
    public JavaPlugin owner() {
        return ctx.plugin();
    }

    /** Registers this root against Bukkit under the owning plugin. */
    @Override
    public void register() {
        BukkitCommandRegistry.register(owner(), this);
    }

    /** Unregisters this root and refreshes the client command trees. */
    @Override
    public void unregister() {
        BukkitCommandRegistry.unregister(owner(), this);
    }

    @Override
    public boolean execute(CommandSender sender, String label, String[] args) {
        Resolution resolution = resolve(sender, rootPermission, subs, "/" + getName(), args);
        switch (resolution) {
            case Empty ignored -> {
                if (onEmpty != null) {
                    try {
                        onEmpty.accept(new RootContext(sender, page -> sendHelp(sender, page)));
                    } catch (Throwable t) {
                        ctx.plugin().getLogger().log(Level.SEVERE,
                                "Bare-root handler of '/" + getName() + "' failed", t);
                    }
                } else {
                    sendHelp(sender);
                }
            }
            case Message message -> send(sender, message.key(), message.phs());
            case Run run -> {
                try {
                    run.sub().executor.accept(run.context());
                } catch (Throwable t) {
                    ctx.plugin().getLogger().log(Level.SEVERE,
                            "Subcommand '" + run.path() + "' failed", t);
                }
            }
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        return tab(sender, rootPermission, subs, args);
    }

    /**
     * Pure tab completion against a node list: the root permission gates the whole tree,
     * then completion recurses through groups down to the leaf being typed.
     */
    static List<String> tab(CommandSender sender, @Nullable String rootPermission,
            List<Sub> subs, String[] args) {
        if (rootPermission != null && !sender.hasPermission(rootPermission)) {
            return List.of();
        }
        return tabAt(sender, subs, args, 0);
    }

    /**
     * Pure resolution of an invocation against a node list: no message is sent and no
     * executor is run, so the outcome is decided independently of the Bukkit context.
     * The permission chain (root, then every group and leaf on the path) is enforced as
     * the tree is descended.
     */
    static Resolution resolve(CommandSender sender, @Nullable String rootPermission,
            List<Sub> subs, String rootPath, String[] args) {
        if (rootPermission != null && !sender.hasPermission(rootPermission)) {
            return new Message("snlib.no-permission");
        }
        if (args.length == 0) {
            return new Empty();
        }
        Sub sub = find(subs, args[0]);
        if (sub == null) {
            return new Message("snlib.unknown-subcommand", Ph.of("value", args[0]));
        }
        return dispatch(sender, sub, args, 0, rootPath + " " + sub.name);
    }

    /**
     * Recursively resolves {@code sub}: a group dispatches on the next token among its
     * children (with child aliases) and falls back to a full-path usage (no token) or a
     * full-path unknown-subcommand message (unknown token); a leaf validates arity and
     * conditions, then parses its positional arguments relative to itself. {@code matchedAt}
     * is the index in {@code args} of the token that selected {@code sub}; {@code path} is
     * the full command path up to and including it.
     */
    private static Resolution dispatch(CommandSender sender, Sub sub, String[] args,
            int matchedAt, String path) {
        if (sub.permission != null && !sender.hasPermission(sub.permission)) {
            return new Message("snlib.no-permission");
        }
        if (!sub.children.isEmpty()) {
            int next = matchedAt + 1;
            if (next >= args.length) {
                return new Message("snlib.usage", Ph.of("usage", groupUsage(sender, sub, path)));
            }
            Sub child = find(sub.children, args[next]);
            if (child == null) {
                return new Message("snlib.unknown-subcommand",
                        Ph.of("value", path + " " + args[next]));
            }
            return dispatch(sender, child, args, next, path + " " + child.name);
        }
        String[] subArgs = Arrays.copyOfRange(args, matchedAt + 1, args.length);
        if (subArgs.length < sub.requiredArgs) {
            return new Message("snlib.usage", Ph.of("usage", usageOf(sub, path)));
        }
        for (Condition condition : sub.conditions) {
            int at = condition.index();
            if (at >= 0 && at < subArgs.length && !condition.test().test(subArgs[at])) {
                return new Message("snlib.usage", Ph.of("usage", usageOf(sub, path)));
            }
        }
        Map<String, Object> values = new LinkedHashMap<>();
        int index = 0;
        int lastIndex = sub.args.size() - 1;
        for (Map.Entry<String, Arg<?>> entry : sub.args.entrySet()) {
            if (index >= subArgs.length) {
                break;
            }
            String token = subArgs[index];
            if (index == lastIndex && isGreedy(entry.getValue())) {
                token = String.join(" ",
                        Arrays.copyOfRange(subArgs, index, subArgs.length));
            }
            try {
                values.put(entry.getKey(), entry.getValue().parse(sender, token));
            } catch (Arg.ArgParseException e) {
                return new Message(e.langKey(), e.phs());
            }
            index++;
        }
        if (sub.executor == null) {
            return new Message("snlib.usage", Ph.of("usage", usageOf(sub, path)));
        }
        return new Run(sub, new CommandContext(sender, values, subArgs), path);
    }

    /**
     * Recursive tab completion: at group depth suggests the child names the sender may
     * use, at leaf depth completes the leaf's positional argument. {@code pos} is the index
     * in {@code args} of the token being resolved at this level.
     */
    private static List<String> tabAt(CommandSender sender, List<Sub> nodes, String[] args,
            int pos) {
        if (args.length <= pos + 1) {
            String partial = args.length <= pos ? "" : args[pos].toLowerCase(Locale.ROOT);
            return suggestNames(sender, nodes, partial);
        }
        Sub sub = find(nodes, args[pos]);
        if (sub == null) {
            return List.of();
        }
        if (sub.permission != null && !sender.hasPermission(sub.permission)) {
            return List.of();
        }
        if (!sub.children.isEmpty()) {
            return tabAt(sender, sub.children, args, pos + 1);
        }
        return tabLeaf(sender, sub, args, pos + 1);
    }

    /** Visible node names the sender may use, filtered by {@code partial} and sorted. */
    private static List<String> suggestNames(CommandSender sender, List<Sub> nodes,
            String partial) {
        List<String> names = new ArrayList<>();
        for (Sub sub : nodes) {
            if (!sub.visible) {
                continue;
            }
            if (sub.permission != null && !sender.hasPermission(sub.permission)) {
                continue;
            }
            if (sub.name.startsWith(partial)) {
                names.add(sub.name);
            }
        }
        Collections.sort(names);
        return names;
    }

    /** Positional-argument completion of a leaf; {@code argsStart} is the index of its first argument. */
    private static List<String> tabLeaf(CommandSender sender, Sub sub, String[] args,
            int argsStart) {
        int argIndex = args.length - 1 - argsStart;
        if (argIndex >= sub.args.size()) {
            if (sub.args.isEmpty()) {
                return List.of();
            }
            Map.Entry<String, Arg<?>> last = entryAt(sub, sub.args.size() - 1);
            if (!isGreedy(last.getValue())) {
                return List.of();
            }
            List<String> greedySuggestions = last.getValue()
                    .suggest(sender, args[args.length - 1], last.getKey());
            return greedySuggestions == null ? List.of() : greedySuggestions;
        }
        Map.Entry<String, Arg<?>> entry = entryAt(sub, argIndex);
        List<String> suggestions = entry.getValue()
                .suggest(sender, args[args.length - 1], entry.getKey());
        return suggestions == null ? List.of() : suggestions;
    }

    /** Generated help, first page. */
    void sendHelp(CommandSender sender) {
        sendHelp(sender, 1);
    }

    /**
     * Generated help: header plus one entry per reachable leaf (groups are flattened, so a
     * leaf renders with its full path), paginated through {@link Page}; a footer with the
     * page indicator appears only when the entries span several pages.
     */
    void sendHelp(CommandSender sender, int pageNumber) {
        List<HelpLine> lines = collectHelp(sender, subs, "/" + getName(), rootPermission);
        Page<HelpLine> page = Page.of(lines, HELP_PAGE_SIZE);
        int current = page.clamp(pageNumber);
        send(sender, "snlib.help.header", Ph.of("plugin", ctx.plugin().getName()));
        for (HelpLine line : page.page(current)) {
            send(sender, "snlib.help.entry",
                    Ph.of("usage", line.usage()),
                    Ph.of("description", line.description()),
                    Ph.of("permission", line.permission() == null ? "" : line.permission()));
        }
        if (page.totalPages() > 1) {
            send(sender, "snlib.help.footer",
                    Ph.of("page", current),
                    Ph.of("total", page.totalPages()),
                    Ph.of("command", getName()));
        }
    }

    /**
     * Flattens the tree into one help line per reachable leaf: a node hidden or whose own
     * permission the sender lacks (and its subtree) is skipped; a group recurses into its
     * children; a leaf yields its full-path usage, description and effective permission
     * ({@code inheritedPermission} narrowed by each node's own permission on the path).
     */
    static List<HelpLine> collectHelp(CommandSender sender, List<Sub> nodes, String path,
            @Nullable String inheritedPermission) {
        List<HelpLine> out = new ArrayList<>();
        for (Sub sub : nodes) {
            if (!sub.visible) {
                continue;
            }
            if (sub.permission != null && !sender.hasPermission(sub.permission)) {
                continue;
            }
            String effective = sub.permission != null ? sub.permission : inheritedPermission;
            String here = path + " " + sub.name;
            if (sub.children.isEmpty()) {
                out.add(new HelpLine(usageOf(sub, here), sub.description, effective));
            } else {
                out.addAll(collectHelp(sender, sub.children, here, effective));
            }
        }
        return out;
    }

    /** Optional help page token; anything unparseable falls back to page 1. */
    private static int pageFrom(@Nullable String raw) {
        if (raw == null) {
            return 1;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException e) {
            return 1;
        }
    }

    /** Whether the arg consumes every remaining token (only factory args can). */
    private static boolean isGreedy(Arg<?> arg) {
        return arg instanceof Args.SnArg<?> snArg && snArg.greedy();
    }

    /** Node matching {@code token} by name or alias among {@code nodes}, or null when none. */
    private static @Nullable Sub find(List<Sub> nodes, String token) {
        String key = token.toLowerCase(Locale.ROOT);
        for (Sub sub : nodes) {
            if (sub.name.equals(key) || sub.aliases.contains(key)) {
                return sub;
            }
        }
        return null;
    }

    /** Full-path usage of a leaf: its explicit usage, or the path plus its argument hints. */
    static String usageOf(Sub sub, String fullPath) {
        if (sub.usage != null) {
            return sub.usage;
        }
        StringBuilder out = new StringBuilder(fullPath);
        int index = 0;
        int lastIndex = sub.args.size() - 1;
        for (Map.Entry<String, Arg<?>> entry : sub.args.entrySet()) {
            boolean optional = index >= sub.requiredArgs;
            out.append(optional ? " [" : " <").append(entry.getKey());
            if (index == lastIndex && isGreedy(entry.getValue())) {
                out.append("...");
            }
            out.append(optional ? ']' : '>');
            index++;
        }
        return out.toString();
    }

    /** Full-path usage of a group: the path plus the child names the sender may use. */
    static String groupUsage(CommandSender sender, Sub group, String path) {
        List<String> names = new ArrayList<>();
        for (Sub child : group.children) {
            if (!child.visible) {
                continue;
            }
            if (child.permission != null && !sender.hasPermission(child.permission)) {
                continue;
            }
            names.add(child.name);
        }
        if (names.isEmpty()) {
            return path + " <subcommand>";
        }
        return path + " <" + String.join("|", names) + ">";
    }

    private static Map.Entry<String, Arg<?>> entryAt(Sub sub, int index) {
        int i = 0;
        for (Map.Entry<String, Arg<?>> entry : sub.args.entrySet()) {
            if (i == index) {
                return entry;
            }
            i++;
        }
        throw new IllegalStateException("Argument index out of range: " + index);
    }

    private static boolean hasSub(List<Sub> subs, String name) {
        for (Sub sub : subs) {
            if (sub.name.equals(name) || sub.aliases.contains(name)) {
                return true;
            }
        }
        return false;
    }

    /** Lang module when declared, shared default templates otherwise. */
    private void send(CommandSender sender, String key, Ph... phs) {
        if (lang != null) {
            lang.send(sender, key, phs);
            return;
        }
        String template = DEFAULT_MESSAGES.get(key);
        if (template == null) {
            template = "<missing:" + key + ">";
        }
        sender.sendMessage(SnText.color(SnText.applyLocals(template, phs)));
    }

    /**
     * Declarative raw-token condition declared through
     * {@link SubCommandBuilder#when(int, Predicate)}: a failing token at {@code index}
     * rejects the invocation with the usage message before any typed parsing.
     */
    record Condition(int index, Predicate<String> test) {
    }

    /** One generated-help line: a leaf's full-path usage, description and effective permission. */
    record HelpLine(String usage, String description, @Nullable String permission) {
    }

    /** Outcome of {@link #resolve}: {@link Empty}, a {@link Message} to send, or a {@link Run}. */
    sealed interface Resolution permits Empty, Message, Run {
    }

    /** The root was invoked with zero arguments; the bare-root hook or the help applies. */
    record Empty() implements Resolution {
    }

    /** The resolved leaf, its parsed context and its full path; ready to run its executor. */
    record Run(Sub sub, CommandContext context, String path) implements Resolution {
    }

    /** A message to send back to the sender: a lang key with its local placeholders. */
    static final class Message implements Resolution {

        private final String key;
        private final Ph[] phs;

        Message(String key, Ph... phs) {
            this.key = key;
            this.phs = phs == null ? new Ph[0] : phs.clone();
        }

        String key() {
            return key;
        }

        Ph[] phs() {
            return phs.clone();
        }
    }

    /** Immutable subcommand node; built by {@link SubCommandBuilder}. */
    static final class Sub {

        final String name;
        final List<String> aliases;
        final @Nullable String permission;
        final @Nullable String usage;
        final String description;
        final boolean visible;
        final Map<String, Arg<?>> args;
        final int requiredArgs;
        final List<Condition> conditions;
        final @Nullable Consumer<CommandContext> executor;
        final List<Sub> children;

        Sub(String name, List<String> aliases, @Nullable String permission,
                @Nullable String usage, String description, boolean visible,
                Map<String, Arg<?>> args, int requiredArgs, List<Condition> conditions,
                @Nullable Consumer<CommandContext> executor, List<Sub> children) {
            this.name = name.trim().toLowerCase(Locale.ROOT);
            List<String> lowered = new ArrayList<>(aliases.size());
            for (String alias : aliases) {
                lowered.add(alias.trim().toLowerCase(Locale.ROOT));
            }
            this.aliases = List.copyOf(lowered);
            this.permission = permission;
            this.usage = usage;
            this.description = description;
            this.visible = visible;
            this.args = Collections.unmodifiableMap(new LinkedHashMap<>(args));
            this.requiredArgs = requiredArgs;
            this.conditions = List.copyOf(conditions);
            this.executor = executor;
            this.children = List.copyOf(children);
        }

        static Sub of(String name, @Nullable String permission, String description,
                Consumer<CommandContext> executor) {
            return new Sub(name, List.of(), permission, null, description, true,
                    Map.of(), 0, List.of(), executor, List.of());
        }
    }
}
