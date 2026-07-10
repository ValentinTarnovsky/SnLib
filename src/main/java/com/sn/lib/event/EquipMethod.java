package com.sn.lib.event;

/** Input vector through which an armour piece was equipped or unequipped. */
public enum EquipMethod {

    /** Shift-click of the piece between the inventory and its armour slot. */
    SHIFT_CLICK,

    /** Drag of the piece onto the armour slot inside the inventory. */
    DRAG,

    /**
     * Cursor pick-up and drop into or out of the armour slot. Also the generic manual
     * vector reported when the synthesized source does not expose the exact input.
     */
    PICK_DROP,

    /** Right-click equip of the piece held in the hand, without opening the inventory. */
    HOTBAR,

    /** Number-key swap while hovering the armour slot. */
    HOTBAR_SWAP,

    /** Auto-equip by a dispenser. */
    DISPENSER,

    /** The piece broke after spending its durability. */
    BROKE,

    /** The piece left its slot because the player died. */
    DEATH
}
