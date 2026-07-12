package com.sn.lib.bridge.wire;

import java.util.HashMap;
import java.util.Map;

/**
 * WireId to {@link SnWireType} lookup for one channel/namespace. Registration is
 * first-claim-wins with a hard error on duplicates: two types claiming one wireId is a
 * programming bug, never a silent override.
 *
 * <p>Not thread-safe by itself; the owning channel registers types at setup time and
 * only reads afterwards (safe publication is the channel's responsibility).</p>
 */
public final class WireTypeRegistry {

    private final Map<String, SnWireType<?>> types = new HashMap<>(16);

    /** Registers each type; throws {@link SnWireException} when a wireId is already claimed. */
    public void register(SnWireType<?>... wireTypes) {
        for (SnWireType<?> type : wireTypes) {
            SnWireType<?> previous = types.putIfAbsent(type.wireId(), type);
            if (previous != null && previous != type) {
                throw new SnWireException("duplicate wireId: '" + type.wireId()
                        + "' is already registered (wireIds are claimed exactly once)");
            }
        }
    }

    /** The registered type, or null when this side does not know the id. */
    public SnWireType<?> find(String wireId) {
        return types.get(wireId);
    }

    /**
     * Decodes one full message body ({@code wireId + version + len + fields}).
     *
     * @throws UnknownWireIdException when the wireId is not registered here (the caller
     *         answers with a {@link NackReason#UNKNOWN_WIRE_ID} NACK)
     * @throws SnWireException on any malformed layout
     */
    public DecodedMessage decode(byte[] body) {
        SnBuf buf = SnBuf.forRead(body);
        String wireId = buf.str();
        int version = buf.u16();
        int len = buf.i32();
        if (len < 0 || len > buf.remaining()) {
            throw new SnWireException("invalid bodyLen for '" + wireId + "': " + len
                    + " (" + buf.remaining() + " bytes remain)");
        }
        SnBuf slice = buf.readSlice(len);
        SnWireType<?> type = types.get(wireId);
        if (type == null) {
            throw new UnknownWireIdException(wireId);
        }
        Object message = type.decodeFields(slice, version);
        return new DecodedMessage(type, version, message);
    }

    /** One decoded message: its type, the EMITTER's version, and the value itself. */
    public record DecodedMessage(SnWireType<?> type, int emitterVersion, Object message) {
    }
}
