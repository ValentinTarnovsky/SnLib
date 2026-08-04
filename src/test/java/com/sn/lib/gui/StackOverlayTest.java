package com.sn.lib.gui;

import java.util.List;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.sn.lib.Ph;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * Rule-level cover of the appearance overlay a template paints over a PLUGIN-SUPPLIED stack
 * ({@code GuiSession.bind(slot, template, stack, phs)}, {@link PhCollector#stack} and
 * {@link GuiEntry#stack}): the name replaces only when the template declares one, the lore
 * appends only when the template declares some, and a template declaring neither leaves the
 * stack alone.
 *
 * <p>Building a real {@code ItemStack} needs a running server, so the contract is driven
 * through the two pure rules {@code StackOverlay.apply} composes - a null from either one is
 * precisely "do not touch that field of the stack", which is how the untouched case is
 * asserted. The fallback to the template render is driven the same way, through the null
 * the collectors report when the plugin supplied no stack: that null IS the branch the
 * render paths take.</p>
 */
class StackOverlayTest {

    private static final List<Component> EXISTING = List.of(
            Component.text("first"), Component.text("second"));

    @Test
    void aDeclaredNameOverridesTheStacksOwn() {
        Component name = StackOverlay.name("&aReward");

        assertNotNull(name);
        // The legacy code is consumed by the pipeline, not left sitting in the text.
        assertEquals("Reward", plain(name));
    }

    @Test
    void aRenderedNameIsNonItalicLikeEveryOtherRenderPath() {
        Component name = StackOverlay.name("Plain");

        assertNotNull(name);
        assertEquals(TextDecoration.State.FALSE, name.decoration(TextDecoration.ITALIC));
    }

    @Test
    void aNameResolvesTheLocalPlaceholdersOfTheEntry() {
        Component name = StackOverlay.name("&e%reward%", Ph.of("reward", "Diamond"));

        assertNotNull(name);
        assertEquals("Diamond", plain(name));
    }

    @Test
    void anUndeclaredNameLeavesTheStacksOwnAlone() {
        assertNull(StackOverlay.name(null));
        assertNull(StackOverlay.name(""));
    }

    @Test
    void aNameThatResolvesToNothingIsNotDeclared() {
        // Emptiness is judged AFTER the locals resolve, exactly like the template-rendered
        // path: a name that is one placeholder the entry left empty overrides nothing.
        assertNull(StackOverlay.name("%reward%", Ph.of("reward", "")));
    }

    @Test
    void declaredLoreIsAppendedAfterTheStacksOwnLore() {
        List<Component> lore = StackOverlay.lore(EXISTING, List.of("&7Click to claim"));

        assertNotNull(lore);
        assertEquals(3, lore.size());
        assertEquals("first", plain(lore.get(0)));
        assertEquals("second", plain(lore.get(1)));
        assertEquals("Click to claim", plain(lore.get(2)));
    }

    @Test
    void declaredLoreOnAStackWithoutLoreIsTheWholeLore() {
        List<Component> lore = StackOverlay.lore(null, List.of("&7Only line"));

        assertNotNull(lore);
        assertEquals(1, lore.size());
        assertEquals("Only line", plain(lore.get(0)));
    }

    @Test
    void loreResolvesTheLocalPlaceholdersOfTheEntry() {
        List<Component> lore = StackOverlay.lore(null, List.of("&7Chance: %chance%%"),
                Ph.of("chance", 25));

        assertNotNull(lore);
        assertEquals("Chance: 25%", plain(lore.get(0)));
    }

    @Test
    void aLoreLineWithNewlinesSplitsIntoOneLinePerSegment() {
        // The same split SnItem.lore applies, so a multi-line value can flow through a
        // single placeholder on this path too.
        List<Component> lore = StackOverlay.lore(null, List.of("%body%"),
                Ph.of("body", "one\ntwo\nthree"));

        assertNotNull(lore);
        assertEquals(3, lore.size());
        assertEquals("one", plain(lore.get(0)));
        assertEquals("two", plain(lore.get(1)));
        assertEquals("three", plain(lore.get(2)));
    }

    @Test
    void anEmptyDeclaredLineIsStillALine() {
        // A blank lore line is a deliberate spacer, so it survives; only an empty LIST
        // means the template declared nothing.
        List<Component> lore = StackOverlay.lore(null, List.of("&7Top", "", "&7Bottom"));

        assertNotNull(lore);
        assertEquals(3, lore.size());
        assertEquals("", plain(lore.get(1)));
    }

    @Test
    void undeclaredLoreLeavesTheStacksOwnAlone() {
        // Null, never an empty list: an empty list would CLEAR the lore the stack came with.
        assertNull(StackOverlay.lore(EXISTING, null));
        assertNull(StackOverlay.lore(EXISTING, List.of()));
    }

    @Test
    void aTemplateDeclaringNeitherFieldLeavesTheStackUntouched() {
        // Both rules null, so apply() writes no meta at all and returns the plain clone -
        // the whole "the supplied stack is authoritative" contract for a bare template.
        assertNull(StackOverlay.name(""));
        assertNull(StackOverlay.lore(EXISTING, List.of()));
    }

    @Test
    void everyShapeOfAnUndeclaredFieldIsAStrictPassThrough() {
        // A consumer handing over a fully-formed stack needs SnLib to add NOTHING: no empty
        // line appended, no lore cleared, no name written. Every way a template can leave
        // the two fields out has to reach that same "do not touch it" null, whether the key
        // is absent (the "" / List.of() defaults GuiItemDef.renderOver reads), written
        // blank, or written as an empty list.
        for (String undeclaredName : new String[] {null, ""}) {
            assertNull(StackOverlay.name(undeclaredName),
                    () -> "name(" + undeclaredName + ") must not touch the stack's name");
            assertNull(StackOverlay.name(undeclaredName, Ph.of("unused", "x")));
        }
        for (List<String> undeclaredLore : List.of(List.<String>of())) {
            assertNull(StackOverlay.lore(EXISTING, undeclaredLore));
            assertNull(StackOverlay.lore(null, undeclaredLore));
            assertNull(StackOverlay.lore(EXISTING, undeclaredLore, Ph.of("unused", "x")));
        }
        assertNull(StackOverlay.lore(EXISTING, null));
        assertNull(StackOverlay.lore(null, null));
    }

    @Test
    void anUndeclaredLoreIsNeverConfusedWithADeclaredEmptyOne() {
        // The one distinction the whole strictness rests on: an empty LIST means "declared
        // nothing" (null, leave the lore alone), while a list holding an empty STRING means
        // "declared one blank line" (a spacer, appended like any other line).
        assertNull(StackOverlay.lore(EXISTING, List.of()));

        List<Component> withSpacer = StackOverlay.lore(EXISTING, List.of(""));
        assertNotNull(withSpacer);
        assertEquals(3, withSpacer.size());
        assertEquals("", plain(withSpacer.get(2)));
    }

    @Test
    void theExistingLoreIsCopiedAndItsLinesAreKeptAsTheyAre() {
        List<Component> lore = StackOverlay.lore(EXISTING, List.of("&7Extra"));

        assertNotNull(lore);
        assertEquals(2, EXISTING.size());
        assertNotSame(EXISTING, lore);
        assertSame(EXISTING.get(0), lore.get(0));
    }

    @Test
    void aCollectorThatSuppliesNoStackFallsBackToTheTemplateRender() {
        // Null is what the render paths branch on: no stack supplied means the template
        // builds the cell exactly as it did before this overload existed.
        assertNull(new PhCollector().stack());
        assertNull(new PhCollector().add("name", "Diamond").stack());
        assertNull(new PhCollector().stack(null).stack());
    }

    @Test
    void anEntryThatSuppliesNoStackFallsBackToTheTemplateRender() {
        assertNull(new GuiEntry().stack());
        assertNull(new GuiEntry().template("reward").add("id", 1).stack());
        assertNull(new GuiEntry().stack(null).stack());
    }

    private static String plain(Component component) {
        return PlainTextComponentSerializer.plainText().serialize(component);
    }
}
