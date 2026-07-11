package com.sn.lib.bridge.wire;

/**
 * Parsed frame header (see {@link WireProtocol} for the byte layout). Plain final class
 * instead of a record because the tag is a byte array and array fields break record
 * value equality; headers are never compared, only read.
 */
public final class FrameHeader {

    private final int frameVersion;
    private final int flags;
    private final int msgId;
    private final int chunkIndex;
    private final int chunkCount;
    private final byte[] tag;

    FrameHeader(int frameVersion, int flags, int msgId, int chunkIndex, int chunkCount, byte[] tag) {
        this.frameVersion = frameVersion;
        this.flags = flags;
        this.msgId = msgId;
        this.chunkIndex = chunkIndex;
        this.chunkCount = chunkCount;
        this.tag = tag;
    }

    public int frameVersion() {
        return frameVersion;
    }

    public int flags() {
        return flags;
    }

    public boolean toProxy() {
        return (flags & WireProtocol.FLAG_TO_PROXY) != 0;
    }

    /** Correlation id; unsigned 32-bit carried in an int bit pattern. */
    public int msgId() {
        return msgId;
    }

    public int chunkIndex() {
        return chunkIndex;
    }

    public int chunkCount() {
        return chunkCount;
    }

    /** The received truncated HMAC tag ({@link WireProtocol#HMAC_TAG_LENGTH} bytes, not copied). */
    public byte[] tag() {
        return tag;
    }

    @Override
    public String toString() {
        return "FrameHeader[v" + frameVersion + " flags=" + flags + " msgId=" + msgId
                + " chunk " + (chunkIndex + 1) + "/" + chunkCount + "]";
    }
}
