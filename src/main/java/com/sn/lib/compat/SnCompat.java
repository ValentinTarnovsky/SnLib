package com.sn.lib.compat;

import java.lang.reflect.Method;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.jetbrains.annotations.Nullable;

/**
 * Feature probing for API added after the 1.20.4 runtime floor.
 *
 * <p>Every use of Paper/Adventure API newer than 1.20.4 must go through {@link #probe} or
 * {@link #since} so older servers degrade with a single WARN instead of throwing. Known
 * version-sensitive points: {@code ItemMeta#setMaxStackSize} and
 * {@code ItemMeta#setEnchantmentGlintOverride} (1.20.5+), and
 * {@code ItemFlag.HIDE_ADDITIONAL_TOOLTIP} (alias of the legacy
 * {@code HIDE_POTION_EFFECTS}).</p>
 *
 * <p>Server-wide statics allowed by the SnLib contract: probe results describe the server,
 * not a consumer.</p>
 */
public final class SnCompat {

    private static final Map<String, Method> CACHE = new ConcurrentHashMap<>();

    /**
     * Keys probed and not found on this server. A {@link ConcurrentHashMap} cannot hold
     * null values, so the cached miss sentinel lives in this set.
     */
    private static final Set<String> MISSING = ConcurrentHashMap.newKeySet();

    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private SnCompat() {
    }

    /**
     * Reflectively looks up a public method, once, caching hit and miss. The cache key
     * includes the parameter types, so two overloads of the same method name never
     * collide in the cache.
     *
     * <p>Classloader guard: only server API and JDK classes are probeable. If {@code owner}
     * was loaded by a PluginClassLoader other than SnLib's own, this warns once and returns
     * null WITHOUT caching: a {@link Method} retains its declaring {@link Class} and
     * therefore its PluginClassLoader, so caching it would leak the consumer's classloader
     * across reloads.</p>
     *
     * @param owner  server API or JDK class declaring the method
     * @param name   public method name
     * @param params parameter types
     * @return the method, or null when missing on this server (one WARN, miss cached)
     */
    public static @Nullable Method probe(Class<?> owner, String name, Class<?>... params) {
        if (isForeignPluginClass(owner)) {
            warnOnce("loader:" + owner.getName(), "probe of " + owner.getName()
                    + " rejected: class loaded by a foreign PluginClassLoader;"
                    + " only server API/JDK classes are allowed");
            return null;
        }
        StringBuilder sig = new StringBuilder(owner.getName()).append('#').append(name).append('(');
        for (int i = 0; i < params.length; i++) {
            if (i > 0) {
                sig.append(',');
            }
            sig.append(params[i].getName());
        }
        String key = sig.append(')').toString();
        Method cached = CACHE.get(key);
        if (cached != null) {
            return cached;
        }
        if (MISSING.contains(key)) {
            return null;
        }
        try {
            Method method = owner.getMethod(name, params);
            CACHE.put(key, method);
            return method;
        } catch (NoSuchMethodException e) {
            MISSING.add(key);
            warnOnce(key, name + " requires a newer MC; degrading");
            return null;
        }
    }

    /**
     * Version gate: {@code modern} when the server supports 1.{@code minor}+, otherwise
     * {@code fallback} with one WARN per call site.
     */
    public static <T> T since(int minor, Supplier<T> modern, Supplier<T> fallback) {
        if (SnVersion.supports(minor)) {
            return modern.get();
        }
        warnOnce("since:" + callSiteTag(), "1." + minor + "+ API not available on "
                + SnVersion.MAJOR + "." + SnVersion.MINOR + "." + SnVersion.PATCH
                + "; using fallback");
        return fallback.get();
    }

    /**
     * Name-based check covering both {@code org.bukkit.plugin.java.PluginClassLoader} and
     * Paper's {@code PaperPluginClassLoader} without referencing internal server API.
     */
    private static boolean isForeignPluginClass(Class<?> owner) {
        ClassLoader loader = owner.getClassLoader();
        if (loader == null || loader == SnCompat.class.getClassLoader()) {
            return false;
        }
        for (Class<?> type = loader.getClass(); type != null; type = type.getSuperclass()) {
            if (type.getName().endsWith("PluginClassLoader")) {
                return true;
            }
        }
        return false;
    }

    private static String callSiteTag() {
        return StackWalker.getInstance().walk(frames -> frames
                .filter(frame -> !frame.getClassName().equals(SnCompat.class.getName()))
                .findFirst()
                .map(frame -> frame.getClassName() + "#" + frame.getMethodName()
                        + ":" + frame.getLineNumber())
                .orElse("unknown"));
    }

    private static void warnOnce(String tag, String message) {
        if (WARNED.add(tag)) {
            Bukkit.getLogger().warning("[SnLib] " + message);
        }
    }
}
