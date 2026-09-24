package com.sn.lib.command.internal;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.server.PluginEnableEvent;
import org.bukkit.event.server.ServerLoadEvent;

/**
 * Shared listener that takes back the keys of the roots with command priority after another
 * plugin may have overwritten them: a plugin that enables later and registers a label equal
 * to one of their aliases, or writes into the known commands directly. Runs at
 * {@link EventPriority#MONITOR}, once the enabling plugin registered its commands, and when
 * the server finishes loading (startup and reload), through
 * {@link BukkitCommandRegistry#reclaimPriority()}. The enabling plugin is passed along, so a
 * command it could not register under a key a root holds is remembered as the one to hand
 * the key to. A plugin that registers commands in a later tick is taken back on the next of
 * these events, or on the owner's next reload.
 *
 * <p><b>Wiring note:</b> INSCRIBED into the ListenerHub; the {@code registerEvents} call
 * happens only in the SnLibPlugin bootstrap. Never register it anywhere else.</p>
 */
public final class CommandPriorityListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onPluginEnable(PluginEnableEvent event) {
        BukkitCommandRegistry.reclaimPriority(event.getPlugin());
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onServerLoad(ServerLoadEvent event) {
        BukkitCommandRegistry.reclaimPriority();
    }
}
