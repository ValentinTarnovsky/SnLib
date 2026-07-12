package com.sn.lib.bridge.wire;

/**
 * A request failed because the other side answered a typed {@link NackMsg}, not because
 * of a transport failure (timeout, queue expiry, shutdown). Carries the {@link NackReason}
 * so a caller can map it to a precise outcome instead of a blanket timeout.
 */
public final class SnNackException extends SnWireException {

    private final NackReason reason;

    public SnNackException(NackReason reason, String message) {
        super(message);
        this.reason = reason;
    }

    /** Why the other side refused the request. */
    public NackReason reason() {
        return reason;
    }
}
