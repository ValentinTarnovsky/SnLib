package com.sn.lib.teleport;

import java.util.function.Consumer;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable per-request configuration of a warmup teleport.
 *
 * <p>Built through {@link #builder()}; every setting is optional and defaults to a plain
 * warmup teleport that messages through the shared {@code snlib.teleport.*} keys. The two
 * shortcuts {@link #instant()} and {@link #warmup(int)} cover the common cases without a
 * builder.</p>
 *
 * <p>Message keys resolve against the lang module of the requesting context when it is
 * declared, falling back to the embedded English defaults otherwise (the teleport module
 * never requires the lang module). Override any of the three keys to point at a
 * consumer-owned message; the value is looked up exactly like a normal lang key.</p>
 */
public final class TeleportOptions {

    /** Default lang key of the warmup-start message; carries {@code {time}} (seconds). */
    public static final String DEFAULT_WARMUP_KEY = "snlib.teleport.warmup";

    /** Default lang key of the message sent when a move cancels the pending teleport. */
    public static final String DEFAULT_CANCELLED_MOVE_KEY = "snlib.teleport.cancelled-move";

    /** Default lang key of the message sent when damage cancels the pending teleport. */
    public static final String DEFAULT_CANCELLED_DAMAGE_KEY = "snlib.teleport.cancelled-damage";

    private static final TeleportOptions INSTANT = builder().build();

    private final int warmupSeconds;
    private final @Nullable String cooldownCategory;
    private final int cooldownSeconds;
    private final String warmupKey;
    private final String cancelledMoveKey;
    private final String cancelledDamageKey;
    private final boolean silent;
    private final @Nullable Consumer<Player> onComplete;

    private TeleportOptions(Builder builder) {
        this.warmupSeconds = Math.max(0, builder.warmupSeconds);
        this.cooldownCategory = builder.cooldownCategory;
        this.cooldownSeconds = Math.max(0, builder.cooldownSeconds);
        this.warmupKey = builder.warmupKey;
        this.cancelledMoveKey = builder.cancelledMoveKey;
        this.cancelledDamageKey = builder.cancelledDamageKey;
        this.silent = builder.silent;
        this.onComplete = builder.onComplete;
    }

    /** New options builder with every setting at its default. */
    public static Builder builder() {
        return new Builder();
    }

    /** Shared no-warmup options: the teleport is dispatched immediately, no cooldown. */
    public static TeleportOptions instant() {
        return INSTANT;
    }

    /** Options with only the warmup set (in seconds); every other setting stays default. */
    public static TeleportOptions warmup(int seconds) {
        return builder().warmupSeconds(seconds).build();
    }

    /** Warmup length in seconds, never negative; {@code 0} means an instant teleport. */
    public int warmupSeconds() {
        return warmupSeconds;
    }

    /** Warmup length converted to ticks ({@code warmupSeconds * 20}). */
    public long warmupTicks() {
        return warmupSeconds * 20L;
    }

    /** Cooldown category armed on completion, or null when no cooldown is applied. */
    public @Nullable String cooldownCategory() {
        return cooldownCategory;
    }

    /** Cooldown length in seconds, never negative; ignored when the category is null. */
    public int cooldownSeconds() {
        return cooldownSeconds;
    }

    /** Lang key of the warmup-start message. */
    public String warmupKey() {
        return warmupKey;
    }

    /** Lang key of the message sent when a move cancels the teleport. */
    public String cancelledMoveKey() {
        return cancelledMoveKey;
    }

    /** Lang key of the message sent when damage cancels the teleport. */
    public String cancelledDamageKey() {
        return cancelledDamageKey;
    }

    /** Whether every message of this request is suppressed. */
    public boolean silent() {
        return silent;
    }

    /** Callback run on the main thread after a successful teleport, or null. */
    public @Nullable Consumer<Player> onComplete() {
        return onComplete;
    }

    /**
     * Builder for {@link TeleportOptions}. Reusable: {@link #build()} snapshots the current
     * settings and the builder may keep being configured for another build.
     */
    public static final class Builder {

        private int warmupSeconds;
        private @Nullable String cooldownCategory;
        private int cooldownSeconds;
        private String warmupKey = DEFAULT_WARMUP_KEY;
        private String cancelledMoveKey = DEFAULT_CANCELLED_MOVE_KEY;
        private String cancelledDamageKey = DEFAULT_CANCELLED_DAMAGE_KEY;
        private boolean silent;
        private @Nullable Consumer<Player> onComplete;

        private Builder() {
        }

        /** Sets the warmup in seconds ({@code 0} = instant); negatives clamp to zero. */
        public Builder warmupSeconds(int seconds) {
            this.warmupSeconds = seconds;
            return this;
        }

        /**
         * Arms the given cooldown category for {@code seconds} on completion and rejects a
         * request while that category is still running (result {@link TeleportResult#ON_COOLDOWN}).
         * The category is shared with {@code sn.cooldowns()}, so the same key can gate other
         * actions.
         */
        public Builder cooldown(String category, int seconds) {
            this.cooldownCategory = category;
            this.cooldownSeconds = seconds;
            return this;
        }

        /** Overrides the warmup-start message key; the value still receives {@code {time}}. */
        public Builder warmupKey(String key) {
            if (key != null) {
                this.warmupKey = key;
            }
            return this;
        }

        /** Overrides the cancelled-by-move message key. */
        public Builder cancelledMoveKey(String key) {
            if (key != null) {
                this.cancelledMoveKey = key;
            }
            return this;
        }

        /** Overrides the cancelled-by-damage message key. */
        public Builder cancelledDamageKey(String key) {
            if (key != null) {
                this.cancelledDamageKey = key;
            }
            return this;
        }

        /** Suppresses every message of this request (warmup and both cancel messages). */
        public Builder silent(boolean silent) {
            this.silent = silent;
            return this;
        }

        /** Sets the callback run on the main thread after a successful teleport. */
        public Builder onComplete(@Nullable Consumer<Player> onComplete) {
            this.onComplete = onComplete;
            return this;
        }

        /** Builds an immutable snapshot of the current settings. */
        public TeleportOptions build() {
            return new TeleportOptions(this);
        }
    }
}
