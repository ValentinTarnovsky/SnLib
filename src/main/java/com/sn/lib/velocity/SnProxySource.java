package com.sn.lib.velocity;

import com.sn.lib.SnExperimental;
import com.velocitypowered.api.proxy.Player;

/**
 * Origin of one inbound bridge message on the proxy: the carrier player whose backend
 * connection carried it, and the backend server it came from.
 *
 * @param player     carrier player (still connected when the handler runs; treat as
 *                   best-effort afterwards)
 * @param serverName registered name of the backend that sent the message
 */
@SnExperimental
public record SnProxySource(Player player, String serverName) {
}
