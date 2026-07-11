package com.sn.lib.region;

import java.util.Collections;
import java.util.Iterator;
import java.util.NoSuchElementException;
import java.util.Objects;

import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.jetbrains.annotations.Nullable;

/**
 * Immutable, axis-aligned block cuboid identified by world name plus two normalized corners.
 *
 * <p>Coordinates are block coordinates and every edge is inclusive: a cuboid built from two
 * clicked blocks contains both blocks. Corners are normalized on construction (min &lt;= max
 * per axis), so callers never worry about corner order. The core (containment, iteration,
 * size, serialization) is pure and never touches Bukkit statics; only the bridge methods
 * ({@link #of(Location, Location)}, {@link #contains(Location)}, {@link #world()},
 * {@link #blocks()}, {@link #center()}) do.</p>
 *
 * <p>The serialized form {@code world;minX;minY;minZ;maxX;maxY;maxZ} is a sibling of
 * {@link com.sn.lib.util.LocationSerializer}: same {@code ;} separator, per-part trimming and
 * lenient null-returning {@link #deserialize(String)}. It is NOT parseable by
 * {@code LocationSerializer.deserialize} (that format has 4 or 6 parts). Consistent with the
 * util package philosophy, {@link #of(Location, Location)} throws on invalid input (programmer
 * path, fail fast) while {@link #deserialize(String)} returns null (data path, lenient).</p>
 */
public final class Cuboid {

    private static final String SEPARATOR = ";";

    private final String worldName;
    private final int minX;
    private final int minY;
    private final int minZ;
    private final int maxX;
    private final int maxY;
    private final int maxZ;

    private Cuboid(String worldName, int minX, int minY, int minZ, int maxX, int maxY, int maxZ) {
        this.worldName = worldName;
        this.minX = minX;
        this.minY = minY;
        this.minZ = minZ;
        this.maxX = maxX;
        this.maxY = maxY;
        this.maxZ = maxZ;
    }

    /**
     * Builds a cuboid from two corners in any order, normalizing min/max per axis.
     *
     * @throws IllegalArgumentException if {@code worldName} is null or blank
     */
    public static Cuboid of(String worldName, int x1, int y1, int z1, int x2, int y2, int z2) {
        if (worldName == null || worldName.isBlank()) {
            throw new IllegalArgumentException("worldName cannot be null or blank");
        }
        return new Cuboid(worldName,
                Math.min(x1, x2), Math.min(y1, y2), Math.min(z1, z2),
                Math.max(x1, x2), Math.max(y1, y2), Math.max(z1, z2));
    }

    /**
     * Builds a cuboid from two corner locations using their block coordinates.
     *
     * <p>Programmer path, fails fast: null corners, corners without a loaded world or corners
     * in different worlds throw instead of returning null.</p>
     *
     * @throws IllegalArgumentException if a corner is null, has no loaded world, or the
     *         corners are in different worlds
     */
    public static Cuboid of(Location a, Location b) {
        if (a == null || b == null) {
            throw new IllegalArgumentException("Corner locations cannot be null");
        }
        if (!a.isWorldLoaded() || !b.isWorldLoaded()) {
            throw new IllegalArgumentException("Both corners need a loaded world");
        }
        String worldA = a.getWorld().getName();
        String worldB = b.getWorld().getName();
        if (!worldA.equals(worldB)) {
            throw new IllegalArgumentException(
                    "Corners are in different worlds: " + worldA + " vs " + worldB);
        }
        return of(worldA, a.getBlockX(), a.getBlockY(), a.getBlockZ(),
                b.getBlockX(), b.getBlockY(), b.getBlockZ());
    }

    /** Name of the world this cuboid lives in. */
    public String worldName() {
        return worldName;
    }

    /** Minimum block X (inclusive). */
    public int minX() {
        return minX;
    }

    /** Minimum block Y (inclusive). */
    public int minY() {
        return minY;
    }

    /** Minimum block Z (inclusive). */
    public int minZ() {
        return minZ;
    }

    /** Maximum block X (inclusive). */
    public int maxX() {
        return maxX;
    }

    /** Maximum block Y (inclusive). */
    public int maxY() {
        return maxY;
    }

    /** Maximum block Z (inclusive). */
    public int maxZ() {
        return maxZ;
    }

    /** Block count along X: {@code maxX - minX + 1}. */
    public int widthX() {
        return maxX - minX + 1;
    }

    /** Block count along Y: {@code maxY - minY + 1}. */
    public int heightY() {
        return maxY - minY + 1;
    }

    /** Block count along Z: {@code maxZ - minZ + 1}. */
    public int depthZ() {
        return maxZ - minZ + 1;
    }

    /** Total block volume, computed in long so large cuboids never overflow int. */
    public long size() {
        return (long) widthX() * heightY() * depthZ();
    }

    /** Pure, world-agnostic containment check with inclusive edges. */
    public boolean contains(int x, int y, int z) {
        return x >= minX && x <= maxX
                && y >= minY && y <= maxY
                && z >= minZ && z <= maxZ;
    }

    /** World-aware containment: false when {@code worldName} does not match this cuboid's world. */
    public boolean contains(String worldName, int x, int y, int z) {
        return this.worldName.equals(worldName) && contains(x, y, z);
    }

    /**
     * Containment check for a location; never throws.
     *
     * <p>A null point, a point without a loaded world or a point in a differently named
     * world yields false. Uses block coordinates.</p>
     */
    public boolean contains(@Nullable Location point) {
        if (point == null || !point.isWorldLoaded()) {
            return false;
        }
        return contains(point.getWorld().getName(),
                point.getBlockX(), point.getBlockY(), point.getBlockZ());
    }

    /**
     * Whether this cuboid overlaps {@code other}; edges are inclusive, so cuboids merely
     * touching on a border count as intersecting. Different worlds never intersect.
     */
    public boolean intersects(Cuboid other) {
        if (other == null || !worldName.equals(other.worldName)) {
            return false;
        }
        return minX <= other.maxX && maxX >= other.minX
                && minY <= other.maxY && maxY >= other.minY
                && minZ <= other.maxZ && maxZ >= other.minZ;
    }

    /**
     * Returns a NEW cuboid grown (or shrunk with negative deltas) by the given amount in BOTH
     * directions of each axis.
     *
     * <p>Never throws: if shrinking crosses min over max on an axis, that axis collapses to a
     * single block at the midpoint of the original range.</p>
     */
    public Cuboid expand(int dx, int dy, int dz) {
        int newMinX = minX - dx;
        int newMaxX = maxX + dx;
        int newMinY = minY - dy;
        int newMaxY = maxY + dy;
        int newMinZ = minZ - dz;
        int newMaxZ = maxZ + dz;
        if (newMinX > newMaxX) {
            newMinX = newMaxX = midpoint(minX, maxX);
        }
        if (newMinY > newMaxY) {
            newMinY = newMaxY = midpoint(minY, maxY);
        }
        if (newMinZ > newMaxZ) {
            newMinZ = newMaxZ = midpoint(minZ, maxZ);
        }
        return new Cuboid(worldName, newMinX, newMinY, newMinZ, newMaxX, newMaxY, newMaxZ);
    }

    private static int midpoint(int min, int max) {
        return (int) (((long) min + max) / 2L);
    }

    /** Pure per-block visitor, so iteration is testable without Bukkit. */
    @FunctionalInterface
    public interface BlockConsumer {

        /** Visits one block coordinate of the cuboid. */
        void accept(int x, int y, int z);
    }

    /** Visits every block coordinate exactly once, in x then y then z order. Pure. */
    public void forEach(BlockConsumer action) {
        for (int x = minX; x <= maxX; x++) {
            for (int y = minY; y <= maxY; y++) {
                for (int z = minZ; z <= maxZ; z++) {
                    action.accept(x, y, z);
                }
            }
        }
    }

    /**
     * Lazy Bukkit bridge over every block location; never materializes a list, so large
     * cuboids never spike the heap.
     *
     * <p>Lenient contract (like {@code LocationSerializer.deserialize}): when the world is not
     * loaded the result is an empty Iterable, never an exception.</p>
     */
    public Iterable<Location> blocks() {
        World world = world();
        if (world == null) {
            return Collections.emptyList();
        }
        return () -> new Iterator<>() {

            private int x = minX;
            private int y = minY;
            private int z = minZ;
            private boolean done = false;

            @Override
            public boolean hasNext() {
                return !done;
            }

            @Override
            public Location next() {
                if (done) {
                    throw new NoSuchElementException();
                }
                Location current = new Location(world, x, y, z);
                z++;
                if (z > maxZ) {
                    z = minZ;
                    y++;
                    if (y > maxY) {
                        y = minY;
                        x++;
                        if (x > maxX) {
                            done = true;
                        }
                    }
                }
                return current;
            }
        };
    }

    /** Bukkit bridge: the resolved world, or null if it is not loaded. */
    public @Nullable World world() {
        return Bukkit.getWorld(worldName);
    }

    /** Geometric center (+0.5 per axis past the block midpoint), or null if the world is not loaded. */
    public @Nullable Location center() {
        World world = world();
        if (world == null) {
            return null;
        }
        return new Location(world,
                (minX + maxX + 1) / 2.0D,
                (minY + maxY + 1) / 2.0D,
                (minZ + maxZ + 1) / 2.0D);
    }

    /** Serializes to {@code world;minX;minY;minZ;maxX;maxY;maxZ} (normalized min to max order). */
    public String serialize() {
        return worldName + SEPARATOR
                + minX + SEPARATOR + minY + SEPARATOR + minZ + SEPARATOR
                + maxX + SEPARATOR + maxY + SEPARATOR + maxZ;
    }

    /**
     * Null-safe inverse of {@link #serialize()}; never throws.
     *
     * <p>Null/blank input, a part count other than 7, a malformed number or a blank world
     * name all yield null. Each part is trimmed individually ({@code "world ; 1 ; ..."}
     * parses) and corners are re-normalized, tolerating hand-written data out of order. The
     * world is NOT required to be loaded: binding is lazy via {@link #world()}, so cuboids
     * deserialize safely in onEnable before worlds load.</p>
     */
    public static @Nullable Cuboid deserialize(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String[] parts = raw.trim().split(SEPARATOR);
        if (parts.length != 7) {
            return null;
        }
        String world = parts[0].trim();
        if (world.isBlank()) {
            return null;
        }
        try {
            return of(world,
                    Integer.parseInt(parts[1].trim()),
                    Integer.parseInt(parts[2].trim()),
                    Integer.parseInt(parts[3].trim()),
                    Integer.parseInt(parts[4].trim()),
                    Integer.parseInt(parts[5].trim()),
                    Integer.parseInt(parts[6].trim()));
        } catch (NumberFormatException ex) {
            return null;
        }
    }

    @Override
    public boolean equals(Object o) {
        if (this == o) {
            return true;
        }
        if (!(o instanceof Cuboid other)) {
            return false;
        }
        return minX == other.minX && minY == other.minY && minZ == other.minZ
                && maxX == other.maxX && maxY == other.maxY && maxZ == other.maxZ
                && worldName.equals(other.worldName);
    }

    @Override
    public int hashCode() {
        return Objects.hash(worldName, minX, minY, minZ, maxX, maxY, maxZ);
    }

    @Override
    public String toString() {
        return serialize();
    }
}
