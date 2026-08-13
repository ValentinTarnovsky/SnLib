package com.sn.lib.gui;

import java.util.Locale;
import java.util.function.Consumer;

import org.jetbrains.annotations.Nullable;

/**
 * What a menu lets its viewer do with their OWN inventory while it is open, declared per
 * menu through the {@code player-inventory:} key (1.28.0) and defaulting to
 * {@link #LOCKED}, which is the behaviour of every release before it.
 *
 * <p>The policy governs the BOTTOM inventory only. What the menu's own cells accept is a
 * separate, per-cell decision: the item-level {@code input: true} key (see
 * {@link GuiItemDef#input()}). The two are orthogonal by design - a menu may open the
 * player inventory without declaring a single input cell (the viewer just moves their own
 * items normally while the menu is up), and a cell declared as input is read the same way
 * under either policy.</p>
 *
 * <p>In practice an input cell needs {@link #OPEN} to be REACHABLE: under {@link #LOCKED}
 * every bottom click is cancelled, so the viewer can never pick a stack up and their
 * cursor is always empty. {@link GuiDef} WARNs about that combination at parse time.</p>
 */
public enum PlayerInventoryPolicy {

    /**
     * Default, and byte-identical to every release before 1.28.0: every click and every
     * drag over the player's own inventory is cancelled while the menu is open. Nothing
     * moves, nothing can be picked up, and the viewer's cursor stays empty for the whole
     * session.
     */
    LOCKED,

    /**
     * The viewer uses their own inventory normally while the menu is open: plain clicks,
     * number keys, drops, offhand swaps and drags that stay inside the player inventory
     * are left uncancelled. Two things stay cancelled regardless, and they are what makes
     * this safe: every click over the MENU's cells, and {@code COLLECT_TO_CURSOR} (the
     * double-click gather), so no rendered GUI stack can reach the cursor or the player.
     *
     * <p>Under this policy a shift-click on a bottom stack becomes an OFFER rather than a
     * move: the event is cancelled and the stack is reported to the session as an
     * {@link ItemOffer} of kind {@link ItemOffer.Kind#SHIFT_CLICK}, since a shift-click
     * aims INTO the menu and SnLib never writes into a menu's inventory on a consumer's
     * behalf.</p>
     */
    OPEN;

    /**
     * Lenient resolution of the declared {@code player-inventory:} value: blank means the
     * {@link #LOCKED} default, case is irrelevant and a hyphen reads as an underscore.
     * An unknown value WARNs through {@code warn} and falls back to {@link #LOCKED},
     * matching how {@code inventory-type} resolves - a malformed menu field never throws
     * and never silently opens an inventory the owner did not ask to open.
     */
    static PlayerInventoryPolicy resolve(@Nullable String raw, Consumer<String> warn) {
        String name = raw == null ? "" : raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        if (name.isEmpty()) {
            return LOCKED;
        }
        try {
            return valueOf(name);
        } catch (IllegalArgumentException e) {
            warn.accept("Invalid player-inventory '" + raw + "'; using LOCKED");
            return LOCKED;
        }
    }
}
