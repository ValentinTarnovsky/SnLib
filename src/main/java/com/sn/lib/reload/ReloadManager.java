package com.sn.lib.reload;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.logging.Level;

import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.gui.Gui;
import com.sn.lib.gui.GuiManager;
import com.sn.lib.gui.GuiSession;
import com.sn.lib.lang.SnLang;
import com.sn.lib.yml.YmlManager;

/**
 * Reload orchestrator of a consumer context, reached through {@code sn.reload()}.
 *
 * <p>{@link #reloadPlugin()} rebuilds the modules of the OWNING plugin in a strict order:
 * close own GUIs, cancel own render/update tasks, re-read ymls (config managed re-merge,
 * then lang, guis and items), re-register commands with a client tree refresh,
 * re-dispatch the registered reloadables, cycle the recipes, and finally re-open GUIs
 * only when opted in. The reload of one consumer NEVER touches the state of other
 * consumers nor of the library itself (no-interference). It is invoked by the default
 * {@code reload} subcommand, by {@code /snlib reload <plugin>} and programmatically;
 * {@link Sn#reloadAll()} delegates here.</p>
 *
 * <p>Declared I/O exception: the synchronous re-read performed by this flow is accepted
 * ONLY because reload is an administrative command; it never runs during gameplay.
 * Main-thread only. A reload NEVER reloads classes: updating SnLib.jar requires a
 * server restart.</p>
 */
public final class ReloadManager {

    private final Sn ctx;
    private final List<Reloadable> registered = new CopyOnWriteArrayList<>();

    private volatile boolean reopenGuis;

    /** Creates the manager for the given context; instantiated by the context. */
    public ReloadManager(Sn ctx) {
        this.ctx = ctx;
    }

    /**
     * Registers a consumer component to be re-dispatched (typed re-cache) on every
     * {@link #reloadPlugin()} of this context.
     */
    public void register(Reloadable reloadable) {
        if (reloadable != null) {
            registered.add(reloadable);
        }
    }

    /**
     * Opt-in of the final reload step: when enabled, the GUIs open at the moment of the
     * reload are re-opened for their viewers on their page afterwards. Default off:
     * reloaded GUIs stay closed.
     */
    public ReloadManager reopenGuis(boolean reopen) {
        this.reopenGuis = reopen;
        return this;
    }

    /** Reloads every module of the owning plugin in the strict documented order. */
    public void reloadPlugin() {
        GuiManager guis = guisOrNull();
        List<OpenGui> reopen = reopenGuis && guis != null
                ? capture(guis.openSessions())
                : List.of();
        // 1. Close this context's GUIs BEFORE re-reading ymls; closing each per-viewer
        //    session also cancels its render/update TaskHandles.
        if (guis != null) {
            guis.closeAll();
        }
        // 2. Cancel the remaining per-context render/update tasks (held-effects timer).
        ctx.items().cancelTasks();
        // 3. Re-read ymls: config (managed re-merge) first, then lang, guis and items.
        YmlManager yml = ymlOrNull();
        if (yml != null) {
            yml.reloadAll();
        }
        SnLang lang = langOrNull();
        if (lang != null) {
            lang.reload();
        }
        if (guis != null) {
            guis.load();
        }
        ctx.items().reload();
        // 4. Re-register this owner's command roots; each register pass refreshes the
        //    client trees via player.updateCommands().
        ctx.commands().reregisterAll();
        // 5. Re-dispatch the registered reloadables (typed re-cache); the per-file
        //    onReload hooks already fired during the re-read.
        for (Reloadable reloadable : registered) {
            try {
                reloadable.reload();
            } catch (Throwable t) {
                ctx.plugin().getLogger().log(Level.SEVERE,
                        "A registered Reloadable failed during the reload", t);
            }
        }
        // 6. Recipe cycle on the main thread: unregister every recipe key of this owner
        //    and re-add the recipes of the reloaded definitions.
        ctx.items().reloadRecipes();
        // 7. Re-open the captured GUIs only when opted in; by default they stay closed.
        for (OpenGui open : reopen) {
            Player player = Bukkit.getPlayer(open.viewer());
            Gui gui = guis == null ? null : guis.get(open.guiId());
            if (player != null && player.isOnline() && gui != null) {
                gui.open(player, open.page());
            }
        }
    }

    /** Immutable snapshot of the open sessions taken before the reload closes them. */
    private static List<OpenGui> capture(List<GuiSession> sessions) {
        List<OpenGui> out = new ArrayList<>(sessions.size());
        for (GuiSession session : sessions) {
            out.add(new OpenGui(session.viewerId(), session.guiId(), session.page()));
        }
        return out;
    }

    private @Nullable GuiManager guisOrNull() {
        try {
            return ctx.guis();
        } catch (UnsupportedOperationException undeclared) {
            return null;
        }
    }

    private @Nullable YmlManager ymlOrNull() {
        try {
            return ctx.yml();
        } catch (UnsupportedOperationException undeclared) {
            return null;
        }
    }

    private @Nullable SnLang langOrNull() {
        try {
            return ctx.lang();
        } catch (UnsupportedOperationException undeclared) {
            return null;
        }
    }

    /** Viewer, gui and page of one session captured before the reload closed it. */
    private record OpenGui(UUID viewer, String guiId, int page) {
    }
}
