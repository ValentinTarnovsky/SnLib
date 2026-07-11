package com.sn.lib.bridge.internal;

/**
 * Per-namespace transport counters, main-thread confined (plain longs on purpose).
 * Surfaced by {@code /snlib bridge status}: for an operator with 8 Pterodactyl consoles,
 * these numbers are the difference between "expiro y quedo contado" and ghost silence.
 */
public final class BridgeCounters {

    // Public fields on purpose: internal class (outside semver), mutated by the two
    // cores (bridge.internal on Paper, velocity.internal on the proxy) under their own
    // confinement (main thread / synchronized core)
    public long helloSent;
    public long handshakes;
    public long sent;
    public long received;
    public long hmacDrops;
    public long malformed;
    public long expired;
    public long queueOverflow;
    public long nacksReceived;
    public long nacksSent;
    public long legacyFrames;
    public long orphanFrames;

    public long sent() {
        return sent;
    }

    public long received() {
        return received;
    }

    public long hmacDrops() {
        return hmacDrops;
    }

    public long expired() {
        return expired;
    }

    public long legacyFrames() {
        return legacyFrames;
    }

    /** Called by the channel wrapper (public package) when legacy-stack traffic shows up. */
    public void legacyFrame() {
        legacyFrames++;
    }

    /** One-line snapshot for the status command. */
    public String snapshot() {
        return "tx=" + sent + " rx=" + received + " handshakes=" + handshakes
                + " expirados=" + expired + " overflow=" + queueOverflow
                + " hmacDrop=" + hmacDrops + " malformados=" + malformed
                + " nackIn=" + nacksReceived + " nackOut=" + nacksSent
                + " legacy=" + legacyFrames + " huerfanos=" + orphanFrames;
    }
}
