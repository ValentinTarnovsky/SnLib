package com.sn.lib.bridge.internal;

import org.bukkit.entity.Player;
import org.bukkit.plugin.messaging.PluginMessageListener;

/**
 * Single Messenger callback for every bridge channel (real and legacy): Bukkit gives us
 * the channel string, the runtime demuxes to the right {@link ChannelCore}. Registered
 * per channel by {@link BridgeRuntime} under the SnLib plugin; plugin messages arrive on
 * the main thread, which is exactly the core's confinement contract.
 */
public final class BridgeMessageListener implements PluginMessageListener {

    static final BridgeMessageListener INSTANCE = new BridgeMessageListener();

    private BridgeMessageListener() {
    }

    @Override
    public void onPluginMessageReceived(String channel, Player player, byte[] message) {
        BridgeRuntime runtime = BridgeRuntime.live();
        if (runtime != null) {
            runtime.onPluginMessage(channel, player, message);
        }
    }
}
