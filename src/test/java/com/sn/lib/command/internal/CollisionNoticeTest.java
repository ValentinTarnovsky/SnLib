package com.sn.lib.command.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The collision report of {@link BukkitCommandRegistry}, decided and worded by
 * {@link CollisionNotice} without any Bukkit CommandMap. No server is needed.
 */
class CollisionNoticeTest {

    @Test
    void aStaticRootAlwaysWarns() {
        // dynamic == false keeps the 1.37.0 WARN on every pass, whatever else holds.
        assertEquals(CollisionNotice.WARN, CollisionNotice.of(false, true, false));
        assertEquals(CollisionNotice.WARN, CollisionNotice.of(false, true, true));
        assertEquals(CollisionNotice.WARN, CollisionNotice.of(false, false, false));
        assertEquals(CollisionNotice.WARN, CollisionNotice.of(false, false, true));
    }

    @Test
    void aReachableDynamicRootIsNotedOnceAtInfo() {
        assertEquals(CollisionNotice.INFO, CollisionNotice.of(true, true, false));
        assertEquals(CollisionNotice.NONE, CollisionNotice.of(true, true, true));
    }

    @Test
    void anUnreachableDynamicRootStillWarns() {
        // The namespaced key is someone else's too: the command answers under neither.
        assertEquals(CollisionNotice.WARN, CollisionNotice.of(true, false, false));
        assertEquals(CollisionNotice.WARN, CollisionNotice.of(true, false, true));
    }

    @Test
    void theOccupantIsItsPluginOrAnotherCommand() {
        assertEquals("Essentials", CollisionNotice.occupant("Essentials"));
        assertEquals("another command", CollisionNotice.occupant(null));
        assertEquals("another command", CollisionNotice.occupant(" "));
    }

    @Test
    void rootInfoNamesTheNamespacedForm() {
        assertEquals("Command '/money' of SnDungeons is taken by Essentials; kept it."
                        + " This command answers as /sndungeons:money",
                CollisionNotice.rootInfo("money", "SnDungeons", "Essentials", "sndungeons"));
    }

    @Test
    void rootWarnIsThe137Text() {
        assertEquals("Command '/money' of SnDungeons collides with an existing command;"
                        + " kept the existing one",
                CollisionNotice.rootWarn("money", "SnDungeons"));
    }

    @Test
    void aliasInfoNamesTheNamespacedAlias() {
        assertEquals("Alias '/bal' of '/money' in SnDungeons is taken by another command;"
                        + " kept it. This alias answers as /sndungeons:bal",
                CollisionNotice.aliasInfo("bal", "money", "SnDungeons",
                        CollisionNotice.occupant(null), "sndungeons"));
    }

    @Test
    void withoutPriorityNothingIsTaken() {
        // No commandPriority(): every occupant keeps its key, exactly as in 1.38.0.
        for (CollisionNotice.Occupant occupant : CollisionNotice.Occupant.values()) {
            assertFalse(CollisionNotice.takesOver(false, occupant), occupant.name());
        }
    }

    @Test
    void priorityTakesOnlyFromOtherPluginsAndPlainSnLibRoots() {
        assertFalse(CollisionNotice.takesOver(true, CollisionNotice.Occupant.OWN));
        assertFalse(CollisionNotice.takesOver(true, CollisionNotice.Occupant.SERVER));
        assertTrue(CollisionNotice.takesOver(true, CollisionNotice.Occupant.PLUGIN));
        assertTrue(CollisionNotice.takesOver(true, CollisionNotice.Occupant.SNLIB_ROOT));
        // A plugin with priority keeps the key it holds: no trading it back and forth.
        assertFalse(CollisionNotice.takesOver(true,
                CollisionNotice.Occupant.SNLIB_PRIORITY_ROOT));
    }

    @Test
    void rootTakeoverNamesTheNamespacedFormOfTheDisplacedCommand() {
        assertEquals(
                "Command '/money' of SnDungeons took the name from Essentials (command priority); Essentials still answers as /essentials:money",
                CollisionNotice.rootTakeover("money", "SnDungeons", "Essentials",
                        "essentials"));
    }

    @Test
    void rootTakeoverEndsAtThePriorityWhenTheDisplacedCommandHasNoNamespacedForm() {
        assertEquals(
                "Command '/money' of SnDungeons took the name from Essentials (command priority)",
                CollisionNotice.rootTakeover("money", "SnDungeons", "Essentials", null));
    }

    @Test
    void aliasTakeoverNamesTheNamespacedAliasOfTheDisplacedCommand() {
        assertEquals(
                "Alias '/bal' of '/money' in SnDungeons took the name from Essentials (command priority); Essentials still answers as /essentials:bal",
                CollisionNotice.aliasTakeover("bal", "money", "SnDungeons", "Essentials",
                        "essentials"));
    }

    @Test
    void aliasTakeoverEndsAtThePriorityWhenTheDisplacedCommandHasNoNamespacedForm() {
        assertEquals(
                "Alias '/bal' of '/money' in SnDungeons took the name from Essentials (command priority)",
                CollisionNotice.aliasTakeover("bal", "money", "SnDungeons", "Essentials",
                        null));
    }
}
