package com.sn.lib.bridge.wire;

import java.util.Set;

/**
 * Infra wireIds reserved by SnBridge itself. The authoritative ledger (including verb
 * ids reserved for the Tier 2 phase) lives in docs/SNBRIDGE-SPEC.md section 12;
 * {@code WireIdLedgerTest} parses that ledger and asserts it is duplicate-free, that
 * every shipped TYPE constant appears in it, and that this class matches the shipped
 * types - so claiming a new snlib id without editing the ledger fails CI. Rule: an id is
 * claimed once in history and NEVER reused, deprecation means "stop emitting".
 */
public final class WireIds {

    public static final String HELLO = "snlib:hello";
    public static final String HELLO_ACK = "snlib:hello_ack";
    public static final String NACK = "snlib:nack";
    public static final String HEARTBEAT = "snlib:heartbeat";

    /** Every infra id defined so far (immutable). */
    public static final Set<String> INFRA = Set.of(HELLO, HELLO_ACK, NACK, HEARTBEAT);

    /** Namespace prefix reserved for SnBridge itself; consumer channels must not claim it. */
    public static final String RESERVED_PREFIX = "snlib:";

    private WireIds() {
        // Constants only
    }
}
