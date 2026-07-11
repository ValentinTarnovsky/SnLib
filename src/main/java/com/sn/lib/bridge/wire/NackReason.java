package com.sn.lib.bridge.wire;

/**
 * Reason codes carried by {@link NackMsg}. Encoded by EXPLICIT id (never ordinal: the
 * whole point of this protocol is that reordering an enum cannot corrupt the wire).
 * Unknown ids decode as {@link #UNRECOGNIZED} so an older side still surfaces a NACK
 * from a newer counterpart instead of dropping it as malformed.
 */
public enum NackReason {

    /** Catch-all when the received reason id postdates this build. */
    UNRECOGNIZED(0),
    /** The destination negotiated a lower msgset than the message requires. */
    UNSUPPORTED_MSGSET(1),
    /** The wireId is not registered on the receiving side. */
    UNKNOWN_WIRE_ID(2),
    /** A console verb was rejected by the backend-authoritative allowlist. */
    DENIED_BY_ALLOWLIST(3),
    /** The requested verb (or its vocabulary version) is not served by this backend. */
    UNSUPPORTED_VERB(4),
    /** The body failed to decode. */
    MALFORMED(5),
    /** The receiver failed internally while handling the message. */
    INTERNAL_ERROR(6);

    private final int id;

    NackReason(int id) {
        this.id = id;
    }

    /** Stable wire id of this reason. */
    public int id() {
        return id;
    }

    /** Decodes an id, mapping unknown values to {@link #UNRECOGNIZED}. */
    public static NackReason fromId(int id) {
        for (NackReason reason : VALUES) {
            if (reason.id == id) {
                return reason;
            }
        }
        return UNRECOGNIZED;
    }

    private static final NackReason[] VALUES = values();
}
