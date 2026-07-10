package com.sn.lib.hook;

import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.tenant.TenantRegistry;

/**
 * Reactive soft-dependency hook keyed by its owning plugin.
 *
 * <p>The hook resolves lazily against the target plugin (present, enabled, semver gate,
 * optional required class) and is activated/deactivated live by {@link HookListener} when
 * the target enables or disables. Every instance is registered under its owner in a
 * per-owner {@link TenantRegistry}, so a consumer disable removes its hooks (and
 * force-disables them through the sweep callback) without touching other consumers.</p>
 *
 * @param <T> hook adapter type produced by the factory
 */
public final class SoftDependency<T> {

    /** Server-wide static justified: the registry itself; contents keyed per owning plugin. */
    private static final TenantRegistry<SoftDependency<?>> REGISTRY =
            new TenantRegistry<>(SoftDependency::forceDisable);

    private final JavaPlugin owner;
    private final String pluginName;
    private final Supplier<T> factory;

    private @Nullable String minVersion;
    private @Nullable String requiredClass;

    private volatile @Nullable T instance;
    private volatile boolean resolved;
    private volatile boolean disabled;

    private SoftDependency(JavaPlugin owner, String pluginName, Supplier<T> factory) {
        this.owner = owner;
        this.pluginName = pluginName;
        this.factory = factory;
    }

    /**
     * Creates a hook owned by {@code owner} against the plugin named {@code pluginName}.
     * The factory must be the only code referencing the target's classes, so a missing
     * target never triggers {@link NoClassDefFoundError} outside the isolated
     * instantiation boundary.
     */
    public static <T> SoftDependency<T> of(JavaPlugin owner, String pluginName, Supplier<T> factory) {
        SoftDependency<T> dependency = new SoftDependency<>(owner, pluginName, factory);
        REGISTRY.add(owner, dependency);
        return dependency;
    }

    /** Applies the action to every registered hook of every owner; HookListener's iteration source. */
    public static void forEachRegistered(Consumer<SoftDependency<?>> action) {
        REGISTRY.forEachOwner((owner, hooks) -> hooks.forEach(action));
    }

    /** Parks every hook (of any owner) targeting {@code pluginName}; the sweeper's notification. */
    public static void targetDisabled(String pluginName) {
        forEachRegistered(dependency -> {
            if (dependency.pluginName().equalsIgnoreCase(pluginName)) {
                dependency.deactivate();
            }
        });
    }

    /** Requires the target's version to be at least {@code version} (semver gate). */
    public SoftDependency<T> minVersion(String version) {
        this.minVersion = version;
        invalidate();
        return this;
    }

    /** Requires {@code className} to be loadable from the target plugin's classloader. */
    public SoftDependency<T> requiresClass(String className) {
        this.requiredClass = className;
        invalidate();
        return this;
    }

    /** Plugin that owns this hook; used for the deferred per-owner registry inscription. */
    public JavaPlugin owner() {
        return owner;
    }

    /** Name of the target plugin this hook binds to. */
    public String pluginName() {
        return pluginName;
    }

    /** True when the hook is currently active (resolving it first if needed). */
    public boolean isAvailable() {
        return get().isPresent();
    }

    /** The active hook adapter, resolving it first if needed; empty when unavailable. */
    public Optional<T> get() {
        if (disabled) {
            return Optional.empty();
        }
        if (!resolved) {
            resolve();
        }
        return Optional.ofNullable(instance);
    }

    /** Drops the current adapter; the next {@link #get()} resolves again. */
    public void invalidate() {
        instance = null;
        resolved = false;
    }

    /** Permanently disables this hook (consumer teardown); it never resolves again. */
    public void forceDisable() {
        disabled = true;
        instance = null;
        resolved = true;
    }

    /** Re-resolves immediately; called by {@link HookListener} on target enable. */
    void refresh() {
        invalidate();
        resolve();
    }

    /**
     * Parks the hook as unavailable WITHOUT lazy re-resolution; called by
     * {@link HookListener} on target disable, when the target may still report
     * {@code isEnabled()} during its own {@code PluginDisableEvent}.
     */
    void deactivate() {
        instance = null;
        resolved = true;
    }

    private synchronized void resolve() {
        if (disabled || resolved) {
            return;
        }
        T created = null;
        Plugin target = Bukkit.getPluginManager().getPlugin(pluginName);
        if (target != null && target.isEnabled() && versionOk(target) && classOk(target)) {
            created = instantiate();
        }
        instance = created;
        resolved = true;
    }

    private boolean versionOk(Plugin target) {
        String required = minVersion;
        if (required == null) {
            return true;
        }
        String installed = target.getDescription().getVersion();
        if (SemverComparator.compareVersions(installed, required) >= 0) {
            return true;
        }
        owner.getLogger().warning("Hook '" + pluginName + "' requiere version >= " + required
                + " (instalada: " + installed + "); hook deshabilitado");
        return false;
    }

    private boolean classOk(Plugin target) {
        String required = requiredClass;
        if (required == null) {
            return true;
        }
        try {
            Class.forName(required, false, target.getClass().getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError e) {
            owner.getLogger().warning("Hook '" + pluginName + "': clase requerida " + required
                    + " no encontrada; hook deshabilitado");
            return false;
        }
    }

    /**
     * Isolated instantiation boundary: the factory runs only here, and any Throwable
     * (including NoClassDefFoundError from a hook adapter compiled against a missing API)
     * is caught so a broken hook never propagates to the caller.
     */
    private @Nullable T instantiate() {
        try {
            return factory.get();
        } catch (Throwable t) {
            owner.getLogger().warning("Hook '" + pluginName + "' fallo al instanciar: " + t);
            return null;
        }
    }
}
