package com.sn.lib.bridge.wire;

/**
 * Builds and verifies single frames: header + HMAC + body chunk. Chunk splitting and
 * reassembly live in {@link Chunker}/{@link ChunkReassembler}; this class only knows one
 * frame at a time.
 *
 * <p>Decode order is deliberate: magic and frame version are checked BEFORE the HMAC (so
 * garbage and incompatible frames are cheap to reject and countable apart), the HMAC is
 * checked before anything of the body is interpreted, and the tag check is constant
 * time. A frame failing any check throws {@link SnWireException}; callers drop + count,
 * never process.</p>
 */
public final class FrameCodec {

    private FrameCodec() {
        // Static utility
    }

    /** Encodes one complete frame with its computed HMAC tag. */
    public static byte[] encode(int msgId, int chunkIndex, int chunkCount, boolean toProxy,
            boolean response, byte[] chunkBody, int bodyOff, int bodyLen, HmacSigner signer,
            long sessionNonce) {
        if (chunkCount < 1 || chunkCount > 0xFFFF) {
            throw new SnWireException("chunkCount out of range: " + chunkCount);
        }
        if (chunkIndex < 0 || chunkIndex >= chunkCount) {
            throw new SnWireException("chunkIndex " + chunkIndex + " out of range for chunkCount " + chunkCount);
        }
        byte[] frame = new byte[WireProtocol.HEADER_LENGTH + bodyLen];
        frame[0] = (byte) WireProtocol.MAGIC;
        frame[1] = (byte) WireProtocol.FRAME_VERSION;
        frame[2] = (byte) WireProtocol.flags(toProxy, response);
        frame[3] = (byte) (msgId >>> 24);
        frame[4] = (byte) (msgId >>> 16);
        frame[5] = (byte) (msgId >>> 8);
        frame[6] = (byte) msgId;
        frame[7] = (byte) (chunkIndex >>> 8);
        frame[8] = (byte) chunkIndex;
        frame[9] = (byte) (chunkCount >>> 8);
        frame[10] = (byte) chunkCount;
        byte[] tag = signer.tag(frame, sessionNonce, chunkBody, bodyOff, bodyLen);
        System.arraycopy(tag, 0, frame, 11, WireProtocol.HMAC_TAG_LENGTH);
        System.arraycopy(chunkBody, bodyOff, frame, WireProtocol.HEADER_LENGTH, bodyLen);
        return frame;
    }

    /**
     * Verifies and parses one raw frame.
     *
     * <p>{@code expectToProxy} is the direction THIS receiver accepts (the proxy passes
     * true, a backend passes false). The direction bit travels inside the signed header,
     * so the HMAC makes it tamper-proof, and this check is what actually rejects an
     * authentic captured frame reflected to the other leg. It runs AFTER the HMAC so a
     * reflection counts apart from plain garbage.</p>
     *
     * @return the parsed header; the body is obtained via {@link #body}
     * @throws SnWireException on bad magic, unsupported frame version, truncation, HMAC
     *         mismatch or a direction not matching {@code expectToProxy}
     */
    public static FrameHeader decode(byte[] frame, HmacSigner signer, long sessionNonce,
            boolean expectToProxy) {
        if (frame == null || frame.length < WireProtocol.HEADER_LENGTH) {
            throw new SnWireException("Truncated frame: " + (frame == null ? "null" : frame.length + " bytes"));
        }
        int magic = frame[0] & 0xFF;
        if (magic != WireProtocol.MAGIC) {
            throw new SnWireException("Invalid magic: 0x" + Integer.toHexString(magic)
                    + " (not an SnBridge frame)");
        }
        int frameVersion = frame[1] & 0xFF;
        if (frameVersion < WireProtocol.FRAME_VERSION_MIN || frameVersion > WireProtocol.FRAME_VERSION) {
            throw new SnWireException("frameVersion " + frameVersion + " outside the supported range ["
                    + WireProtocol.FRAME_VERSION_MIN + ", " + WireProtocol.FRAME_VERSION + "]");
        }
        int flags = frame[2] & 0xFF;
        int msgId = ((frame[3] & 0xFF) << 24) | ((frame[4] & 0xFF) << 16)
                | ((frame[5] & 0xFF) << 8) | (frame[6] & 0xFF);
        int chunkIndex = ((frame[7] & 0xFF) << 8) | (frame[8] & 0xFF);
        int chunkCount = ((frame[9] & 0xFF) << 8) | (frame[10] & 0xFF);
        if (chunkCount < 1 || chunkIndex >= chunkCount) {
            throw new SnWireException("Invalid chunking in header: index " + chunkIndex + ", count " + chunkCount);
        }
        byte[] receivedTag = new byte[WireProtocol.HMAC_TAG_LENGTH];
        System.arraycopy(frame, 11, receivedTag, 0, WireProtocol.HMAC_TAG_LENGTH);
        byte[] expected = signer.tag(frame, sessionNonce,
                frame, WireProtocol.HEADER_LENGTH, frame.length - WireProtocol.HEADER_LENGTH);
        if (!HmacSigner.tagsEqual(expected, receivedTag)) {
            throw new SnWireException("Invalid HMAC (frame discarded: spoofing, different secret or a nonce from another session)");
        }
        boolean toProxy = (flags & WireProtocol.FLAG_TO_PROXY) != 0;
        if (toProxy != expectToProxy) {
            throw new SnWireException("Reflected frame: direction " + (toProxy ? "to-proxy" : "to-backend")
                    + " on a receiver expecting the opposite; discarded as reflected");
        }
        return new FrameHeader(frameVersion, flags, msgId, chunkIndex, chunkCount, receivedTag);
    }

    /** Copies the body chunk out of a frame already verified by {@link #decode}. */
    public static byte[] body(byte[] frame) {
        byte[] out = new byte[frame.length - WireProtocol.HEADER_LENGTH];
        System.arraycopy(frame, WireProtocol.HEADER_LENGTH, out, 0, out.length);
        return out;
    }
}
