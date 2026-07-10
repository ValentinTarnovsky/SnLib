package com.sn.lib.command.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import org.bukkit.Bukkit;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandMap;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginCommand;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.bukkit.plugin.java.JavaPlugin;

import com.sn.lib.command.RootCommand;
import com.sn.lib.tenant.TenantRegistry;

/**
 * Bridge between {@link RootCommand} trees and the Bukkit command system, preferring the
 * public API in two paths: (a) a command declared in the owner's plugin.yml gets its
 * executor and tab completer wired through {@code plugin.getCommand(name)}; (b) undeclared
 * roots and dynamic aliases go through Paper's public {@code Bukkit.getCommandMap()},
 * each with a WARN. After every register and unregister the online players get
 * {@code updateCommands()} so their client trees never show ghosts.
 *
 * <p>Registered roots are tracked in a {@link TenantRegistry} keyed by the owning plugin:
 * the tenant sweep detaches each command and removes the whole owner key when the
 * consumer disables, even if the owner never called the teardown.</p>
 */
public final class BukkitCommandRegistry {

    /** Server-wide static justified: root commands keyed per owning plugin for the sweep. */
    private static final TenantRegistry<RootCommand> COMMANDS =
            new TenantRegistry<>(BukkitCommandRegistry::sweep);

    private BukkitCommandRegistry() {
    }

    /**
     * Registers the root for its owner. Reload-safe: a root already registered by the
     * same owner under the same name is detached and replaced first.
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
            registerDynamicAliases(owner, command, declared);
        } else {
            owner.getLogger().warning("Comando '/" + command.getName()
                    + "' no declarado en el plugin.yml de " + owner.getName()
                    + "; registro dinamico via CommandMap");
            Bukkit.getCommandMap().register(prefix(owner), command);
        }
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
     * Each register pass refreshes the online players' command trees.
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
     * Aliases built in code for a plugin.yml command are not part of its declaration;
     * they get CommandMap entries pointing at the root tree, with one WARN.
     */
    private static void registerDynamicAliases(JavaPlugin owner, RootCommand command,
            PluginCommand declared) {
        List<String> dynamic = new ArrayList<>();
        for (String alias : command.getAliases()) {
            if (!alias.equalsIgnoreCase(command.getName())
                    && !containsIgnoreCase(declared.getAliases(), alias)) {
                dynamic.add(alias.toLowerCase(Locale.ROOT));
            }
        }
        if (dynamic.isEmpty()) {
            return;
        }
        owner.getLogger().warning("Aliases " + dynamic + " de '/" + command.getName()
                + "' no declarados en el plugin.yml de " + owner.getName()
                + "; registro dinamico via CommandMap");
        Map<String, Command> known = Bukkit.getCommandMap().getKnownCommands();
        for (String alias : dynamic) {
            known.putIfAbsent(alias, command);
            known.putIfAbsent(prefix(owner) + ":" + alias, command);
        }
    }

    /** Detaches the command from whichever path registered it. */
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

    private static boolean containsIgnoreCase(List<String> values, String value) {
        for (String candidate : values) {
            if (candidate.equalsIgnoreCase(value)) {
                return true;
            }
        }
        return false;
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
