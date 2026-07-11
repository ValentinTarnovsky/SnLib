package com.sn.lib.bridge.wire;

/**
 * Failure at the wire layer: truncated payload, invalid frame, HMAC mismatch, oversized
 * message, chunking violation or an unknown wireId.
 *
 * <p>Unchecked on purpose: wire failures are not recoverable by the caller beyond
 * "drop the message and count it", which is exactly what the transport layers do. The
 * message text is operator-facing (Spanish, house style).</p>
 */
public class SnWireException extends RuntimeException {

    public SnWireException(String message) {
        super(message);
    }

    public SnWireException(String message, Throwable cause) {
        super(message, cause);
    }
}
