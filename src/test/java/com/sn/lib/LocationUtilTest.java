package com.sn.lib;

import org.bukkit.Location;
import org.junit.jupiter.api.Test;

import com.sn.lib.region.Cuboid;
import com.sn.lib.util.LocationUtil;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

/**
 * Covers only the null-safe paths testable without a World: {@code new Location(null, x, y, z)}
 * touches no Bukkit statics and {@code getWorld()} returns null. The world-aware positive
 * paths are covered by delegation to Cuboid (already tested) plus the manual release smoke.
 */
class LocationUtilTest {

    @Test
    void inCuboidNullSafePathsReturnFalse() {
        Location worldless = new Location(null, 1, 1, 1);
        Location cornerA = new Location(null, 0, 0, 0);
        Location cornerB = new Location(null, 5, 5, 5);
        assertDoesNotThrow(() -> {
            assertFalse(LocationUtil.inCuboid(null, cornerA, cornerB));
            assertFalse(LocationUtil.inCuboid(worldless, null, cornerB));
            assertFalse(LocationUtil.inCuboid(worldless, cornerA, null));
            assertFalse(LocationUtil.inCuboid(worldless, cornerA, cornerB));
        });
    }

    @Test
    void distance2dNullSafePathsReturnInfinity() {
        Location a = new Location(null, 0, 0, 0);
        Location b = new Location(null, 3, 0, 4);
        assertDoesNotThrow(() -> {
            assertEquals(Double.POSITIVE_INFINITY, LocationUtil.distance2dSquared(null, b));
            assertEquals(Double.POSITIVE_INFINITY, LocationUtil.distance2dSquared(a, null));
            assertEquals(Double.POSITIVE_INFINITY, LocationUtil.distance2dSquared(a, b));
            assertEquals(Double.POSITIVE_INFINITY, LocationUtil.distance2d(null, b));
            assertEquals(Double.POSITIVE_INFINITY, LocationUtil.distance2d(a, b));
        });
    }

    @Test
    void distanceToBoxNullSafePathsReturnInfinity() {
        Cuboid box = Cuboid.of("w", 0, 0, 0, 4, 4, 4);
        Location worldless = new Location(null, 2, 2, 2);
        assertDoesNotThrow(() -> {
            assertEquals(Double.POSITIVE_INFINITY, LocationUtil.distanceToBoxSquared(null, worldless));
            assertEquals(Double.POSITIVE_INFINITY, LocationUtil.distanceToBoxSquared(box, null));
            assertEquals(Double.POSITIVE_INFINITY, LocationUtil.distanceToBoxSquared(box, worldless));
        });
    }
}
