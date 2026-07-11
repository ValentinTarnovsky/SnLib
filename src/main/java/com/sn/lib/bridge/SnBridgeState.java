package com.sn.lib.bridge;

import com.sn.lib.SnExperimental;

/**
 * Namespace-level bridge state, aggregated over the live handshaken carrier connections.
 * There is deliberately no ERROR state: transport failures surface as typed
 * {@link SnDeliveryResult}s and counters, never as a sticky broken state.
 *
 * <p>Platform-pure on purpose (no Bukkit imports): the Velocity side reuses this enum.</p>
 */
@SnExperimental
public enum SnBridgeState {

    /**
     * No handshaken connection yet: right after registration, after a backend restart
     * until the first player joins, or after the last carrier disconnected. Sends queue
     * (bounded, TTL'd) instead of failing.
     */
    WARMING,

    /** At least one carrier connection completed HELLO/HELLO_ACK; traffic flows. */
    READY
}
