package com.sn.lib;

import java.util.HashSet;
import java.util.Set;

import org.junit.jupiter.api.Test;

import com.sn.lib.region.Cuboid;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class CuboidTest {

    @Test
    void normalizesCornersOnConstruction() {
        Cuboid cuboid = Cuboid.of("w", 5, 9, 5, 1, 1, 1);
        assertEquals(1, cuboid.minX());
        assertEquals(1, cuboid.minY());
        assertEquals(1, cuboid.minZ());
        assertEquals(5, cuboid.maxX());
        assertEquals(9, cuboid.maxY());
        assertEquals(5, cuboid.maxZ());
    }

    @Test
    void containsIsEdgeInclusive() {
        Cuboid cuboid = Cuboid.of("w", 0, 0, 0, 4, 6, 8);
        int[][] corners = {
                {0, 0, 0}, {4, 0, 0}, {0, 6, 0}, {0, 0, 8},
                {4, 6, 0}, {4, 0, 8}, {0, 6, 8}, {4, 6, 8}
        };
        for (int[] corner : corners) {
            assertTrue(cuboid.contains(corner[0], corner[1], corner[2]),
                    "corner " + corner[0] + "," + corner[1] + "," + corner[2]);
        }
        int[][] faceCenters = {
                {0, 3, 4}, {4, 3, 4}, {2, 0, 4}, {2, 6, 4}, {2, 3, 0}, {2, 3, 8}
        };
        for (int[] face : faceCenters) {
            assertTrue(cuboid.contains(face[0], face[1], face[2]),
                    "face center " + face[0] + "," + face[1] + "," + face[2]);
        }
    }

    @Test
    void containsRejectsOutsidePoints() {
        Cuboid cuboid = Cuboid.of("w", 0, 0, 0, 4, 6, 8);
        assertFalse(cuboid.contains(-1, 3, 4));
        assertFalse(cuboid.contains(5, 3, 4));
        assertFalse(cuboid.contains(2, -1, 4));
        assertFalse(cuboid.contains(2, 7, 4));
        assertFalse(cuboid.contains(2, 3, -1));
        assertFalse(cuboid.contains(2, 3, 9));
    }

    @Test
    void containsIsWorldAware() {
        Cuboid cuboid = Cuboid.of("w", 0, 0, 0, 4, 4, 4);
        assertTrue(cuboid.contains("w", 2, 2, 2));
        assertFalse(cuboid.contains("otherworld", 2, 2, 2));
        assertFalse(cuboid.contains((String) null, 2, 2, 2));
    }

    @Test
    void sizeMatchesInclusiveVolume() {
        assertEquals(24L, Cuboid.of("w", 0, 0, 0, 1, 2, 3).size());
        assertEquals(1L, Cuboid.of("w", 7, 7, 7, 7, 7, 7).size());
        Cuboid huge = Cuboid.of("w", 0, 0, 0, 2_000_000, 300, 2_000_000);
        assertEquals(2_000_001L * 301L * 2_000_001L, huge.size());
        assertTrue(huge.size() > Integer.MAX_VALUE);
    }

    @Test
    void forEachVisitsEveryBlockExactlyOnce() {
        Cuboid cuboid = Cuboid.of("w", 0, 0, 0, 2, 2, 2);
        Set<String> visited = new HashSet<>();
        cuboid.forEach((x, y, z) -> {
            assertTrue(cuboid.contains(x, y, z), "visited outside: " + x + "," + y + "," + z);
            assertTrue(visited.add(x + "," + y + "," + z), "visited twice: " + x + "," + y + "," + z);
        });
        assertEquals(27, visited.size());
    }

    @Test
    void forEachOnSingleBlockVisitsOne() {
        Set<String> visited = new HashSet<>();
        Cuboid.of("w", 3, 3, 3, 3, 3, 3).forEach((x, y, z) -> visited.add(x + "," + y + "," + z));
        assertEquals(Set.of("3,3,3"), visited);
    }

    @Test
    void intersectsDetectsOverlapTouchingAndDisjoint() {
        Cuboid base = Cuboid.of("w", 0, 0, 0, 4, 4, 4);
        Cuboid overlapping = Cuboid.of("w", 2, 2, 2, 6, 6, 6);
        assertTrue(base.intersects(overlapping));
        assertTrue(overlapping.intersects(base));
        Cuboid touching = Cuboid.of("w", 4, 0, 0, 8, 4, 4);
        assertTrue(base.intersects(touching));
        Cuboid disjoint = Cuboid.of("w", 6, 0, 0, 8, 4, 4);
        assertFalse(base.intersects(disjoint));
        Cuboid otherWorld = Cuboid.of("other", 0, 0, 0, 4, 4, 4);
        assertFalse(base.intersects(otherWorld));
    }

    @Test
    void expandGrowsBothDirectionsAndClampsCollapse() {
        Cuboid base = Cuboid.of("w", 0, 0, 0, 4, 4, 4);
        Cuboid grown = base.expand(1, 0, 2);
        assertEquals(-1, grown.minX());
        assertEquals(5, grown.maxX());
        assertEquals(0, grown.minY());
        assertEquals(4, grown.maxY());
        assertEquals(-2, grown.minZ());
        assertEquals(6, grown.maxZ());
        Cuboid collapsed = assertDoesNotThrow(() -> base.expand(-10, 0, 0));
        assertEquals(1, collapsed.widthX());
        assertEquals(collapsed.minX(), collapsed.maxX());
        assertEquals(5, collapsed.heightY());
        assertEquals(Cuboid.of("w", 0, 0, 0, 4, 4, 4), base);
    }

    @Test
    void serializeUsesNormalizedMinMaxOrder() {
        assertEquals("w;1;1;1;5;9;5", Cuboid.of("w", 5, 9, 5, 1, 1, 1).serialize());
    }

    @Test
    void serializeDeserializeRoundTripPreservesEquality() {
        Cuboid original = Cuboid.of("world_nether", -3, 8, 120, 44, -2, 9);
        Cuboid copy = Cuboid.deserialize(original.serialize());
        assertNotNull(copy);
        assertEquals(original, copy);
        assertEquals(original.hashCode(), copy.hashCode());
    }

    @Test
    void deserializeTrimsPartsLikeLocationSerializer() {
        Cuboid cuboid = Cuboid.deserialize("w ; 1 ; 2 ; 3 ; 4 ; 5 ; 6");
        assertNotNull(cuboid);
        assertEquals("w", cuboid.worldName());
        assertEquals(1, cuboid.minX());
        assertEquals(2, cuboid.minY());
        assertEquals(3, cuboid.minZ());
        assertEquals(4, cuboid.maxX());
        assertEquals(5, cuboid.maxY());
        assertEquals(6, cuboid.maxZ());
    }

    @Test
    void deserializeReturnsNullOnMalformedInput() {
        assertNull(assertDoesNotThrow(() -> Cuboid.deserialize(null)));
        assertNull(assertDoesNotThrow(() -> Cuboid.deserialize("")));
        assertNull(assertDoesNotThrow(() -> Cuboid.deserialize("w;1;2;3;4;5")));
        assertNull(assertDoesNotThrow(() -> Cuboid.deserialize("w;1;2;3;4;5;6;7")));
        assertNull(assertDoesNotThrow(() -> Cuboid.deserialize("w;1;2;3;4;x;6")));
        assertNull(assertDoesNotThrow(() -> Cuboid.deserialize(";1;2;3;4;5;6")));
    }

    @Test
    void deserializeRenormalizesSwappedCorners() {
        Cuboid cuboid = Cuboid.deserialize("w;5;5;5;1;1;1");
        assertNotNull(cuboid);
        assertEquals(1, cuboid.minX());
        assertEquals(1, cuboid.minY());
        assertEquals(1, cuboid.minZ());
        assertEquals(5, cuboid.maxX());
        assertEquals(5, cuboid.maxY());
        assertEquals(5, cuboid.maxZ());
    }
}
