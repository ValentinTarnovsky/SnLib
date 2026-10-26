package com.sn.lib.item.internal;

import java.lang.reflect.Proxy;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.destroystokyo.paper.profile.PlayerProfile;

import com.sn.lib.item.internal.SkullOwnerPolicy.Plan;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure units for the rule that keeps an owner WITHOUT textures off a skull meta (Paper starts
 * an uncached Mojang lookup per {@code setItemMeta} for such a meta). Touch no server.
 */
class SkullOwnerPolicyTest {

    private static PlayerProfile profile(boolean textured) {
        return (PlayerProfile) Proxy.newProxyInstance(PlayerProfile.class.getClassLoader(),
                new Class<?>[] {PlayerProfile.class},
                (proxy, method, args) -> "hasTextures".equals(method.getName()) ? textured : null);
    }

    private static UUID offlineUuid(String name) {
        return UUID.nameUUIDFromBytes(("OfflinePlayer:" + name)
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
    }

    @Test
    void profileCountsAsTexturedOnlyWhenItCarriesTextures() {
        assertTrue(SkullOwnerPolicy.hasTextures(profile(true)));
        assertFalse(SkullOwnerPolicy.hasTextures(profile(false)));
        assertFalse(SkullOwnerPolicy.hasTextures(null));
    }

    @Test
    void cachedTexturedProfileIsAppliedFirst() {
        assertEquals(Plan.APPLY_CACHED, SkullOwnerPolicy.decide(true, false, false, false));
        assertEquals(Plan.APPLY_CACHED, SkullOwnerPolicy.decide(true, true, true, true));
    }

    @Test
    void ownerOfTheServerIsAppliedOnlyWithTextures() {
        assertEquals(Plan.APPLY_OWN, SkullOwnerPolicy.decide(false, true, true, true));
        // An offline-mode player whose profile does carry a skin (skin restorer) is applied.
        assertEquals(Plan.APPLY_OWN, SkullOwnerPolicy.decide(false, true, true, false));
    }

    @Test
    void untexturedOwnerNeverReachesTheMetaAndOfflineIdentityNeverFetches() {
        Plan offline = SkullOwnerPolicy.decide(false, false, true,
                SkinResolver.mojangResolvable(offlineUuid("Bot_0042")));
        assertEquals(Plan.DEFAULT_ONLY, offline);
    }

    @Test
    void untexturedMojangOwnerGetsDefaultHeadAndOneFetch() {
        Plan known = SkullOwnerPolicy.decide(false, false, true,
                SkinResolver.mojangResolvable(UUID.randomUUID()));
        assertEquals(Plan.DEFAULT_AND_FETCH, known);
        // A name the server has not cached: unknown identity, Mojang is asked once.
        assertEquals(Plan.DEFAULT_AND_FETCH, SkullOwnerPolicy.decide(false, false, false, false));
    }

    @Test
    void noPlanAppliesAnOwnerWithoutTextures() {
        for (boolean cached : new boolean[] {false, true}) {
            for (boolean own : new boolean[] {false, true}) {
                for (boolean known : new boolean[] {false, true}) {
                    for (boolean mojang : new boolean[] {false, true}) {
                        Plan plan = SkullOwnerPolicy.decide(cached, own, known, mojang);
                        boolean applies = plan == Plan.APPLY_CACHED || plan == Plan.APPLY_OWN;
                        assertEquals(cached || own, applies,
                                "owner applied only with textures: " + cached + "/" + own
                                        + "/" + known + "/" + mojang);
                    }
                }
            }
        }
    }
}
