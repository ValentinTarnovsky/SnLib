package com.sn.lib.bridge;

import com.sn.lib.SnExperimental;

/**
 * Terminal outcome of one bridge send, shared by BOTH tiers (typed channels and verbs)
 * and BOTH platforms. Every send future resolves with exactly one of these; queueing is
 * NOT a value here because it is not terminal (observe it via {@code pending()} and the
 * status command instead).
 *
 * <p>Platform-pure on purpose (no Bukkit imports): the Velocity side reuses this enum.</p>
 */
@SnExperimental
public enum SnDeliveryResult {

    /** Handed to a live handshaken connection. Tier 1 ends here: there is no app-level ack. */
    SENT,

    /** Verbs only: the receiving side confirmed execution (app-level ACK). */
    DELIVERED,

    /** Verbs only: rejected by the backend-authoritative allowlist (NACK). */
    DENIED_BY_ALLOWLIST,

    /** Verbs only: the destination does not serve this verb or its vocabulary version. */
    UNSUPPORTED_AT_DESTINATION,

    /** The HELLO negotiation says the destination does not speak this msgset. */
    UNSUPPORTED_MSGSET,

    /**
     * Verbs only: the destination executed nothing for a reason of its own (target
     * player offline, malformed spec); the detail says which.
     */
    FAILED_AT_DESTINATION,

    /** Died in queue: no carrier, no handshake, queue overflow, carrier lost mid-chunk, or shutdown. */
    EXPIRED_TTL,

    /** Proxy side only: the destination server name does not exist. */
    UNKNOWN_SERVER
}
