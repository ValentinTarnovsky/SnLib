package com.sn.lib.item.internal;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

/**
 * Pure key-canonicalization units for {@link SkinResolver#normalizeKey}: the same player
 * keyed by UUID or by name (any case, any surrounding whitespace) must collapse to one
 * cache slot so the async resolver never fetches or stores the same owner twice. Touches
 * no Bukkit type.
 */
class SkinResolverKeyTest {

    @Test
    void nullAndBlankYieldNoKey() {
        assertNull(SkinResolver.normalizeKey(null));
        assertNull(SkinResolver.normalizeKey(""));
        assertNull(SkinResolver.normalizeKey("   "));
    }

    @Test
    void nameLowercasesAndTrims() {
        assertEquals("notch", SkinResolver.normalizeKey("Notch"));
        assertEquals("notch", SkinResolver.normalizeKey("  NOTCH  "));
    }

    @Test
    void differentCaseNamesShareOneKey() {
        assertEquals(SkinResolver.normalizeKey("Steve"), SkinResolver.normalizeKey("sTeVe"));
    }

    @Test
    void uuidNormalizesToCanonicalLowercaseString() {
        UUID id = UUID.randomUUID();
        String upper = id.toString().toUpperCase(java.util.Locale.ROOT);
        assertEquals(id.toString(), SkinResolver.normalizeKey(upper),
                "a mixed-case UUID collapses to its canonical lowercase form");
    }

    @Test
    void uuidAndItsTrimmedFormShareOneKey() {
        UUID id = UUID.randomUUID();
        assertEquals(SkinResolver.normalizeKey(id.toString()),
                SkinResolver.normalizeKey("  " + id + "  "));
    }

    @Test
    void nameThatIsNotAUuidStaysAName() {
        assertEquals("player_1", SkinResolver.normalizeKey("Player_1"));
    }
}
