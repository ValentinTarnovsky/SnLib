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
 * ({@code minecraft:entity.player.levelup}), so ids added by newer servers keep working.
 * An unresolvable id logs one WARN and the play call becomes a no-op. Null, blank and
 * {@code "none"} specs are silent no-ops.</p>
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
        player.playSound(player.getLocation(), parsed.sound(), parsed.volume(), parsed.pitch());
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
        world.playSound(location, parsed.sound(), parsed.volume(), parsed.pitch());
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
        if (sound == null) {
            warnOnce("id:" + parts[0], "Invalid sound '" + parts[0]
                    + "': not resolved by enum nor by Registry.SOUNDS; ignored");
            return null;
        }
        float volume = parts.length > 1 ? parseFloat(parts[1], trimmed) : 1.0f;
        float pitch = parts.length > 2 ? parseFloat(parts[2], trimmed) : 1.0f;
        return new Parsed(sound, volume, pitch);
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

    private record Parsed(Sound sound, float volume, float pitch) {
    }
}
