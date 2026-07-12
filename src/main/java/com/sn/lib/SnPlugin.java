package com.sn.lib;

import java.util.logging.Level;

import org.bukkit.plugin.java.JavaPlugin;

/**
 * Mandatory base class of every SnLib consumer and the ONLY initialization path of the
 * library: {@code SnLib.init} is package-private, so extending this class is the single
 * public way to obtain a context.
 *
 * <p><b>Bytecode-side API-level handshake.</b> {@link #requiredApiLevel()} must be
 * implemented exactly as {@code return SnApi.LEVEL;}: javac inlines the literal the
 * consumer compiled against into the CONSUMER's own class file. At runtime that constant
 * is compared with {@link SnLibPlugin#apiLevel()}, the level inlined in the installed
 * SnLib.jar. A consumer built against a newer API level than the installed jar disables
 * itself cleanly with an update message instead of failing later with
 * {@code NoSuchMethodError} or {@code NoClassDefFoundError}.</p>
 *
 * <p>Part of the frozen entrypoint ({@code SnPlugin} + {@code requiredApiLevel()} +
 * {@link SnSpec} + {@link SnApi#LEVEL}): this surface never changes within a major
 * version. Consumers must declare {@code depend: [SnLib]} in their plugin.yml.</p>
 */
public abstract class SnPlugin extends JavaPlugin {

    private Sn sn;

    /** Implement it EXACTLY as {@code return SnApi.LEVEL;} - inlines the consumer's compile-time API level. */
    protected abstract int requiredApiLevel();

    @Override
    public final void onEnable() {
        int installed = SnLibPlugin.get().apiLevel();
        int required = requiredApiLevel();
        if (installed < required) {
            getLogger().severe("Requires SnLib API level " + required + " (installed: " + installed
                    + "). Update SnLib.jar (restart required): https://github.com/ValentinTarnovsky/SnLib/releases");
            getServer().getPluginManager().disablePlugin(this);
            return;
        }
        this.sn = SnLib.init(this, buildSpec());
        try {
            onInnerEnable();
        } catch (Throwable t) {
            getLogger().log(Level.SEVERE, "onInnerEnable failed", t);
            getServer().getPluginManager().disablePlugin(this);
        }
    }

    @Override
    public final void onDisable() {
        try {
            onInnerDisable();
        } finally {
            if (sn != null) {
                sn.shutdown();
            }
        }
    }

    /**
     * Modules this consumer declares; override to opt in
     * ({@code SnSpec.builder().config("config.yml").lang()...build()}). The default
     * declares no optional module.
     */
    protected SnSpec buildSpec() {
        return SnSpec.builder().build();
    }

    /** Consumer enable logic; runs after the handshake and the context initialization. */
    protected abstract void onInnerEnable();

    /** Consumer disable logic; runs before the context teardown. Optional. */
    protected void onInnerDisable() {
    }

    /** SnLib context of this plugin; available from {@link #onInnerEnable()} on. */
    public final Sn sn() {
        return sn;
    }
}
