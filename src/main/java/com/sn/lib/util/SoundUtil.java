package com.sn.lib.util;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.NamespacedKey;
import org.bukkit.Registry;
import org.bukkit.Sound;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Plays sounds from YML-style specs: {@code "SOUND_ID [volume] [pitch]"}.
 *
 * <p>Resolution treats {@link Sound} as an open set (never switch/EnumSet): first
 * {@link Sound#valueOf(String)} for enum-style ids ({@code ENTITY_PLAYER_LEVELUP}), then
 * {@link Registry#SOUNDS} by {@link NamespacedKey} for key-style ids
 * ({@code minecraft:entity.player.levelup}), so ids added by newer servers keep working.</p>
 *
 * <p>An id that carries an explicit namespace and resolves to nothing on the server
 * ({@code okimc:click-2}, a sound that only exists inside a resource pack) is NOT an error:
 * it is sent to the client by name through the raw-string {@code playSound} overload, which
 * is the only way a custom pack sound can ever play. Writing the namespace is the opt-in;
 * a bare id that resolves to nothing ({@code ENTITY_PLAYER_LEVELUPP}) is still a typo and
 * logs one WARN. Null, blank and {@code "none"} specs are silent no-ops.</p>
 *
 * <p>Server-wide statics allowed by the SnLib contract: whether a sound id resolves is a
 * fact about the server, not about a consumer.</p>
 */
public final class SoundUtil {

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private SoundUtil() {
    }

    /** Plays {@code spec} only to {@code player}, at the player's own location. */
    public static void play(Player player, String spec) {
        if (player == null) {
            return;
        }
        Parsed parsed = parse(spec);
        if (parsed == null) {
            return;
        }
        if (parsed.sound() != null) {
            player.playSound(player.getLocation(), parsed.sound(), parsed.volume(), parsed.pitch());
        } else {
            player.playSound(player.getLocation(), parsed.customId(), parsed.volume(), parsed.pitch());
        }
    }

    /**
     * True when {@code spec} would produce a sound: the intentional no-op specs (null,
     * blank, {@code "none"}) count as resolvable, an unresolvable sound id does not. Lets
     * callers distinguish "played nothing on purpose" from "bad spec" without a WARN.
     */
    public static boolean resolves(String spec) {
        if (spec == null) {
            return true;
        }
        String trimmed = spec.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("none")) {
            return true;
        }
        String id = trimmed.split("\\s+")[0];
        return resolve(id) != null || customId(id) != null;
    }

    /** Plays {@code spec} to every player near {@code location}. */
    public static void playAt(Location location, String spec) {
        if (location == null) {
            return;
        }
        World world = location.getWorld();
        if (world == null) {
            return;
        }
        Parsed parsed = parse(spec);
        if (parsed == null) {
            return;
        }
        if (parsed.sound() != null) {
            world.playSound(location, parsed.sound(), parsed.volume(), parsed.pitch());
        } else {
            world.playSound(location, parsed.customId(), parsed.volume(), parsed.pitch());
        }
    }

    private static @Nullable Parsed parse(String spec) {
        if (spec == null) {
            return null;
        }
        String trimmed = spec.trim();
        if (trimmed.isEmpty() || trimmed.equalsIgnoreCase("none")) {
            return null;
        }
        String[] parts = trimmed.split("\\s+");
        Sound sound = resolve(parts[0]);
        String custom = sound == null ? customId(parts[0]) : null;
        if (sound == null && custom == null) {
            warnOnce("id:" + parts[0], "Invalid sound '" + parts[0]
                    + "': not resolved by enum nor by Registry.SOUNDS, and it carries no"
                    + " namespace, so it cannot be a resource pack sound either; ignored."
                    + " A custom pack sound must be written as 'namespace:id'");
            return null;
        }
        float volume = parts.length > 1 ? parseFloat(parts[1], trimmed) : 1.0f;
        float pitch = parts.length > 2 ? parseFloat(parts[2], trimmed) : 1.0f;
        return new Parsed(sound, custom, volume, pitch);
    }

    private static @Nullable Sound resolve(String id) {
        String enumName = id.toUpperCase(Locale.ROOT);
        if (enumName.startsWith("MINECRAFT:")) {
            enumName = enumName.substring("MINECRAFT:".length());
        }
        enumName = enumName.replace('.', '_');
        try {
            return Sound.valueOf(enumName);
        } catch (IllegalArgumentException notAnEnumName) {
            NamespacedKey key = NamespacedKey.fromString(id.toLowerCase(Locale.ROOT));
            return key == null ? null : Registry.SOUNDS.get(key);
        }
    }

    /**
     * The resource pack sound name for {@code id}, or {@code null} when {@code id} is not an
     * explicitly namespaced key. Only an id the author namespaced by hand is trusted to the
     * client by name; a bare unresolved id stays a typo.
     */
    private static @Nullable String customId(String id) {
        int colon = id.indexOf(':');
        if (colon <= 0 || colon == id.length() - 1) {
            return null;
        }
        String lower = id.toLowerCase(Locale.ROOT);
        return NamespacedKey.fromString(lower) == null ? null : lower;
    }

    private static float parseFloat(String token, String spec) {
        try {
            return Float.parseFloat(token);
        } catch (NumberFormatException ex) {
            warnOnce("num:" + spec, "Invalid volume/pitch in '" + spec + "'; using 1.0");
            return 1.0f;
        }
    }

    private static void warnOnce(String tag, String message) {
        if (WARNED.add(tag)) {
            Bukkit.getLogger().warning("[SnLib] " + message);
        }
    }

    /** Exactly one of {@code sound} and {@code customId} is non-null. */
    private record Parsed(@Nullable Sound sound, @Nullable String customId, float volume, float pitch) {
    }
}
