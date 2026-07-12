package com.sn.lib.velocity;

import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

import java.util.concurrent.CompletionException;

import com.sn.lib.SnExperimental;
import com.sn.lib.bridge.SnDelivery;
import com.sn.lib.bridge.SnDeliveryResult;
import com.sn.lib.bridge.wire.SnNackException;
import com.sn.lib.bridge.wire.SnWireException;
import com.sn.lib.bridge.wire.SnWireType;
import com.sn.lib.bridge.wire.Verbs;
import com.sn.lib.velocity.internal.ProxyBridgeRuntime;
import com.sn.lib.velocity.internal.ProxyChannelCore;

/**
 * Tier 2 verbs for consumer PROXY plugins: generic actions SnLib itself runs on the
 * target backend, so a proxy-only plugin needs no Paper jar of its own. Reached via
 * {@link SnProxy#verbs()} and addressed per backend with {@link #on}.
 *
 * <pre>{@code
 * SnVerbs verbs = SnProxy.verbs();
 * verbs.on("gens").console("crates key give " + name + " vote 1")
 *     .thenAccept(d -> { if (!d.ok()) logger.warn("gens console: {}", d); });
 * verbs.on("work").message(uuid, "<red>KeyAll in 5m");
 * verbs.on("work").bossbar(uuid, "keyall", bar -> bar.text("<red>Soon").progress(0.5f));
 * }</pre>
 *
 * <p>Every call returns a terminal {@link SnDelivery}: DELIVERED on backend ack,
 * DENIED_BY_ALLOWLIST for a blocked console command, FAILED_AT_DESTINATION when the
 * backend could not run it (player offline, bad spec), UNKNOWN_SERVER for a bad name, or
 * EXPIRED_TTL on timeout. Verbs are at-most-once: never use them for paid deliveries
 * without your own DB persistence.</p>
 */
@SnExperimental
public final class SnVerbs {

    private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(5);

    private final ProxyBridgeRuntime runtime;

    SnVerbs(ProxyBridgeRuntime runtime) {
        this.runtime = runtime;
    }

    /** Targets one backend server by its registered name. */
    public Target on(String serverName) {
        return new Target(serverName);
    }

    /** Verb calls against one backend. */
    @SnExperimental
    public final class Target {

        private final String serverName;

        private Target(String serverName) {
            this.serverName = serverName;
        }

        /** Runs a console command on the backend, subject to its allowlist. */
        public CompletableFuture<SnDelivery> console(String command) {
            return send(Verbs.Console.TYPE, new Verbs.Console(command));
        }

        /** Sends a chat message to one player on the backend (SnText formatting). */
        public CompletableFuture<SnDelivery> message(UUID player, String text) {
            return send(Verbs.Message.TYPE, new Verbs.Message(player, text));
        }

        /**
         * Shows a title + subtitle with tick timings. Timings saturate at 0..65535 ticks
         * (~54 min), keeping the effectively-permanent-title idiom working.
         */
        public CompletableFuture<SnDelivery> title(UUID player, String title, String subtitle,
                int fadeInTicks, int stayTicks, int fadeOutTicks) {
            return send(Verbs.Title.TYPE, new Verbs.Title(player, title, subtitle,
                    clampTicks(fadeInTicks), clampTicks(stayTicks), clampTicks(fadeOutTicks)));
        }

        /** Shows an action bar line to one player. */
        public CompletableFuture<SnDelivery> actionbar(UUID player, String text) {
            return send(Verbs.Actionbar.TYPE, new Verbs.Actionbar(player, text));
        }

        /** Plays a sound for one player; {@code spec} is the SoundUtil "SOUND_ID [volume] [pitch]" form. */
        public CompletableFuture<SnDelivery> sound(UUID player, String spec) {
            return send(Verbs.Sound.TYPE, new Verbs.Sound(player, spec));
        }

        /** Runs an ActionEngine action list for one player (the most general verb). */
        public CompletableFuture<SnDelivery> actions(UUID player, List<String> actions) {
            return send(Verbs.Actions.TYPE, new Verbs.Actions(player, actions));
        }

        /** Shows (or replaces) a bridge-owned bossbar for one player. */
        public CompletableFuture<SnDelivery> bossbar(UUID player, String barId,
                Consumer<BarSpec> spec) {
            BarSpec built = new BarSpec();
            spec.accept(built);
            return send(Verbs.Bossbar.TYPE, new Verbs.Bossbar(player, Verbs.BAR_SHOW, barId,
                    built.text, built.progress, built.color, built.overlay));
        }

        /** Updates a bridge-owned bossbar's text/progress for one player. */
        public CompletableFuture<SnDelivery> bossbarUpdate(UUID player, String barId, String text,
                float progress) {
            return send(Verbs.Bossbar.TYPE, new Verbs.Bossbar(player, Verbs.BAR_UPDATE, barId,
                    text, progress, "", ""));
        }

        /** Hides a bridge-owned bossbar for one player. */
        public CompletableFuture<SnDelivery> bossbarHide(UUID player, String barId) {
            return send(Verbs.Bossbar.TYPE, new Verbs.Bossbar(player, Verbs.BAR_HIDE, barId,
                    "", 1.0f, "", ""));
        }

        /**
         * Requests the backend's effective console allowlist (drift audit). The report's
         * {@code result} is DELIVERED with the patterns when the backend answered, or a
         * failure result (UNKNOWN_SERVER / EXPIRED_TTL / ...) otherwise - so an empty
         * pattern list ALWAYS means a genuine deny-all allowlist, never an error.
         */
        public CompletableFuture<AllowlistReport> allowlist() {
            if (runtime.proxy().getServer(serverName).isEmpty()) {
                return CompletableFuture.completedFuture(new AllowlistReport(
                        SnDeliveryResult.UNKNOWN_SERVER, List.of(),
                        "'" + serverName + "' is not a server registered in velocity.toml"));
            }
            ProxyChannelCore core = runtime.verbsCore();
            return core.request(normalize(serverName), Verbs.AllowlistReq.TYPE,
                    new Verbs.AllowlistReq(), Verbs.Allowlist.TYPE, DEFAULT_TIMEOUT.toMillis())
                    .handle((response, error) -> {
                        if (error != null) {
                            SnDelivery d = toDelivery(null, error);
                            return new AllowlistReport(d.result(), List.of(), d.detail());
                        }
                        if (response instanceof Verbs.Allowlist a) {
                            return new AllowlistReport(SnDeliveryResult.DELIVERED, a.patterns(), "");
                        }
                        return new AllowlistReport(SnDeliveryResult.FAILED_AT_DESTINATION,
                                List.of(), "unexpected response");
                    });
        }

        private <T> CompletableFuture<SnDelivery> send(SnWireType<T> type, T verb) {
            if (runtime.proxy().getServer(serverName).isEmpty()) {
                return CompletableFuture.completedFuture(SnDelivery.of(
                        SnDeliveryResult.UNKNOWN_SERVER,
                        "'" + serverName + "' is not a server registered in velocity.toml"));
            }
            // Never-void backstop: an argument that a wire codec rejects (out-of-range
            // field, oversize list) must resolve a terminal SnDelivery, not throw
            CompletableFuture<Object> request;
            try {
                ProxyChannelCore core = runtime.verbsCore();
                request = core.request(normalize(serverName), type, verb, Verbs.Ack.TYPE,
                        DEFAULT_TIMEOUT.toMillis());
            } catch (SnWireException e) {
                return CompletableFuture.completedFuture(
                        SnDelivery.of(SnDeliveryResult.FAILED_AT_DESTINATION, e.getMessage()));
            }
            return request.handle(SnVerbs::toDelivery);
        }
    }

    /**
     * Result of an allowlist audit: a terminal {@link SnDeliveryResult} plus the patterns
     * (empty unless {@code result} is DELIVERED). Distinguishes "backend unreachable" from
     * "genuine deny-all allowlist".
     */
    @SnExperimental
    public record AllowlistReport(SnDeliveryResult result, List<String> patterns, String detail) {
        public AllowlistReport {
            patterns = List.copyOf(patterns);
        }

        public boolean ok() {
            return result == SnDeliveryResult.DELIVERED;
        }
    }

    private static int clampTicks(int ticks) {
        return Math.min(0xFFFF, Math.max(0, ticks));
    }

    /** Fluent bossbar spec for {@link Target#bossbar}. */
    @SnExperimental
    public static final class BarSpec {
        private String text = "";
        private float progress = 1.0f;
        private String color = "";
        private String overlay = "";

        public BarSpec text(String text) {
            this.text = text == null ? "" : text;
            return this;
        }

        public BarSpec progress(float progress) {
            this.progress = progress;
            return this;
        }

        /** Adventure BossBar.Color name (e.g. "RED"); empty keeps the default. */
        public BarSpec color(String color) {
            this.color = color == null ? "" : color;
            return this;
        }

        /** Adventure BossBar.Overlay name (e.g. "NOTCHED_10"); empty keeps the default. */
        public BarSpec overlay(String overlay) {
            this.overlay = overlay == null ? "" : overlay;
            return this;
        }
    }

    private static SnDelivery toDelivery(Object response, Throwable error) {
        if (error != null) {
            Throwable cause = error instanceof CompletionException && error.getCause() != null
                    ? error.getCause() : error;
            // A typed backend NACK maps to a precise result; only genuine transport
            // failures (timeout, queue expiry, shutdown, carrier lost) stay EXPIRED_TTL
            if (cause instanceof SnNackException nack) {
                return switch (nack.reason()) {
                    case UNKNOWN_WIRE_ID, UNSUPPORTED_VERB ->
                            SnDelivery.of(SnDeliveryResult.UNSUPPORTED_AT_DESTINATION, cause.getMessage());
                    case UNSUPPORTED_MSGSET ->
                            SnDelivery.of(SnDeliveryResult.UNSUPPORTED_MSGSET, cause.getMessage());
                    case DENIED_BY_ALLOWLIST ->
                            SnDelivery.of(SnDeliveryResult.DENIED_BY_ALLOWLIST, cause.getMessage());
                    default ->
                            SnDelivery.of(SnDeliveryResult.FAILED_AT_DESTINATION, cause.getMessage());
                };
            }
            return SnDelivery.of(SnDeliveryResult.EXPIRED_TTL, cause.getMessage());
        }
        if (!(response instanceof Verbs.Ack ack)) {
            return SnDelivery.of(SnDeliveryResult.FAILED_AT_DESTINATION, "unexpected response");
        }
        return switch (ack.code()) {
            case Verbs.ACK_DELIVERED -> SnDelivery.of(SnDeliveryResult.DELIVERED, ack.detail());
            case Verbs.ACK_DENIED_BY_ALLOWLIST ->
                    SnDelivery.of(SnDeliveryResult.DENIED_BY_ALLOWLIST, ack.detail());
            case Verbs.ACK_UNSUPPORTED_VERB ->
                    SnDelivery.of(SnDeliveryResult.UNSUPPORTED_AT_DESTINATION, ack.detail());
            default -> SnDelivery.of(SnDeliveryResult.FAILED_AT_DESTINATION, ack.detail());
        };
    }

    private static String normalize(String serverName) {
        return serverName.toLowerCase(java.util.Locale.ROOT);
    }
}
