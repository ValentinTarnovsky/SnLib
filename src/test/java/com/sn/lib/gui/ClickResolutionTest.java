package com.sn.lib.gui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.event.inventory.ClickType;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sn.lib.yml.SnYmlTestAccess;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ClickResolutionTest {

    @Test
    void shiftKeyMapsOnlyShiftClicks() {
        assertEquals(GuiItemDef.ClickKey.SHIFT_RIGHT, GuiItemDef.shiftKey(ClickType.SHIFT_RIGHT));
        assertEquals(GuiItemDef.ClickKey.SHIFT_LEFT, GuiItemDef.shiftKey(ClickType.SHIFT_LEFT));
        assertNull(GuiItemDef.shiftKey(ClickType.RIGHT));
        assertNull(GuiItemDef.shiftKey(ClickType.LEFT));
        assertNull(GuiItemDef.shiftKey(ClickType.MIDDLE));
        assertNull(GuiItemDef.shiftKey(ClickType.DOUBLE_CLICK));
        assertNull(GuiItemDef.shiftKey(null));
    }

    @Test
    void sideKeyGroupsRightFamily() {
        assertEquals(GuiItemDef.ClickKey.RIGHT, GuiItemDef.sideKey(ClickType.RIGHT));
        assertEquals(GuiItemDef.ClickKey.RIGHT, GuiItemDef.sideKey(ClickType.SHIFT_RIGHT));
    }

    @Test
    void sideKeyGroupsDoubleClickAndCreativeWithLeft() {
        assertEquals(GuiItemDef.ClickKey.LEFT, GuiItemDef.sideKey(ClickType.LEFT));
        assertEquals(GuiItemDef.ClickKey.LEFT, GuiItemDef.sideKey(ClickType.SHIFT_LEFT));
        assertEquals(GuiItemDef.ClickKey.LEFT, GuiItemDef.sideKey(ClickType.DOUBLE_CLICK));
        assertEquals(GuiItemDef.ClickKey.LEFT, GuiItemDef.sideKey(ClickType.CREATIVE));
    }

    @Test
    void sideKeyMapsMiddle() {
        assertEquals(GuiItemDef.ClickKey.MIDDLE, GuiItemDef.sideKey(ClickType.MIDDLE));
    }

    @Test
    void sideKeyNullForKeyboardAndUnknownClicks() {
        assertNull(GuiItemDef.sideKey(ClickType.NUMBER_KEY));
        assertNull(GuiItemDef.sideKey(ClickType.DROP));
        assertNull(GuiItemDef.sideKey(ClickType.CONTROL_DROP));
        assertNull(GuiItemDef.sideKey(ClickType.SWAP_OFFHAND));
        assertNull(GuiItemDef.sideKey(ClickType.UNKNOWN));
    }

    @Test
    void basicClickIsExactlyTheFourMouseClicks() {
        assertTrue(GuiItemDef.basicClick(ClickType.LEFT));
        assertTrue(GuiItemDef.basicClick(ClickType.RIGHT));
        assertTrue(GuiItemDef.basicClick(ClickType.SHIFT_LEFT));
        assertTrue(GuiItemDef.basicClick(ClickType.SHIFT_RIGHT));
        assertFalse(GuiItemDef.basicClick(ClickType.MIDDLE));
        assertFalse(GuiItemDef.basicClick(ClickType.DOUBLE_CLICK));
        assertFalse(GuiItemDef.basicClick(ClickType.NUMBER_KEY));
        assertFalse(GuiItemDef.basicClick(ClickType.DROP));
        assertFalse(GuiItemDef.basicClick(ClickType.CREATIVE));
        assertFalse(GuiItemDef.basicClick(ClickType.UNKNOWN));
        assertFalse(GuiItemDef.basicClick(null));
    }

    // ------------------------------------------------------- the drop key (1.31.0)

    @Test
    void dropKeyMapsBothDropClicksAndNothingElse() {
        assertEquals(GuiItemDef.ClickKey.DROP, GuiItemDef.dropKey(ClickType.DROP));
        assertEquals(GuiItemDef.ClickKey.DROP, GuiItemDef.dropKey(ClickType.CONTROL_DROP));
        // Exhaustive over the enum, so the other keyboard clicks can never quietly join:
        // opening NUMBER_KEY, SWAP_OFFHAND or UNKNOWN is an explicit non-goal of 1.31.0.
        for (ClickType click : ClickType.values()) {
            if (click == ClickType.DROP || click == ClickType.CONTROL_DROP) {
                continue;
            }
            assertNull(GuiItemDef.dropKey(click), click.name());
        }
        assertNull(GuiItemDef.dropKey(null));
    }

    @Test
    void aDeclaredDropListWinsOverTheGenericOneForBothDropClicks(@TempDir File dir)
            throws IOException {
        GuiItemDef def = item(dir,
                "click-actions: ['[message] generic']",
                "drop-click-actions: ['[message] dropped']");

        assertEquals(List.of("[message] dropped"), def.clickActionsFor(ClickType.DROP));
        assertEquals(List.of("[message] dropped"), def.clickActionsFor(ClickType.CONTROL_DROP));
        assertTrue(def.specificActionsFor(ClickType.DROP));
        assertTrue(def.specificActionsFor(ClickType.CONTROL_DROP));
        // The mouse half of the matrix does not notice the new entry at all.
        assertSame(def.clickActions(), def.clickActionsFor(ClickType.LEFT));
        assertSame(def.clickActions(), def.clickActionsFor(ClickType.RIGHT));
        assertSame(def.clickActions(), def.clickActionsFor(ClickType.SHIFT_LEFT));
        assertSame(def.clickActions(), def.clickActionsFor(ClickType.MIDDLE));
        assertFalse(def.specificActionsFor(ClickType.LEFT));
    }

    @Test
    void dropRequirementsAndDenyActionsResolveLikeEveryOtherKey(@TempDir File dir)
            throws IOException {
        GuiItemDef def = item(dir,
                "click-actions: ['[message] generic']",
                "click-requirements: ['%vault_eco_balance% > 0']",
                "deny-actions: ['[message] generic-deny']",
                "drop-click-actions: ['[message] dropped']",
                "drop-click-requirements: ['%vault_eco_balance% > 100']",
                "drop-click-deny-actions: ['[message] drop-deny']");

        assertEquals(List.of("[message] drop-deny"), def.denyActionsFor(ClickType.DROP));
        assertEquals(List.of("[message] drop-deny"), def.denyActionsFor(ClickType.CONTROL_DROP));
        assertNotSame(def.clickRequirement(), def.clickRequirementFor(ClickType.DROP));
        assertNotSame(def.clickRequirement(), def.clickRequirementFor(ClickType.CONTROL_DROP));
        assertSame(def.denyActions(), def.denyActionsFor(ClickType.LEFT));
        assertSame(def.clickRequirement(), def.clickRequirementFor(ClickType.LEFT));
    }

    @Test
    void eachDropFieldFallsBackToTheGenericOneOnItsOwn(@TempDir File dir) throws IOException {
        GuiItemDef def = item(dir,
                "click-actions: ['[message] generic']",
                "click-requirements: ['%vault_eco_balance% > 0']",
                "deny-actions: ['[message] generic-deny']",
                "drop-click-actions: ['[message] dropped']");

        assertEquals(List.of("[message] dropped"), def.clickActionsFor(ClickType.DROP));
        assertSame(def.clickRequirement(), def.clickRequirementFor(ClickType.DROP));
        assertSame(def.denyActions(), def.denyActionsFor(ClickType.DROP));
    }

    @Test
    void anItemWithoutDropListsResolvesExactlyAsItDidBefore1310(@TempDir File dir)
            throws IOException {
        GuiItemDef def = item(dir,
                "click-actions: ['[message] generic']",
                "click-requirements: ['%vault_eco_balance% > 0']",
                "deny-actions: ['[message] generic-deny']",
                "middle-click-actions: ['[message] middle']");

        // The whole compatibility claim of 1.31.0 in one loop: a menu that declares no
        // drop list resolves a Q press to the generic trio, exactly as 1.30.0 did.
        for (ClickType click : new ClickType[] {ClickType.DROP, ClickType.CONTROL_DROP}) {
            assertSame(def.clickActions(), def.clickActionsFor(click), click.name());
            assertSame(def.clickRequirement(), def.clickRequirementFor(click), click.name());
            assertSame(def.denyActions(), def.denyActionsFor(click), click.name());
            assertFalse(def.specificActionsFor(click), click.name());
        }
    }

    @Test
    void strictClicksLetADeclaredDropThroughAndKeepDiscardingAnUndeclaredOne(@TempDir File dir)
            throws IOException {
        GuiItemDef declared = item(dir,
                "click-actions: ['[message] generic']",
                "drop-click-actions: ['[message] dropped']");
        GuiItemDef plain = item(dir, "click-actions: ['[message] generic']");

        assertFalse(discardedByStrictClicks(declared, ClickType.DROP));
        assertFalse(discardedByStrictClicks(declared, ClickType.CONTROL_DROP));
        assertTrue(discardedByStrictClicks(plain, ClickType.DROP));
        assertTrue(discardedByStrictClicks(plain, ClickType.CONTROL_DROP));
    }

    @Test
    void strictClicksStillDiscardTheOtherKeyboardClicksWithADropListDeclared(@TempDir File dir)
            throws IOException {
        GuiItemDef def = item(dir,
                "click-actions: ['[message] generic']",
                "drop-click-actions: ['[message] dropped']");

        for (ClickType click : new ClickType[] {ClickType.NUMBER_KEY, ClickType.SWAP_OFFHAND,
                ClickType.UNKNOWN}) {
            assertTrue(discardedByStrictClicks(def, click), click.name());
        }
    }

    @Test
    void paginationTagsAreDetectedInTheDropListToo(@TempDir File dir) throws IOException {
        assertEquals(GuiItemDef.NavKind.NEXT,
                item(dir, "drop-click-actions: ['[next-page]']").navKind());
        assertEquals(GuiItemDef.NavKind.PREVIOUS,
                item(dir, "drop-click-actions: ['[previous-page]']").navKind());
        assertEquals(GuiItemDef.NavKind.NONE,
                item(dir, "click-actions: ['[message] generic']").navKind());
    }

    // ------------------------------------------------------------------ fixtures

    /**
     * Parses ONE item out of real yml text through the production parser, the way
     * {@code GuiDef} does at load: {@code lines} are the item's own keys, written under an
     * {@code item:} root and indented here, with flow sequences keeping a fixture to one
     * line per key. Going through the parser is the point - a hand-built definition would
     * prove the resolution and nothing about whether the yml key is read at all. Any parse
     * warning fails the test, since every fixture here is meant to be valid config.
     */
    private static GuiItemDef item(File dir, String... lines) throws IOException {
        StringBuilder text = new StringBuilder("item:\n");
        for (String line : lines) {
            text.append("  ").append(line).append('\n');
        }
        File file = File.createTempFile("item", ".yml", dir);
        Files.writeString(file.toPath(), text.toString(), StandardCharsets.UTF_8);
        List<String> warnings = new ArrayList<>();
        GuiItemDef def = GuiItemDef.parse(SnYmlTestAccess.of(file), "item", "test-item",
                Map.of(), warnings::add);
        assertNotNull(def, "the fixture did not parse");
        assertTrue(warnings.isEmpty(), () -> "unexpected warnings: " + warnings);
        return def;
    }

    /**
     * The strict-clicks gate of {@code GuiSession.runClick} as a pure predicate: under
     * {@code strict-clicks: true} a click is discarded when it is not one of the four basic
     * mouse clicks AND the item declares no specific actions list covering it. Mirrored
     * here because the gate needs a live session while both of its inputs are pure.
     */
    private static boolean discardedByStrictClicks(GuiItemDef item, ClickType click) {
        return !GuiItemDef.basicClick(click) && !item.specificActionsFor(click);
    }
}
