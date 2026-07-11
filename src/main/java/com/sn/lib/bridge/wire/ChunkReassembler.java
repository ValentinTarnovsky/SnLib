package com.sn.lib.bridge.wire;

import java.util.HashMap;
import java.util.Map;

/**
 * Rebuilds full message bodies from verified frames of ONE connection. Plugin messages
 * ride an ordered stream, so chunks of a message arrive strictly in order; any index gap
 * or duplicate means corruption or tampering and kills that message's state.
 *
 * <p>Hard caps guard memory (a spoofed client must not buy unbounded buffers): total
 * bytes per message and concurrent in-flight messages, both constructor-set. On carrier
 * disconnect/switch the OWNER calls {@link #clear()}: partial state never survives the
 * connection it rode on (and the sender side accounts the aborted message as expired,
 * never as silently lost).</p>
 *
 * <p>Not thread-safe: confine one instance to its connection's handler thread.</p>
 */
public final class ChunkReassembler {

    private final int maxMessageBytes;
    private final int maxPendingMessages;
    private final Map<Integer, Pending> pending;

    /**
     * @param maxMessageBytes    hard cap of one reassembled body
     * @param maxPendingMessages hard cap of concurrent partially-received messages
     */
    public ChunkReassembler(int maxMessageBytes, int maxPendingMessages) {
        if (maxMessageBytes <= 0 || maxPendingMessages <= 0) {
            throw new SnWireException("Caps del reassembler deben ser positivos");
        }
        this.maxMessageBytes = maxMessageBytes;
        this.maxPendingMessages = maxPendingMessages;
        this.pending = new HashMap<>(4);
    }

    /**
     * Feeds one verified frame.
     *
     * @return the complete body when this frame finished a message, else null
     * @throws SnWireException on out-of-order chunks, size cap or pending cap violations
     *         (the violating message's state is discarded before throwing)
     */
    public byte[] accept(FrameHeader header, byte[] chunkBody) {
        int msgId = header.msgId();
        if (header.chunkCount() == 1) {
            pending.remove(msgId);
            checkSize(chunkBody.length);
            return chunkBody;
        }
        Pending state = pending.get(msgId);
        if (header.chunkIndex() == 0) {
            if (state != null) {
                pending.remove(msgId);
            }
            if (pending.size() >= maxPendingMessages) {
                throw new SnWireException("Reassembler lleno: " + pending.size()
                        + " mensajes parciales en vuelo (cap " + maxPendingMessages + "), frame descartado");
            }
            checkSize(chunkBody.length);
            state = new Pending(header.chunkCount(), maxMessageBytes);
            state.append(chunkBody);
            pending.put(msgId, state);
            return null;
        }
        if (state == null) {
            throw new SnWireException("Chunk " + (header.chunkIndex() + 1) + "/" + header.chunkCount()
                    + " de msgId " + msgId + " sin chunk inicial: mensaje descartado");
        }
        if (header.chunkCount() != state.chunkCount || header.chunkIndex() != state.received) {
            pending.remove(msgId);
            throw new SnWireException("Chunk fuera de orden para msgId " + msgId + ": llego "
                    + header.chunkIndex() + "/" + header.chunkCount() + ", se esperaba "
                    + state.received + "/" + state.chunkCount + "; mensaje descartado");
        }
        if (state.size + chunkBody.length > maxMessageBytes) {
            pending.remove(msgId);
            throw new SnWireException("Mensaje " + msgId + " supera el cap de " + maxMessageBytes
                    + " bytes al reensamblar; descartado");
        }
        state.append(chunkBody);
        if (state.received < state.chunkCount) {
            return null;
        }
        pending.remove(msgId);
        return state.toArray();
    }

    /** Drops ALL partial state; MUST be called when the carrier connection dies or switches. */
    public void clear() {
        pending.clear();
    }

    /** Partially received messages right now (diagnostics). */
    public int pendingCount() {
        return pending.size();
    }

    private void checkSize(int bytes) {
        if (bytes > maxMessageBytes) {
            throw new SnWireException("Mensaje de " + bytes + " bytes supera el cap de "
                    + maxMessageBytes + "; descartado");
        }
    }

    private static final class Pending {
        final int chunkCount;
        final int maxMessageBytes;
        byte[] buffer;
        int size;
        int received;

        Pending(int chunkCount, int maxMessageBytes) {
            this.chunkCount = chunkCount;
            this.maxMessageBytes = maxMessageBytes;
            this.buffer = new byte[Math.min(maxMessageBytes, 64 * 1024)];
        }

        void append(byte[] chunk) {
            if (size + chunk.length > buffer.length) {
                // Geometric growth CLAMPED to the cap so one message never allocates past it;
                // safe because accept() already rejected any chunk that would cross the cap
                int wanted = Math.min(maxMessageBytes, Math.max(size + chunk.length, buffer.length * 2));
                byte[] bigger = new byte[wanted];
                System.arraycopy(buffer, 0, bigger, 0, size);
                buffer = bigger;
            }
            System.arraycopy(chunk, 0, buffer, size, chunk.length);
            size += chunk.length;
            received++;
        }

        byte[] toArray() {
            byte[] out = new byte[size];
            System.arraycopy(buffer, 0, out, 0, size);
            return out;
        }
    }
}
