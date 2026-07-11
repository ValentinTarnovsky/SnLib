package com.sn.lib.bridge.internal;

import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;
import org.bukkit.event.player.PlayerRegisterChannelEvent;

/**
 * Shared bridge listener (inscribed in ListenerHub). The channel-register event is the
 * REAL handshake trigger behind a proxy: Velocity announces its channels (a
 * minecraft:register payload) only after the backend join completed, so a HELLO sent
 * inside PlayerJoinEvent would be silently dropped by Bukkit; the join handler is only
 * the fast path for connections that registered earlier. Quit drops the carrier's
 * sessions so partial reassembly state never survives its connection.
 */
public final class BridgeConnectionListener implements Listener {

    @EventHandler(priority = EventPriority.MONITOR)
    public void onJoin(PlayerJoinEvent event) {
        BridgeRuntime runtime = BridgeRuntime.live();
        if (runtime != null) {
            runtime.onJoin(event.getPlayer());
        }
    }

    @EventHandler
    public void onChannelRegistered(PlayerRegisterChannelEvent event) {
        BridgeRuntime runtime = BridgeRuntime.live();
        if (runtime != null) {
            runtime.onChannelRegistered(event.getPlayer(), event.getChannel());
        }
    }

    @EventHandler(priority = EventPriority.MONITOR)
    public void onQuit(PlayerQuitEvent event) {
        BridgeRuntime runtime = BridgeRuntime.live();
        if (runtime != null) {
            runtime.onQuit(event.getPlayer());
        }
    }
}
