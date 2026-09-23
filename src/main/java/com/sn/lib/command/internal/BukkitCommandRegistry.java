package com.sn.lib.command.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.command.RootCommand;
import com.sn.lib.tenant.TenantRegistry;

/**
 * Bridge between {@link RootCommand} trees and the Bukkit command system, preferring the
 * public API in two paths: (a) a command declared in the owner's plugin.yml gets its
 * executor and tab completer wired through {@code plugin.getCommand(name)}; (b) undeclared
 * roots go through Paper's public {@code Bukkit.getCommandMap()}, with a WARN unless the root
 * was built with {@code dynamic()} (registered at runtime on purpose). In both
 * paths the dynamic aliases (builder varargs, an alias supplier, or the config-driven
 * binding) are reconciled against the CommandMap's known commands. After every register and
 * unregister the online players get {@code updateCommands()} so their client trees never
 * show ghosts, and a pass that actually mutated the map goes through {@link CommandSync}
 * instead so the server's own dispatcher is rebuilt where that is what publishes the key.
 *
 * <p>Every write into the known commands goes through the map's own {@code get} and
 * {@code put}, never through {@code putIfAbsent} or an {@code entrySet} bulk removal. On
 * Paper 1.20.6+ that map is {@code BukkitBrigForwardingMap}, a {@code HashMap} subclass whose
 * overridden methods forward to the Brigadier dispatcher: the ones it does NOT override -
 * {@code putIfAbsent} among them - silently operate on the dead inherited table, and its
 * entry-set iterator does not support removal. Only the overridden methods reach the server.</p>
 *
 * <p>Registered roots are tracked in a {@link TenantRegistry} keyed by the owning plugin:
 * the tenant sweep detaches each command and removes the whole owner key when the
 * consumer disables, even if the owner never called the teardown.</p>
 *
 * <p>Dynamic aliases are re-sourced on every register pass (the reload flow re-registers
 * the same root instance): the alias supplier is re-evaluated, aliases that appeared are
 * claimed when their key is free, and aliases that disappeared are removed from the known
 * commands. The plugin.yml WARN covers the fallback path only - a config binding or a
 * supplier owns its aliases at runtime, so those are registered silently. The supplier is
 * stored here, alongside the registered root, so the {@link RootCommand} core stays
 * immutable; the state is weakly keyed so that detaching a root - which resets its active
 * aliases but KEEPS its binding, so the same instance can be registered again - cannot leak
 * a root the owner has dropped.</p>
 *
 * <p>A key another command already holds is reported through {@link CollisionNotice}: a WARN
 * on every pass, except for a {@code dynamic()} root that still owns the namespaced
 * {@code <plugin>:<key>} form, whose collision is noted once per owner and key at INFO. The
 * namespaced key an alias claims while its bare key is taken is tracked as well, so detaching
 * the root or dropping the alias releases it.</p>
 */
public final class BukkitCommandRegistry {

    /** Server-wide static justified: root commands keyed per owning plugin for the sweep. */
    private static final TenantRegistry<RootCommand> COMMANDS =
            new TenantRegistry<>(BukkitCommandRegistry::sweep);

    /**
     * Server-wide static justified: per-root registration state keyed by root instance
     * identity. Weakly keyed because a detached root keeps its entry - {@link RootCommand}
     * inherits identity equality, so the key is the instance itself and the entry dies with it.
     */
    private static final Map<RootCommand, RootState> STATES =
            Collections.synchronizedMap(new WeakHashMap<>());

    /**
     * Server-wide static justified: collided keys (lowercased) each owner already noted at
     * INFO. Keyed by owner and key, never by root instance, because a consumer may rebuild
     * its roots on every reload; swept with the owner's other registrations when its context
     * shuts down, so every enable notes each collision once.
     */
    private static final TenantRegistry<String> COLLISIONS_NOTED = new TenantRegistry<>();

    private BukkitCommandRegistry() {
    }

    /**
     * Binds the dynamic-alias supplier of a root before it is registered; a null supplier
     * means the builder / plugin.yml aliases are the sole source. Called by the command
     * builder at build time so the supplier travels with the root into every register pass.
     */
    public static void bindAliasSupplier(RootCommand command,
            @Nullable Supplier<Collection<String>> supplier) {
        bindAliasSupplier(command, supplier, false);
    }

    /**
     * {@link #bindAliasSupplier(RootCommand, Supplier)} that also records whether the root
     * was built with {@code dynamic()}: registered at runtime on purpose, so an undeclared
     * root or alias is not WARNed and a collision that leaves it reachable under its
     * namespaced key is noted once at INFO. A plugin.yml declaration of the name wins: the
     * flag has no effect on a declared root.
     */
    public static void bindAliasSupplier(RootCommand command,
            @Nullable Supplier<Collection<String>> supplier, boolean dynamic) {
        STATES.put(command, new RootState(supplier, dynamic));
    }

    /**
     * Registers the root for its owner. Reload-safe: a root already registered by the
     * same owner under the same name is detached and replaced first. Re-registering the
     * SAME root instance keeps it in place and only reconciles its dynamic aliases.
     */
    public static void register(JavaPlugin owner, RootCommand command) {
        boolean mutated = false;
        for (RootCommand existing : COMMANDS.forOwner(owner)) {
            if (existing != command && existing.getName().equalsIgnoreCase(command.getName())) {
                COMMANDS.remove(owner, existing);
                mutated |= detach(existing);
            }
        }
        RootState state = STATES.computeIfAbsent(command, ignored -> new RootState(null, false));
        PluginCommand declared = owner.getCommand(command.getName());
        boolean dynamic = state.dynamic && declared == null;
        if (declared != null) {
            PluginCommandAdapter adapter = new PluginCommandAdapter(command);
            declared.setExecutor(adapter);
            declared.setTabCompleter(adapter);
        } else {
            if (!dynamic) {
                owner.getLogger().warning("Command '/" + command.getName()
                        + "' not declared in the plugin.yml of " + owner.getName()
                        + "; dynamic registration via CommandMap");
            }
            CommandMap map = Bukkit.getCommandMap();
            Map<String, Command> known = map.getKnownCommands();
            String name = command.getName();
            String prefix = prefix(owner);
            Command taken = claim(known, name, command);
            Command takenNamespaced = claim(known, prefix + ":" + name, command);
            command.register(map);
            mutated |= taken == null || takenNamespaced == null;
            if (taken != null && taken != command) {
                boolean ownsNamespace = takenNamespaced == null || takenNamespaced == command;
                switch (notice(owner, name, dynamic, ownsNamespace)) {
                    case INFO -> owner.getLogger().info(CollisionNotice.rootInfo(name,
                            owner.getName(), occupantOf(taken), prefix));
                    case WARN -> owner.getLogger().warning(
                            CollisionNotice.rootWarn(name, owner.getName()));
                    case NONE -> {
                    }
                }
            }
        }
        mutated |= reconcileAliases(owner, command, declared, state, dynamic);
        COMMANDS.add(owner, command);
        refresh(mutated);
    }

    /**
     * Roots currently registered by the owner, sorted by name; the input of the command lang
     * pass. The backing set is a {@code ConcurrentHashMap} key set and therefore unordered,
     * so the sort is what keeps the seeded lang block byte-identical across boots instead of
     * churning the owner's file on every start.
     */
    public static List<RootCommand> rootsOf(JavaPlugin owner) {
        List<RootCommand> roots = new ArrayList<>(COMMANDS.forOwner(owner));
        roots.sort(Comparator.comparing(RootCommand::getName));
        return List.copyOf(roots);
    }

    /** Unregisters one root of the owner and refreshes the client command trees. */
    public static void unregister(JavaPlugin owner, RootCommand command) {
        COMMANDS.remove(owner, command);
        refresh(detach(command));
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
        refresh(detach(command));
    }

    /**
     * Reconciles the dynamic aliases of a root against the CommandMap. The desired set is
     * the alias supplier's value when it has one (authoritative, config-driven), otherwise
     * the builder / plugin.yml aliases; the root name and the plugin.yml declared aliases
     * are always excluded. Aliases that appeared are claimed when their key is free; aliases
     * that disappeared since the previous pass are removed. The "not declared in the
     * plugin.yml" WARN fires on the fallback path of a root that is not {@code dynamic()}
     * only (see {@link AliasReconciler#warnsUndeclared(boolean, Collection, Collection)}); a
     * collision is reported through {@link CollisionNotice}.
     *
     * <p>Only the aliases this layer actually owns are recorded as active, so an alias that
     * lost a collision is retried - and re-reported, unless the notice was a once-per-enable
     * INFO - on the next pass instead of being remembered as registered. Its namespaced key,
     * claimed when free, is recorded apart and released once the alias leaves the desired
     * set. Returns whether the CommandMap was mutated.</p>
     */
    private static boolean reconcileAliases(JavaPlugin owner, RootCommand command,
            @Nullable PluginCommand declared, RootState state, boolean dynamic) {
        Collection<String> supplied = evaluate(owner, command, state.supplier);
        List<String> declaredAliases = declared == null ? List.of() : declared.getAliases();
        List<String> desired = AliasReconciler.resolve(supplied, command.getAliases(),
                command.getName(), declaredAliases);
        AliasReconciler.Diff diff = AliasReconciler.diff(state.active, desired);
        List<String> released = AliasReconciler.diff(state.namespaced, desired).removed();
        if (diff.added().isEmpty() && diff.removed().isEmpty() && released.isEmpty()) {
            return false;
        }
        CommandMap map = Bukkit.getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        String prefix = prefix(owner);
        for (String alias : diff.removed()) {
            known.remove(alias, command);
            known.remove(prefix + ":" + alias, command);
        }
        for (String alias : released) {
            known.remove(prefix + ":" + alias, command);
        }
        List<String> added = new ArrayList<>();
        List<String> collided = new ArrayList<>();
        List<String> namespaced = new ArrayList<>();
        List<String> warned = new ArrayList<>();
        for (String alias : diff.added()) {
            Command previous = claim(known, alias, command);
            Command previousNamespaced = claim(known, prefix + ":" + alias, command);
            if (previous == null || previous == command) {
                added.add(alias);
                continue;
            }
            collided.add(alias);
            boolean ownsNamespace = previousNamespaced == null || previousNamespaced == command;
            if (ownsNamespace) {
                namespaced.add(alias);
            }
            switch (notice(owner, alias, dynamic, ownsNamespace)) {
                case INFO -> owner.getLogger().info(CollisionNotice.aliasInfo(alias,
                        command.getName(), owner.getName(), occupantOf(previous), prefix));
                case WARN -> warned.add(alias);
                case NONE -> {
                }
            }
        }
        List<String> owned = new ArrayList<>(desired);
        owned.removeAll(collided);
        state.active = List.copyOf(owned);
        state.namespaced = List.copyOf(namespaced);
        if (AliasReconciler.warnsUndeclared(dynamic, supplied, added)) {
            owner.getLogger().warning("Aliases " + added + " of '/" + command.getName()
                    + "' not declared in the plugin.yml of " + owner.getName()
                    + "; dynamic registration via CommandMap");
        }
        if (!warned.isEmpty()) {
            owner.getLogger().warning("Aliases " + warned + " of '/" + command.getName()
                    + "' collide with existing commands; kept the existing ones");
        }
        return true;
    }

    /**
     * Decides how a collided key is reported and, for the once-per-enable INFO, records the
     * key as noted so the later passes of this enable stay silent. Register passes run on the
     * main thread, so the check and the record need no further coordination.
     */
    private static CollisionNotice notice(JavaPlugin owner, String key, boolean dynamic,
            boolean ownsNamespace) {
        String noted = key.toLowerCase(Locale.ROOT);
        CollisionNotice notice = CollisionNotice.of(dynamic, ownsNamespace,
                COLLISIONS_NOTED.forOwner(owner).contains(noted));
        if (notice == CollisionNotice.INFO) {
            COLLISIONS_NOTED.add(owner, noted);
        }
        return notice;
    }

    /**
     * Plugin behind the command that kept a key: a {@link PluginIdentifiableCommand} (a
     * plugin.yml command) names its plugin, a root of another SnLib consumer names its owner,
     * anything else is {@link CollisionNotice#UNKNOWN_OCCUPANT}.
     */
    private static String occupantOf(Command occupant) {
        String plugin = null;
        if (occupant instanceof PluginIdentifiableCommand identifiable) {
            Plugin owner = identifiable.getPlugin();
            plugin = owner == null ? null : owner.getName();
        } else if (occupant instanceof RootCommand root) {
            plugin = root.owner().getName();
        }
        return CollisionNotice.occupant(plugin);
    }

    /**
     * Claims the key for the root when it is free, returning the occupant that kept it or null
     * when the claim landed. The read and the write go through the map's OWN {@code get} and
     * {@code put} rather than {@code putIfAbsent}: on Paper 1.20.6+ those two are the overridden
     * methods that reach the Brigadier dispatcher, while the inherited {@code putIfAbsent}
     * writes into a dead table and reports success.
     */
    private static @Nullable Command claim(Map<String, Command> known, String key,
            RootCommand command) {
        Command previous = known.get(key);
        if (previous != null) {
            return previous;
        }
        known.put(key, command);
        return null;
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

    /**
     * Detaches the command from whichever path registered it and clears its active aliases,
     * returning whether the CommandMap was mutated. Every key this layer could have claimed is
     * removed BY KEY - the root name and its namespaced form for the fallback path, each
     * active alias and its namespaced form, and the namespaced form held by each alias that
     * lost its bare key - because a bulk {@code entrySet().removeIf} throws on Paper 1.20.6+,
     * whose entry-set iterator streams the dispatcher and refuses removal.
     *
     * <p>The binding itself survives: only {@link RootState#active} and
     * {@link RootState#namespaced} are reset, so the same root instance can be detached and
     * registered again without losing the supplier that makes its aliases config-driven or
     * its {@code dynamic()} flag.</p>
     */
    private static boolean detach(RootCommand command) {
        JavaPlugin owner = command.owner();
        CommandMap map = Bukkit.getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        String prefix = prefix(owner);
        RootState state = STATES.get(command);
        boolean mutated = false;
        for (String alias : state == null ? List.<String>of() : state.active) {
            mutated |= known.remove(alias, command);
            mutated |= known.remove(prefix + ":" + alias, command);
        }
        for (String alias : state == null ? List.<String>of() : state.namespaced) {
            mutated |= known.remove(prefix + ":" + alias, command);
        }
        String name = command.getName();
        mutated |= known.remove(name, command);
        mutated |= known.remove(prefix + ":" + name, command);
        command.unregister(map);
        PluginCommand declared = owner.getCommand(name);
        if (declared != null
                && declared.getExecutor() instanceof PluginCommandAdapter adapter
                && adapter.root() == command) {
            declared.setExecutor(null);
            declared.setTabCompleter(null);
        }
        if (state != null) {
            state.active = List.of();
            state.namespaced = List.of();
        }
        return mutated;
    }

    /**
     * Publishes a register or unregister pass: a pass that mutated the CommandMap goes through
     * {@link CommandSync}, which on the server generations that need it rebuilds the dispatcher
     * before re-sending the trees; anything else only refreshes the client trees.
     */
    private static void refresh(boolean mutated) {
        if (mutated && CommandSync.sync()) {
            return;
        }
        updateCommands();
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

    /**
     * Per-root registration state: the alias supplier, the {@code dynamic()} flag, the alias
     * keys currently registered, and the aliases that lost their bare key but hold its
     * namespaced {@code <plugin>:<alias>} form.
     */
    private static final class RootState {

        private final @Nullable Supplier<Collection<String>> supplier;
        private final boolean dynamic;
        private volatile List<String> active = List.of();
        private volatile List<String> namespaced = List.of();

        RootState(@Nullable Supplier<Collection<String>> supplier, boolean dynamic) {
            this.supplier = supplier;
            this.dynamic = dynamic;
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
