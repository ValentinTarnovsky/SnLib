package com.sn.lib.bridge.wire;

/**
 * Typed rejection travelling back to the sender: what was refused ({@code refWireId} and
 * the offender's msgId), why, and a human detail for the operator. NACKs surface
 * rate-limited on the proxy side so a denied allowlist pattern or an unknown verb is a
 * visible incident, not ghost silence across 8 consoles.
 *
 * @param refMsgId  msgId of the frame that provoked this NACK
 * @param refWireId wireId that was refused ("" when it never decoded far enough to know)
 * @param reason    typed reason
 * @param detail    operator-facing detail, may be ""
 */
public record NackMsg(int refMsgId, String refWireId, NackReason reason, String detail) {

    public static final SnWireType<NackMsg> TYPE = SnWireType.of(
            WireIds.NACK, 1,
            (buf, m) -> {
                buf.i32(m.refMsgId());
                buf.str(m.refWireId());
                buf.u8(m.reason().id());
                buf.str(m.detail());
            },
            (buf, version) -> new NackMsg(buf.i32(), buf.str(), NackReason.fromId(buf.u8()), buf.str()));
}
