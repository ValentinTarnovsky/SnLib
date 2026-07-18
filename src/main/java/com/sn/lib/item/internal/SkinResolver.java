package com.sn.lib.item.internal;

import java.time.Duration;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

import com.destroystokyo.paper.profile.PlayerProfile;

import com.sn.lib.Sn;
import com.sn.lib.SnLibPlugin;

/**
 * Off-thread skin resolution for player heads: fills in the texture of a {@code skull-owner}
 * whose profile the server does not have cached, then re-applies the textured head.
 *
 * <p>The synchronous path of {@code SnItem} stays non-blocking (UUID via
 * {@code getOfflinePlayer(UUID)}, name via {@code getOfflinePlayerIfCached}); this is the
 * ASYNC completion it was missing. When a head has no cached textured profile,
 * {@link #request} builds a base {@link PlayerProfile} on the calling thread and calls
 * {@link PlayerProfile#update()} (the Paper network fetch runs on Paper's own executor -
 * never HTTP on the calling thread) and, when it lands, hops to the main thread to store
 * the textured profile and invoke the caller's re-render callback.</p>
 *
 * <p>Results live in a shared {@link SkinCache}: a positive TTL so repeated GUI opens do
 * not refetch, a short negative TTL so a genuinely unresolvable owner is not hammered, and
 * an in-flight guard so concurrent renders of the same owner trigger a single fetch. The
 * only remaining WARN is for a genuinely unresolvable owner (no such player, or no skin),
 * logged once per owner. Server-wide statics justified by contract note (a) of
 * {@code SnLib}: skin data is content-addressed and identical for every consumer;
 * {@link #clearCache()} runs on the SnLib plugin teardown.</p>
 */
public final class SkinResolver {

    private static final long POSITIVE_TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final long NEGATIVE_TTL_MILLIS = Duration.ofMinutes(2).toMillis();
    private static final int CACHE_CAP = 512;

    private static final SkinCache<PlayerProfile> CACHE = new SkinCache<>(
            POSITIVE_TTL_MILLIS, NEGATIVE_TTL_MILLIS, CACHE_CAP, System::currentTimeMillis);

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private SkinResolver() {
    }

    /**
     * Canonical cache key of an owner: a UUID normalizes to its lowercase string form, any
     * other value to its lowercase trimmed form, so the same player keyed by UUID or by
     * name (any case) shares one cache slot. Null or blank yields null.
     */
    public static @Nullable String normalizeKey(String owner) {
        if (owner == null) {
            return null;
        }
        String trimmed = owner.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        try {
            return UUID.fromString(trimmed).toString();
        } catch (IllegalArgumentException notAUuid) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
    }

    /**
     * A cached textured profile for {@code owner}, or null on a miss. Cheap cache read,
     * safe on any thread; consumed by {@code SnItem.build()} so every consumer (GUI or a
     * direct build) shows the texture once the shared cache holds it.
     */
    public static @Nullable PlayerProfile cachedProfile(String owner) {
        String key = normalizeKey(owner);
        return key == null ? null : CACHE.get(key);
    }

    /**
     * Requests an off-thread texture fetch for {@code owner} owned by {@code ctx}'s
     * scheduler and runs {@code onLanded} on the main thread when a textured profile lands.
     * Gated by the shared cache: nothing happens when the key is already cached, already
     * in flight, or inside its negative window. Intended for the GUI path, where
     * {@code onLanded} re-renders the affected slot.
     */
    public static void request(Sn ctx, String owner, Runnable onLanded) {
        String key = normalizeKey(owner);
        if (key == null || !CACHE.beginFetch(key)) {
            return;
        }
        PlayerProfile base = baseProfile(owner);
        if (base == null) {
            // Unbuildable base (invalid owner): treat as unresolvable so we do not retry it
            // every render; onLanded is not run.
            land(key, owner, null, onLanded);
            return;
        }
        CompletableFuture<PlayerProfile> fetch;
        try {
            fetch = base.update();
        } catch (Throwable updateUnavailable) {
            land(key, owner, null, onLanded);
            return;
        }
        fetch.whenComplete((updated, error) ->
                hop(ctx, key, owner, error == null ? updated : null, onLanded));
    }

    /**
     * Fire-and-forget warm-up for a direct (non-GUI) build: schedules the fetch through
     * SnLib's own context so the NEXT build of the same owner shows the texture, with no
     * re-render. A no-op when SnLib is disabled or the call is off the main thread (a
     * consumer building off-thread hops back on its own).
     */
    public static void requestSelf(String owner) {
        if (normalizeKey(owner) == null || !Bukkit.isPrimaryThread()) {
            return;
        }
        try {
            Sn self = SnLibPlugin.get().selfContext();
            if (self != null) {
                request(self, owner, () -> {
                });
            }
        } catch (IllegalStateException snLibDisabled) {
            // SnLib not enabled: direct builds simply show the best cached texture.
        }
    }

    /** Empties the shared cache and the WARN dedupe; called by the SnLib plugin on disable. */
    public static void clearCache() {
        CACHE.clear();
        WARNED.clear();
    }

    /**
     * Base profile to update: {@code Bukkit.createProfile(UUID)} for a UUID owner, else a
     * name-only {@code Bukkit.createProfile(name)} that {@link PlayerProfile#update()} then
     * fills in. Any failure yields null.
     */
    private static @Nullable PlayerProfile baseProfile(String owner) {
        String trimmed = owner.trim();
        try {
            return Bukkit.createProfile(UUID.fromString(trimmed));
        } catch (IllegalArgumentException notAUuid) {
            try {
                return Bukkit.createProfile(trimmed);
            } catch (Throwable invalidName) {
                return null;
            }
        }
    }

    /**
     * Hops the fetch completion (running on Paper's executor) onto the owner's main thread
     * to touch the cache and GUI. A disabled owner during scheduling just releases the
     * in-flight mark; nothing else can run safely then.
     */
    private static void hop(Sn ctx, String key, String owner,
                            @Nullable PlayerProfile profile, Runnable onLanded) {
        try {
            ctx.scheduler().sync(() -> land(key, owner, profile, onLanded));
        } catch (Throwable schedulingFailed) {
            CACHE.endFetch(key);
        }
    }

    /**
     * Main-thread landing: releases the in-flight mark, then either caches the textured
     * profile and re-renders, or negative-caches the failure and WARNs once. A re-render
     * callback that throws is swallowed with one WARN so it never breaks a Bukkit task.
     */
    private static void land(String key, String owner,
                             @Nullable PlayerProfile profile, Runnable onLanded) {
        CACHE.endFetch(key);
        if (profile != null && profile.hasTextures()) {
            CACHE.put(key, profile);
            try {
                onLanded.run();
            } catch (Throwable rerenderFailed) {
                warnOnce("skin-rerender", "A skin re-render callback failed (" + rerenderFailed
                        + "); the texture is cached and will show on the next render");
            }
            return;
        }
        CACHE.putNegative(key);
        warnOnce("skull-owner:" + owner, "skull-owner '" + owner + "' could not be resolved to a "
                + "textured profile (unknown player or no skin); keeping the default head");
    }

    private static void warnOnce(String tag, String message) {
        if (WARNED.add(tag)) {
            Bukkit.getLogger().warning("[SnLib] " + message);
        }
    }
}
