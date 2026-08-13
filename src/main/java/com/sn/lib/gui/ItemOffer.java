package com.sn.lib.gui;

import java.util.Objects;

import org.bukkit.entity.Player;
import org.bukkit.event.inventory.ClickType;
import org.bukkit.inventory.ItemStack;

/**
 * One stack a viewer OFFERED to a menu (1.28.0): the report SnLib hands a session when the
 * player aimed an item of their own at it. Delivered to the handler registered through
 * {@link GuiSession#onOffer(java.util.function.Consumer)}, on the main thread, inside the
 * inventory event that produced it.
 *
 * <p><b>The library guarantee: the item is READ, never consumed.</b> Every event that
 * produces an offer is cancelled BEFORE the handler runs, and this record carries a
 * defensive clone. SnLib never moves, writes, shrinks or deletes the offered stack, and
 * never writes it into the menu's inventory - the cancel is what puts the cursor, the drag
 * or the shift-clicked stack back exactly where it was. What "accepting" an offer means
 * belongs entirely to the consumer: it reads {@link #stack()}, decides how much of it it
 * takes, and then writes the remainder back itself.</p>
 *
 * <p>Which fields carry information depends on {@link #kind()}:</p>
 * <ul>
 *   <li>{@link Kind#CURSOR} - the viewer clicked an input cell while holding a stack.
 *       {@link #slot()} is the input cell they aimed at; {@link #playerSlot()} is -1.
 *       {@link #click()} is LEFT or RIGHT, the only two clicks that offer, so a consumer
 *       that wants the vanilla "right click deposits one" convention reads it here.</li>
 *   <li>{@link Kind#DRAG} - the viewer dragged a stack over exactly ONE input cell.
 *       {@link #slot()} is that cell; {@link #playerSlot()} is -1. {@link #click()} is the
 *       mouse button behind the drag: RIGHT for a single-item drag, LEFT for an even
 *       spread.</li>
 *   <li>{@link Kind#SHIFT_CLICK} - the viewer shift-clicked a stack in their OWN
 *       inventory while the menu declared {@code player-inventory: open}. No cell was
 *       aimed at, so {@link #slot()} is -1 and {@link #playerSlot()} is the player
 *       inventory slot the stack came from. That index is what makes a DEPOSIT
 *       expressible: compute how much you accepted and write the remainder back with
 *       {@code viewer.getInventory().setItem(offer.playerSlot(), remainder)}.</li>
 * </ul>
 *
 * <p><b>Ghost stacks, and why the write-back needs a resend.</b> The click was cancelled,
 * so the client is still drawing the stack it had before the event. When a consumer then
 * changes that slot server-side (the remainder of a partial deposit, or clearing it on a
 * full one) the client does not know, and keeps painting the stale stack until something
 * else refreshes it. Follow every write-back with
 * {@code viewer.updateInventory()} - one resend, right after the write.</p>
 *
 * @param viewer     player who made the offer; always the session's own viewer
 * @param kind       how the stack was offered, which decides what {@code slot} and
 *                   {@code playerSlot} mean
 * @param slot       menu cell the offer targeted, or -1 for a {@link Kind#SHIFT_CLICK}
 * @param playerSlot player-inventory slot the stack came from, or -1 for the two kinds
 *                   that come from the cursor
 * @param stack      what was offered, WITH its real amount (a deposit needs the amount),
 *                   always a defensive copy: mutating it changes nothing anywhere
 * @param click      mouse button behind the offer, never null
 */
public record ItemOffer(Player viewer, Kind kind, int slot, int playerSlot, ItemStack stack,
                        ClickType click) {

    /** How the viewer aimed the stack at the menu; decides which slot field is set. */
    public enum Kind {
        /** Clicked an input cell with a stack on the cursor (LEFT or RIGHT). */
        CURSOR,
        /** Dragged a stack over exactly one input cell. */
        DRAG,
        /** Shift-clicked a stack in the player's own inventory (needs {@code player-inventory: open}). */
        SHIFT_CLICK
    }

    /**
     * Copies the stack on the way in, so the offer can never alias the live cursor, the
     * live player-inventory slot or anything the caller keeps mutating. The accessor hands
     * that same copy out without cloning again: it is already an instance nothing else
     * references, and the consumer is expected to read its amount and its meta.
     */
    public ItemOffer {
        Objects.requireNonNull(viewer, "viewer");
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(click, "click");
        stack = Objects.requireNonNull(stack, "stack").clone();
    }
}
