package com.sn.lib.util;

import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.OfflinePlayer;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.SkullMeta;
import org.jetbrains.annotations.Nullable;

import com.destroystokyo.paper.profile.PlayerProfile;
import com.destroystokyo.paper.profile.ProfileProperty;

/**
 * Builds {@code PLAYER_HEAD} stacks from texture values without NMS.
 *
 * <p>Accepted inputs (see {@link #extractTextureValue}): {@code texture-}/{@code texture:},
 * {@code base64-}/{@code base64:}, {@code basehead-} prefixes, raw base64 payloads
 * ({@code eyJ...}) and http(s) skin URLs (wrapped into the standard textures JSON and
 * base64-encoded).</p>
 *
 * <p>Identical textures share a deterministic profile UUID
 * ({@link UUID#nameUUIDFromBytes}) so the client caches them across heads. Application is
 * multi-tier: Paper {@link PlayerProfile} first, then a reflective legacy GameProfile
 * fallback; if both fail the head stays default and one WARN is logged.</p>
 *
 * <p>Server-wide statics allowed by the SnLib contract: the texture to profile mapping is
 * content-addressed and identical for every consumer. The cache is bounded LRU
 * (cap {@value #CACHE_CAP}); {@link #clearCache()} is invoked by the SnLib plugin
 * teardown.</p>
 */
public final class HeadUtil {

    private static final int CACHE_CAP = 512;

    private static final String[] PREFIXES = {
            "texture-", "texture:", "base64-", "base64:", "basehead-"
    };

    /**
     * Access-order LRU; every access must synchronize on the map itself. Server-wide
     * static justified: bounded cache of texture profiles, not per-consumer data.
     */
    private static final Map<String, PlayerProfile> PROFILE_CACHE =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, PlayerProfile> eldest) {
                    return size() > CACHE_CAP;
                }
            };

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private HeadUtil() {
    }

    /**
     * Creates a {@code PLAYER_HEAD} stack showing the given texture.
     *
     * @param value any input accepted by {@link #extractTextureValue}; null or empty
     *              yields a default head
     * @return a {@code PLAYER_HEAD} stack of amount 1
     */
    public static ItemStack fromBase64(String value) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (value == null || value.isEmpty()) {
            return head;
        }
        if (head.getItemMeta() instanceof SkullMeta skull) {
            applyBase64(skull, value);
            head.setItemMeta(skull);
        }
        return head;
    }

    /**
     * Creates a {@code PLAYER_HEAD} stack showing the given player's skin.
     *
     * <p>No NMS and no HTTP of its own: the skin resolves from the server's profile cache
     * (a transient Steve renders until the profile is cached).</p>
     *
     * @param owner the head owner; null yields a default head
     * @return a {@code PLAYER_HEAD} stack of amount 1
     */
    public static ItemStack fromPlayer(@Nullable OfflinePlayer owner) {
        ItemStack head = new ItemStack(Material.PLAYER_HEAD);
        if (owner == null) {
            return head;
        }
        if (head.getItemMeta() instanceof SkullMeta skull) {
            applyOwner(skull, owner);
            head.setItemMeta(skull);
        }
        return head;
    }

    /**
     * Applies a player owner to a skull meta via {@link SkullMeta#setOwningPlayer}. The
     * skin resolves from the server's profile cache (transient Steve until cached); no NMS
     * and no HTTP of its own. Null meta or null owner is a no-op.
     */
    public static void applyOwner(SkullMeta meta, @Nullable OfflinePlayer owner) {
        if (meta == null || owner == null) {
            return;
        }
        meta.setOwningPlayer(owner);
    }

    /**
     * Applies a fully resolved {@link PlayerProfile} (textures included) to a skull meta
     * via {@link SkullMeta#setPlayerProfile}. Used to re-apply a profile the async skin
     * resolver fetched off-thread. Null meta or null profile is a no-op.
     */
    public static void applyProfile(SkullMeta meta, @Nullable PlayerProfile profile) {
        if (meta == null || profile == null) {
            return;
        }
        meta.setPlayerProfile(profile);
    }

    /**
     * Applies a texture to a skull meta with a deterministic profile UUID derived from the
     * texture bytes. Unparseable values leave the meta untouched with one WARN.
     */
    public static void applyBase64(SkullMeta meta, String value) {
        if (meta == null) {
            return;
        }
        String texture = extractTextureValue(value);
        if (texture == null) {
            warnOnce("texture:" + value, "Invalid head texture '" + abbreviate(value)
                    + "'; keeping the default head");
            return;
        }
        try {
            meta.setPlayerProfile(profileFor(texture));
        } catch (Throwable paperFailure) {
            applyLegacyGameProfile(meta, texture, paperFailure);
        }
    }

    /**
     * Normalizes a raw texture input to its base64 payload.
     *
     * <p>Supported: {@code texture-}/{@code texture:}, {@code base64-}/{@code base64:},
     * {@code basehead-} prefixes (recursively unwrapped), raw {@code eyJ...} base64, and
     * http(s) URLs (encoded into the standard textures JSON).</p>
     *
     * @return the base64 texture value, or null when the input is not a texture
     */
    public static @Nullable String extractTextureValue(String value) {
        if (value == null) {
            return null;
        }
        String trimmed = value.trim();
        if (trimmed.isEmpty()) {
            return null;
        }
        String lower = trimmed.toLowerCase(Locale.ROOT);
        for (String prefix : PREFIXES) {
            if (lower.startsWith(prefix)) {
                String rest = trimmed.substring(prefix.length()).trim();
                if (rest.isEmpty()) {
                    return null;
                }
                String nested = extractTextureValue(rest);
                return nested != null ? nested : rest;
            }
        }
        if (lower.startsWith("http://") || lower.startsWith("https://")) {
            String json = "{\"textures\":{\"SKIN\":{\"url\":\"" + trimmed + "\"}}}";
            return Base64.getEncoder().encodeToString(json.getBytes(StandardCharsets.UTF_8));
        }
        if (trimmed.startsWith("eyJ")) {
            return trimmed;
        }
        return null;
    }

    /** Empties the bounded profile cache; called by the SnLib plugin on disable. */
    public static void clearCache() {
        synchronized (PROFILE_CACHE) {
            PROFILE_CACHE.clear();
        }
    }

    private static PlayerProfile profileFor(String texture) {
        synchronized (PROFILE_CACHE) {
            PlayerProfile cached = PROFILE_CACHE.get(texture);
            if (cached != null) {
                return cached;
            }
        }
        UUID profileId = UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8));
        PlayerProfile profile = Bukkit.createProfile(profileId);
        profile.setProperty(new ProfileProperty("textures", texture));
        synchronized (PROFILE_CACHE) {
            PROFILE_CACHE.put(texture, profile);
        }
        return profile;
    }

    /**
     * Legacy tier: reflective {@code com.mojang.authlib.GameProfile} injected into the
     * meta's {@code profile} field. No compile-time dependency; any failure is swallowed
     * with one WARN so head rendering never crashes a consumer.
     */
    private static void applyLegacyGameProfile(SkullMeta meta, String texture, Throwable cause) {
        try {
            UUID profileId = UUID.nameUUIDFromBytes(texture.getBytes(StandardCharsets.UTF_8));
            Class<?> gameProfileClass = Class.forName("com.mojang.authlib.GameProfile");
            Class<?> propertyClass = Class.forName("com.mojang.authlib.properties.Property");
            Object profile = gameProfileClass.getConstructor(UUID.class, String.class)
                    .newInstance(profileId, "SnLibHead");
            Object property = propertyClass.getConstructor(String.class, String.class)
                    .newInstance("textures", texture);
            Object properties = gameProfileClass.getMethod("getProperties").invoke(profile);
            properties.getClass().getMethod("put", Object.class, Object.class)
                    .invoke(properties, "textures", property);
            Field profileField = meta.getClass().getDeclaredField("profile");
            profileField.setAccessible(true);
            profileField.set(meta, profile);
        } catch (Throwable legacyFailure) {
            warnOnce("head-apply", "Could not apply the head texture (PlayerProfile: "
                    + cause + "; legacy GameProfile: " + legacyFailure
                    + "); keeping the default head");
        }
    }

    private static String abbreviate(String value) {
        if (value == null) {
            return "null";
        }
        return value.length() <= 40 ? value : value.substring(0, 40) + "...";
    }

    private static void warnOnce(String tag, String message) {
        if (WARNED.add(tag)) {
            Bukkit.getLogger().warning("[SnLib] " + message);
        }
    }
}
