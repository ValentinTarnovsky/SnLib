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
import com.sn.lib.yml.internal.ResourceFolders;

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
 * <p>Extra menu folders: a modular consumer keeps each module's menus next to the rest of
 * that module's files through {@link #loadFolder(String, String)}, e.g.
 * {@code loadFolder("modules/party/guis", "party")}. Those menus load under the id
 * {@code "<namespace>:<file name>"} ({@code party:shop}); a {@code guis/} id has no
 * {@code :} unless its file is literally named that way, which Windows forbids and a
 * collision WARN covers elsewhere. Each folder owns its namespace. Registered folders are
 * remembered and reloaded together with {@code guis/}.</p>
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
    /** Extra menu folders (normalized path to id namespace) in registration order; guarded by {@code guis}. */
    private final Map<String, String> folders = new LinkedHashMap<>();
    /** Whether a deferred "no menu loaded" check is already scheduled; main thread only. */
    private boolean emptyCheckPending;

    /** Creates the module for the given context and hooks its quit cleanup. */
    public GuiManager(Sn ctx) {
        this.ctx = ctx;
        this.plugin = ctx.plugin();
        QuitCleanupListener.register(plugin, this::closeSessionsOf);
    }

    /**
     * Seeds the consumer jar's bundled {@code guis/*.yml} into the data folder, creates
     * {@code guis/} if still missing and (re)parses one GUI per {@code .yml} file in it,
     * then does the same for every folder registered through
     * {@link #loadFolder(String, String)}. Requires the yml module; without it the folder
     * cannot be mounted and a WARN is logged. When no menu at all was loaded, one WARN is
     * logged a tick later, so folders registered from {@code onInnerEnable} still count.
     * Synchronous I/O by design: runs only in onEnable and in the reload flow.
     */
    public void load() {
        YmlManager files = filesOrWarn("the guis/ folder");
        if (files == null) {
            return;
        }
        File dir = new File(plugin.getDataFolder(), GuiSeeder.GUIS_DIR);
        // Seed the bundled guis BEFORE listing: the consumer never gets control before this
        // runs, so an unseeded folder would otherwise load nothing. Managed semantics, so
        // this also runs on every reload (missing files reseed, existing files re-merge).
        seedBundledGuis(files);
        File[] found = null;
        if (dir.exists() || dir.mkdirs()) {
            found = dir.listFiles(
                    (parent, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        } else {
            // Only guis/ is lost: the registered folders below still load.
            plugin.getLogger().warning("Could not create folder " + dir.getPath());
        }
        synchronized (guis) {
            guis.clear();
            if (found != null && found.length > 0) {
                Arrays.sort(found, Comparator.comparing(File::getName));
                for (File file : found) {
                    String name = file.getName();
                    String id = name.substring(0, name.length() - ".yml".length());
                    SnYml yml = mounts.computeIfAbsent("guis/" + name, files::load);
                    guis.put(id, new Gui(ctx, GuiDef.parse(ctx, id, yml)));
                }
            }
            for (Map.Entry<String, String> folder : folders.entrySet()) {
                loadFolderInto(files, folder.getKey(), folder.getValue(), false);
            }
            if (guis.isEmpty() && !emptyCheckPending) {
                if (plugin.isEnabled()) {
                    // Deferred: the context loads guis/ before onInnerEnable, where the
                    // consumer registers its extra folders, so an immediate check would
                    // WARN falsely.
                    emptyCheckPending = true;
                    ctx.scheduler().syncLater(1L, this::warnIfNoMenus);
                } else {
                    logNoMenus();
                }
            }
        }
    }

    /**
     * Registers an extra menu folder next to {@code guis/}: seeds the jar's bundled
     * top-level {@code <folder>/*.yml} (managed, gated by {@code update-configs}, exactly
     * like {@code guis/}), creates the folder if missing and loads one menu per file under
     * the id {@code "<namespace>:<file name>"}. {@code loadFolder("modules/party/guis",
     * "party")} serves {@code guis().get("party:shop")} and the {@code [open] party:shop}
     * action.
     *
     * <p>The folder is remembered: every reload ({@code sn.reload()},
     * {@code /<root> reload}) reloads it together with {@code guis/}, picking up new files.
     * Registering the same folder again closes its open menus, re-reads its files from disk
     * and reloads it under the given namespace. Each folder needs its own namespace. An id
     * that is already loaded (a {@code guis/} file literally named {@code party:shop.yml},
     * possible outside Windows) is skipped with one WARN, so the first definition wins.
     * Requires the yml module, like {@link #load()}. Main thread only, like the whole GUI
     * module.</p>
     *
     * @param folder    data-folder-relative path, also the jar resource prefix; {@code \}
     *                  is normalized to {@code /}, and empty, {@code .} and {@code ..}
     *                  segments are resolved; a blank path, a path holding {@code :}, one
     *                  climbing out of the data folder, or {@code guis} is rejected
     * @param namespace non-blank id namespace without {@code :} or whitespace, not used by
     *                  another registered folder
     * @throws IllegalArgumentException on an invalid folder or namespace, or a namespace
     *         another folder already uses
     */
    public void loadFolder(String folder, String namespace) {
        String dirPath = normalizeFolder(folder);
        String ns = requireNamespace(namespace);
        YmlManager files = filesOrWarn("the " + dirPath + "/ folder");
        if (files == null) {
            return;
        }
        String previous;
        synchronized (guis) {
            for (Map.Entry<String, String> registered : folders.entrySet()) {
                if (registered.getValue().equals(ns) && !registered.getKey().equals(dirPath)) {
                    throw new IllegalArgumentException("Menu namespace '" + ns
                            + "' is already used by the folder " + registered.getKey());
                }
            }
            previous = folders.put(dirPath, ns);
            if (previous != null) {
                removeNamespace(previous);
            }
            loadFolderInto(files, dirPath, ns, true);
        }
        if (previous != null) {
            closeSessionsOfNamespace(previous);
        }
    }

    /**
     * Forgets a folder registered through {@link #loadFolder(String, String)}: its menus
     * stop resolving through {@link #get(String)} and every open session of a menu in its
     * namespace is closed. An unknown folder is ignored. The files stay on disk.
     *
     * @param folder the folder as given to {@link #loadFolder(String, String)}
     * @throws IllegalArgumentException on a blank folder or {@code guis}
     */
    public void unloadFolder(String folder) {
        String dirPath = normalizeFolder(folder);
        String ns;
        synchronized (guis) {
            ns = folders.remove(dirPath);
            if (ns == null) {
                return;
            }
            removeNamespace(ns);
        }
        closeSessionsOfNamespace(ns);
    }

    /**
     * Seeds and parses one registered folder into the loaded menus under
     * {@code "<ns>:<file name>"} ids. With {@code reread}, the folder's already mounted
     * files are re-read from disk after seeding; the reload flow skips it because it
     * re-reads every mount itself. Caller holds the {@code guis} lock.
     */
    private void loadFolderInto(YmlManager files, String dirPath, String ns, boolean reread) {
        File jar = GuiSeeder.consumerJar(plugin);
        if (jar == null) {
            plugin.getLogger().warning("Could not locate the jar of " + plugin.getName()
                    + "; the bundled " + dirPath + "/*.yml were not seeded into " + dirPath + "/");
        } else {
            GuiSeeder.seed(jar, plugin.getDataFolder(), dirPath, files.config().file(),
                    plugin.getLogger());
        }
        if (reread) {
            String mountPrefix = dirPath + "/";
            for (Map.Entry<String, SnYml> mount : mounts.entrySet()) {
                if (mount.getKey().startsWith(mountPrefix)) {
                    mount.getValue().reload();
                }
            }
        }
        File dir = new File(plugin.getDataFolder(), dirPath);
        if (!dir.exists() && !dir.mkdirs()) {
            plugin.getLogger().warning("Could not create folder " + dir.getPath());
            return;
        }
        File[] found = dir.listFiles(
                (parent, name) -> name.toLowerCase(Locale.ROOT).endsWith(".yml"));
        if (found == null) {
            return;
        }
        Arrays.sort(found, Comparator.comparing(File::getName));
        for (File file : found) {
            String name = file.getName();
            String id = ns + ":" + name.substring(0, name.length() - ".yml".length());
            if (guis.containsKey(id)) {
                warnOnce("gui-collision:" + id, "Menu id '" + id + "' from " + dirPath
                        + " is already loaded; the file is skipped");
                continue;
            }
            SnYml yml = mounts.computeIfAbsent(dirPath + "/" + name, files::load);
            guis.put(id, new Gui(ctx, GuiDef.parse(ctx, id, yml)));
        }
    }

    /**
     * Drops every loaded menu of a namespace; each registered folder owns its namespace
     * alone, so this touches exactly one folder's menus. Caller holds the {@code guis} lock.
     */
    private void removeNamespace(String ns) {
        String prefix = ns + ":";
        guis.keySet().removeIf(id -> id.startsWith(prefix));
    }

    /** Closes this context's open sessions of a menu in {@code ns}; runs outside the lock. */
    private void closeSessionsOfNamespace(String ns) {
        String prefix = ns + ":";
        for (GuiSession session : openSessions()) {
            if (session.guiId().startsWith(prefix)) {
                session.close();
            }
        }
    }

    /** Deferred "no menu loaded" check scheduled by {@link #load()}. */
    private void warnIfNoMenus() {
        emptyCheckPending = false;
        synchronized (guis) {
            if (!guis.isEmpty()) {
                return;
            }
        }
        logNoMenus();
    }

    private void logNoMenus() {
        plugin.getLogger().warning("guis() is declared but no menu was loaded: the guis/ "
                + "folder and every folder registered through loadFolder are empty. Bundle the "
                + "menus as guis/*.yml (or <folder>/*.yml) in the jar so they seed, or drop the "
                + "files into the folder.");
    }

    /**
     * The yml module, or null after one WARN naming {@code what} could not be loaded
     * because the spec declared {@code guis()} without {@code config()}.
     */
    private @Nullable YmlManager filesOrWarn(String what) {
        try {
            return ctx.yml();
        } catch (UnsupportedOperationException e) {
            plugin.getLogger().warning("guis() declared without config(): " + what
                    + " cannot be loaded and sn.guis() stays empty");
            return null;
        }
    }

    /** Normalized data-folder-relative folder of an extra menu source. */
    private static String normalizeFolder(String folder) {
        return ResourceFolders.normalize(folder, GuiSeeder.GUIS_DIR, "Menu");
    }

    /** Validated id namespace of an extra menu source. */
    private static String requireNamespace(String namespace) {
        String ns = namespace == null ? "" : namespace.trim();
        if (ns.isEmpty()) {
            throw new IllegalArgumentException("Menu namespace is blank");
        }
        for (int i = 0; i < ns.length(); i++) {
            char c = ns.charAt(i);
            if (c == ':' || Character.isWhitespace(c)) {
                throw new IllegalArgumentException(
                        "Menu namespace '" + ns + "' must not contain ':' or whitespace");
            }
        }
        return ns;
    }

    /**
     * Seeds/merges the consumer jar's bundled {@code guis/*.yml} through the managed
     * updater, gated by the config's {@code update-configs}. A jar that cannot be located
     * WARNs and leaves the folder to whatever is already on disk.
     */
    private void seedBundledGuis(YmlManager files) {
        File jar = GuiSeeder.consumerJar(plugin);
        if (jar == null) {
            plugin.getLogger().warning("Could not locate the jar of " + plugin.getName()
                    + "; the bundled guis/*.yml were not seeded into the guis/ folder");
            return;
        }
        GuiSeeder.seed(jar, plugin.getDataFolder(), files.config().file(), plugin.getLogger());
    }

    /**
     * GUI loaded under {@code id}, or null: the file name without extension for a menu of
     * {@code guis/}, {@code "<namespace>:<file name>"} for a menu of a folder registered
     * through {@link #loadFolder(String, String)}.
     */
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
