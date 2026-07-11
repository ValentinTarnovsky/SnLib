package com.sn.lib.bridge.wire;

/**
 * Liveness ping over an established channel; the receiver echoes the same id back. Used
 * by diagnostics ({@code /snlib bridge status} round-trip time), never by application
 * logic.
 *
 * @param echoId opaque id chosen by the sender and echoed verbatim
 */
public record HeartbeatMsg(long echoId) {

    public static final SnWireType<HeartbeatMsg> TYPE = SnWireType.of(
            WireIds.HEARTBEAT, 1,
            (buf, m) -> buf.i64(m.echoId()),
            (buf, version) -> new HeartbeatMsg(buf.i64()));
}
