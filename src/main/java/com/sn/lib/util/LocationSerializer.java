package com.sn.lib.util;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;

/**
 * Serializes locations to the compact form {@code world;x;y;z;yaw;pitch}.
 *
 * <p>Numbers use {@link Double#toString(double)}/{@link Float#toString(float)},
 * which are locale-independent, so round-trips are stable on any JVM locale.
 * Deserialization is null-safe: malformed input, wrong part count or an
 * unloaded world all yield null instead of throwing.</p>
 */
public final class LocationSerializer {

    private static final String SEPARATOR = ";";

    private LocationSerializer() {
    }

    /** Serializes to {@code world;x;y;z;yaw;pitch}; null location or world yields null. */
    public static String serialize(Location location) {
        if (location == null || location.getWorld() == null) {
            return null;
        }
        return location.getWorld().getName() + SEPARATOR
                + location.getX() + SEPARATOR
                + location.getY() + SEPARATOR
                + location.getZ() + SEPARATOR
                + location.getYaw() + SEPARATOR
                + location.getPitch();
    }

    /**
     * Null-safe inverse of {@link #serialize(Location)}.
     *
     * <p>Accepts 6 parts, or 4 parts ({@code world;x;y;z}) with yaw and pitch
     * defaulting to 0. Returns null on any parse failure.</p>
     */
    public static Location deserialize(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(SEPARATOR);
        if (parts.length != 4 && parts.length != 6) {
            return null;
        }
        World world = Bukkit.getWorld(parts[0].trim());
        if (world == null) {
            return null;
        }
        try {
            double x = Double.parseDouble(parts[1].trim());
            double y = Double.parseDouble(parts[2].trim());
            double z = Double.parseDouble(parts[3].trim());
            float yaw = parts.length == 6 ? Float.parseFloat(parts[4].trim()) : 0F;
            float pitch = parts.length == 6 ? Float.parseFloat(parts[5].trim()) : 0F;
            return new Location(world, x, y, z, yaw, pitch);
        } catch (NumberFormatException ex) {
            return null;
        }
    }
}
