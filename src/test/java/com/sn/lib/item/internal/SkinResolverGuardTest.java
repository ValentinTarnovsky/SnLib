package com.sn.lib.item.internal;

import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure units for the guards that keep {@link SkinResolver} away from Mojang for identities
 * Mojang cannot know (offline-mode, name-based UUIDs) and that bound its WARN output. Touch
 * no Bukkit type: a request that reaches the network would need a live server and throw.
 */
class SkinResolverGuardTest {

    /** The UUID an offline-mode server derives for a name, version 3. */
    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name).getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void offlineUuidIsVersionThreeAndNotResolvable() {
        UUID offline = offlineUuid("Bot_0042");
        assertEquals(3, offline.version());
        assertFalse(SkinResolver.mojangResolvable(offline));
    }

    @Test
    void randomUuidIsResolvable() {
        assertTrue(SkinResolver.mojangResolvable(UUID.randomUUID()));
        assertFalse(SkinResolver.mojangResolvable(null));
    }

    @Test
    void ownerGuardRejectsOfflineUuidInAnyTextForm() {
        UUID offline = offlineUuid("Bot_0042");
        assertFalse(SkinResolver.mojangResolvableOwner(offline.toString()));
        assertFalse(SkinResolver.mojangResolvableOwner(offline.toString().toUpperCase(java.util.Locale.ROOT)));
        assertFalse(SkinResolver.mojangResolvableOwner("  " + offline + "  "));
    }

    @Test
    void ownerGuardAcceptsRandomUuidAndNames() {
        assertTrue(SkinResolver.mojangResolvableOwner(UUID.randomUUID().toString()));
        assertTrue(SkinResolver.mojangResolvableOwner("Notch"));
        assertTrue(SkinResolver.mojangResolvableOwner("Player_1"));
        assertFalse(SkinResolver.mojangResolvableOwner(null));
        assertFalse(SkinResolver.mojangResolvableOwner("   "));
    }

    @Test
    void requestForOfflineUuidNeverStartsAFetch() {
        // A fetch would call Bukkit.createProfile and throw without a server; reaching the
        // end proves the guard returned first. The landing callback must not run either.
        AtomicBoolean landed = new AtomicBoolean();
        String owner = offlineUuid("Bot_0001").toString();
        for (int i = 0; i < 3; i++) {
            assertDoesNotThrow(() -> SkinResolver.request(null, owner, () -> landed.set(true)));
        }
        assertFalse(landed.get());
    }

    @Test
    void canonicalKeysAreReturnedWithoutCopying() {
        String uuid = UUID.randomUUID().toString();
        assertSame(uuid, SkinResolver.normalizeKey(uuid), "a canonical UUID is its own key");
        String name = "notch";
        assertSame(name, SkinResolver.normalizeKey(name), "a lowercase name is its own key");
    }

    @Test
    void nonCanonicalUuidFormsStillCollapseToTheCanonicalKey() {
        UUID id = UUID.randomUUID();
        String canonical = id.toString();
        assertEquals(canonical, SkinResolver.normalizeKey(canonical.toUpperCase(java.util.Locale.ROOT)));
        assertEquals(canonical, SkinResolver.normalizeKey(" " + canonical + " "));
        // Java accepts short group forms; the key is still the canonical text.
        assertEquals("00000001-0002-0003-0004-000000000005", SkinResolver.normalizeKey("1-2-3-4-5"));
    }

    @Test
    void unresolvedWarnIsOncePerOwnerAndCapped() {
        Set<String> seen = new HashSet<>();
        assertEquals(1, SkinResolver.unresolvedVerdict(seen, 3, "a"), "first sighting logs");
        assertEquals(0, SkinResolver.unresolvedVerdict(seen, 3, "a"), "a repeat is silent");
        assertEquals(1, SkinResolver.unresolvedVerdict(seen, 3, "b"));
        assertEquals(2, SkinResolver.unresolvedVerdict(seen, 3, "c"), "the cap-filling sighting closes the log");
        assertEquals(0, SkinResolver.unresolvedVerdict(seen, 3, "d"), "past the cap nothing logs");
        assertEquals(0, SkinResolver.unresolvedVerdict(seen, 3, "c"));
        assertEquals(3, seen.size(), "the dedupe set never grows past the cap");
    }
}
