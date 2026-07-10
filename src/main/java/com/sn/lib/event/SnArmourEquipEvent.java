package com.sn.lib.event;

import org.bukkit.entity.Player;
import org.bukkit.event.HandlerList;
import org.bukkit.inventory.EquipmentSlot;
import org.bukkit.inventory.ItemStack;
import org.jetbrains.annotations.Nullable;

/**
 * Fired when a player equips or unequips an armour piece through any vector.
 *
 * <p>Synthesized by the library's shared armour-equip listener. Cancellation is binding
 * only when the underlying source is cancellable ({@link EquipMethod#DISPENSER}); events
 * reporting an already-applied change (primary source, {@link EquipMethod#DEATH}) expose
 * it as a consumer-level signal.</p>
 */
public final class SnArmourEquipEvent extends SnPlayerEvent {

    private static final HandlerList HANDLERS = new HandlerList();

    private final EquipMethod method;
    private final EquipmentSlot slot;
    private @Nullable ItemStack oldPiece;
    private @Nullable ItemStack newPiece;

    /**
     * @param player   player whose armour changed
     * @param method   input vector of the change
     * @param slot     armour slot affected
     * @param oldPiece piece leaving the slot, or null when the slot was empty
     * @param newPiece piece entering the slot, or null when the slot empties
     */
    public SnArmourEquipEvent(Player player, EquipMethod method, EquipmentSlot slot,
            @Nullable ItemStack oldPiece, @Nullable ItemStack newPiece) {
        super(player);
        this.method = method;
        this.slot = slot;
        this.oldPiece = oldPiece;
        this.newPiece = newPiece;
    }

    /** Input vector of the change. */
    public EquipMethod getMethod() {
        return method;
    }

    /** Armour slot affected. */
    public EquipmentSlot getSlot() {
        return slot;
    }

    /** Piece leaving the slot, or null when the slot was empty. */
    public @Nullable ItemStack getOldPiece() {
        return oldPiece;
    }

    public void setOldPiece(@Nullable ItemStack oldPiece) {
        this.oldPiece = oldPiece;
    }

    /** Piece entering the slot, or null when the slot empties. */
    public @Nullable ItemStack getNewPiece() {
        return newPiece;
    }

    public void setNewPiece(@Nullable ItemStack newPiece) {
        this.newPiece = newPiece;
    }

    @Override
    public HandlerList getHandlers() {
        return HANDLERS;
    }

    public static HandlerList getHandlerList() {
        return HANDLERS;
    }
}
