package com.sn.lib.command.internal;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import org.bukkit.Bukkit;
import org.bukkit.NamespacedKey;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.SnLib;
import com.sn.lib.SnLibPlugin;
import com.sn.lib.command.Args;
import com.sn.lib.command.CommandContext;
import com.sn.lib.compat.SnVersion;
import com.sn.lib.hook.SoftDependency;
import com.sn.lib.text.SnText;
import com.sn.lib.util.TagIo;

/**
 * Diagnostic root command of the library itself: {@code /snlib} is registered over the
 * bootstrap's own context (the selfCtx {@code SnLibPlugin} creates through the in-package
 * {@code SnLib.init}), through the same {@code sn.commands()} module every consumer uses;
 * no loose {@code SnCommands} instance and no second config are involved. Every
 * subcommand is tab-gated by its {@code snlib.admin.*} permission from plugin.yml.
 *
 * <p>Reload contract: without arguments {@code /snlib reload} reloads EXCLUSIVELY the
 * library's own surface (its own {@code plugins/SnLib/config.yml}: the {@code debug} and
 * {@code bstats} keys) and never touches any consumer context; with a plugin name it
 * delegates to THAT plugin's reload manager. Hard rule: a reload NEVER reloads classes;
 * updating SnLib.jar requires a server restart.</p>
 */
public final class SnLibCommand {

    private SnLibCommand() {
    }

    /** Registers the {@code /snlib} tree over the bootstrap's own context. */
    public static void register(SnLibPlugin plugin, Sn selfCtx) {
        selfCtx.commands().root("snlib")
                .description("SnLib administration command")
                .sub("version")
                        .permission("snlib.admin.version")
                        .description("Shows the library version, API level and server version")
                        .executes(context -> version(plugin, context.sender()))
                        .and()
                .sub("plugins")
                        .permission("snlib.admin.plugins")
                        .description("Lists the consumers hooked to SnLib")
                        .executes(context -> plugins(plugin, context.sender()))
                        .and()
                .sub("integrations")
                        .permission("snlib.admin.integrations")
                        .description("Lists the registered soft-dependency hooks")
                        .executes(context -> integrations(context.sender()))
                        .and()
                .sub("iteminfo")
                        .permission("snlib.admin.iteminfo")
                        .description("Dumps the PDC tags of the held item")
                        .executes(SnLibCommand::itemInfo)
                        .and()
                .sub("reload")
                        .permission("snlib.admin.reload")
                        .usage("/snlib reload [plugin]")
                        .description("Reloads the library's own surface or one consumer")
                        .argOptional("plugin", Args.oneOf(SnLibCommand::hookedConsumerNames))
                        .executes(context -> reload(plugin, selfCtx, context))
                        .and()
                .register();
    }

    private static void version(SnLibPlugin plugin, CommandSender sender) {
        String detected = SnVersion.MAJOR + "." + SnVersion.MINOR
                + (SnVersion.PATCH > 0 ? "." + SnVersion.PATCH : "");
        send(sender, "&7SnLib version: &f" + plugin.getPluginMeta().getVersion());
        send(sender, "&7API level: &f" + plugin.apiLevel());
        send(sender, "&7Server: &f" + Bukkit.getBukkitVersion() + " &7(detected: &f"
                + detected + (SnVersion.isFolia() ? " Folia" : "") + "&7)");
    }

    /** Consumers hooked to SnLib, read from the public context registry. */
    private static void plugins(SnLibPlugin self, CommandSender sender) {
        List<String> lines = new ArrayList<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin == self || !(plugin instanceof JavaPlugin javaPlugin)) {
                continue;
            }
            if (SnLib.context(javaPlugin) == null) {
                continue;
            }
            lines.add("&8- &f" + plugin.getName() + " &7v" + plugin.getPluginMeta().getVersion());
        }
        if (lines.isEmpty()) {
            send(sender, "&7No consumers are hooked to SnLib.");
            return;
        }
        Collections.sort(lines);
        send(sender, "&7Consumers hooked to SnLib (&f" + lines.size() + "&7):");
        for (String line : lines) {
            send(sender, line);
        }
    }

    private static void integrations(CommandSender sender) {
        List<String> lines = new ArrayList<>();
        SoftDependency.forEachRegistered(dependency -> lines.add("&8- &f"
                + dependency.owner().getName() + " &7-> &f" + dependency.pluginName()
                + "&7: " + (dependency.isAvailable() ? "&aactive" : "&cinactive")));
        if (lines.isEmpty()) {
            send(sender, "&7No soft-dependency hooks are registered.");
            return;
        }
        Collections.sort(lines);
        send(sender, "&7Registered integrations (&f" + lines.size() + "&7):");
        for (String line : lines) {
            send(sender, line);
        }
    }

    /**
     * Dumps every PDC key of the held item. Keys whose namespace belongs to a loaded
     * plugin are read through {@link TagIo} (the library's string-tag convention); the
     * rest fall back to a raw string read, and non-string tags render as a type note.
     */
    private static void itemInfo(CommandContext context) {
        if (!(context.sender() instanceof Player player)) {
            send(context.sender(), "&cOnly players can inspect a held item.");
            return;
        }
        ItemStack item = player.getInventory().getItemInMainHand();
        if (item.getType().isAir()) {
            send(player, "&cHold an item to inspect its tags.");
            return;
        }
        ItemMeta meta = item.getItemMeta();
        Set<NamespacedKey> keys = meta == null
                ? Set.of()
                : meta.getPersistentDataContainer().getKeys();
        String type = item.getType().getKey().getKey();
        if (keys.isEmpty()) {
            send(player, "&7The item &f" + type + " &7has no PDC tags.");
            return;
        }
        Map<String, JavaPlugin> byNamespace = pluginsByNamespace();
        List<String> lines = new ArrayList<>(keys.size());
        for (NamespacedKey key : keys) {
            lines.add("&8- &f" + key + " &7= &f" + tagValue(item, key, byNamespace));
        }
        Collections.sort(lines);
        send(player, "&7PDC tags of &f" + type + " &7(&f" + lines.size() + "&7):");
        for (String line : lines) {
            send(player, line);
        }
    }

    /**
     * Without arguments reloads ONLY the library's own surface (its own config.yml:
     * debug + bstats) through the selfCtx reload manager; with a plugin name it delegates
     * to that consumer's reload manager. Classes are never reloaded.
     */
    /** Tab options of {@code /snlib reload}: SnLib itself plus every hooked consumer. */
    private static Collection<String> hookedConsumerNames() {
        List<String> names = new ArrayList<>();
        names.add(SnLibPlugin.get().getName());
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin instanceof JavaPlugin javaPlugin && !(plugin instanceof SnLibPlugin)
                    && SnLib.context(javaPlugin) != null) {
                names.add(plugin.getName());
            }
        }
        Collections.sort(names);
        return names;
    }

    private static void reload(SnLibPlugin self, Sn selfCtx, CommandContext context) {
        String targetName = context.raw(0);
        if (targetName == null || targetName.equalsIgnoreCase(self.getName())) {
            selfCtx.reloadAll();
            send(context.sender(), "&aSnLib configuration reloaded (debug + bstats).");
            send(context.sender(), "&7A reload never reloads classes: updating SnLib.jar"
                    + " requires a server restart.");
            return;
        }
        Plugin target = Bukkit.getPluginManager().getPlugin(targetName);
        if (!(target instanceof JavaPlugin javaTarget)) {
            send(context.sender(), "&cPlugin not found: &f" + targetName);
            return;
        }
        Sn targetCtx = SnLib.context(javaTarget);
        if (targetCtx == null) {
            send(context.sender(), "&cPlugin &f" + target.getName()
                    + " &cis not hooked to SnLib.");
            return;
        }
        targetCtx.reloadAll();
        send(context.sender(), "&aConfiguration of &f" + target.getName() + " &areloaded.");
    }

    /** Value of one PDC tag: TagIo read when the namespace maps to a plugin, raw otherwise. */
    private static String tagValue(ItemStack item, NamespacedKey key,
            Map<String, JavaPlugin> byNamespace) {
        try {
            JavaPlugin owner = byNamespace.get(key.getNamespace());
            String value = owner != null
                    ? TagIo.get(item, owner, key.getKey())
                    : rawString(item, key);
            return value != null ? value : "&8<non-string tag>";
        } catch (IllegalArgumentException nonString) {
            return "&8<non-string tag>";
        }
    }

    private static @Nullable String rawString(ItemStack item, NamespacedKey key) {
        ItemMeta meta = item.getItemMeta();
        if (meta == null) {
            return null;
        }
        return meta.getPersistentDataContainer().get(key, PersistentDataType.STRING);
    }

    /**
     * Loaded plugins keyed by their lowercased name, which is exactly the namespace a
     * {@code NamespacedKey(plugin, key)} carries.
     */
    private static Map<String, JavaPlugin> pluginsByNamespace() {
        Map<String, JavaPlugin> out = new HashMap<>();
        for (Plugin plugin : Bukkit.getPluginManager().getPlugins()) {
            if (plugin instanceof JavaPlugin javaPlugin) {
                out.put(plugin.getName().toLowerCase(Locale.ROOT), javaPlugin);
            }
        }
        return out;
    }

    private static void send(CommandSender sender, String text) {
        sender.sendMessage(SnText.color(text));
    }
}
