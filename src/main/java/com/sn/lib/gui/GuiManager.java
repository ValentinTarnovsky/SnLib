package com.sn.lib.gui;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.plugin.Plugin;
import org.bukkit.plugin.java.JavaPlugin;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.action.ActionHandler;
import com.sn.lib.internal.QuitCleanupListener;
import com.sn.lib.tenant.TenantRegistry;
import com.sn.lib.yml.SnYml;
import com.sn.lib.yml.YmlManager;

/**
 * GUI module of a consumer context, reached through {@code sn.guis()}.
 *
 * <p>{@link #load()} creates the {@code guis/} folder and loads ONE GUI per file (the id
 * is the file name without extension) following the golden spec
 * ({@code docs/menu-example.yml}): any supported field the config user sets already
 * works with zero plugin code. Open sessions are registered per owner in a
 * {@link TenantRegistry}, so a disable of one consumer closes exactly that consumer's
 * GUIs (no-interference); quit cleanup runs through the shared quit listener.</p>
 *
 * <p>Custom click actions are plain action-engine tags: {@link #registerAction} delegates
 * to {@code sn.actions()}. Main-thread only, like the whole GUI module.</p>
 */
public final class GuiManager {

    /**
     * PDC key name stamped on every rendered GUI stack (payload {@code "<guiId>:<slot>"}),
     * namespaced per owner plugin by TagIo; the anti-theft protection listener resolves
     * marked stacks through it.
     */
    public static final String ITEM_TAG = "snlib_gui_item";

    /**
     * Server-wide static justified: open GUI sessions keyed per owning plugin. The sweep
     * callback closes each session (cancels timers, untracks the holder, force-closes the
     * viewer) when its owner key is removed.
     */
    static final TenantRegistry<GuiSession> SESSIONS = new TenantRegistry<>(GuiSession::close);

    private final Sn ctx;
    private final JavaPlugin plugin;
    private final Map<String, Gui> guis = new LinkedHashMap<>();
    private final Map<String, SnYml> mounts = new ConcurrentHashMap<>();
    private final Set<String> warnedOnce = ConcurrentHashMap.newKeySet();

    /** Creates the module for the given context and hooks its quit cleanup. */
    public GuiManager(Sn ctx) {
        this.ctx = ctx;
        this.plugin = ctx.plugin();
        QuitCleanupListener.register(plugin, this::closeSessionsOf);
    }

    /**
     * Creates {@code guis/} if missing and (re)parses one GUI per {@code .yml} file in
     * it. Requires the yml module; without it the folder cannot be mounted and a WARN is
     * logged. Synchronous I/O by design: runs only in onEnable and in the reload flow.
     */
    public void load() {
        YmlManager files;
        try {
            files = ctx.yml();
        } catch (UnsupportedOperationException e) {
            plugin.getLogger().warning("guis() declared without config(): the guis/ folder "
                    + "cannot be loaded and sn.guis() stays empty");
            return;
        }
        File dir = new File(plugin.getDataFolder(), "guis");
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Could not create folder " + dir.getPath());
            return;
        }
        File[] found = dir.listFiles(
                (parent, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        synchronized (guis) {
            guis.clear();
            if (found == null || found.length == 0) {
                return;
            }
            Arrays.sort(found, Comparator.comparing(File::getName));
            for (File file : found) {
                String name = file.getName();
                String id = name.substring(0, name.length() - ".yml".length());
                SnYml yml = mounts.computeIfAbsent("guis/" + name, files::load);
                guis.put(id, new Gui(ctx, GuiDef.parse(ctx, id, yml)));
            }
        }
    }

    /** GUI loaded under {@code id} (file name without extension), or null. */
    public @Nullable Gui get(String id) {
        if (id == null) {
            return null;
        }
        synchronized (guis) {
            return guis.get(id.trim());
        }
    }

    /**
     * Registers a custom click action tag for this context; sugar over
     * {@code sn.actions().register}.
     */
    public void registerAction(String tag, ActionHandler handler) {
        ctx.actions().register(tag, handler);
    }

    /**
     * Reloads the module: closes every open GUI of this context natively (sessions are
     * per viewer, so nobody keeps a stale inventory), re-reads every mounted file from
     * disk and re-parses the definitions, picking up new files.
     */
    public void reload() {
        closeAll();
        for (SnYml yml : mounts.values()) {
            yml.reload();
        }
        load();
    }

    /** Snapshot of the open sessions of THIS context; the reload flow's reopen source. */
    public List<GuiSession> openSessions() {
        return new ArrayList<>(SESSIONS.forOwner(plugin));
    }

    /** Closes every open GUI session of THIS context. */
    public void closeAll() {
        closeAll(plugin);
    }

    /**
     * Closes every open GUI session registered by {@code owner}; sessions of every other
     * plugin stay untouched (no-interference).
     */
    public void closeAll(Plugin owner) {
        for (GuiSession session : new ArrayList<>(SESSIONS.forOwner(owner))) {
            session.close();
        }
    }

    /** Logs a GUI misuse warning once per key for this context (bindPaged gating). */
    void warnOnce(String key, String message) {
        if (warnedOnce.add(key)) {
            plugin.getLogger().warning(message);
        }
    }

    /** Quit/kick cleanup: drops the leaving viewer's sessions of this context. */
    private void closeSessionsOf(UUID viewer) {
        List<GuiSession> mine = new ArrayList<>(SESSIONS.forOwner(plugin));
        for (GuiSession session : mine) {
            if (session.viewerId().equals(viewer)) {
                session.close();
            }
        }
    }
}
