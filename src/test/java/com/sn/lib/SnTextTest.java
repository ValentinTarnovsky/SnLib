package com.sn.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;

import com.sn.lib.text.SnText;

/**
 * Component-tree assertions for {@link SnText#color(String)}: a legacy COLOR code resets the
 * decorations opened by earlier legacy format codes (vanilla / legacyAmpersand semantics),
 * while author-written MiniMessage tags keep pure MiniMessage semantics. The tree is
 * flattened into spans carrying their effective (inherited) bold flag and color.
 */
class SnTextTest {

    /** A run of text with its effective bold flag and color after inheriting parent styles. */
    private record Span(String text, boolean bold, TextColor color) {
    }

    private static List<Span> spans(Component component) {
        List<Span> out = new ArrayList<>();
        collect(component, false, null, out);
        return out;
    }

    private static void collect(Component component, boolean parentBold, TextColor parentColor, List<Span> out) {
        boolean bold = switch (component.style().decoration(TextDecoration.BOLD)) {
            case TRUE -> true;
            case FALSE -> false;
            case NOT_SET -> parentBold;
        };
        TextColor color = component.style().color() != null ? component.style().color() : parentColor;
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            out.add(new Span(text.content(), bold, color));
        }
        for (Component child : component.children()) {
            collect(child, bold, color, out);
        }
    }

    /** First flattened span whose visible text contains {@code needle}. */
    private static Span find(Component component, String needle) {
        for (Span span : spans(component)) {
            if (span.text().contains(needle)) {
                return span;
            }
        }
        throw new AssertionError("no span containing '" + needle + "' in " + spans(component));
    }

    private static String plain(Component component) {
        StringBuilder sb = new StringBuilder();
        appendPlain(component, sb);
        return sb.toString();
    }

    private static void appendPlain(Component component, StringBuilder sb) {
        if (component instanceof TextComponent text) {
            sb.append(text.content());
        }
        for (Component child : component.children()) {
            appendPlain(child, sb);
        }
    }

    @Test
    void legacyColorAfterBoldResetsBoldButKeepsColor() {
        Component c = SnText.color("&#8354f2&lSnClans &8| &7&cBody");
        assertTrue(find(c, "SnClans").bold(), "the legacy &l prefix stays bold");
        assertFalse(find(c, "|").bold(), "a legacy color code must clear the bold");
        Span body = find(c, "Body");
        assertFalse(body.bold(), "bold must not bleed past the legacy color codes");
        assertEquals(NamedTextColor.RED, body.color(), "&c renders red");
    }

    @Test
    void boldThenLegacyColorCharClearsBold() {
        Component c = SnText.color("&lA&cB");
        assertTrue(find(c, "A").bold());
        assertFalse(find(c, "B").bold());
    }

    @Test
    void boldThenLegacyHexColorClearsBold() {
        Component c = SnText.color("&lA&#ff0000B");
        assertTrue(find(c, "A").bold());
        Span b = find(c, "B");
        assertFalse(b.bold());
        assertEquals(TextColor.color(0xFF0000), b.color());
    }

    @Test
    void resetClearsBold() {
        Component c = SnText.color("&lA&rB");
        assertTrue(find(c, "A").bold());
        assertFalse(find(c, "B").bold());
    }

    @Test
    void miniMessageColorTagDoesNotResetDecorations() {
        // Pure MiniMessage input: a <gray> color tag must NOT clear the author's <bold>.
        Component c = SnText.color("<bold>A<gray>B");
        assertTrue(find(c, "A").bold());
        assertTrue(find(c, "B").bold(), "a MiniMessage color tag keeps pure MiniMessage semantics");
        assertEquals(NamedTextColor.GRAY, find(c, "B").color());
    }

    @Test
    void legacyHexColorValueIsTranslatedExactly() {
        Component c = SnText.color("&#8354f2SnClans");
        assertEquals(TextColor.color(0x8354F2), find(c, "SnClans").color());
    }

    @Test
    void rgbGradientComposesWithLegacyBold() {
        Component c = SnText.color("[rgb]&lHello");
        assertEquals("Hello", plain(c));
        for (Span span : spans(c)) {
            assertTrue(span.bold(), "every gradient glyph keeps the legacy &l bold: " + span);
            assertNotNull(span.color(), "every gradient glyph carries a hex color: " + span);
        }
    }

    @Test
    void smallCapsComposesWithLegacyBold() {
        Component c = SnText.color("[small]&lhi");
        assertEquals(SnText.smallCaps("hi"), plain(c));
        assertTrue(find(c, SnText.smallCaps("hi")).bold());
    }

    @Test
    void rgbGradientLegacyColorClearsAccumulatedBold() {
        // The prefix-bleed shape: withPrefix inserts the prefix after [rgb], so the
        // prefix's &l must not survive its own trailing &8/&7 into the message body.
        Component c = SnText.color("[rgb]&#8354f2&lSnMiniGames &8| &7Configuration reloaded.");
        assertEquals("SnMiniGames | Configuration reloaded.", plain(c));
        assertTrue(find(c, "S").bold(), "the brand glyphs keep the prefix's &l");
        assertFalse(find(c, "|").bold(), "&8 must clear the accumulated bold");
        assertFalse(find(c, "C").bold(), "bold must not bleed into the message body");
    }

    @Test
    void rgbGradientHexColorClearsAccumulatedFormat() {
        Component c = SnText.color("[rgb]&lA&#ff0000B");
        assertTrue(find(c, "A").bold());
        assertFalse(find(c, "B").bold(), "a hex color inside the gradient clears the format");
    }

    @Test
    void rgbSpanClosesAndOuterLegacyColorIsRestored() {
        Component c = SnText.color("&7Hola [rgb]Mundo[/rgb] chau");
        assertEquals("Hola Mundo chau", plain(c));
        assertEquals(NamedTextColor.GRAY, find(c, "Hola").color());
        assertEquals(TextColor.color(0xF300F3), find(c, "M").color(),
                "the span's first glyph is the first gradient anchor");
        assertEquals(NamedTextColor.GRAY, find(c, "chau").color(), "[/rgb] restores the outer &7");
    }

    @Test
    void closedRgbSpanWithNoOuterCodesRestoresDefault() {
        Component c = SnText.color("hola [rgb]x[/rgb] chau");
        assertEquals("hola x chau", plain(c));
        assertEquals(null, find(c, "hola").color());
        assertEquals(null, find(c, "chau").color(), "no outer code -> [/rgb] resets to default");
    }

    @Test
    void eachRgbSpanGetsTheFullGradient() {
        Component c = SnText.color("[rgb]Ab[/rgb] x [rgb]Cd");
        assertEquals(TextColor.color(0xF300F3), find(c, "A").color());
        assertEquals(TextColor.color(0xFF5327), find(c, "b").color());
        assertEquals(null, find(c, "x").color());
        assertEquals(TextColor.color(0xF300F3), find(c, "C").color(),
                "the second span restarts at the first anchor");
        assertEquals(TextColor.color(0xFF5327), find(c, "d").color());
    }

    @Test
    void closingRgbRestoresOuterColorAndFormat() {
        Component c = SnText.color("&7&lHola [rgb]X[/rgb] chau");
        Span chau = find(c, "chau");
        assertEquals(NamedTextColor.GRAY, chau.color());
        assertTrue(chau.bold(), "[/rgb] re-emits the outer &l too");
    }

    @Test
    void smallSpanClosesMidLine() {
        Component c = SnText.color("Hola [small]mundo[/small] chau");
        assertEquals("Hola " + SnText.smallCaps("mundo") + " chau", plain(c));
    }

    @Test
    void rgbAndSmallSpansNest() {
        Component c = SnText.color("[rgb][small]hi[/small] yo[/rgb] end");
        assertEquals(SnText.smallCaps("hi") + " yo end", plain(c));
        assertEquals(TextColor.color(0xF300F3), find(c, SnText.smallCaps("h")).color());
        assertEquals(null, find(c, "end").color());
    }

    @Test
    void unclosedMidLineRgbRunsToEndOfLine() {
        Component c = SnText.color("&7a [rgb]bc");
        assertEquals(NamedTextColor.GRAY, find(c, "a").color());
        assertEquals(TextColor.color(0xF300F3), find(c, "b").color());
        assertEquals(TextColor.color(0xFF5327), find(c, "c").color());
    }

    @Test
    void strayCloseTagsAreConsumedSilently() {
        assertEquals("hola chau", plain(SnText.color("hola [/rgb][/small]chau")));
    }

    @Test
    void spanTagsAreCaseInsensitive() {
        assertEquals(plain(SnText.color("a [rgb]b[/rgb] c")),
                plain(SnText.color("a [RGB]b[/RGB] c")));
    }

    @Test
    void noPrefixTagIsStrippedFromTheRender() {
        Component c = SnText.color("[noprefix]&7Hello");
        assertEquals("Hello", plain(c));
    }

    @Test
    void noPrefixTagComposesWithOtherLeadingTagsInAnyOrder() {
        assertEquals("Hi", plain(SnText.color("[noprefix][rgb]Hi")));
        assertEquals("Hi", plain(SnText.color("[rgb][NoPrefix]Hi")));
    }
}
