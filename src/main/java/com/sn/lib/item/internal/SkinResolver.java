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
 * not refetch, a bounded negative cache so a genuinely unresolvable owner is not hammered,
 * and an in-flight guard so concurrent renders of the same owner trigger a single fetch.
 * The only remaining WARN is for a genuinely unresolvable owner (no such player, or no
 * skin), logged once per owner and capped per server run.</p>
 *
 * <p>Mojang is asked about Mojang accounts only. Every account UUID is a random (version 4)
 * UUID; an offline-mode UUID is name-based (version 3) and can never be resolved, so
 * {@link #mojangResolvable(UUID)} keeps such an owner away from the network altogether (it
 * keeps whatever the server already knows about it, normally the default head) instead of
 * burning a lookup, a rate-limit slot and a stack trace per render. Server-wide statics
 * justified by contract note (a) of
 * {@code SnLib}: skin data is content-addressed and identical for every consumer;
 * {@link #clearCache()} runs on the SnLib plugin teardown.</p>
 */
public final class SkinResolver {

    private static final long POSITIVE_TTL_MILLIS = Duration.ofMinutes(30).toMillis();
    private static final long NEGATIVE_TTL_MILLIS = Duration.ofMinutes(10).toMillis();
    private static final int CACHE_CAP = 512;
    /**
     * A failed owner costs a key and a long; the bound only has to outlast the owners a
     * server renders in rotation, or the entries evict each other and every render fetches.
     */
    private static final int NEGATIVE_CAP = 4096;
    /** Distinct unresolvable owners that get their own WARN line per server run. */
    private static final int UNRESOLVED_WARN_CAP = 32;

    private static final SkinCache<PlayerProfile> CACHE = new SkinCache<>(
            POSITIVE_TTL_MILLIS, NEGATIVE_TTL_MILLIS, CACHE_CAP, NEGATIVE_CAP,
            System::currentTimeMillis);

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();
    private static final Set<String> UNRESOLVED_WARNED = ConcurrentHashMap.newKeySet();

    private static final int WARN_NONE = 0;
    private static final int WARN_OWNER = 1;
    private static final int WARN_LAST = 2;

    private SkinResolver() {
    }

    /**
     * Canonical cache key of an owner: a UUID normalizes to its lowercase string form, any
     * other value to its lowercase trimmed form, so the same player keyed by UUID or by
     * name (any case) shares one cache slot. Null or blank yields null.
     *
     * <p>Runs on every head render, so the common shapes allocate nothing: a name has no
     * dash and a UUID is never parsed when it already is in canonical lowercase form.</p>
     */
    public static @Nullable String normalizeKey(String owner) {
        if (owner == null) {
            return null;
        }
        String trimmed = owner.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.indexOf('-') < 0) {
            // UUID.fromString needs its four dashes, so this is a name.
            return trimmed.toLowerCase(Locale.ROOT);
        }
        if (isCanonicalUuid(trimmed)) {
            return trimmed;
        }
        try {
            return UUID.fromString(trimmed).toString();
        } catch (IllegalArgumentException notAUuid) {
            return trimmed.toLowerCase(Locale.ROOT);
        }
    }

    /** True for the lowercase 36-character text form of a UUID, what {@code UUID.toString()} prints. */
    private static boolean isCanonicalUuid(String text) {
        if (text.length() != 36) {
            return false;
        }
        for (int i = 0; i < 36; i++) {
            char c = text.charAt(i);
            if (i == 8 || i == 13 || i == 18 || i == 23) {
                if (c != '-') {
                    return false;
                }
            } else if ((c < '0' || c > '9') && (c < 'a' || c > 'f')) {
                return false;
            }
        }
        return true;
    }

    /**
     * Whether Mojang can know this account: only a random (version 4) UUID can. A
     * name-based (version 3) UUID is an offline-mode identity that no session server
     * resolves, so asking is a wasted lookup that also feeds the rate limit.
     */
    public static boolean mojangResolvable(UUID id) {
        return id != null && id.version() == 4;
    }

    /**
     * Whether a fetch for {@code owner} may reach Mojang at all: a name always may (the
     * server cannot tell what it is without asking), a UUID only when
     * {@link #mojangResolvable(UUID)} holds. Allocation free for a canonical key.
     */
    static boolean mojangResolvableOwner(@Nullable String owner) {
        String key = normalizeKey(owner);
        if (key == null) {
            return false;
        }
        if (isCanonicalUuid(key)) {
            // The version is the first digit of the third group of the canonical text.
            return key.charAt(14) == '4';
        }
        return true;
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
     * in flight, or inside its negative window, and never for an owner UUID that is not a
     * Mojang account (an offline-mode, version 3 UUID). Intended for the GUI path, where
     * {@code onLanded} re-renders the affected slot.
     */
    public static void request(Sn ctx, String owner, Runnable onLanded) {
        String key = normalizeKey(owner);
        if (key == null || !mojangResolvableOwner(key) || !CACHE.beginFetch(key)) {
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
        UNRESOLVED_WARNED.clear();
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
        warnUnresolved(key, owner);
    }

    /**
     * One WARN line per owner per server run, and at most {@value #UNRESOLVED_WARN_CAP}
     * distinct owners: past that, one closing line says the rest is silent, so a server
     * full of unresolvable heads cannot flood its log.
     */
    private static void warnUnresolved(String key, String owner) {
        int verdict = unresolvedVerdict(UNRESOLVED_WARNED, UNRESOLVED_WARN_CAP, key);
        if (verdict == WARN_OWNER) {
            Bukkit.getLogger().warning("[SnLib] skull-owner '" + owner + "' could not be resolved to a "
                    + "textured profile (unknown player or no skin); keeping the default head");
        } else if (verdict == WARN_LAST) {
            Bukkit.getLogger().warning("[SnLib] skull-owner '" + owner + "' could not be resolved "
                    + "either; further unresolvable skull-owners are not logged (default heads are kept)");
        }
    }

    /**
     * Pure decision of {@link #warnUnresolved}: {@code WARN_OWNER} for a first sighting
     * below the cap, {@code WARN_LAST} for the sighting that fills it, {@code WARN_NONE} for
     * a repeat or anything past the cap. {@code seen} never grows past {@code cap}.
     */
    static int unresolvedVerdict(Set<String> seen, int cap, String key) {
        if (seen.size() >= cap || !seen.add(key)) {
            return WARN_NONE;
        }
        return seen.size() >= cap ? WARN_LAST : WARN_OWNER;
    }

    private static void warnOnce(String tag, String message) {
        if (WARNED.add(tag)) {
            Bukkit.getLogger().warning("[SnLib] " + message);
        }
    }
}
