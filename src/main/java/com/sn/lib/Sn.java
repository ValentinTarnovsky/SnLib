package com.sn.lib;

import org.bukkit.plugin.java.JavaPlugin;

import com.sn.lib.action.ActionEngine;
import com.sn.lib.command.SnCommands;
import com.sn.lib.cooldown.Cooldowns;
import com.sn.lib.db.DbConfig;
import com.sn.lib.db.SnDb;
import com.sn.lib.debug.SnDebug;
import com.sn.lib.gui.GuiManager;
import com.sn.lib.item.ItemRegistry;
import com.sn.lib.lang.SnLang;
import com.sn.lib.papi.SnPapi;
import com.sn.lib.scheduler.SnScheduler;
import com.sn.lib.yml.YmlManager;

/**
 * Per-plugin SnLib context: the handle through which a consumer reaches every module
 * it declared in its {@link SnSpec}.
 *
 * <p>Module accessor policy: each typed accessor (scheduler, yml, lang, guis, items, db,
 * papi, ...) only works if the corresponding module was declared in the owning spec; an
 * accessor for an undeclared module throws {@link UnsupportedOperationException} naming
 * the missing {@code SnSpec.builder()} call.</p>
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
    private final ItemRegistry items;
    private final GuiManager guis;
    private final SnDb db;
    private final SnCommands commands;

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

    /** True once teardown of this context started; module I/O must go synchronous. */
    public boolean isShuttingDown() {
        return shuttingDown;
    }

    /**
     * Shuts down every module owned by this context and releases its registrations.
     * Idempotent: only the first call runs the teardown, and it flips the context to
     * synchronous-write mode before anything else.
     */
    public void shutdown() {
        if (shuttingDown) {
            return;
        }
        shuttingDown = true;
        commands.unregisterAll();
        if (db != null) {
            db.shutdown();
        }
    }

    /** Reloads every reloadable module owned by this context. */
    public void reloadAll() {
    }
}
