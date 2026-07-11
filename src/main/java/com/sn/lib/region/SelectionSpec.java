package com.sn.lib.region;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Particle;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.item.SnItem;

/**
 * Immutable declaration of a selection class: wand appearance, edge visuals, limits and
 * consumer callbacks, identified by a short id carried in the wand's PDC tag.
 *
 * <p>Built via {@link #builder(String)}. Every field has a default and the documented
 * clamps are applied once in {@link Builder#build()}, never at runtime. Visual fields are
 * stored pure (particle by NAME, dust color as three ints): resolution to Bukkit types
 * ({@code Particle}, {@code Color}) belongs to the renderer, so the spec never forces
 * Bukkit statics and the color parsing stays pure.</p>
 *
 * <p>{@link #toBuilder()} copies every field to a fresh builder, letting consumers
 * compose callbacks over a spec built elsewhere (for example one loaded from YML).</p>
 */
public final class SelectionSpec {

    /** Server-wide static justified: WARN dedup keyed by offending input, not per consumer. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private final String id;
    private final @Nullable String permission;
    private final @Nullable SnItem wandItem;
    private final @Nullable ItemStack wandTemplate;
    private final String particleName;
    private final int dustRed;
    private final int dustGreen;
    private final int dustBlue;
    private final float dustSize;
    private final double step;
    private final long refreshIntervalTicks;
    private final double renderDistance;
    private final Visibility visibility;
    private final int particleBudget;
    private final long maxRenderVolume;
    private final long maxVolume;
    private final long timeoutTicks;
    private final boolean completeEnds;
    private final boolean silent;
    private final @Nullable Consumer<Cuboid> onSelect;
    private final @Nullable Consumer<SelectionSession> onUpdate;
    private final @Nullable Consumer<UUID> onCancel;

    /** Who sees the rendered selection edges. */
    public enum Visibility {
        /** Only the selecting player (default). */
        OWNER_ONLY,
        /** Every player of the box's world within render distance. */
        WORLD
    }

    private SelectionSpec(Builder builder) {
        this.id = builder.id;
        this.permission = builder.permission;
        this.wandItem = builder.wandItem;
        this.wandTemplate = builder.wandTemplate;
        this.particleName = builder.particleName;
        this.dustRed = clampRgb(builder.dustRed);
        this.dustGreen = clampRgb(builder.dustGreen);
        this.dustBlue = clampRgb(builder.dustBlue);
        this.dustSize = Math.max(0.1F, Math.min(4.0F, builder.dustSize));
        this.step = Math.max(0.1D, builder.step);
        this.refreshIntervalTicks = Math.max(1L, builder.refreshIntervalTicks);
        this.renderDistance = builder.renderDistance;
        this.visibility = builder.visibility;
        this.particleBudget = builder.particleBudget;
        this.maxRenderVolume = builder.maxRenderVolume;
        this.maxVolume = builder.maxVolume;
        this.timeoutTicks = builder.timeoutTicks;
        this.completeEnds = builder.completeEnds;
        this.silent = builder.silent;
        this.onSelect = builder.onSelect;
        this.onUpdate = builder.onUpdate;
        this.onCancel = builder.onCancel;
    }

    /** Starts a builder; a null or blank id falls back to {@code "default"}. */
    public static Builder builder(String id) {
        return new Builder(id == null || id.isBlank() ? "default" : id);
    }

    /** Short identifier of this spec; stored as the wand's PDC tag value. */
    public String id() {
        return id;
    }

    /** Permission required to use the wand, or null for no gate. */
    public @Nullable String permission() {
        return permission;
    }

    /** Declared wand appearance, or null when a template or the manager fallback applies. */
    public @Nullable SnItem wandItem() {
        return wandItem;
    }

    /** Wand template stack when the builder received one, or null. */
    @Nullable ItemStack wandTemplate() {
        return wandTemplate;
    }

    /**
     * Edge particle by NAME (default {@code "DUST"}); the renderer resolves it leniently
     * (REDSTONE alias, invalid name falls back with one WARN).
     */
    public String particleName() {
        return particleName;
    }

    /** Red component of the DUST color, 0 to 255 (default 255). */
    public int dustRed() {
        return dustRed;
    }

    /** Green component of the DUST color, 0 to 255 (default 140). */
    public int dustGreen() {
        return dustGreen;
    }

    /** Blue component of the DUST color, 0 to 255 (default 0). */
    public int dustBlue() {
        return dustBlue;
    }

    /** DUST particle size, clamped 0.1 to 4.0 (default 1.2). */
    public float dustSize() {
        return dustSize;
    }

    /** Distance between edge points in blocks, minimum 0.1 (default 0.5). */
    public double step() {
        return step;
    }

    /** Render refresh period in ticks, minimum 1 (default 5). */
    public long refreshIntervalTicks() {
        return refreshIntervalTicks;
    }

    /** Max distance from a viewer to the box for particles to be shown (default 64). */
    public double renderDistance() {
        return renderDistance;
    }

    /** Who sees the edges (default {@link Visibility#OWNER_ONLY}). */
    public Visibility visibility() {
        return visibility;
    }

    /** Cap of particle points PER refresh PER viewer (default 2000). */
    public int particleBudget() {
        return particleBudget;
    }

    /** Block volume above which only the 8 corners are marked (default 250000). */
    public long maxRenderVolume() {
        return maxRenderVolume;
    }

    /** Cap of the selectable volume in blocks; 0 = no cap (default). */
    public long maxVolume() {
        return maxVolume;
    }

    /** Session timeout in ticks; 0 = never expires (default). */
    public long timeoutTicks() {
        return timeoutTicks;
    }

    /** Whether the session ends by itself after a successful onSelect (default false). */
    public boolean completeEnds() {
        return completeEnds;
    }

    /** Whether every selection message is suppressed (default false). */
    public boolean silent() {
        return silent;
    }

    /** Callback run with the completed cuboid after the event survives, or null. */
    public @Nullable Consumer<Cuboid> onSelect() {
        return onSelect;
    }

    /** Callback run with the session on every position set, or null. */
    public @Nullable Consumer<SelectionSession> onUpdate() {
        return onUpdate;
    }

    /**
     * Callback run with the player's UUID when the session is cancelled, or null. It
     * receives a UUID and not a Player because on quit/kick the Player may no longer be
     * valid; quit callbacks must be idempotent (QuitCleanupListener contract).
     */
    public @Nullable Consumer<UUID> onCancel() {
        return onCancel;
    }

    /** Copies every field of this spec into a fresh builder. */
    public Builder toBuilder() {
        Builder builder = new Builder(id);
        builder.permission = permission;
        builder.wandItem = wandItem;
        builder.wandTemplate = wandTemplate;
        builder.particleName = particleName;
        builder.dustRed = dustRed;
        builder.dustGreen = dustGreen;
        builder.dustBlue = dustBlue;
        builder.dustSize = dustSize;
        builder.step = step;
        builder.refreshIntervalTicks = refreshIntervalTicks;
        builder.renderDistance = renderDistance;
        builder.visibility = visibility;
        builder.particleBudget = particleBudget;
        builder.maxRenderVolume = maxRenderVolume;
        builder.maxVolume = maxVolume;
        builder.timeoutTicks = timeoutTicks;
        builder.completeEnds = completeEnds;
        builder.silent = silent;
        builder.onSelect = onSelect;
        builder.onUpdate = onUpdate;
        builder.onCancel = onCancel;
        return builder;
    }

    private static int clampRgb(int component) {
        return Math.max(0, Math.min(255, component));
    }

    private static void warnOnce(String tag, String message) {
        if (WARNED.add(tag)) {
            Bukkit.getLogger().warning("[SnLib] " + message);
        }
    }

    /** Fluent builder of {@link SelectionSpec}; one method per field, defaults preloaded. */
    public static final class Builder {

        private final String id;
        private @Nullable String permission;
        private @Nullable SnItem wandItem;
        private @Nullable ItemStack wandTemplate;
        private String particleName = "DUST";
        private int dustRed = 255;
        private int dustGreen = 140;
        private int dustBlue = 0;
        private float dustSize = 1.2F;
        private double step = 0.5D;
        private long refreshIntervalTicks = 5L;
        private double renderDistance = 64.0D;
        private Visibility visibility = Visibility.OWNER_ONLY;
        private int particleBudget = 2000;
        private long maxRenderVolume = 250_000L;
        private long maxVolume = 0L;
        private long timeoutTicks = 0L;
        private boolean completeEnds;
        private boolean silent;
        private @Nullable Consumer<Cuboid> onSelect;
        private @Nullable Consumer<SelectionSession> onUpdate;
        private @Nullable Consumer<UUID> onCancel;

        private Builder(String id) {
            this.id = id;
        }

        /** Permission required to use the wand; null or blank means no gate. */
        public Builder permission(@Nullable String permission) {
            this.permission = permission == null || permission.isBlank() ? null : permission;
            return this;
        }

        /** Wand appearance as an SnItem builder; replaces any template previously set. */
        public Builder wandItem(@Nullable SnItem wandItem) {
            this.wandItem = wandItem;
            this.wandTemplate = null;
            return this;
        }

        /** Wand appearance as a template stack (cloned); replaces any SnItem previously set. */
        public Builder wandItem(@Nullable ItemStack template) {
            this.wandTemplate = template == null ? null : template.clone();
            this.wandItem = null;
            return this;
        }

        /** Edge particle by name; null or blank keeps the current value. */
        public Builder particle(@Nullable String particleName) {
            if (particleName != null && !particleName.isBlank()) {
                this.particleName = particleName;
            }
            return this;
        }

        /** Sugar over {@link #particle(String)}: stores the enum constant's name. */
        public Builder particle(@Nullable Particle particle) {
            return particle == null ? this : particle(particle.name());
        }

        /** DUST color components; each is clamped to 0..255 on build. */
        public Builder dustColor(int red, int green, int blue) {
            this.dustRed = red;
            this.dustGreen = green;
            this.dustBlue = blue;
            return this;
        }

        /**
         * Lenient pure parse of {@code "R, G, B"} or {@code "#RRGGBB"}. A malformed value
         * never throws: the current color is kept and one WARN is logged per input.
         */
        public Builder dustColor(@Nullable String rgbOrHex) {
            if (rgbOrHex == null || rgbOrHex.isBlank()) {
                return this;
            }
            String raw = rgbOrHex.trim();
            if (raw.startsWith("#")) {
                if (raw.length() == 7) {
                    try {
                        int rgb = Integer.parseInt(raw.substring(1), 16);
                        return dustColor((rgb >> 16) & 0xFF, (rgb >> 8) & 0xFF, rgb & 0xFF);
                    } catch (NumberFormatException ignored) {
                        // falls through to the WARN below
                    }
                }
            } else {
                String[] parts = raw.split(",");
                if (parts.length == 3) {
                    try {
                        return dustColor(Integer.parseInt(parts[0].trim()),
                                Integer.parseInt(parts[1].trim()),
                                Integer.parseInt(parts[2].trim()));
                    } catch (NumberFormatException ignored) {
                        // falls through to the WARN below
                    }
                }
            }
            warnOnce("dust-color:" + raw, "Color de seleccion invalido '" + raw
                    + "' (formatos: \"R, G, B\" o \"#RRGGBB\"); se conserva el default");
            return this;
        }

        /** DUST particle size; clamped 0.1 to 4.0 on build. */
        public Builder dustSize(float dustSize) {
            this.dustSize = dustSize;
            return this;
        }

        /** Distance between edge points in blocks; clamped to a 0.1 minimum on build. */
        public Builder step(double step) {
            this.step = step;
            return this;
        }

        /** Render refresh period in ticks; clamped to a 1 minimum on build. */
        public Builder refreshIntervalTicks(long refreshIntervalTicks) {
            this.refreshIntervalTicks = refreshIntervalTicks;
            return this;
        }

        /** Max distance from a viewer to the box for particles to be shown. */
        public Builder renderDistance(double renderDistance) {
            this.renderDistance = renderDistance;
            return this;
        }

        /** Who sees the edges; null keeps the current value. */
        public Builder visibility(@Nullable Visibility visibility) {
            if (visibility != null) {
                this.visibility = visibility;
            }
            return this;
        }

        /** Cap of particle points per refresh per viewer. */
        public Builder particleBudget(int particleBudget) {
            this.particleBudget = particleBudget;
            return this;
        }

        /** Block volume above which only the 8 corners are marked. */
        public Builder maxRenderVolume(long maxRenderVolume) {
            this.maxRenderVolume = maxRenderVolume;
            return this;
        }

        /** Cap of the selectable volume in blocks; 0 = no cap. */
        public Builder maxVolume(long maxVolume) {
            this.maxVolume = maxVolume;
            return this;
        }

        /** Session timeout in ticks; 0 = never expires. */
        public Builder timeoutTicks(long timeoutTicks) {
            this.timeoutTicks = timeoutTicks;
            return this;
        }

        /** Whether the session ends by itself after a successful onSelect. */
        public Builder completeEnds(boolean completeEnds) {
            this.completeEnds = completeEnds;
            return this;
        }

        /** Whether every selection message is suppressed. */
        public Builder silent(boolean silent) {
            this.silent = silent;
            return this;
        }

        /** Callback run with the completed cuboid after the event survives. */
        public Builder onSelect(@Nullable Consumer<Cuboid> onSelect) {
            this.onSelect = onSelect;
            return this;
        }

        /** Callback run with the session on every position set. */
        public Builder onUpdate(@Nullable Consumer<SelectionSession> onUpdate) {
            this.onUpdate = onUpdate;
            return this;
        }

        /** Callback run with the player's UUID when the session is cancelled. */
        public Builder onCancel(@Nullable Consumer<UUID> onCancel) {
            this.onCancel = onCancel;
            return this;
        }

        /** Builds the immutable spec, applying every documented clamp. */
        public SelectionSpec build() {
            return new SelectionSpec(this);
        }
    }
}
