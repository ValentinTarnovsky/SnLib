package com.sn.lib.bridge;

import java.time.Duration;

import com.sn.lib.SnExperimental;

/**
 * Per-send options. Today only the queue TTL: how long the message may wait for a
 * carrier/handshake before dying as {@link SnDeliveryResult#EXPIRED_TTL}. A TTL of zero
 * means "now or never" (no queueing at all).
 *
 * @param ttlMillis max queue lifetime in milliseconds; negative means "use the default
 *                  from plugins/SnLib/config.yml (bridge.default-ttl-seconds)"
 */
@SnExperimental
public record SnSendOpts(long ttlMillis) {

    private static final SnSendOpts DEFAULTS = new SnSendOpts(-1L);

    /** Default options: TTL from the SnLib config. */
    public static SnSendOpts defaults() {
        return DEFAULTS;
    }

    /** Explicit queue TTL for this send. */
    public static SnSendOpts ttl(Duration ttl) {
        return new SnSendOpts(Math.max(0L, ttl.toMillis()));
    }
}
