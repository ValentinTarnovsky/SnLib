package com.sn.lib;

import org.bukkit.plugin.java.JavaPlugin;

import com.sn.lib.action.ActionEngine;
import com.sn.lib.bossbar.BossBarUtil;
import com.sn.lib.command.SnCommands;
import com.sn.lib.cooldown.Cooldowns;
import com.sn.lib.cron.SnCron;
import com.sn.lib.db.DbConfig;
import com.sn.lib.db.SnDb;
import com.sn.lib.debug.SnDebug;
import com.sn.lib.discord.DiscordWebhook;
import com.sn.lib.economy.EconomyBridge;
import com.sn.lib.gui.GuiManager;
import com.sn.lib.hologram.HologramUtil;
import com.sn.lib.hook.SoftDependency;
import com.sn.lib.item.ItemRegistry;
import com.sn.lib.item.internal.EquipmentBackup;
import com.sn.lib.item.internal.RecipeLoader;
import com.sn.lib.lang.SnLang;
import com.sn.lib.leaderboard.LeaderboardCache;
import com.sn.lib.papi.SnPapi;
import com.sn.lib.reload.ReloadManager;
import com.sn.lib.scheduler.SnScheduler;
import com.sn.lib.tenant.TenantRegistry;
import com.sn.lib.yml.YmlManager;

/**
 * Per-plugin SnLib context: the handle through which a consumer reaches every module
 * it declared in its {@link SnSpec}.
 *
 * <p>Module accessor policy: each typed accessor (scheduler, yml, lang, guis, items, db,
 * papi, ...) only works if the corresponding module was declared in the owning spec; an
 * accessor for an undeclared module throws {@link UnsupportedOperationException} naming
 * the missing {@code SnSpec.builder()} call. Every declared module is wired at
 * construction; no accessor of a declared module ever throws.</p>
 *
 * <p>{@link #shutdown()} tears the context down in a strict documented order and is
 * idempotent; {@link #reloadAll()} delegates to the {@link ReloadManager}. Both operate
 * ONLY on state owned by this plugin: contexts of other consumers are never touched
 * (no-interference). This class is part of the frozen entrypoint surface: its accessor
 * set only grows within a major version.</p>
 */
public final class Sn {

    private final JavaPlugin plugin;
    private final SnSpec spec;
    private final SnScheduler scheduler;
    private final SnPapi papi;
    private final YmlManager yml;
    private final SnDebug debug;
    private final ActionEngine actions;
    private final SnLang lang;
    private final Cooldowns cooldowns;
    private final EconomyBridge economy;
    private final BossBarUtil bossbars;
    private final HologramUtil holograms;
    private final SnCron cron;
    private final LeaderboardCache leaderboards;
    private final DiscordWebhook discord;
    private final ItemRegistry items;
    private final GuiManager guis;
    private final SnDb db;
    private final SnCommands commands;
    private final ReloadManager reload;

    /** Set by the teardown before anything else; flips SnYml.save() to synchronous writes. */
    volatile boolean shuttingDown;

    Sn(JavaPlugin plugin, SnSpec spec) {
        this.plugin = plugin;
        this.spec = spec;
        this.scheduler = new SnScheduler(plugin);
        this.papi = new SnPapi(this);
        this.yml = spec.config() == null ? null : new YmlManager(this, spec.config());
        this.debug = new SnDebug(plugin, yml == null ? null : yml.config());
        this.actions = new ActionEngine(this);
        this.lang = spec.lang() ? new SnLang(this, yml == null ? null : yml.config()) : null;
        this.cooldowns = new Cooldowns(this);
        this.economy = new EconomyBridge(this);
        this.bossbars = new BossBarUtil(this);
        this.holograms = new HologramUtil(this);
        this.cron = new SnCron(this);
        this.leaderboards = new LeaderboardCache(this);
        this.discord = new DiscordWebhook(this);
        this.items = new ItemRegistry(this);
        String itemsFile = spec.items();
        if (itemsFile != null) {
            if (yml != null) {
                items.loadAll(yml.managed(itemsFile));
            } else {
                plugin.getLogger().warning("items(\"" + itemsFile + "\") declarado sin config(): "
                        + "el archivo no se monta y sn.items() queda solo programatico");
            }
        }
        this.guis = spec.guis() ? new GuiManager(this) : null;
        if (guis != null) {
            guis.load();
        }
        this.db = spec.db()
                ? new SnDb(this, DbConfig.load(plugin,
                        yml == null ? null : yml.config().getSection("database")))
                : null;
        this.commands = new SnCommands(this, lang, spec.debugCommand());
        this.reload = new ReloadManager(this);
    }

    /** Consumer plugin that owns this context. */
    public JavaPlugin plugin() {
        return plugin;
    }

    /** Module declaration this context was initialized with. */
    SnSpec spec() {
        return spec;
    }

    /**
     * Yml manager of the owning plugin: managed/seedOnly/data files plus the mounted
     * main config.
     *
     * @throws UnsupportedOperationException if the spec did not declare the config
     *         module via {@code SnSpec.builder().config(...)}
     */
    public YmlManager yml() {
        if (yml == null) {
            throw new UnsupportedOperationException(
                    "Modulo yml no declarado: falta SnSpec.builder().config(\"config.yml\")");
        }
        return yml;
    }

    /** Folia-aware scheduler bound to the owning plugin; available in every context. */
    public SnScheduler scheduler() {
        return scheduler;
    }

    /**
     * Runtime debug service of the owning plugin; available in every context. Toggles
     * persist to the main config when the yml module is declared, in memory otherwise.
     */
    public SnDebug debug() {
        return debug;
    }

    /**
     * PlaceholderAPI bridge of the owning plugin; available in every context. Every
     * apply returns the text intact when PlaceholderAPI is absent.
     */
    public SnPapi papi() {
        return papi;
    }

    /**
     * Action engine of the owning plugin; available in every context. Runs YML action
     * lists of the form {@code [tag] argumento} and accepts custom tags via
     * {@link ActionEngine#register}.
     */
    public ActionEngine actions() {
        return actions;
    }

    /**
     * Cooldown store of the owning plugin; available in every context. Unexpired entries
     * survive relogs by design; only categories registered as session categories reset
     * on quit/kick.
     */
    public Cooldowns cooldowns() {
        return cooldowns;
    }

    /**
     * Language module of the owning plugin: {@code lang/messages_<code>.yml} with the
     * shared {@code snlib.*} keys always-merged in and English fallback per key.
     *
     * @throws UnsupportedOperationException if the spec did not declare the lang
     *         module via {@code SnSpec.builder().lang()}
     */
    public SnLang lang() {
        if (lang == null) {
            throw new UnsupportedOperationException(
                    "Modulo lang no declarado: falta SnSpec.builder().lang()");
        }
        return lang;
    }

    /**
     * Economy bridge of the owning plugin; available in every context. Operations resolve
     * Vault when present, then the command backend configured via
     * {@link EconomyBridge#useCommandBackend}; with no backend available they WARN once
     * and report failure.
     */
    public EconomyBridge economy() {
        return economy;
    }

    /**
     * Boss bar service of the owning plugin; available in every context. Bars are
     * Adventure boss bars (zero packets) with titles rendered through the SnText
     * pipeline; quitting viewers are dropped automatically and the context teardown
     * hides every bar of this owner.
     */
    public BossBarUtil bossbars() {
        return bossbars;
    }

    /**
     * Hologram service of the owning plugin; available in every context. Holograms are
     * real TextDisplay entities marked in the PDC; the context teardown deletes this
     * owner's entities and the library purges orphaned markers on chunk load and at
     * startup.
     */
    public HologramUtil holograms() {
        return holograms;
    }

    /**
     * Cron service of the owning plugin; available in every context. Jobs run on the main
     * thread at the calendar instants of a 5-field cron subset or the daily/hourly
     * shortcuts, re-armed after every run; jobs scheduled with {@code catchUp(true)}
     * persist their last run to a data yml and fire one missed run on the next schedule.
     */
    public SnCron cron() {
        return cron;
    }

    /**
     * Leaderboard cache of the owning plugin; available in every context. Each board runs
     * its query asynchronously on a fixed interval and swaps an immutable snapshot behind
     * a volatile reference, so getTop/positionOf/valueOf are lock-free cache reads safe
     * for PlaceholderAPI resolvers.
     */
    public LeaderboardCache leaderboards() {
        return leaderboards;
    }

    /**
     * Discord webhook dispatcher of the owning plugin; available in every context.
     * Messages queue FIFO and post asynchronously through the JDK HttpClient, honoring
     * Retry-After on 429; the context teardown drains whatever is still queued.
     */
    public DiscordWebhook discord() {
        return discord;
    }

    /**
     * Item registry of the owning plugin; available in every context and works with zero
     * files: definitions come from {@code ItemDef.builder()}, from YML sections or from
     * the items file declared via {@code SnSpec.builder().items(...)}.
     */
    public ItemRegistry items() {
        return items;
    }

    /**
     * GUI module of the owning plugin: the {@code guis/} folder with one GUI per file,
     * one session and inventory per viewer, and opt-in pagination per menu.
     *
     * @throws UnsupportedOperationException if the spec did not declare the guis
     *         module via {@code SnSpec.builder().guis()}
     */
    public GuiManager guis() {
        if (guis == null) {
            throw new UnsupportedOperationException(
                    "Modulo guis no declarado: falta SnSpec.builder().guis()");
        }
        return guis;
    }

    /**
     * Database module of the owning plugin: dual SQLite/MySQL over one Hikari pool with
     * every query and update off the main thread.
     *
     * @throws UnsupportedOperationException if the spec did not declare the db module
     *         via {@code SnSpec.builder().db()}
     */
    public SnDb db() {
        if (db == null) {
            throw new UnsupportedOperationException(
                    "Modulo db no declarado: falta SnSpec.builder().db()");
        }
        return db;
    }

    /**
     * Command module of the owning plugin; available in every context. Roots built here
     * inject reload and help subcommands by default, tab-complete gated by permission,
     * and are unregistered on shutdown.
     */
    public SnCommands commands() {
        return commands;
    }

    /**
     * Reload orchestrator of the owning plugin; available in every context. Rebuilds the
     * declared modules in a strict order and re-dispatches the reloadables registered
     * through {@link ReloadManager#register}; the default {@code reload} subcommand and
     * {@code /snlib reload <plugin>} delegate here.
     */
    public ReloadManager reload() {
        return reload;
    }

    /** True once teardown of this context started; module I/O must go synchronous. */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Shuts down every module owned by this context and releases its registrations, in a
     * strict order that never loses a pending write. Idempotent: only the first call runs
     * the teardown, and it flips the context to synchronous-write mode before anything
     * else, so every persistence performed inside it (equipment backups, debug toggles,
     * data files) writes SYNC on the calling thread instead of going through a scheduler
     * that is about to be cancelled.
     */
    public void shutdown() {
        if (shuttingDown) {
            return;
        }
        // 0. Flip to synchronous-write mode FIRST: SnYml.save() now writes inline and
        //    SnFuture.join accepts the teardown thread.
        shuttingDown = true;
        // 1. Close this owner's open GUIs; each per-viewer session cancels its timers,
        //    untracks its holder and force-closes its viewer.
        if (guis != null) {
            guis.closeAll();
        }
        // 2. Unregister this owner's command roots and refresh the client command trees.
        commands.unregisterAll();
        // 3. Drain the coalesced async yml writes BEFORE cancelling the scheduler that
        //    would run them.
        if (yml != null) {
            yml.flushAll();
        }
        // 4. Only now cancel every remaining task of the owning plugin.
        items.cancelTasks();
        scheduler.cancelAll();
        // 5. Locked items: put the displaced real equipment back; the write-through
        //    store persists synchronously through the shuttingDown flag.
        EquipmentBackup.restoreAll(plugin);
        // 6. Player caches: save every dirty entry and join the writes...
        // 7. ...then close the pool (joins pending work, shutdownNow after timeout).
        if (db != null) {
            db.flushPlayerCaches();
            db.shutdown();
        }
        // 8. Remove this owner's recipe keys from the server.
        RecipeLoader.unregisterAll(plugin);
        // 9. Cooldown store of this owner.
        cooldowns.clearAll();
        // 10. Own integrations: force-disable this owner's soft-dependency hooks and
        //     unregister its PlaceholderAPI expansions.
        SoftDependency.forEachRegistered(hook -> {
            if (hook.owner() == plugin) {
                hook.forceDisable();
            }
        });
        papi.unregisterAll();
        // 11. Release the BungeeCord outgoing channel if [connect] registered it.
        actions.shutdown();
        // 12. Teardown hooks of the extra modules, before the generic removeOwner: hide
        //     this owner's bossbars, delete its TextDisplay holograms so they do not
        //     linger as orphans until the next startup purge, and drain the queued
        //     Discord webhooks best-effort under a short deadline.
        bossbars.hideAll();
        holograms.deleteAll();
        discord.drain();
        // 13. Remove this owner's key from EVERY tenant registry and detach the context.
        TenantRegistry.sweepOwner(plugin);
        SnLib.detach(plugin, this);
    }

    /** Reloads every module owned by this context; delegates to the reload manager. */
    public void reloadAll() {
        reload.reloadPlugin();
    }
}
