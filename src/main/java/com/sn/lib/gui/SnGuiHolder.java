package com.sn.lib.gui;

import org.bukkit.inventory.Inventory;
import org.bukkit.plugin.Plugin;

import com.sn.lib.tenant.OwnedHolder;

/**
 * Inventory holder of every GUI inventory the library creates: one holder per
 * {@link GuiSession}, shared by every inventory that session recreates.
 *
 * <p>Identification is ALWAYS {@code holder instanceof SnGuiHolder}, NEVER by inventory
 * title: titles are viewer-resolved and may collide across plugins. As an
 * {@link OwnedHolder} it also carries the owning plugin, which is how the tenant sweeper
 * and the quit cleanup listener close library inventories of exactly one owner.</p>
 */
public final class SnGuiHolder implements OwnedHolder {

    private final Plugin owner;
    private final String guiId;
    private final GuiSession session;

    private volatile Inventory inventory;

    SnGuiHolder(Plugin owner, String guiId, GuiSession session) {
        this.owner = owner;
        this.guiId = guiId;
        this.session = session;
    }

    /** Consumer plugin whose context opened this GUI. */
    @Override
    public Plugin owner() {
        return owner;
    }

    /** Id of the GUI definition (the file name without extension). */
    public String guiId() {
        return guiId;
    }

    /** Per-viewer session behind this holder. */
    public GuiSession session() {
        return session;
    }

    /**
     * Current inventory of the session. The session recreates the inventory on title or
     * size changes while keeping THIS holder, so instanceof identification survives every
     * recreation.
     */
    @Override
    public Inventory getInventory() {
        Inventory current = inventory;
        if (current == null) {
            throw new IllegalStateException("Session of gui '" + guiId + "' has not created its inventory yet");
        }
        return current;
    }

    /** Swaps the backing inventory; only the owning session calls this. */
    void inventory(Inventory inventory) {
        this.inventory = inventory;
    }
}
