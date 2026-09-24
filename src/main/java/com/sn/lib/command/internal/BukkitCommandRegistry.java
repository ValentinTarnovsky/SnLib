package com.sn.lib.command.internal;

import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.WeakHashMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.command.defaults.BukkitCommand;
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
 *
 * <p>Command priority ({@code SnSpec.Builder.commandPriority()}): a root of such a plugin
 * takes a bare key (its name on the fallback path or on the declared path, and each alias)
 * from the command of another plugin or from a root of another SnLib plugin without priority
 * ({@link CollisionNotice#takesOver}); the namespaced keys, and any key holding a {@code :},
 * are claimed as always, never taken. The displaced command is remembered per key (weakly,
 * with its plugin's name) and gets the key back when the root releases it, unless the server
 * is stopping; a plugin that enables later and could not register the key is remembered the
 * same way. {@link #reclaimPriority(Plugin)} takes back a key a later plugin overwrote.
 * Without priority none of this runs and every pass is the 1.38.0 one.</p>
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
        bindAliasSupplier(command, supplier, dynamic, false);
    }

    /**
     * {@link #bindAliasSupplier(RootCommand, Supplier, boolean)} that also records whether the
     * owner declared command priority: the root then takes its bare name and aliases from the
     * commands of other plugins and hands them back when it releases them.
     */
    public static void bindAliasSupplier(RootCommand command,
            @Nullable Supplier<Collection<String>> supplier, boolean dynamic, boolean priority) {
        STATES.put(command, new RootState(supplier, dynamic, priority));
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
        RootState state = STATES.computeIfAbsent(command,
                ignored -> new RootState(null, false, false));
        PluginCommand declared = owner.getCommand(command.getName());
        boolean dynamic = state.dynamic && declared == null;
        if (declared != null) {
            PluginCommandAdapter adapter = new PluginCommandAdapter(command);
            declared.setExecutor(adapter);
            declared.setTabCompleter(adapter);
            if (state.priority) {
                mutated |= takeDeclared(owner, Bukkit.getCommandMap().getKnownCommands(),
                        command, declared, state);
            }
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
            Command taken = claimOrTake(owner, known, name, command, state);
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
        if (state.priority) {
            // After the reconcile, which returns early on an empty alias diff, so a re-register
            // of the same root (the reload flow) takes its overwritten keys back too.
            mutated |= reclaimRoot(owner, command, state);
        }
        COMMANDS.add(owner, command);
        refresh(mutated);
    }

    /**
     * {@link #reclaimPriority(Plugin)} with no plugin just enabled: the server finished
     * loading.
     */
    public static void reclaimPriority() {
        reclaimPriority(null);
    }

    /**
     * Takes back, for every registered root of a plugin with command priority, each bare key
     * it holds that another plugin overwrote since (a later plugin whose label equals one of
     * its aliases, or a direct write into the known commands), under the same rules as the
     * register pass. Called on the main thread by {@link CommandPriorityListener} when a
     * plugin finishes enabling and when the server finishes loading; publishes the change
     * once, and only when a key moved. The aliases are checked one by one against the map:
     * the alias reconcile skips a pass whose alias set did not change. Each root is isolated:
     * a failure (a third-party command that throws when asked for its plugin) is logged by its
     * owner and the other roots still run, so the event of an unrelated plugin never fails.
     *
     * @param enabled the plugin that just finished enabling, or null; for every bare key a
     *                root holds with nobody displaced there, the command of this plugin at
     *                {@code <plugin>:<key>} (Bukkit refused it the bare key) is remembered as
     *                displaced, silently, so it gets the key when the root releases it
     */
    public static void reclaimPriority(@Nullable Plugin enabled) {
        List<RootCommand> roots = new ArrayList<>();
        COMMANDS.forEachOwner((owner, commands) -> roots.addAll(commands));
        boolean mutated = reclaimRoots(roots);
        if (enabled != null) {
            noteLatecomer(roots, enabled);
        }
        if (mutated) {
            refresh(true);
        }
    }

    /**
     * {@link #reclaim} of every root with command priority among {@code roots}, each one
     * isolated; the others are skipped. Returns whether the map was mutated (or may have
     * been, when a root failed halfway).
     */
    private static boolean reclaimRoots(Collection<RootCommand> roots) {
        boolean mutated = false;
        for (RootCommand command : roots) {
            RootState state = STATES.get(command);
            if (state == null || !state.priority) {
                continue;
            }
            JavaPlugin owner = command.owner();
            try {
                mutated |= reclaimRoot(owner, command, state);
            } catch (Throwable t) {
                owner.getLogger().warning("Could not take back the keys of '/"
                        + command.getName() + "' (command priority): " + t);
                mutated = true;
            }
        }
        return mutated;
    }

    /**
     * Remembers {@code enabled}'s command as the one displaced from each bare key a root with
     * command priority holds with nobody displaced there yet, when that command answers at
     * {@code <enabled>:<key>}: the plugin enabled after the root and Bukkit refused it the
     * bare key, so the root hands the key to it when it releases the key. Writes nothing into
     * the known commands and logs nothing. Each root is isolated.
     */
    private static void noteLatecomer(Collection<RootCommand> roots, Plugin enabled) {
        Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        String prefix = enabled.getName().toLowerCase(Locale.ROOT);
        for (RootCommand command : roots) {
            RootState state = STATES.get(command);
            if (state == null || !state.priority || command.owner() == enabled) {
                continue;
            }
            try {
                List<String> keys = new ArrayList<>(state.active);
                keys.add(command.getName());
                for (String key : keys) {
                    if (known.get(key) != command || state.displaced.containsKey(key)) {
                        continue;
                    }
                    Command late = known.get(prefix + ":" + key);
                    if (late != null && late != command && pluginOf(late) == enabled) {
                        state.displaced.put(key,
                                new Displaced(enabled.getName(), new WeakReference<>(late)));
                    }
                }
            } catch (Throwable t) {
                command.owner().getLogger().warning("Could not note the commands of "
                        + enabled.getName() + " behind '/" + command.getName()
                        + "' (command priority): " + t);
            }
        }
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

    /**
     * Unregisters one root of the owner and refreshes the client command trees. Under command
     * priority a key the root gave back may be one another root of the same owner wants (its
     * name, or an alias it holds), so the owner's remaining roots take theirs back first.
     */
    public static void unregister(JavaPlugin owner, RootCommand command) {
        COMMANDS.remove(owner, command);
        boolean mutated = detach(command);
        RootState state = STATES.get(command);
        if (mutated && state != null && state.priority) {
            mutated |= reclaimRoots(new ArrayList<>(COMMANDS.forOwner(owner)));
        }
        refresh(mutated);
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
            release(known, alias, command, state);
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
            Command previous = claimOrTake(owner, known, alias, command, state);
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

    /**
     * {@link #claim} of a BARE key (the root name on the fallback path, or an alias): under
     * command priority an occupant the root {@link CollisionNotice#takesOver takes over} is
     * replaced and remembered as displaced, and the key counts as claimed. Without priority,
     * or against any other occupant, this is exactly {@link #claim}. Namespaced keys never
     * come here: the {@code <plugin>:<key>} form of another plugin is never taken.
     */
    private static @Nullable Command claimOrTake(JavaPlugin owner, Map<String, Command> known,
            String key, RootCommand command, RootState state) {
        Command previous = known.get(key);
        if (previous == null) {
            known.put(key, command);
            return null;
        }
        if (previous == command || !state.priority
                || !takes(owner, command, key, previous)) {
            return previous;
        }
        take(owner, known, key, command, state, previous);
        return null;
    }

    /**
     * Whether a root with command priority takes a key from its holder: never a key holding
     * a {@code :} (a namespaced form stays its plugin's even when a config lists it as an
     * alias), otherwise as {@link CollisionNotice#takesOver} decides for the holder's kind.
     */
    private static boolean takes(JavaPlugin owner, RootCommand command, String key,
            Command holder) {
        return key.indexOf(':') < 0
                && CollisionNotice.takesOver(true, occupantKind(owner, command, holder));
    }

    /**
     * Declared path under command priority: Bukkit left the bare name of the plugin.yml
     * command with another plugin (the declared command answers as {@code <plugin>:<name>}).
     * When the root takes that occupant over, the {@link RootCommand} itself goes into the bare
     * key: its label is the name, so a later plugin registering the same label does not
     * replace it. The declared command keeps its namespaced key and its executor adapter.
     * Returns whether the map was mutated.
     */
    private static boolean takeDeclared(JavaPlugin owner, Map<String, Command> known,
            RootCommand command, PluginCommand declared, RootState state) {
        String name = command.getName();
        Command holder = known.get(name);
        if (holder == null || holder == declared || holder == command
                || !takes(owner, command, name, holder)) {
            return false;
        }
        take(owner, known, name, command, state, holder);
        return true;
    }

    /**
     * Takes back the bare keys of one root with command priority: its name (on the declared
     * path only when the owner's plugin.yml command does not hold it) and every alias it
     * holds, each from an occupant it takes over; a key it does not take is left as it is,
     * silently. A key found empty is left alone too (the server owner may have cleared it on
     * purpose, e.g. a {@code commands.yml} alias with no target). Returns whether the map was
     * mutated.
     */
    private static boolean reclaimRoot(JavaPlugin owner, RootCommand command, RootState state) {
        Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        String name = command.getName();
        PluginCommand declared = owner.getCommand(name);
        boolean mutated = declared != null
                ? takeDeclared(owner, known, command, declared, state)
                : retake(owner, known, name, command, state);
        for (String alias : state.active) {
            mutated |= retake(owner, known, alias, command, state);
        }
        return mutated;
    }

    /** Takes a bare key the root should hold back from an occupant it takes over. */
    private static boolean retake(JavaPlugin owner, Map<String, Command> known, String key,
            RootCommand command, RootState state) {
        Command holder = known.get(key);
        if (holder == null || holder == command || !takes(owner, command, key, holder)) {
            return false;
        }
        take(owner, known, key, command, state, holder);
        return true;
    }

    /**
     * Puts the root into the bare key, remembers the occupant as the one to hand the key back
     * to (the last one displaced from that key) and notes the takeover once per enable.
     */
    private static void take(JavaPlugin owner, Map<String, Command> known, String key,
            RootCommand command, RootState state, Command occupant) {
        known.put(key, command);
        String plugin = pluginNameOf(occupant);
        if (plugin != null) {
            state.displaced.put(key, new Displaced(plugin, new WeakReference<>(occupant)));
        }
        noteTakeover(owner, known, command, key, occupant);
    }

    /**
     * Logs a takeover at INFO once per owner and key during this enable, naming the
     * namespaced form the displaced command still answers under when that key is still its
     * own. Recorded in {@link #COLLISIONS_NOTED} as {@code "priority:" + key}, apart from the
     * collision note of the same key, so a later loss of that key is still reported.
     */
    private static void noteTakeover(JavaPlugin owner, Map<String, Command> known,
            RootCommand command, String key, Command occupant) {
        String noted = "priority:" + key.toLowerCase(Locale.ROOT);
        if (COLLISIONS_NOTED.forOwner(owner).contains(noted)) {
            return;
        }
        COLLISIONS_NOTED.add(owner, noted);
        String plugin = pluginNameOf(occupant);
        String occupantPrefix = null;
        if (plugin != null) {
            String candidate = plugin.toLowerCase(Locale.ROOT);
            if (known.get(candidate + ":" + key) == occupant) {
                occupantPrefix = candidate;
            }
        }
        String occupantName = CollisionNotice.occupant(plugin);
        owner.getLogger().info(key.equals(command.getName())
                ? CollisionNotice.rootTakeover(key, owner.getName(), occupantName,
                        occupantPrefix)
                : CollisionNotice.aliasTakeover(key, command.getName(), owner.getName(),
                        occupantName, occupantPrefix));
    }

    /**
     * Who holds a key the root wants, from {@code org.bukkit} types alone, in this order: the
     * root itself, a root of the same owner, or the owner's own command is {@code OWN}; a
     * root of another owner, or the plugin.yml command wired to one, is {@code SNLIB_ROOT} or
     * {@code SNLIB_PRIORITY_ROOT} by that root's own priority flag; otherwise the command is
     * judged by the plugin that provides it ({@link #pluginOf}): another plugin's makes it
     * {@code PLUGIN}, none makes it {@code SERVER} (vanilla, Bukkit and Paper commands, the
     * wrappers of Paper's Brigadier commands, {@code commands.yml} aliases).
     */
    static CollisionNotice.Occupant occupantKind(@Nullable Plugin owner,
            @Nullable RootCommand command, Command occupant) {
        if (occupant == command) {
            return CollisionNotice.Occupant.OWN;
        }
        RootCommand root = rootOf(occupant);
        if (root != null) {
            if (root.owner() == owner) {
                return CollisionNotice.Occupant.OWN;
            }
            RootState state = STATES.get(root);
            return state != null && state.priority
                    ? CollisionNotice.Occupant.SNLIB_PRIORITY_ROOT
                    : CollisionNotice.Occupant.SNLIB_ROOT;
        }
        Plugin plugin = pluginOf(occupant);
        if (plugin == null) {
            return CollisionNotice.Occupant.SERVER;
        }
        return plugin == owner ? CollisionNotice.Occupant.OWN : CollisionNotice.Occupant.PLUGIN;
    }

    /**
     * Plugin that provides a command: the owner of a root (or of the root a plugin.yml command
     * runs); the plugin a {@link PluginIdentifiableCommand} names, unless it is a
     * {@link BukkitCommand} (Paper's wrapper of a Brigadier command names a plugin but is a
     * class of the server); otherwise the plugin whose class loader loaded the command's class
     * ({@link JavaPlugin#getProvidingPlugin}), so a plugin registering its own
     * {@code Command} or {@code BukkitCommand} counts as that plugin. Null for a class of the
     * server itself and for a command naming no plugin.
     */
    private static @Nullable Plugin pluginOf(Command command) {
        RootCommand root = rootOf(command);
        if (root != null) {
            return root.owner();
        }
        try {
            if (command instanceof PluginIdentifiableCommand identifiable
                    && !(command instanceof BukkitCommand)) {
                return identifiable.getPlugin();
            }
            return JavaPlugin.getProvidingPlugin(command.getClass());
        } catch (RuntimeException none) {
            // IllegalArgumentException: not loaded by a plugin class loader (the server's own
            // classes); IllegalStateException: a plugin still in its static initializer; or a
            // third-party getPlugin() that throws. Either way no plugin is named.
            return null;
        }
    }

    /** The root behind a command: the root itself, or the one a plugin.yml command runs. */
    private static @Nullable RootCommand rootOf(Command command) {
        if (command instanceof RootCommand root) {
            return root;
        }
        if (command instanceof PluginCommand declared
                && declared.getExecutor() instanceof PluginCommandAdapter adapter) {
            return adapter.root();
        }
        return null;
    }

    /** Name of the plugin that provides a command ({@link #pluginOf}), or null. */
    private static @Nullable String pluginNameOf(Command command) {
        Plugin plugin = pluginOf(command);
        return plugin == null ? null : plugin.getName();
    }

    /**
     * Removes the root from a bare key and, when it held the key, hands the key back to the
     * command it displaced there (see {@link #restore}). The displaced entry is forgotten
     * either way. Returns whether the root was removed from the key. Without priority nothing
     * was ever displaced and this is {@code known.remove(key, command)}.
     */
    private static boolean release(Map<String, Command> known, String key, RootCommand command,
            @Nullable RootState state) {
        boolean removed = known.remove(key, command);
        Displaced displaced = state == null ? null : state.displaced.remove(key);
        if (removed && displaced != null) {
            restore(known, key, displaced);
        }
        return removed;
    }

    /**
     * Hands a released bare key back, silently: never while the server is stopping, and only
     * when nothing took the key meanwhile. The displaced command gets it when it can still run
     * there ({@link #returnable}); otherwise the live command answering under the displaced
     * plugin's namespaced {@code <plugin>:<key>} form does (a plugin that re-registered its
     * commands, or that a plugin manager reloaded, holds a new command object), when that one
     * is returnable; otherwise nobody does.
     */
    private static void restore(Map<String, Command> known, String key, Displaced displaced) {
        if (Bukkit.isStopping() || known.get(key) != null) {
            return;
        }
        String plugin = displaced.plugin();
        Command back = displaced.command().get();
        if (back == null || !returnable(known, back, plugin, key)) {
            back = known.get(plugin.toLowerCase(Locale.ROOT) + ":" + key);
            if (back == null || !returnable(known, back, plugin, key)) {
                return;
            }
        }
        known.put(key, back);
        if (back instanceof RootCommand root && !key.equals(root.getName())) {
            adopt(root, key);
        }
    }

    /**
     * Records a key handed back to a root as one of its active aliases (it was one, or it
     * held only its namespaced form), so the root's own detach or alias reconcile releases
     * it. The lists are replaced, never mutated in place.
     */
    private static void adopt(RootCommand root, String key) {
        RootState state = STATES.get(root);
        if (state == null || state.active.contains(key)) {
            return;
        }
        List<String> active = new ArrayList<>(state.active);
        active.add(key);
        List<String> namespaced = new ArrayList<>(state.namespaced);
        namespaced.remove(key);
        state.active = List.copyOf(active);
        state.namespaced = List.copyOf(namespaced);
    }

    /**
     * Whether a command can take a bare key of {@code plugin} back: it must be provided by an
     * enabled plugin of that name ({@link #pluginOf}, so never a class of the server). A root
     * must still be registered and still want the key (its name, an alias it holds, or an
     * alias that holds only its namespaced form), so the key never points at a root that
     * unregistered or dropped it. Any other command must still answer under its
     * {@code <plugin>:<key>} form, so a command its plugin dropped is never put back.
     */
    private static boolean returnable(Map<String, Command> known, Command command,
            String plugin, String key) {
        Plugin provider = pluginOf(command);
        if (provider == null || !provider.isEnabled()
                || !plugin.equalsIgnoreCase(provider.getName())) {
            return false;
        }
        if (command instanceof RootCommand root) {
            if (!COMMANDS.forOwner(root.owner()).contains(root)) {
                return false;
            }
            if (key.equals(root.getName())) {
                return true;
            }
            RootState state = STATES.get(root);
            return state != null
                    && (state.active.contains(key) || state.namespaced.contains(key));
        }
        return known.get(plugin.toLowerCase(Locale.ROOT) + ":" + key) == command;
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
     *
     * <p>Each bare key the root held under command priority goes back to the command it
     * displaced there ({@link #release}); the rest of the displaced record is dropped.</p>
     */
    private static boolean detach(RootCommand command) {
        JavaPlugin owner = command.owner();
        CommandMap map = Bukkit.getCommandMap();
        Map<String, Command> known = map.getKnownCommands();
        String prefix = prefix(owner);
        RootState state = STATES.get(command);
        boolean mutated = false;
        for (String alias : state == null ? List.<String>of() : state.active) {
            mutated |= release(known, alias, command, state);
            mutated |= known.remove(prefix + ":" + alias, command);
        }
        for (String alias : state == null ? List.<String>of() : state.namespaced) {
            mutated |= known.remove(prefix + ":" + alias, command);
        }
        String name = command.getName();
        mutated |= release(known, name, command, state);
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
            state.displaced.clear();
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
     * Per-root registration state: the alias supplier, the {@code dynamic()} flag, the
     * command priority flag of the owner, the alias keys currently registered, the aliases
     * that lost their bare key but hold its namespaced {@code <plugin>:<alias>} form, and,
     * under priority, the command displaced from each bare key the root took (lowercased key
     * to the last command displaced there, see {@link Displaced}).
     */
    private static final class RootState {

        private final @Nullable Supplier<Collection<String>> supplier;
        private final boolean dynamic;
        private final boolean priority;
        private final Map<String, Displaced> displaced = new ConcurrentHashMap<>();
        private volatile List<String> active = List.of();
        private volatile List<String> namespaced = List.of();

        RootState(@Nullable Supplier<Collection<String>> supplier, boolean dynamic,
                boolean priority) {
            this.supplier = supplier;
            this.dynamic = dynamic;
            this.priority = priority;
        }
    }

    /**
     * The command displaced from a bare key: its plugin's name and the command itself, held
     * weakly so a plugin a plugin manager unloads is never kept alive through this record
     * (a live command stays strongly reachable through the known commands). Once collected,
     * {@link #restore} falls back to the live {@code <plugin>:<key>} form.
     */
    private record Displaced(String plugin, WeakReference<Command> command) {
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
