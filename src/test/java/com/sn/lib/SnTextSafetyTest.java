package com.sn.lib;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextColor;

import com.sn.lib.text.SnText;

/**
 * Section-sign safety of {@link SnText#color(String)} (MiniMessage 4.25 hard-rejects a raw
 * {@code §}) plus the {@link SnText#plain(String)}, {@link SnText#visibleLength(String)},
 * {@link SnText#section(String)} and {@link SnText#cosmetic(String)} helpers.
 */
class SnTextSafetyTest {

    private record Span(String text, TextColor color) {
    }

    private static List<Span> spans(Component component) {
        List<Span> out = new ArrayList<>();
        collect(component, null, out);
        return out;
    }

    private static void collect(Component component, TextColor parentColor, List<Span> out) {
        TextColor color = component.style().color() != null ? component.style().color() : parentColor;
        if (component instanceof TextComponent text && !text.content().isEmpty()) {
            out.add(new Span(text.content(), color));
        }
        for (Component child : component.children()) {
            collect(child, color, out);
        }
    }

    private static Span find(Component component, String needle) {
        for (Span span : spans(component)) {
            if (span.text().contains(needle)) {
                return span;
            }
        }
        throw new AssertionError("no span containing '" + needle + "' in " + spans(component));
    }

    // ------------------------------------------------------------------
    // Section safety
    // ------------------------------------------------------------------

    @Test
    void exactRosterCrashStringRenders() {
        // The live crash: a PAPI-expanded '§a' reaching color() through a GUI item.
        Component c = SnText.color("<gray>Status: §aonline");
        assertEquals("Status: online", SnText.plain("<gray>Status: §aonline"));
        assertEquals(NamedTextColor.GRAY, find(c, "Status: ").color());
        assertEquals(NamedTextColor.GREEN, find(c, "online").color());
    }

    @Test
    void sectionSimpleCodeMatchesAmpersandRender() {
        // Normalizing § to & must produce a render IDENTICAL to the & equivalent.
        assertEquals(SnText.color("&aonline"), SnText.color("§aonline"));
    }

    @Test
    void sectionBungeeHexFormRenders() {
        Component c = SnText.color("§x§8§3§5§4§f§2Hi");
        assertEquals(TextColor.color(0x8354F2), find(c, "Hi").color());
        assertEquals(SnText.color("&#8354f2Hi"), c);
    }

    @Test
    void mixedAmpersandSectionAndTagRendersWithoutException() {
        Component c = SnText.color("&aA§lB<red>C");
        assertEquals("ABC", SnText.plain("&aA§lB<red>C"));
        assertEquals(NamedTextColor.GREEN, find(c, "A").color());
        assertEquals(NamedTextColor.RED, find(c, "C").color());
    }

    @Test
    void sectionFreeInputIsUntouched() {
        // The §-free path must be byte-for-byte the same render as before the safety step.
        assertEquals(SnText.color("&#8354f2&lSnClans &8| &7&cBody"),
                SnText.color("&#8354f2&lSnClans &8| &7&cBody"));
        assertEquals("&#8354f2&lSnClans", SnText.normalizePapiOutput("&#8354f2&lSnClans"));
    }

    // ------------------------------------------------------------------
    // plain / visibleLength
    // ------------------------------------------------------------------

    @Test
    void plainStripsEveryStylingForm() {
        assertEquals("Hello", SnText.plain("&#8354f2&lHel<red>lo"));
        assertEquals("online", SnText.plain("<gray>§aonline"));
        assertNull(SnText.plain(null));
    }

    @Test
    void visibleLengthCountsPlainCodepoints() {
        assertEquals(5, SnText.visibleLength("&aHello"));
        assertEquals(5, SnText.visibleLength("&#8354f2&lHello"));
        assertEquals(0, SnText.visibleLength(null));
        assertEquals(0, SnText.visibleLength(""));
    }

    @Test
    void visibleLengthEqualsPlainCodepointCount() {
        String styled = "&a<gradient:red:blue>Gradient</gradient>&r!";
        String plain = SnText.plain(styled);
        assertEquals(plain.codePointCount(0, plain.length()), SnText.visibleLength(styled));
    }

    // ------------------------------------------------------------------
    // section
    // ------------------------------------------------------------------

    @Test
    void sectionSerializesLegacyToSectionCodes() {
        assertEquals("§aHello", SnText.section("&aHello"));
        assertNull(SnText.section(null));
    }

    @Test
    void sectionSerializesHexAsBungeeForm() {
        // Full render then §x§R§R... serialization.
        assertEquals("§x§8§3§5§4§f§2Hi", SnText.section("&#8354f2Hi"));
    }

    @Test
    void sectionRoundTripsBackThroughColor() {
        // section() renders + serializes; feeding it back through color() keeps the colors.
        Component viaSection = SnText.color(SnText.section("&aHello"));
        assertEquals(NamedTextColor.GREEN, find(viaSection, "Hello").color());
    }

    // ------------------------------------------------------------------
    // cosmetic
    // ------------------------------------------------------------------

    @Test
    void cosmeticRendersColorsAndDecorations() {
        Component c = SnText.cosmetic("<red>Hi</red>");
        assertEquals(NamedTextColor.RED, find(c, "Hi").color());
        assertEquals("Hi", SnText.plain("<red>Hi</red>"));
    }

    @Test
    void cosmeticDoesNotFireInteractiveTags() {
        Component c = SnText.cosmetic("<click:run_command:/op me>hi</click>");
        assertTrue(componentText(c).contains("hi"), "the visible text survives");
        assertNoClickEvent(c);
    }

    private static String componentText(Component component) {
        StringBuilder sb = new StringBuilder();
        appendText(component, sb);
        return sb.toString();
    }

    private static void appendText(Component component, StringBuilder sb) {
        if (component instanceof TextComponent text) {
            sb.append(text.content());
        }
        for (Component child : component.children()) {
            appendText(child, sb);
        }
    }

    private static void assertNoClickEvent(Component component) {
        assertFalse(component.clickEvent() != null, "cosmetic render must carry no click event");
        for (Component child : component.children()) {
            assertNoClickEvent(child);
        }
    }
}
