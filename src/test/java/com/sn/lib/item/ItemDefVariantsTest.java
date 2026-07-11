package com.sn.lib.item;

import java.util.List;
import java.util.function.BiConsumer;

import org.bukkit.entity.Player;
import org.bukkit.inventory.ItemStack;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ItemDefVariantsTest {

    @Test
    void shiftPositionalListsDefaultEmpty() {
        ItemDef def = ItemDef.builder().build();
        assertTrue(def.shiftRightClickBlockActions().isEmpty());
        assertTrue(def.shiftRightClickAirActions().isEmpty());
        assertTrue(def.shiftLeftClickBlockActions().isEmpty());
        assertTrue(def.shiftLeftClickAirActions().isEmpty());
        assertNull(def.onShiftRightClickBlock());
        assertNull(def.onShiftRightClickAir());
        assertNull(def.onShiftLeftClickBlock());
        assertNull(def.onShiftLeftClickAir());
    }

    @Test
    void builderSetsShiftPositionalListsAndCallbacks() {
        BiConsumer<Player, ItemStack> rightBlock = (player, item) -> { };
        BiConsumer<Player, ItemStack> rightAir = (player, item) -> { };
        BiConsumer<Player, ItemStack> leftBlock = (player, item) -> { };
        BiConsumer<Player, ItemStack> leftAir = (player, item) -> { };
        ItemDef def = ItemDef.builder()
                .shiftRightClickBlockActions(List.of("[message] srb"))
                .shiftRightClickAirActions(List.of("[message] sra"))
                .shiftLeftClickBlockActions(List.of("[message] slb"))
                .shiftLeftClickAirActions(List.of("[message] sla"))
                .onShiftRightClickBlock(rightBlock)
                .onShiftRightClickAir(rightAir)
                .onShiftLeftClickBlock(leftBlock)
                .onShiftLeftClickAir(leftAir)
                .build();
        assertEquals(List.of("[message] srb"), def.shiftRightClickBlockActions());
        assertEquals(List.of("[message] sra"), def.shiftRightClickAirActions());
        assertEquals(List.of("[message] slb"), def.shiftLeftClickBlockActions());
        assertEquals(List.of("[message] sla"), def.shiftLeftClickAirActions());
        assertSame(rightBlock, def.onShiftRightClickBlock());
        assertSame(rightAir, def.onShiftRightClickAir());
        assertSame(leftBlock, def.onShiftLeftClickBlock());
        assertSame(leftAir, def.onShiftLeftClickAir());
    }

    @Test
    void shiftOverridesGenericDefaultsTrue() {
        assertTrue(ItemDef.builder().build().shiftOverridesGeneric());
    }

    @Test
    void builderDisablesShiftOverridesGeneric() {
        assertFalse(ItemDef.builder().shiftOverridesGeneric(false).build()
                .shiftOverridesGeneric());
    }
}
