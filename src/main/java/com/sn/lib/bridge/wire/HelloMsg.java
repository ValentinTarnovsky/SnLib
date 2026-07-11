package com.sn.lib.bridge.wire;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;

/**
 * Handshake opener, sent by the BACKEND companion on the first available carrier of a
 * (backend, namespace, connection). The proxy answers with {@link HelloAckMsg}; queued
 * application sends flush STRICTLY after the ACK. Signed with the pre-handshake nonce
 * ({@link WireProtocol#HANDSHAKE_NONCE}).
 *
 * @param frameVersionMin lowest frame version this side still speaks
 * @param frameVersionMax highest frame version this side speaks
 * @param msgsetVersion   the namespace's message-set version on this side
 * @param libVersion      SnLib version string (diagnostics only, never branched on)
 * @param nonce           this side's random session-nonce half (final nonce = backend half XOR proxy half)
 * @param capabilities    versioned vocabularies served here (e.g. verb name to vocabulary
 *                        version); empty for plain consumer namespaces
 */
public record HelloMsg(int frameVersionMin, int frameVersionMax, int msgsetVersion,
        String libVersion, long nonce, Map<String, Integer> capabilities) {

    public HelloMsg {
        capabilities = Map.copyOf(capabilities);
    }

    public static final SnWireType<HelloMsg> TYPE = SnWireType.of(
            WireIds.HELLO, 1,
            (buf, m) -> {
                buf.u8(m.frameVersionMin());
                buf.u8(m.frameVersionMax());
                buf.u16(m.msgsetVersion());
                buf.str(m.libVersion());
                buf.i64(m.nonce());
                writeCapabilities(buf, m.capabilities());
            },
            (buf, version) -> new HelloMsg(buf.u8(), buf.u8(), buf.u16(), buf.str(), buf.i64(),
                    readCapabilities(buf)));

    /** Sorted on write so golden fixtures are deterministic regardless of map impl. */
    static void writeCapabilities(SnBuf buf, Map<String, Integer> capabilities) {
        TreeMap<String, Integer> sorted = new TreeMap<>(capabilities);
        buf.u16(sorted.size());
        for (Map.Entry<String, Integer> entry : sorted.entrySet()) {
            buf.str(entry.getKey());
            buf.u16(entry.getValue());
        }
    }

    static Map<String, Integer> readCapabilities(SnBuf buf) {
        int count = buf.u16();
        Map<String, Integer> out = new LinkedHashMap<>(Math.max(4, count));
        for (int i = 0; i < count; i++) {
            out.put(buf.str(), buf.u16());
        }
        return out;
    }
}
