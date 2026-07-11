package com.sn.lib.bridge.wire;

import java.util.Map;

/**
 * Proxy's answer to {@link HelloMsg}: carries the NEGOTIATED frame version (highest
 * common), the proxy side's msgset and capabilities, and the proxy's nonce half. After
 * this message both sides sign with {@code backendNonce ^ proxyNonce} and the namespace
 * leaves WARMING.
 *
 * @param frameVersion  negotiated frame version both sides will emit
 * @param msgsetVersion the namespace's message-set version on the proxy side
 * @param libVersion    SnLib version string on the proxy (diagnostics only)
 * @param nonce         proxy's random session-nonce half
 * @param capabilities  versioned vocabularies known to the proxy side
 */
public record HelloAckMsg(int frameVersion, int msgsetVersion, String libVersion,
        long nonce, Map<String, Integer> capabilities) {

    public HelloAckMsg {
        capabilities = Map.copyOf(capabilities);
    }

    public static final SnWireType<HelloAckMsg> TYPE = SnWireType.of(
            WireIds.HELLO_ACK, 1,
            (buf, m) -> {
                buf.u8(m.frameVersion());
                buf.u16(m.msgsetVersion());
                buf.str(m.libVersion());
                buf.i64(m.nonce());
                HelloMsg.writeCapabilities(buf, m.capabilities());
            },
            (buf, version) -> new HelloAckMsg(buf.u8(), buf.u16(), buf.str(), buf.i64(),
                    HelloMsg.readCapabilities(buf)));
}
