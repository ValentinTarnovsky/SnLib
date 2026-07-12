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

    // Tier 2 verb family, served by SnLib itself on the snlib:bridge channel
    public static final String VERB_CONSOLE = "snlib:verb/console";
    public static final String VERB_MESSAGE = "snlib:verb/message";
    public static final String VERB_TITLE = "snlib:verb/title";
    public static final String VERB_ACTIONBAR = "snlib:verb/actionbar";
    public static final String VERB_SOUND = "snlib:verb/sound";
    public static final String VERB_BOSSBAR = "snlib:verb/bossbar";
    public static final String VERB_ACTIONS = "snlib:verb/actions";
    public static final String VERB_ACK = "snlib:verb/ack";
    public static final String VERB_ALLOWLIST_REQ = "snlib:verb/allowlist_req";
    public static final String VERB_ALLOWLIST = "snlib:verb/allowlist";

    /** Every infra id defined so far (immutable). */
    public static final Set<String> INFRA = Set.of(HELLO, HELLO_ACK, NACK, HEARTBEAT,
            VERB_CONSOLE, VERB_MESSAGE, VERB_TITLE, VERB_ACTIONBAR, VERB_SOUND, VERB_BOSSBAR,
            VERB_ACTIONS, VERB_ACK, VERB_ALLOWLIST_REQ, VERB_ALLOWLIST);

    /** Namespace prefix reserved for SnBridge itself; consumer channels must not claim it. */
    public static final String RESERVED_PREFIX = "snlib:";

    private WireIds() {
        // Constants only
    }
}
