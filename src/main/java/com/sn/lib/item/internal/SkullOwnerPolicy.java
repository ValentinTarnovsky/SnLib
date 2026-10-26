package com.sn.lib.item.internal;

import org.jetbrains.annotations.Nullable;

import com.destroystokyo.paper.profile.PlayerProfile;

/**
 * Pure decision of what {@code SnItem} puts on a skull meta for a {@code skull-owner}.
 *
 * <p>The rule: <b>an owner is applied only when it carries textures</b>. Paper's
 * {@code CraftMetaSkull.applyToItem} fires an uncached, asynchronous Mojang profile lookup on
 * every {@code setItemMeta} of a {@code PLAYER_HEAD} whose meta has an owner but no textures,
 * so an untextured owner (an offline-mode player without a skin, a name the server has not
 * cached) rebuilt on every menu render costs one lookup per render, outside any cache of
 * ours. Such a head is therefore built as the plain default head with no owner, and an owner
 * Mojang can resolve is fetched once by {@link SkinResolver} (one cached request, then a
 * re-render with the textured profile).</p>
 *
 * <p>Touches no server state, so it is unit tested without a server.</p>
 */
public final class SkullOwnerPolicy {

    /** What to put on the meta and whether to ask Mojang. */
    public enum Plan {
        /** Apply the textured profile of the shared skin cache. */
        APPLY_CACHED,
        /** Apply the textured profile the server already holds for the owner. */
        APPLY_OWN,
        /** Plain default head, no owner, nothing to fetch (Mojang cannot know this owner). */
        DEFAULT_ONLY,
        /** Plain default head, no owner, and one cached off-thread fetch for the skin. */
        DEFAULT_AND_FETCH
    }

    private SkullOwnerPolicy() {
    }

    /** True when the profile exists and carries a textures property. */
    public static boolean hasTextures(@Nullable PlayerProfile profile) {
        return profile != null && profile.hasTextures();
    }

    /**
     * Decides the plan for one owner.
     *
     * @param cachedTextured   the shared skin cache holds a textured profile for the owner
     * @param ownTextured      the profile the server resolved for the owner (an online player's
     *                         own, or a cached one) carries textures
     * @param identityKnown    the server resolved the owner to an identity at all (a player
     *                         object); false for a name it has not cached
     * @param mojangResolvable the identity can be a Mojang account (version 4 UUID); only read
     *                         when {@code identityKnown}
     */
    public static Plan decide(boolean cachedTextured, boolean ownTextured,
                              boolean identityKnown, boolean mojangResolvable) {
        if (cachedTextured) {
            return Plan.APPLY_CACHED;
        }
        if (ownTextured) {
            return Plan.APPLY_OWN;
        }
        if (identityKnown && !mojangResolvable) {
            return Plan.DEFAULT_ONLY;
        }
        return Plan.DEFAULT_AND_FETCH;
    }
}
