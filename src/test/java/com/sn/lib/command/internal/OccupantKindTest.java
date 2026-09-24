package com.sn.lib.command.internal;

import java.lang.reflect.Proxy;

import org.bukkit.command.Command;
import org.bukkit.command.CommandSender;
import org.bukkit.command.PluginIdentifiableCommand;
import org.bukkit.command.defaults.BukkitCommand;
import org.bukkit.plugin.Plugin;
import org.jetbrains.annotations.Nullable;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * How {@link BukkitCommandRegistry#occupantKind} classifies the command holding a key, from
 * {@code org.bukkit} types alone. The roots of SnLib plugins need a running context and are
 * covered by the rules of {@link CollisionNotice#takesOver}; no server is needed here.
 */
class OccupantKindTest {

    private static final Plugin OWNER = plugin("SnDungeons");
    private static final Plugin ESSENTIALS = plugin("Essentials");

    @Test
    void aCommandOfAnotherPluginIsThatPlugins() {
        assertEquals(CollisionNotice.Occupant.PLUGIN,
                BukkitCommandRegistry.occupantKind(OWNER, null,
                        new PluginOwnedCommand("money", ESSENTIALS)));
    }

    @Test
    void aCommandOfTheOwnerIsItsOwn() {
        assertEquals(CollisionNotice.Occupant.OWN,
                BukkitCommandRegistry.occupantKind(OWNER, null,
                        new PluginOwnedCommand("zone", OWNER)));
    }

    @Test
    void aBukkitCommandIsTheServers() {
        // Vanilla, Bukkit and Paper commands (version, plugins, reload, help...).
        assertEquals(CollisionNotice.Occupant.SERVER,
                BukkitCommandRegistry.occupantKind(OWNER, null, new ServerCommand("version")));
    }

    @Test
    void aBrigadierWrapperOfAnotherPluginIsTheServersEvenThoughItNamesThePlugin() {
        // Paper's PluginVanillaCommandWrapper is a BukkitCommand AND a
        // PluginIdentifiableCommand: a command of Paper's Brigadier API, not a Bukkit plugin
        // command, so it is never taken.
        assertEquals(CollisionNotice.Occupant.SERVER,
                BukkitCommandRegistry.occupantKind(OWNER, null,
                        new PluginBrigadierCommand("warp", ESSENTIALS)));
    }

    @Test
    void aCommandNamingNoPluginIsTheServers() {
        // commands.yml aliases, Paper's and Spigot's own commands.
        assertEquals(CollisionNotice.Occupant.SERVER,
                BukkitCommandRegistry.occupantKind(OWNER, null, new PlainCommand("tps")));
        assertEquals(CollisionNotice.Occupant.SERVER,
                BukkitCommandRegistry.occupantKind(OWNER, null,
                        new PluginOwnedCommand("ghost", null)));
    }

    private static Plugin plugin(String name) {
        return (Plugin) Proxy.newProxyInstance(Plugin.class.getClassLoader(),
                new Class<?>[]{Plugin.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "getName" -> name;
                    case "isEnabled" -> true;
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    case "toString" -> name;
                    default -> null;
                });
    }

    /** A command that names no plugin. */
    private static class PlainCommand extends Command {

        PlainCommand(String name) {
            super(name);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            return true;
        }
    }

    /** A plugin.yml-like command of a plugin. */
    private static final class PluginOwnedCommand extends PlainCommand
            implements PluginIdentifiableCommand {

        private final @Nullable Plugin plugin;

        PluginOwnedCommand(String name, @Nullable Plugin plugin) {
            super(name);
            this.plugin = plugin;
        }

        @Override
        public Plugin getPlugin() {
            return plugin;
        }
    }

    /** A vanilla or server command. */
    private static class ServerCommand extends BukkitCommand {

        ServerCommand(String name) {
            super(name);
        }

        @Override
        public boolean execute(CommandSender sender, String label, String[] args) {
            return true;
        }
    }

    /** The shape of Paper's wrapper of a plugin's Brigadier command. */
    private static final class PluginBrigadierCommand extends ServerCommand
            implements PluginIdentifiableCommand {

        private final Plugin plugin;

        PluginBrigadierCommand(String name, Plugin plugin) {
            super(name);
            this.plugin = plugin;
        }

        @Override
        public Plugin getPlugin() {
            return plugin;
        }
    }
}
