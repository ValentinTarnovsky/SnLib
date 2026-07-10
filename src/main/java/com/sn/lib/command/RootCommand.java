package com.sn.lib.command;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Consumer;
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
 * <p>Permission inheritance: a subcommand without its own permission inherits the root
 * permission; a root without permission is public. Tab completion and the generated help
 * list only subcommands that are visible AND whose effective permission the sender
 * holds.</p>
 *
 * <p>Messages resolve through the context lang module when declared; without it the
 * shared {@code snlib.*} default templates bundled with the library render directly.</p>
 */
public final class RootCommand extends Command implements Registrable {

    /** Server-wide static justified: constant default templates mirroring snlib-messages.yml. */
    private static final Map<String, String> DEFAULT_MESSAGES = Map.of(
            "snlib.no-permission", "&cYou do not have permission to use this command.",
            "snlib.usage", "&cUsage: &f{usage}",
            "snlib.invalid-number", "&cInvalid number: &f{value}",
            "snlib.player-not-found", "&cPlayer not found: &f{value}",
            "snlib.unknown-subcommand", "&cUnknown subcommand: &f{value}",
            "snlib.reload-done", "&aConfiguration reloaded.",
            "snlib.help.header", "&6&lCommands",
            "snlib.help.entry", "&e{usage} &8- &7{permission}");

    private final Sn ctx;
    private final @Nullable SnLang lang;
    private final @Nullable String rootPermission;
    private final List<Sub> subs;

    /**
     * Builds the tree, injecting the default subcommands where applicable: {@code reload}
     * and {@code help} unless defaults were opted out, {@code debug} when the spec
     * declared it. A consumer subcommand with the same name replaces the default.
     */
    RootCommand(Sn ctx, @Nullable SnLang lang, String name, List<String> aliases,
            String description, @Nullable String permission, List<Sub> declared,
            boolean withDefaults, boolean debugCommand) {
        super(name.toLowerCase(Locale.ROOT), description,
                "/" + name.toLowerCase(Locale.ROOT), aliases);
        this.ctx = ctx;
        this.lang = lang;
        this.rootPermission = permission;
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
                        context -> sendHelp(context.sender())));
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
        if (rootPermission != null && !sender.hasPermission(rootPermission)) {
            send(sender, "snlib.no-permission");
            return true;
        }
        if (args.length == 0) {
            sendHelp(sender);
            return true;
        }
        Sub sub = find(args[0]);
        if (sub == null) {
            send(sender, "snlib.unknown-subcommand", Ph.of("value", args[0]));
            return true;
        }
        String permission = effectivePermission(sub);
        if (permission != null && !sender.hasPermission(permission)) {
            send(sender, "snlib.no-permission");
            return true;
        }
        String[] subArgs = Arrays.copyOfRange(args, 1, args.length);
        if (subArgs.length < sub.args.size()) {
            send(sender, "snlib.usage", Ph.of("usage", usageOf(sub)));
            return true;
        }
        Map<String, Object> values = new LinkedHashMap<>();
        int index = 0;
        for (Map.Entry<String, Arg<?>> entry : sub.args.entrySet()) {
            try {
                values.put(entry.getKey(), entry.getValue().parse(subArgs[index]));
            } catch (Arg.ArgParseException e) {
                send(sender, e.langKey(), e.phs());
                return true;
            }
            index++;
        }
        if (sub.executor == null) {
            send(sender, "snlib.usage", Ph.of("usage", usageOf(sub)));
            return true;
        }
        try {
            sub.executor.accept(new CommandContext(sender, values, subArgs));
        } catch (Throwable t) {
            ctx.plugin().getLogger().log(Level.SEVERE,
                    "El subcomando '/" + getName() + " " + sub.name + "' fallo", t);
        }
        return true;
    }

    @Override
    public List<String> tabComplete(CommandSender sender, String alias, String[] args) {
        if (rootPermission != null && !sender.hasPermission(rootPermission)) {
            return List.of();
        }
        if (args.length <= 1) {
            String partial = args.length == 0 ? "" : args[0].toLowerCase(Locale.ROOT);
            List<String> names = new ArrayList<>();
            for (Sub sub : subs) {
                if (!sub.visible) {
                    continue;
                }
                String permission = effectivePermission(sub);
                if (permission != null && !sender.hasPermission(permission)) {
                    continue;
                }
                if (sub.name.startsWith(partial)) {
                    names.add(sub.name);
                }
            }
            Collections.sort(names);
            return names;
        }
        Sub sub = find(args[0]);
        if (sub == null) {
            return List.of();
        }
        String permission = effectivePermission(sub);
        if (permission != null && !sender.hasPermission(permission)) {
            return List.of();
        }
        int argIndex = args.length - 2;
        if (argIndex >= sub.args.size()) {
            return List.of();
        }
        List<String> suggestions = argAt(sub, argIndex).suggest(sender, args[args.length - 1]);
        return suggestions == null ? List.of() : suggestions;
    }

    /** Generated help: header plus one entry per visible subcommand the sender may use. */
    private void sendHelp(CommandSender sender) {
        send(sender, "snlib.help.header");
        for (Sub sub : subs) {
            if (!sub.visible) {
                continue;
            }
            String permission = effectivePermission(sub);
            if (permission != null && !sender.hasPermission(permission)) {
                continue;
            }
            send(sender, "snlib.help.entry",
                    Ph.of("usage", usageOf(sub)),
                    Ph.of("permission", permission == null ? "" : permission));
        }
    }

    /** Effective permission of the node: its own or, when absent, the inherited root one. */
    private @Nullable String effectivePermission(Sub sub) {
        return sub.permission != null ? sub.permission : rootPermission;
    }

    private @Nullable Sub find(String token) {
        String key = token.toLowerCase(Locale.ROOT);
        for (Sub sub : subs) {
            if (sub.name.equals(key) || sub.aliases.contains(key)) {
                return sub;
            }
        }
        return null;
    }

    private String usageOf(Sub sub) {
        if (sub.usage != null) {
            return sub.usage;
        }
        StringBuilder out = new StringBuilder("/").append(getName()).append(' ').append(sub.name);
        for (String argName : sub.args.keySet()) {
            out.append(" <").append(argName).append('>');
        }
        return out.toString();
    }

    private static Arg<?> argAt(Sub sub, int index) {
        int i = 0;
        for (Arg<?> arg : sub.args.values()) {
            if (i == index) {
                return arg;
            }
            i++;
        }
        throw new IllegalStateException("Indice de argumento fuera de rango: " + index);
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

    /** Immutable subcommand node; built by {@link SubCommandBuilder}. */
    static final class Sub {

        final String name;
        final List<String> aliases;
        final @Nullable String permission;
        final @Nullable String usage;
        final String description;
        final boolean visible;
        final Map<String, Arg<?>> args;
        final @Nullable Consumer<CommandContext> executor;

        Sub(String name, List<String> aliases, @Nullable String permission,
                @Nullable String usage, String description, boolean visible,
                Map<String, Arg<?>> args, @Nullable Consumer<CommandContext> executor) {
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
            this.executor = executor;
        }

        static Sub of(String name, @Nullable String permission, String description,
                Consumer<CommandContext> executor) {
            return new Sub(name, List.of(), permission, null, description, true,
                    Map.of(), executor);
        }
    }
}
