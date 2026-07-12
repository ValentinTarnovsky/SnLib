package com.sn.lib.bridge.wire;

import java.util.Objects;

/**
 * Typed message codec: a stable string wireId, the current message version, and an
 * explicit positional encoder/decoder pair. This is the ONLY way a message crosses the
 * bridge; reflection-derived codecs are forbidden (ProGuard breaks them).
 *
 * <p>Body layout produced by {@link #encodeMessage}: {@code str wireId, u16 msgVersion,
 * i32 bodyLen, <fields>}. The decoder receives the emitter's version and a buffer SLICED
 * to bodyLen, so it can branch by version and any trailing fields from a newer emitter
 * are skipped - additive evolution for real, enforced by layout instead of promised.</p>
 *
 * <p>Every type MUST be exercised by {@link #selfTest} in unit tests so encoder/decoder
 * field-order drift fails in CI, and MUST have a golden byte fixture (see
 * {@code GoldenFixturesTest}). WireIds live in the ledger in docs/SNBRIDGE-SPEC.md and
 * are never reused.</p>
 *
 * @param <T> the message type, expected to be an immutable record with value equality
 */
public final class SnWireType<T> {

    /** Writes the message fields, in order, nothing else. */
    @FunctionalInterface
    public interface Encoder<T> {
        void encode(SnBuf buf, T message);
    }

    /** Reads the fields written by some emitter version; MUST branch on {@code version} for fields added later. */
    @FunctionalInterface
    public interface Decoder<T> {
        T decode(SnBuf buf, int version);
    }

    private final String wireId;
    private final int version;
    private final Encoder<T> encoder;
    private final Decoder<T> decoder;

    private SnWireType(String wireId, int version, Encoder<T> encoder, Decoder<T> decoder) {
        this.wireId = wireId;
        this.version = version;
        this.encoder = encoder;
        this.decoder = decoder;
    }

    /**
     * Defines a wire type.
     *
     * @param wireId  stable id, format {@code namespace:name} (lowercase); NEVER derived
     *                from a class name, NEVER reused for a different layout
     * @param version current message version, starts at 1, bumps when fields are appended
     */
    public static <T> SnWireType<T> of(String wireId, int version, Encoder<T> encoder, Decoder<T> decoder) {
        if (wireId == null || wireId.isBlank()) {
            throw new SnWireException("empty wireId");
        }
        if (!wireId.equals(wireId.toLowerCase(java.util.Locale.ROOT)) || wireId.indexOf(':') <= 0) {
            throw new SnWireException("invalid wireId: '" + wireId + "' (expected format: namespace:name, lowercase)");
        }
        if (version < 1 || version > 0xFFFF) {
            throw new SnWireException("msgVersion out of range for '" + wireId + "': " + version);
        }
        Objects.requireNonNull(encoder, "encoder");
        Objects.requireNonNull(decoder, "decoder");
        return new SnWireType<>(wireId, version, encoder, decoder);
    }

    public String wireId() {
        return wireId;
    }

    /** Version this side EMITS; the decoder still handles every version below it. */
    public int version() {
        return version;
    }

    /** Full body bytes for one message: wireId + version + length-prefixed fields. */
    public byte[] encodeMessage(T message) {
        SnBuf buf = SnBuf.forWrite(64);
        buf.str(wireId);
        buf.u16(version);
        int lenAt = buf.reserveI32();
        encoder.encode(buf, message);
        buf.patchI32(lenAt, buf.size() - lenAt - 4);
        return buf.toByteArray();
    }

    /** Decodes the fields region of a message that was emitted with {@code emitterVersion}. */
    public T decodeFields(SnBuf slice, int emitterVersion) {
        return decoder.decode(slice, emitterVersion);
    }

    /**
     * Round-trips {@code sample} through encode+decode and fails hard when the result is
     * not {@link Object#equals equal}: catches encoder/decoder field-order drift in CI.
     * Call it from a unit test for EVERY wire type, with every field populated.
     *
     * @return the decoded copy, for extra assertions
     */
    public T selfTest(T sample) {
        byte[] body = encodeMessage(sample);
        SnBuf buf = SnBuf.forRead(body);
        String readId = buf.str();
        if (!wireId.equals(readId)) {
            throw new SnWireException("selfTest of '" + wireId + "': the body says wireId '" + readId + "'");
        }
        int readVersion = buf.u16();
        int len = buf.i32();
        SnBuf slice = buf.readSlice(len);
        T decoded = decoder.decode(slice, readVersion);
        if (slice.remaining() != 0) {
            throw new SnWireException("selfTest of '" + wireId + "': decoder finished with " + slice.remaining()
                    + " bytes left unread (field drift between encoder and decoder)");
        }
        if (!sample.equals(decoded)) {
            throw new SnWireException("selfTest of '" + wireId + "': round-trip mismatch.\n  original: " + sample
                    + "\n  decoded: " + decoded);
        }
        return decoded;
    }

    @Override
    public String toString() {
        return "SnWireType[" + wireId + " v" + version + "]";
    }
}
