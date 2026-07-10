package com.sn.lib.gui;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;

/**
 * One loaded GUI definition and its live per-viewer sessions.
 *
 * <p>{@link #open(Player)} gives each viewer their OWN {@link GuiSession} (own inventory,
 * own holder, own page state) over the shared immutable {@link GuiDef}; opening again for
 * a viewer with a live session re-shows that session instead of stacking a second one.
 * Main-thread only, like the whole GUI module.</p>
 */
public final class Gui {

    private final Sn ctx;
    private final GuiDef def;
    private final Map<UUID, GuiSession> sessions = new ConcurrentHashMap<>();

    Gui(Sn ctx, GuiDef def) {
        this.ctx = ctx;
        this.def = def;
    }

    /** GUI id: the file name without the {@code .yml} extension. */
    public String id() {
        return def.id();
    }

    /** Immutable parsed definition shared by every session. */
    public GuiDef def() {
        return def;
    }

    /** Opens the GUI for the player on page 1. */
    public void open(Player player) {
        open(player, 1);
    }

    /**
     * Opens the GUI for the player on the given page (clamped to a minimum of 1 and
     * forced to 1 while the menu did not opt in to pagination). A live session of this
     * viewer is re-shown on that page; otherwise a new session is created, registered
     * per owner and rendered.
     */
    public void open(Player player, int page) {
        if (player == null) {
            return;
        }
        GuiSession existing = sessions.get(player.getUniqueId());
        if (existing != null && !existing.closed()) {
            existing.reopen(page);
            return;
        }
        GuiSession session = new GuiSession(ctx, this, player, page);
        sessions.put(player.getUniqueId(), session);
        GuiManager.SESSIONS.add(ctx.plugin(), session);
        session.open();
    }

    /** Template declared under {@code templates:} with the given id, or null. */
    public @Nullable GuiTemplate template(String templateId) {
        return def.template(templateId);
    }

    /** Live session of the player in THIS GUI, or null when they have none. */
    public @Nullable GuiSession session(Player player) {
        return player == null ? null : sessions.get(player.getUniqueId());
    }

    /** Drops the session mapping of a viewer; only the closing session calls this. */
    void removeSession(UUID viewer, GuiSession session) {
        sessions.remove(viewer, session);
    }
}
