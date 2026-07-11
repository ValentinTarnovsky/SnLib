package com.sn.lib.bridge.wire;

/**
 * Frame-level constants of the SnBridge wire protocol, single source of truth for both
 * platforms. Frame layout (header {@value #HEADER_LENGTH} bytes, big-endian):
 *
 * <pre>
 * offset  0      magic u8            {@link #MAGIC}
 * offset  1      frameVersion u8     {@link #FRAME_VERSION}
 * offset  2      flags u8            bit0 = direction ({@link #FLAG_TO_PROXY})
 * offset  3-6    msgId u32           correlation + chunk reassembly key
 * offset  7-8    chunkIndex u16
 * offset  9-10   chunkCount u16
 * offset 11-26   hmacTag 16B         HMAC-SHA256 truncated, see {@link HmacSigner}
 * offset 27+     body chunk bytes
 * </pre>
 *
 * <p>The HMAC input is {@code header[0..11) + sessionNonce(8B big-endian) + chunk body}.
 * Direction lives inside the signed header (flags) and the RECEIVER enforces its expected
 * direction in {@link FrameCodec#decode}: the HMAC makes the flag tamper-proof, the
 * receiver-side check is what rejects an authentic captured frame reflected to the other
 * leg. The session nonce (negotiated in HELLO, 0 for the handshake itself) kills
 * cross-session replay.</p>
 *
 * <p>Chunk sizes are asymmetric because the Minecraft protocol caps are: custom payloads
 * TO a backend (serverbound) die near 32KB, payloads TO the proxy (clientbound leg) allow
 * about 1MB. The big blobs (config sync) flow in the CAPPED direction, hence chunking is
 * mandatory, not optional.</p>
 */
public final class WireProtocol {

    /** First byte of every frame; anything else is instant garbage. */
    public static final int MAGIC = 0xB5;

    /** Version of the FRAME layout (header/chunking/hmac), not of any message. */
    public static final int FRAME_VERSION = 1;

    /** Oldest frame version this build can still speak; HELLO negotiates inside [min, current]. */
    public static final int FRAME_VERSION_MIN = 1;

    /** Header bytes before the body chunk. */
    public static final int HEADER_LENGTH = 27;

    /** HMAC-SHA256 tag truncated to this many bytes. */
    public static final int HMAC_TAG_LENGTH = 16;

    /** Flags bit0: set = backend to proxy, clear = proxy to backend. */
    public static final int FLAG_TO_PROXY = 0x01;

    /** Max body bytes per chunk travelling proxy to backend (serverbound cap ~32KB, margin kept). */
    public static final int MAX_CHUNK_BODY_TO_BACKEND = 24_576;

    /** Max body bytes per chunk travelling backend to proxy (clientbound cap ~1MB, margin kept). */
    public static final int MAX_CHUNK_BODY_TO_PROXY = 950_000;

    /** Session nonce used before HELLO negotiates one (the handshake frames themselves). */
    public static final long HANDSHAKE_NONCE = 0L;

    private WireProtocol() {
        // Constants only
    }

    /** Flags byte for a frame heading in the given direction (no other flags defined in v1). */
    public static int flags(boolean toProxy) {
        return toProxy ? FLAG_TO_PROXY : 0;
    }

    /** Max chunk body for the given direction. */
    public static int maxChunkBody(boolean toProxy) {
        return toProxy ? MAX_CHUNK_BODY_TO_PROXY : MAX_CHUNK_BODY_TO_BACKEND;
    }
}
