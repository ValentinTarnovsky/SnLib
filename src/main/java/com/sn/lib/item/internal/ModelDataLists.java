package com.sn.lib.item.internal;

import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

import org.bukkit.Bukkit;
import org.bukkit.Color;
import org.bukkit.entity.Player;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;
import com.sn.lib.compat.SnCompat;
import com.sn.lib.yml.SnYml;

/**
 * The four lists of the 1.21.4+ {@code minecraft:custom_model_data} component (floats,
 * flags, strings, colors) as an owner declares them in yml, plus the one reflective step
 * that writes them onto an {@link ItemMeta}.
 *
 * <p>Each list is null when its key is absent, meaning "leave that list of the component
 * alone", never "clear it"; a declared list REPLACES the whole list of that kind. Keys:
 * {@code custom-model-data-floats} (numbers), {@code custom-model-data-flags}
 * ({@code true}/{@code false}), {@code custom-model-data-strings} (placeholder-resolved
 * text) and {@code custom-model-data-colors} ({@code #RRGGBB}, {@code RRGGBB} or
 * {@code R, G, B}). A bad entry is skipped with one WARN; the rest of the list applies.</p>
 *
 * <p>Compat: SnLib compiles against paper-api 1.21.1 with a 1.20.4 runtime floor, and the
 * component API ({@code ItemMeta#getCustomModelDataComponent},
 * {@code CustomModelDataComponent#setFloats/Flags/Strings/Colors}) exists only from Paper
 * 1.21.4, so it is resolved once by reflection through {@link SnCompat#probe}. Below 1.21.4
 * {@link #apply} is a no-op with ONE WARN, never a throw.</p>
 *
 * <p>The parse rules touch no server state and are unit tested without a server; see
 * {@code ModelDataListsTest}. Server-wide statics allowed by the SnLib contract: the
 * resolved methods and the WARN dedup set describe this server, not a consumer.</p>
 */
public record ModelDataLists(@Nullable List<Float> floats, @Nullable List<Boolean> flags,
                             @Nullable List<String> strings, @Nullable List<Color> colors) {

    /** Yml key of the float list. */
    public static final String FLOATS_KEY = "custom-model-data-floats";
    /** Yml key of the flag list. */
    public static final String FLAGS_KEY = "custom-model-data-flags";
    /** Yml key of the string list. */
    public static final String STRINGS_KEY = "custom-model-data-strings";
    /** Yml key of the color list. */
    public static final String COLORS_KEY = "custom-model-data-colors";

    /** No list declared: applying it touches nothing. */
    public static final ModelDataLists NONE = new ModelDataLists(null, null, null, null);

    private static final String COMPONENT_CLASS =
            "org.bukkit.inventory.meta.components.CustomModelDataComponent";

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    /** True when no list is declared. */
    public boolean isEmpty() {
        return floats == null && flags == null && strings == null && colors == null;
    }

    /**
     * Reads the four keys under {@code prefix} ({@code ""} for the root, otherwise the
     * section path followed by a dot). Every entry resolves through the yml pipeline with
     * {@code viewer} plus the locals {@code phs}, so a placeholder may produce a value; an
     * absent key leaves that list null.
     */
    public static ModelDataLists read(SnYml yml, String prefix, @Nullable Player viewer,
                                      Ph... phs) {
        List<Float> floats = null;
        List<Boolean> flags = null;
        List<String> strings = null;
        List<Color> colors = null;
        if (yml.isSet(prefix + FLOATS_KEY)) {
            floats = parseFloats(yml.getStringList(prefix + FLOATS_KEY, List.of(), viewer, phs),
                    bad -> warnOnce("float:" + bad, "Invalid " + FLOATS_KEY + " entry '" + bad
                            + "' in " + yml.file().getName() + " (expected a number); skipped"));
        }
        if (yml.isSet(prefix + FLAGS_KEY)) {
            flags = parseFlags(yml.getStringList(prefix + FLAGS_KEY, List.of(), viewer, phs),
                    bad -> warnOnce("flag:" + bad, "Invalid " + FLAGS_KEY + " entry '" + bad
                            + "' in " + yml.file().getName() + " (expected true or false); skipped"));
        }
        if (yml.isSet(prefix + STRINGS_KEY)) {
            strings = yml.getStringList(prefix + STRINGS_KEY, List.of(), viewer, phs);
        }
        if (yml.isSet(prefix + COLORS_KEY)) {
            colors = parseColors(yml.getStringList(prefix + COLORS_KEY, List.of(), viewer, phs),
                    bad -> warnOnce("color:" + bad, "Invalid " + COLORS_KEY + " entry '" + bad
                            + "' in " + yml.file().getName()
                            + " (expected '#RRGGBB', 'RRGGBB' or 'R, G, B'); skipped"));
        }
        return new ModelDataLists(floats, flags, strings, colors);
    }

    /** Finite numbers in order; any other entry is handed to {@code onBad} and skipped. */
    public static List<Float> parseFloats(List<String> raw, Consumer<String> onBad) {
        List<Float> out = new ArrayList<>(raw.size());
        for (String entry : raw) {
            String value = entry == null ? "" : entry.trim();
            try {
                float parsed = Float.parseFloat(value);
                if (Float.isFinite(parsed)) {
                    out.add(parsed);
                    continue;
                }
            } catch (NumberFormatException notANumber) {
                // Reported below like a non-finite value.
            }
            onBad.accept(value);
        }
        return out;
    }

    /** {@code true}/{@code false} in any case; any other entry goes to {@code onBad}. */
    public static List<Boolean> parseFlags(List<String> raw, Consumer<String> onBad) {
        List<Boolean> out = new ArrayList<>(raw.size());
        for (String entry : raw) {
            String value = entry == null ? "" : entry.trim().toLowerCase(Locale.ROOT);
            if (value.equals("true")) {
                out.add(Boolean.TRUE);
            } else if (value.equals("false")) {
                out.add(Boolean.FALSE);
            } else {
                onBad.accept(entry == null ? "" : entry.trim());
            }
        }
        return out;
    }

    /** Colors through {@link #parseColor}; an unparsable entry goes to {@code onBad}. */
    public static List<Color> parseColors(List<String> raw, Consumer<String> onBad) {
        List<Color> out = new ArrayList<>(raw.size());
        for (String entry : raw) {
            Color parsed = entry == null ? null : parseColor(entry);
            if (parsed == null) {
                onBad.accept(entry == null ? "" : entry.trim());
            } else {
                out.add(parsed);
            }
        }
        return out;
    }

    /**
     * Parses {@code "R, G, B"} or hex {@code "RRGGBB"}/{@code "#RRGGBB"}; null when malformed
     * or a channel is outside 0-255. Shared with the {@code color} key of {@code SnItem}.
     */
    public static @Nullable Color parseColor(String raw) {
        String value = raw.trim();
        if (value.startsWith("#")) {
            value = value.substring(1);
        }
        try {
            if (value.indexOf(',') >= 0) {
                String[] parts = value.split(",");
                if (parts.length != 3) {
                    return null;
                }
                return Color.fromRGB(Integer.parseInt(parts[0].trim()),
                        Integer.parseInt(parts[1].trim()), Integer.parseInt(parts[2].trim()));
            }
            if (value.length() == 6) {
                return Color.fromRGB(Integer.parseInt(value, 16));
            }
        } catch (IllegalArgumentException invalid) {
            // Malformed number or channel outside 0-255; the caller warns with ONE WARN.
        }
        return null;
    }

    /** {@link #apply(ItemMeta, List, List, List, List)} with this record's lists. */
    public boolean applyTo(ItemMeta meta) {
        return apply(meta, floats, flags, strings, colors);
    }

    /**
     * Writes every non-null list onto the custom_model_data component of {@code meta},
     * keeping the lists left null as the component already holds them. Must run AFTER any
     * {@code setCustomModelData(Integer)}: Paper replaces the whole component there with
     * {@code floats=[value]} and empty other lists.
     *
     * @return true when at least one list was written; false when every list is null, the
     *         server is below 1.21.4 (one WARN) or the reflective call failed (one WARN)
     */
    public static boolean apply(ItemMeta meta, @Nullable List<Float> floats,
                                @Nullable List<Boolean> flags, @Nullable List<String> strings,
                                @Nullable List<Color> colors) {
        if (meta == null || (floats == null && flags == null && strings == null && colors == null)) {
            return false;
        }
        if (!Handles.SUPPORTED) {
            warnOnce("unsupported", "custom-model-data-floats/flags/strings/colors need Paper"
                    + " 1.21.4+ (custom_model_data component lists); skipped");
            return false;
        }
        try {
            Object component = Handles.GET.invoke(meta);
            if (floats != null) {
                Handles.SET_FLOATS.invoke(component, copy(floats));
            }
            if (flags != null) {
                Handles.SET_FLAGS.invoke(component, copy(flags));
            }
            if (strings != null) {
                Handles.SET_STRINGS.invoke(component, copy(strings));
            }
            if (colors != null) {
                Handles.SET_COLORS.invoke(component, copy(colors));
            }
            Handles.SET.invoke(meta, component);
            return true;
        } catch (ReflectiveOperationException | RuntimeException e) {
            warnOnce("invoke", "custom_model_data component lists failed (" + e + "); skipped");
            return false;
        }
    }

    /** Defensive copy without null elements, so a caller's list never reaches the meta. */
    public static <T> @Nullable List<T> copy(@Nullable List<T> source) {
        if (source == null) {
            return null;
        }
        List<T> out = new ArrayList<>(source.size());
        for (T value : source) {
            if (value != null) {
                out.add(value);
            }
        }
        return out;
    }

    private static void warnOnce(String tag, String message) {
        if (WARNED.add(tag)) {
            Bukkit.getLogger().warning("[SnLib] " + message);
        }
    }

    /**
     * The reflective handles, resolved once on first use (lazy holder): the component
     * interface plus its getter/setter on {@link ItemMeta} and its four list setters.
     */
    private static final class Handles {

        static final boolean SUPPORTED;
        static final Method GET;
        static final Method SET;
        static final Method SET_FLOATS;
        static final Method SET_FLAGS;
        static final Method SET_STRINGS;
        static final Method SET_COLORS;

        static {
            Class<?> type;
            try {
                type = Class.forName(COMPONENT_CLASS, false, ItemMeta.class.getClassLoader());
            } catch (ClassNotFoundException | LinkageError below1214) {
                type = null;
            }
            Method get = null;
            Method set = null;
            Method floats = null;
            Method flags = null;
            Method strings = null;
            Method colors = null;
            if (type != null) {
                get = SnCompat.probe(ItemMeta.class, "getCustomModelDataComponent");
                set = SnCompat.probe(ItemMeta.class, "setCustomModelDataComponent", type);
                floats = SnCompat.probe(type, "setFloats", List.class);
                flags = SnCompat.probe(type, "setFlags", List.class);
                strings = SnCompat.probe(type, "setStrings", List.class);
                colors = SnCompat.probe(type, "setColors", List.class);
            }
            GET = get;
            SET = set;
            SET_FLOATS = floats;
            SET_FLAGS = flags;
            SET_STRINGS = strings;
            SET_COLORS = colors;
            SUPPORTED = get != null && set != null && floats != null && flags != null
                    && strings != null && colors != null;
        }

        private Handles() {
        }
    }
}
