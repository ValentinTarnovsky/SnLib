package com.sn.lib.util;

import org.bukkit.Location;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.region.Cuboid;

/**
 * Location math helpers: cuboid containment from two loose corners and 2D/box distances.
 *
 * <p>Every method is null-safe and never throws: invalid input yields false or
 * {@link Double#POSITIVE_INFINITY}. Containment delegates to {@link Cuboid}, the single
 * source of truth for inclusive edges and corner normalization. Combines well with
 * {@code SnChunkMoveEvent} for cheap zone checks on chunk crossing instead of per-move.</p>
 */
public final class LocationUtil {

    private LocationUtil() {
    }

    /**
     * Whether {@code point} lies inside the block cuboid spanned by the two corners
     * (any order, inclusive edges).
     *
     * <p>Any null argument, a corner without a loaded world or corners in different worlds
     * yields false. Valid corners delegate to {@link Cuboid#contains(Location)}; the
     * short-lived Cuboid keeps a single source of truth for edge semantics (convenience
     * method, not a hot path).</p>
     */
    public static boolean inCuboid(@Nullable Location point, @Nullable Location cornerA, @Nullable Location cornerB) {
        if (point == null || cornerA == null || cornerB == null) {
            return false;
        }
        if (!cornerA.isWorldLoaded() || !cornerB.isWorldLoaded()) {
            return false;
        }
        if (!cornerA.getWorld().getName().equals(cornerB.getWorld().getName())) {
            return false;
        }
        return Cuboid.of(cornerA, cornerB).contains(point);
    }

    /**
     * Squared horizontal distance between two locations, ignoring Y.
     *
     * <p>Null locations, locations without a loaded world or different worlds yield
     * {@link Double#POSITIVE_INFINITY}.</p>
     */
    public static double distance2dSquared(@Nullable Location a, @Nullable Location b) {
        if (a == null || b == null || !a.isWorldLoaded() || !b.isWorldLoaded()) {
            return Double.POSITIVE_INFINITY;
        }
        if (!a.getWorld().getName().equals(b.getWorld().getName())) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = a.getX() - b.getX();
        double dz = a.getZ() - b.getZ();
        return dx * dx + dz * dz;
    }

    /**
     * Horizontal distance between two locations, ignoring Y; same infinity contract as
     * {@link #distance2dSquared(Location, Location)}.
     */
    public static double distance2d(@Nullable Location a, @Nullable Location b) {
        return Math.sqrt(distance2dSquared(a, b));
    }

    /**
     * Squared distance from {@code point} to the CLOSEST point of the cuboid's bounding box
     * {@code [min, max + 1)}, clamping per axis in doubles; 0 when the point is inside.
     *
     * <p>A null box, null point, point without a loaded world or a differently named world
     * yields {@link Double#POSITIVE_INFINITY}. This is exactly the "is the viewer near the
     * region" culling check a zone plugin needs.</p>
     */
    public static double distanceToBoxSquared(@Nullable Cuboid box, @Nullable Location point) {
        if (box == null || point == null || !point.isWorldLoaded()) {
            return Double.POSITIVE_INFINITY;
        }
        if (!box.worldName().equals(point.getWorld().getName())) {
            return Double.POSITIVE_INFINITY;
        }
        double dx = point.getX() - clamp(point.getX(), box.minX(), box.maxX() + 1.0D);
        double dy = point.getY() - clamp(point.getY(), box.minY(), box.maxY() + 1.0D);
        double dz = point.getZ() - clamp(point.getZ(), box.minZ(), box.maxZ() + 1.0D);
        return dx * dx + dy * dy + dz * dz;
    }

    private static double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(value, max));
    }
}
