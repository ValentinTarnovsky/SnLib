package com.sn.lib.item;

import java.util.HashSet;
import java.util.List;

import org.bukkit.inventory.EquipmentSlot;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class SnItemAttributeParseTest {

    private static void assertContainsAll(List<String> candidates, String... expected) {
        for (String candidate : expected) {
            assertTrue(candidates.contains(candidate),
                    candidates + " should contain " + candidate);
        }
    }

    @Test
    void genericPrefixedNameYieldsModernAndLegacyCandidates() {
        List<String> candidates = SnItem.attributeKeyCandidates("GENERIC_MOVEMENT_SPEED");
        assertContainsAll(candidates,
                "generic_movement_speed", "movement_speed", "generic.movement_speed");
    }

    @Test
    void bareModernNameYieldsLegacyAlias() {
        List<String> candidates = SnItem.attributeKeyCandidates("ARMOR");
        assertContainsAll(candidates, "armor", "generic.armor");
    }

    @Test
    void playerPrefixYieldsDottedForm() {
        List<String> candidates =
                SnItem.attributeKeyCandidates("PLAYER_BLOCK_INTERACTION_RANGE");
        assertContainsAll(candidates,
                "player.block_interaction_range", "block_interaction_range");
    }

    @Test
    void namespacedInputNormalizes() {
        List<String> candidates = SnItem.attributeKeyCandidates("minecraft:generic.armor");
        assertContainsAll(candidates, "generic.armor");
    }

    @Test
    void candidatesHaveNoDuplicates() {
        for (String raw : List.of("GENERIC_MOVEMENT_SPEED", "ARMOR",
                "PLAYER_BLOCK_INTERACTION_RANGE", "minecraft:generic.armor", "zombie_spawn_reinforcements")) {
            List<String> candidates = SnItem.attributeKeyCandidates(raw);
            assertEquals(new HashSet<>(candidates).size(), candidates.size(),
                    "duplicates in candidates for " + raw + ": " + candidates);
        }
    }

    @Test
    void anyArmorAndBodyMapToNull() {
        assertNull(SnItem.legacySlot("ANY"));
        assertNull(SnItem.legacySlot("ARMOR"));
        assertNull(SnItem.legacySlot("BODY"));
        assertNull(SnItem.legacySlot(null));
        assertNull(SnItem.legacySlot("  "));
    }

    @Test
    void mainhandMapsToHand() {
        assertEquals(EquipmentSlot.HAND, SnItem.legacySlot("MAINHAND"));
        assertEquals(EquipmentSlot.HAND, SnItem.legacySlot("mainhand"));
    }

    @Test
    void offhandMapsToOffHand() {
        assertEquals(EquipmentSlot.OFF_HAND, SnItem.legacySlot("OFFHAND"));
    }

    @Test
    void feetMapsDirect() {
        assertEquals(EquipmentSlot.FEET, SnItem.legacySlot("FEET"));
    }
}
