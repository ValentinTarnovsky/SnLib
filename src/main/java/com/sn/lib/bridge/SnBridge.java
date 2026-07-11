package com.sn.lib.bridge;

import com.sn.lib.Sn;
import com.sn.lib.SnExperimental;
import com.sn.lib.bridge.internal.BridgeRuntime;

/**
 * Bridge module of one consumer context, reached via {@code sn.bridge()} (always
 * available, like {@code selections()}). The backend side of SnBridge: typed channels
 * over plugin messaging toward the Velocity proxy, with HELLO handshake, HMAC, chunking
 * and a bounded TTL'd carrier queue. See docs/SNBRIDGE-SPEC.md for the full design.
 *
 * <p>EXPERIMENTAL: outside the japicmp gate and {@code SnApi.LEVEL} until the SnCredits
 * migration freezes the API (spec section 3). Main-thread confined like every module.</p>
 */
@SnExperimental
public final class SnBridge {

    private final Sn ctx;

    /** Built by the context; consumers reach it through {@code sn.bridge()}. */
    public SnBridge(Sn ctx) {
        this.ctx = ctx;
    }

    /**
     * Claims a namespace and returns its typed channel ({@code snlib:ext/<namespace>}).
     * First-claim-wins across ALL consumers: claiming a namespace another plugin holds
     * is a hard error, never silent fan-out. Idempotent for the same owner.
     *
     * @param namespace lowercase {@code [a-z0-9_-]+}, conventionally the plugin name
     *                  without the Sn prefix (e.g. "sncredits" or "credits")
     * @param msgsetVersion version of THIS plugin's message set, bumped when it appends
     *                  fields or messages; negotiated with the proxy in HELLO
     */
    public SnBridgeChannel channel(String namespace, int msgsetVersion) {
        return BridgeRuntime.claim(ctx, namespace, msgsetVersion);
    }
}
