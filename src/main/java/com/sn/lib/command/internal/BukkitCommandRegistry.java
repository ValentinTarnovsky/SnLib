package com.sn.lib.command.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.command.RootCommand;
import com.sn.lib.tenant.TenantRegistry;

/**
 * Bridge between {@link RootCommand} trees and the Bukkit command system, preferring the
 * public API in two paths: (a) a command declared in the owner's plugin.yml gets its
 * executor and tab completer wired through {@code plugin.getCommand(name)}; (b) undeclared
 * roots go through Paper's public {@code Bukkit.getCommandMap()}, with a WARN. In both
 * paths the dynamic aliases (builder varargs, an alias supplier, or the config-driven
 * binding) are reconciled against the CommandMap's known commands. After every register and
 * unregister the online players get {@code updateCommands()} so their client trees never
 * show ghosts.
 *
 * <p>Registered roots are tracked in a {@link TenantRegistry} keyed by the owning plugin:
 * the tenant sweep detaches each command and removes the whole owner key when the
 * consumer disables, even if the owner never called the teardown.</p>
 *
 * <p>Dynamic aliases are re-sourced on every register pass (the reload flow re-registers
 * the same root instance): the alias supplier is re-evaluated, aliases that appeared are
 * added with {@code putIfAbsent} plus a WARN, and aliases that disappeared are removed from
 * the known commands. The supplier is stored here, alongside the registered root, so the
 * {@link RootCommand} core stays immutable.</p>
 */
public final class BukkitCommandRegistry {

    /** Server-wide static justified: root commands keyed per owning plugin for the sweep. */
    private static final TenantRegistry<RootCommand> COMMANDS =
            new TenantRegistry<>(BukkitCommandRegistry::sweep);

    /** Server-wide static justified: dynamic-alias state keyed by root instance identity. */
    private static final Map<RootCommand, AliasState> ALIASES = new ConcurrentHashMap<>();

    private BukkitCommandRegistry() {
    }

    /**
     * Binds the dynamic-alias supplier of a root before it is registered; a null supplier
     * means the builder / plugin.yml aliases are the sole source. Called by the command
     * builder at build time so the supplier travels with the root into every register pass.
     */
    public static void bindAliasSupplier(RootCommand command,
            @Nullable Supplier<Collection<String>> supplier) {
        ALIASES.put(command, new AliasState(supplier));
    }

    /**
     * Registers the root for its owner. Reload-safe: a root already registered by the
     * same owner under the same name is detached and replaced first. Re-registering the
     * SAME root instance keeps it in place and only reconciles its dynamic aliases.
     */
    public static void register(JavaPlugin owner, RootCommand command) {
        for (RootCommand existing : COMMANDS.forOwner(owner)) {
            if (existing != command && existing.getName().equalsIgnoreCase(command.getName())) {
                COMMANDS.remove(owner, existing);
                detach(existing);
            }
        }
        PluginCommand declared = owner.getCommand(command.getName());
        if (declared != null) {
            PluginCommandAdapter adapter = new PluginCommandAdapter(command);
            declared.setExecutor(adapter);
            declared.setTabCompleter(adapter);
        } else {
            owner.getLogger().warning("Command '/" + command.getName()
                    + "' not declared in the plugin.yml of " + owner.getName()
                    + "; dynamic registration via CommandMap");
            CommandMap map = Bukkit.getCommandMap();
            Map<String, Command> known = map.getKnownCommands();
            known.putIfAbsent(command.getName(), command);
            known.putIfAbsent(prefix(owner) + ":" + command.getName(), command);
            command.register(map);
        }
        reconcileAliases(owner, command, declared);
        COMMANDS.add(owner, command);
        updateCommands();
    }

    /** Unregisters one root of the owner and refreshes the client command trees. */
    public static void unregister(JavaPlugin owner, RootCommand command) {
        COMMANDS.remove(owner, command);
        detach(command);
        updateCommands();
    }

    /**
     * Unregisters every root of the owner, removing the WHOLE owner key; the sweep
     * callback detaches each command and refreshes the client command trees.
     */
    public static void unregisterAll(JavaPlugin owner) {
        COMMANDS.removeOwner(owner);
    }

    /**
     * Re-registers every root of the owner in place; the reload flow's re-register step.
     * Each register pass re-sources the dynamic aliases and refreshes the online players'
     * command trees.
     */
    public static void reregisterAll(JavaPlugin owner) {
        for (RootCommand command : new ArrayList<>(COMMANDS.forOwner(owner))) {
            register(owner, command);
        }
    }

    /** Sweep callback: also runs when the tenant sweeper removes a disabled owner's key. */
    private static void sweep(RootCommand command) {
        detach(command);
        updateCommands();
    }

    /**
     * Reconciles the dynamic aliases of a root against the CommandMap. The desired set is
     * the alias supplier's value when it has one (authoritative, config-driven), otherwise
     * the builder / plugin.yml aliases; the root name and the plugin.yml declared aliases
     * are always excluded. Aliases that appeared are added with {@code putIfAbsent} plus a
     * WARN; aliases that disappeared since the previous pass are removed.
     */
    private static void reconcileAliases(JavaPlugin owner, RootCommand command,
            @Nullable PluginCommand declared) {
        AliasState state = ALIASES.computeIfAbsent(command, ignored -> new AliasState(null));
        Collection<String> supplied = evaluate(owner, command, state.supplier);
        List<String> declaredAliases = declared == null ? List.of() : declared.getAliases();
        List<String> desired = AliasReconciler.resolve(supplied, command.getAliases(),
                command.getName(), declaredAliases);
        AliasReconciler.Diff diff = AliasReconciler.diff(state.active, desired);
        if (diff.added().isEmpty() && diff.removed().isEmpty()) {
            state.active = desired;
            return;
        }
        CommandMap map = Bukkit.getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        String prefix = prefix(owner);
        for (String alias : diff.removed()) {
            known.remove(alias, command);
            known.remove(prefix + ":" + alias, command);
        }
        List<String> added = new ArrayList<>();
        List<String> collided = new ArrayList<>();
        for (String alias : diff.added()) {
            Command previous = known.putIfAbsent(alias, command);
            known.putIfAbsent(prefix + ":" + alias, command);
            if (previous == null || previous == command) {
                added.add(alias);
            } else {
                collided.add(alias);
            }
        }
        state.active = desired;
        if (!added.isEmpty()) {
            owner.getLogger().warning("Aliases " + added + " of '/" + command.getName()
                    + "' not declared in the plugin.yml of " + owner.getName()
                    + "; dynamic registration via CommandMap");
        }
        if (!collided.isEmpty()) {
            owner.getLogger().warning("Aliases " + collided + " of '/" + command.getName()
                    + "' collide with existing commands; kept the existing ones");
        }
    }

    /** Evaluates the alias supplier defensively; a null supplier or a failure means fallback. */
    private static @Nullable Collection<String> evaluate(JavaPlugin owner, RootCommand command,
            @Nullable Supplier<Collection<String>> supplier) {
        if (supplier == null) {
            return null;
        }
        try {
            return supplier.get();
        } catch (Throwable t) {
            owner.getLogger().warning("Alias supplier of '/" + command.getName()
                    + "' failed; falling back to the static aliases: " + t);
            return null;
        }
    }

    /** Detaches the command from whichever path registered it and drops its alias state. */
    private static void detach(RootCommand command) {
        JavaPlugin owner = command.owner();
        CommandMap map = Bukkit.getCommandMap();
        map.getKnownCommands().entrySet().removeIf(entry -> entry.getValue() == command);
        command.unregister(map);
        PluginCommand declared = owner.getCommand(command.getName());
        if (declared != null
                && declared.getExecutor() instanceof PluginCommandAdapter adapter
                && adapter.root() == command) {
            declared.setExecutor(null);
            declared.setTabCompleter(null);
        }
        ALIASES.remove(command);
    }

    /** Refreshes the client-side command tree of every online player. */
    private static void updateCommands() {
        for (Player player : Bukkit.getOnlinePlayers()) {
            player.updateCommands();
        }
    }

    private static String prefix(JavaPlugin owner) {
        return owner.getName().toLowerCase(Locale.ROOT);
    }

    /** Per-root dynamic-alias state: the supplier and the currently-registered alias keys. */
    private static final class AliasState {

        private final @Nullable Supplier<Collection<String>> supplier;
        private volatile List<String> active = List.of();

        AliasState(@Nullable Supplier<Collection<String>> supplier) {
            this.supplier = supplier;
        }
    }

    /** Executor and tab completer of the plugin.yml path, delegating to the root tree. */
    private record PluginCommandAdapter(RootCommand root) implements CommandExecutor, TabCompleter {

        @Override
        public boolean onCommand(CommandSender sender, Command command, String label,
                String[] args) {
            return root.execute(sender, label, args);
        }

        @Override
        public List<String> onTabComplete(CommandSender sender, Command command,
                String alias, String[] args) {
            return root.tabComplete(sender, alias, args);
        }
    }
}
