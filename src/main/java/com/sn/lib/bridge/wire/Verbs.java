package com.sn.lib.bridge.wire;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

/**
 * Wire messages of the Tier 2 verb service: generic actions SnLib itself executes on the
 * backend over the {@code snlib:bridge} channel, so a simple proxy-only plugin needs no
 * Paper jar of its own. One holder class on purpose: the ten records form one message
 * family with shared ack codes and travel over one channel.
 *
 * <p>Every verb call is a request: the backend answers a {@link Ack} (or an
 * {@link Allowlist} for the audit request) as a FLAG_RESPONSE frame under the same
 * msgId. Verbs are at-most-once and FORBIDDEN for paid deliveries without DB-backed
 * persistence on the consumer side (spec section 8).</p>
 */
public final class Verbs {

    // --- Ack codes (explicit wire ids, never ordinals) ---
    public static final int ACK_DELIVERED = 0;
    public static final int ACK_DENIED_BY_ALLOWLIST = 1;
    public static final int ACK_UNSUPPORTED_VERB = 2;
    public static final int ACK_FAILED = 3;

    // --- Bossbar actions ---
    public static final int BAR_SHOW = 1;
    public static final int BAR_UPDATE = 2;
    public static final int BAR_HIDE = 3;

    private Verbs() {
        // Message family holder
    }

    /** Console command execution, gated by the backend-authoritative allowlist. */
    public record Console(String command) {
        public static final SnWireType<Console> TYPE = SnWireType.of(
                WireIds.VERB_CONSOLE, 1,
                (buf, m) -> buf.str(m.command()),
                (buf, v) -> new Console(buf.str()));
    }

    /** Chat message to one player, rendered through the SnText pipeline. */
    public record Message(UUID target, String text) {
        public static final SnWireType<Message> TYPE = SnWireType.of(
                WireIds.VERB_MESSAGE, 1,
                (buf, m) -> {
                    buf.uuid(m.target());
                    buf.str(m.text());
                },
                (buf, v) -> new Message(buf.uuid(), buf.str()));
    }

    /** Title + subtitle with timings in ticks. */
    public record Title(UUID target, String title, String subtitle, int fadeInTicks,
            int stayTicks, int fadeOutTicks) {
        public static final SnWireType<Title> TYPE = SnWireType.of(
                WireIds.VERB_TITLE, 1,
                (buf, m) -> {
                    buf.uuid(m.target());
                    buf.str(m.title());
                    buf.str(m.subtitle());
                    buf.u16(m.fadeInTicks());
                    buf.u16(m.stayTicks());
                    buf.u16(m.fadeOutTicks());
                },
                (buf, v) -> new Title(buf.uuid(), buf.str(), buf.str(), buf.u16(), buf.u16(),
                        buf.u16()));
    }

    /** Action bar line for one player. */
    public record Actionbar(UUID target, String text) {
        public static final SnWireType<Actionbar> TYPE = SnWireType.of(
                WireIds.VERB_ACTIONBAR, 1,
                (buf, m) -> {
                    buf.uuid(m.target());
                    buf.str(m.text());
                },
                (buf, v) -> new Actionbar(buf.uuid(), buf.str()));
    }

    /** Sound for one player; {@code spec} uses the SoundUtil form "SOUND_ID [volume] [pitch]". */
    public record Sound(UUID target, String spec) {
        public static final SnWireType<Sound> TYPE = SnWireType.of(
                WireIds.VERB_SOUND, 1,
                (buf, m) -> {
                    buf.uuid(m.target());
                    buf.str(m.spec());
                },
                (buf, v) -> new Sound(buf.uuid(), buf.str()));
    }

    /**
     * Bossbar control for one player over the backend's BossBarUtil. {@code action} is
     * one of the BAR_* codes; text/progress/color/overlay only matter for SHOW/UPDATE
     * (empty strings keep defaults; unknown color/overlay names fall back to defaults).
     */
    public record Bossbar(UUID target, int action, String barId, String text, float progress,
            String color, String overlay) {
        public static final SnWireType<Bossbar> TYPE = SnWireType.of(
                WireIds.VERB_BOSSBAR, 1,
                (buf, m) -> {
                    buf.uuid(m.target());
                    buf.u8(m.action());
                    buf.str(m.barId());
                    buf.str(m.text());
                    buf.f32(m.progress());
                    buf.str(m.color());
                    buf.str(m.overlay());
                },
                (buf, v) -> new Bossbar(buf.uuid(), buf.u8(), buf.str(), buf.str(), buf.f32(),
                        buf.str(), buf.str()));
    }

    /** ActionEngine action list for one player (the most general verb). */
    public record Actions(UUID target, List<String> actions) {
        public Actions {
            actions = List.copyOf(actions);
        }

        public static final SnWireType<Actions> TYPE = SnWireType.of(
                WireIds.VERB_ACTIONS, 1,
                (buf, m) -> {
                    buf.uuid(m.target());
                    buf.u16(m.actions().size());
                    for (String action : m.actions()) {
                        buf.str(action);
                    }
                },
                (buf, v) -> {
                    UUID target = buf.uuid();
                    int count = buf.u16();
                    List<String> actions = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        actions.add(buf.str());
                    }
                    return new Actions(target, actions);
                });
    }

    /** Backend's answer to any verb: an ACK_* code plus operator-facing detail. */
    public record Ack(int code, String detail) {
        public static final SnWireType<Ack> TYPE = SnWireType.of(
                WireIds.VERB_ACK, 1,
                (buf, m) -> {
                    buf.u8(m.code());
                    buf.str(m.detail());
                },
                (buf, v) -> new Ack(buf.u8(), buf.str()));
    }

    /** Audit request: "send me your effective console allowlist". */
    public record AllowlistReq() {
        public static final SnWireType<AllowlistReq> TYPE = SnWireType.of(
                WireIds.VERB_ALLOWLIST_REQ, 1,
                (buf, m) -> {
                },
                (buf, v) -> new AllowlistReq());
    }

    /** Audit response: the backend's effective console allowlist patterns. */
    public record Allowlist(List<String> patterns) {
        public Allowlist {
            patterns = List.copyOf(patterns);
        }

        public static final SnWireType<Allowlist> TYPE = SnWireType.of(
                WireIds.VERB_ALLOWLIST, 1,
                (buf, m) -> {
                    buf.u16(m.patterns().size());
                    for (String pattern : m.patterns()) {
                        buf.str(pattern);
                    }
                },
                (buf, v) -> {
                    int count = buf.u16();
                    List<String> patterns = new ArrayList<>(count);
                    for (int i = 0; i < count; i++) {
                        patterns.add(buf.str());
                    }
                    return new Allowlist(patterns);
                });
    }
}
