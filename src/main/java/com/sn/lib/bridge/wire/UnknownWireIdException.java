package com.sn.lib.bridge.wire;

/**
 * A decoded body carried a wireId that is not registered on this side. Carries the id so
 * the transport can answer with a typed NACK ({@link NackReason#UNKNOWN_WIRE_ID}) instead
 * of dropping silently.
 */
public final class UnknownWireIdException extends SnWireException {

    private final String wireId;

    public UnknownWireIdException(String wireId) {
        super("wireId desconocido en este lado: '" + wireId + "' (registrar el SnWireType o actualizar la contraparte)");
        this.wireId = wireId;
    }

    /** The unregistered wireId exactly as it came off the wire. */
    public String wireId() {
        return wireId;
    }
}
