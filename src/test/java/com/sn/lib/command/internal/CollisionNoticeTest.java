package com.sn.lib.command.internal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

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
}
