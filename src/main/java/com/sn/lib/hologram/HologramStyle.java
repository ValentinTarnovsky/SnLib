package com.sn.lib.hologram;

import java.util.Locale;
import java.util.Objects;
import java.util.function.Consumer;

import org.bukkit.Color;
import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.jetbrains.annotations.Nullable;

/**
 * Look of a hologram's {@link TextDisplay}: billboard, shadow, see-through, background,
 * alignment, line width, text opacity, scale and view range. Immutable; applied by
 * {@link HologramUtil#spawn(String, org.bukkit.Location, java.util.List, HologramStyle)} and
 * {@link HologramUtil#setStyle(String, HologramStyle)}, and re-applied when a hologram re-binds
 * after a chunk reload.
 *
 * <p>{@link #DEFAULT} holds the vanilla values of a fresh text display (billboard CENTER, the
 * classic hologram behaviour), so a hologram spawned without a style looks exactly as before.</p>
 *
 * <p>The yml form ({@link #read}) uses the keys {@code billboard}, {@code shadow},
 * {@code see-through}, {@code background}, {@code alignment}, {@code line-width},
 * {@code text-opacity}, {@code scale} and {@code view-range}; every key is optional and falls
 * back to the given style.</p>
 *
 * @param billboard   how the text turns toward the viewer; FIXED keeps the spawn yaw
 * @param shadow      whether the text has a drop shadow
 * @param seeThrough  whether the text is visible through blocks
 * @param background  ARGB background color; {@link #DEFAULT_BACKGROUND} is vanilla, alpha 0 is none
 * @param alignment   horizontal alignment of multi-line text
 * @param lineWidth   maximum line width in pixels before the client wraps (minimum 1)
 * @param textOpacity text opacity, 0 to 255 (255 is fully opaque)
 * @param scale       uniform scale of the text (0.1 to 10)
 * @param viewRange   view range multiplier (0.1 to 10; 1 is vanilla)
 */
public record HologramStyle(
        Display.Billboard billboard,
        boolean shadow,
        boolean seeThrough,
        Color background,
        TextDisplay.TextAlignment alignment,
        int lineWidth,
        int textOpacity,
        float scale,
        float viewRange) {

    /** Vanilla text display background: black at alpha 64. */
    public static final Color DEFAULT_BACKGROUND = Color.fromARGB(0x40000000);

    /** A fully transparent background, the yml value {@code none}. */
    public static final Color NO_BACKGROUND = Color.fromARGB(0);

    /** Vanilla line width of a text display, in pixels. */
    public static final int DEFAULT_LINE_WIDTH = 200;

    /** The vanilla look of a fresh text display, billboard CENTER. */
    public static final HologramStyle DEFAULT = new HologramStyle(Display.Billboard.CENTER, false, false,
            DEFAULT_BACKGROUND, TextDisplay.TextAlignment.CENTER, DEFAULT_LINE_WIDTH, 255, 1.0F, 1.0F);

    private static final int MAX_CHANNEL = 255;
    private static final float MIN_FACTOR = 0.1F;
    private static final float MAX_FACTOR = 10.0F;

    /** Validates and clamps the components; a null enum or color takes the {@link #DEFAULT} value. */
    public HologramStyle {
        billboard = billboard == null ? Display.Billboard.CENTER : billboard;
        background = background == null ? DEFAULT_BACKGROUND : background;
        alignment = alignment == null ? TextDisplay.TextAlignment.CENTER : alignment;
        lineWidth = Math.max(1, lineWidth);
        textOpacity = Math.max(0, Math.min(MAX_CHANNEL, textOpacity));
        scale = clampFactor(scale);
        viewRange = clampFactor(viewRange);
    }

    /** This style with another billboard. */
    public HologramStyle withBillboard(Display.Billboard billboard) {
        return new HologramStyle(billboard, shadow, seeThrough, background, alignment, lineWidth,
                textOpacity, scale, viewRange);
    }

    /**
     * Reads a style from a yml section; every missing key keeps the fallback's value. A value
     * that cannot be parsed keeps the fallback's value too and reports one line through
     * {@code warn}, naming the key (the caller prefixes the file and section).
     *
     * <p>{@code background} accepts {@code default} (vanilla), {@code none} (transparent),
     * {@code #RRGGBB} (opaque), {@code #AARRGGBB} and {@code R, G, B} or {@code R, G, B, A}.
     * {@code billboard} is FIXED, VERTICAL, HORIZONTAL or CENTER; {@code alignment} is
     * CENTER, LEFT or RIGHT.</p>
     *
     * @param section  the section holding the keys; null returns the fallback
     * @param fallback the value of every missing or invalid key
     * @param warn     receives one message per invalid value; never null
     */
    public static HologramStyle read(@Nullable ConfigurationSection section, HologramStyle fallback,
                                     Consumer<String> warn) {
        Objects.requireNonNull(fallback, "fallback");
        Objects.requireNonNull(warn, "warn");
        if (section == null) {
            return fallback;
        }
        return new HologramStyle(
                readEnum(section, "billboard", Display.Billboard.class, fallback.billboard(), warn),
                section.getBoolean("shadow", fallback.shadow()),
                section.getBoolean("see-through", fallback.seeThrough()),
                readBackground(section, fallback.background(), warn),
                readEnum(section, "alignment", TextDisplay.TextAlignment.class, fallback.alignment(), warn),
                section.getInt("line-width", fallback.lineWidth()),
                section.getInt("text-opacity", fallback.textOpacity()),
                (float) section.getDouble("scale", fallback.scale()),
                (float) section.getDouble("view-range", fallback.viewRange()));
    }

    /**
     * Parses a background value; null when the text is none of the accepted forms.
     *
     * @param raw {@code default}, {@code none}, {@code #RRGGBB}, {@code #AARRGGBB},
     *            {@code R, G, B} or {@code R, G, B, A}
     */
    public static @Nullable Color parseBackground(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        String text = raw.trim().toLowerCase(Locale.ROOT);
        if (text.equals("default")) {
            return DEFAULT_BACKGROUND;
        }
        if (text.equals("none") || text.equals("transparent")) {
            return NO_BACKGROUND;
        }
        try {
            if (text.startsWith("#") && text.length() == 7) {
                return Color.fromARGB(0xFF000000 | Integer.parseInt(text.substring(1), 16));
            }
            if (text.startsWith("#") && text.length() == 9) {
                return Color.fromARGB((int) Long.parseLong(text.substring(1), 16));
            }
            String[] parts = text.split(",");
            if (parts.length == 3 || parts.length == 4) {
                int alpha = parts.length == 4 ? channel(parts[3]) : MAX_CHANNEL;
                return alpha < 0 || channel(parts[0]) < 0 || channel(parts[1]) < 0 || channel(parts[2]) < 0
                        ? null
                        : Color.fromARGB(alpha, channel(parts[0]), channel(parts[1]), channel(parts[2]));
            }
        } catch (NumberFormatException malformed) {
            return null;
        }
        return null;
    }

    private static Color readBackground(ConfigurationSection section, Color fallback, Consumer<String> warn) {
        if (!section.contains("background")) {
            return fallback;
        }
        String raw = section.getString("background");
        Color parsed = parseBackground(raw);
        if (parsed == null) {
            warn.accept("background '" + raw + "' is not default, none, #RRGGBB, #AARRGGBB,"
                    + " \"R, G, B\" or \"R, G, B, A\"; keeping the previous value");
            return fallback;
        }
        return parsed;
    }

    private static <E extends Enum<E>> E readEnum(ConfigurationSection section, String key, Class<E> type,
                                                  E fallback, Consumer<String> warn) {
        String raw = section.getString(key);
        if (raw == null || raw.isBlank()) {
            return fallback;
        }
        try {
            return Enum.valueOf(type, raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            warn.accept(key + " '" + raw + "' is not one of " + names(type) + "; using " + fallback.name());
            return fallback;
        }
    }

    private static String names(Class<? extends Enum<?>> type) {
        StringBuilder out = new StringBuilder();
        for (Enum<?> constant : type.getEnumConstants()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(constant.name());
        }
        return out.toString();
    }

    /** A 0-255 channel, or -1 when the number is outside that range. */
    private static int channel(String part) {
        int value = Integer.parseInt(part.trim());
        return value < 0 || value > MAX_CHANNEL ? -1 : value;
    }

    private static float clampFactor(float value) {
        if (Float.isNaN(value)) {
            return 1.0F;
        }
        return Math.max(MIN_FACTOR, Math.min(MAX_FACTOR, value));
    }
}
