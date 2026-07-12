package com.sn.lib.bridge.wire;

import java.util.ArrayList;
import java.util.List;

/**
 * Splits one full message body into direction-aware frames. Chunk size is asymmetric on
 * purpose (see {@link WireProtocol}): serverbound payloads cap near 32KB while
 * clientbound allow ~1MB, and the big blobs flow in the capped direction.
 */
public final class Chunker {

    private Chunker() {
        // Static utility
    }

    /**
     * Splits {@code fullBody} into one or more signed frames.
     *
     * @throws SnWireException when the body would need more than 65535 chunks (a payload
     *         that absurd is a bug upstream, not something to ship in pieces)
     */
    public static List<byte[]> split(byte[] fullBody, boolean toProxy, boolean response,
            int msgId, HmacSigner signer, long sessionNonce) {
        if (fullBody == null || fullBody.length == 0) {
            throw new SnWireException("Body vacio: no hay nada que fragmentar");
        }
        int maxChunk = WireProtocol.maxChunkBody(toProxy);
        long count = ((long) fullBody.length + maxChunk - 1) / maxChunk;
        if (count > 0xFFFF) {
            throw new SnWireException("Body de " + fullBody.length + " bytes necesita " + count
                    + " chunks (max 65535): payload absurdo, revisar el emisor");
        }
        int chunkCount = (int) count;
        List<byte[]> frames = new ArrayList<>(chunkCount);
        int offset = 0;
        for (int index = 0; index < chunkCount; index++) {
            int len = Math.min(maxChunk, fullBody.length - offset);
            frames.add(FrameCodec.encode(msgId, index, chunkCount, toProxy, response,
                    fullBody, offset, len, signer, sessionNonce));
            offset += len;
        }
        return frames;
    }
}
