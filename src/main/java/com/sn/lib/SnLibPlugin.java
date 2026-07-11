package com.sn.lib;

import org.bstats.bukkit.Metrics;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.command.internal.SnLibCommand;
import com.sn.lib.compat.SnVersion;
import com.sn.lib.hologram.internal.HologramChunkListener;
import com.sn.lib.tenant.internal.ListenerHub;
import com.sn.lib.tenant.internal.TenantSweeper;
import com.sn.lib.util.HeadUtil;
import com.sn.lib.util.PlayerLookup;

/**
 * Bootstrap plugin of the SnLib runtime. Loaded at STARTUP before every consumer; the
 * single point that registers the shared listeners and the runtime side of the consumer
 * API-level handshake (see {@link SnPlugin}).
 *
 * <p>Owns its own context over {@code plugins/SnLib/config.yml} (library debug output
 * plus the bStats opt-out), created through the same package-private
 * {@code SnLib.init} every consumer goes through.</p>
 */
public final class SnLibPlugin extends JavaPlugin {

    /** Service id of SnLib at bstats.org. */
    private static final int BSTATS_SERVICE_ID = 32541;

    private static volatile @Nullable SnLibPlugin instance;

    private @Nullable Sn selfCtx;
    private @Nullable Metrics metrics;

    /**
     * Running SnLib bootstrap. Consumers never call this directly; {@link SnPlugin}
     * uses it for the handshake, guaranteed present by {@code depend: [SnLib]} plus
     * {@code load: STARTUP}.
     */
    public static SnLibPlugin get() {
        SnLibPlugin plugin = instance;
        if (plugin == null) {
            throw new IllegalStateException(
                    "SnLib no esta habilitado: el consumer necesita depend: [SnLib]");
        }
        return plugin;
    }

    /**
     * API level of the installed SnLib.jar: {@link SnApi#LEVEL} as inlined in THIS jar
     * at build time, compared against the consumer's {@code requiredApiLevel()}.
     */
    public int apiLevel() {
        return SnApi.LEVEL;
    }

    /** Context of the library itself, or null while disabled. */
    public @Nullable Sn selfContext() {
        return selfCtx;
    }

    @Override
    public void onEnable() {
        instance = this;
        logDetectedVersion();
        ListenerHub.registerAll(this);
        Sn ctx = SnLib.init(this, buildSelfSpec());
        this.selfCtx = ctx;
        SnLibCommand.register(this, ctx);
        if (ctx.yml().config().getBoolean("bstats", true)) {
            this.metrics = new Metrics(this, BSTATS_SERVICE_ID);
        }
        ctx.scheduler().sync(this::purgeOrphanHolograms);
        getLogger().info("SnLib " + getPluginMeta().getVersion()
                + " enabled (API level " + SnApi.LEVEL + ")");
    }

    @Override
    public void onDisable() {
        TenantSweeper.cascadeAll();
        Metrics activeMetrics = this.metrics;
        if (activeMetrics != null) {
            activeMetrics.shutdown();
            this.metrics = null;
        }
        HeadUtil.clearCache();
        PlayerLookup.clearCache();
        this.selfCtx = null;
        instance = null;
    }

    /**
     * Forces {@link SnVersion} class initialization (which WARNs once on unknown forward
     * versions and never hard-fails) and logs the detected server version.
     */
    private void logDetectedVersion() {
        String detected = SnVersion.MAJOR + "." + SnVersion.MINOR
                + (SnVersion.PATCH > 0 ? "." + SnVersion.PATCH : "");
        getLogger().info("Servidor detectado: " + detected
                + (SnVersion.isFolia() ? " (Folia)" : ""));
    }

    /**
     * Startup scan for orphaned hologram entities. Deferred to the first tick because
     * SnLib enables at STARTUP, before any world loads and before any consumer registers
     * its holograms; by the first tick both happened and every marked TextDisplay without
     * a live registration is a leftover from a previous run.
     */
    private void purgeOrphanHolograms() {
        int purged = HologramChunkListener.purgeLoadedWorlds();
        if (purged > 0) {
            getLogger().info("Purgados " + purged + " hologramas huerfanos de arranques previos");
        }
    }

    private static SnSpec buildSelfSpec() {
        return SnSpec.builder().config("config.yml").debugCommand().build();
    }
}
