package com.sn.lib.text.internal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;

import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;

import com.sn.lib.text.SnText;

/**
 * {@link RenderCache} behind {@link SnText#color(String)}: a repeated line returns the same
 * component instance, a cached render equals a fresh one for every input form, and both
 * bounds (key length and entry count) hold.
 */
class RenderCacheTest {

    /**
     * Compares what reaches the client. Component equality is not usable here: a MiniMessage
     * gradient is a VirtualComponent whose equals compares renderer identity, so two
     * identical renders of the same line are never equal.
     */
    private static final LegacyComponentSerializer RENDERED = LegacyComponentSerializer.builder()
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    @BeforeEach
    void reset() {
        RenderCache.clear();
    }

    @Test
    void repeatedLineReturnsCachedInstance() {
        Component first = SnText.color("&aHello <bold>world");
        Component second = SnText.color("&aHello <bold>world");
        assertSame(first, second);
        assertEquals(1, RenderCache.size());
    }

    @Test
    void cachedRenderEqualsFreshRender() {
        List<String> inputs = List.of(
                "&aGreen &lbold &cred",
                "&#ff8800Hex &x&f&f&0&0&0&0legacy-hex",
                "§aSection signs",
                "[rgb]Gradient title",
                "[center]&eCentered",
                "[small]small caps [/small]normal",
                "<gradient:#ff0000:#0000ff>Mini</gradient> <red>tags",
                "a < b and > c",
                "");
        for (String input : inputs) {
            Component cached = SnText.color(input);
            assertSame(cached, SnText.color(input), input);
            RenderCache.clear();
            Component fresh = SnText.color(input);
            assertEquals(RENDERED.serialize(cached), RENDERED.serialize(fresh), input);
        }
    }

    @Test
    void longLineIsNeverStored() {
        String line = "&a" + "x".repeat(RenderCache.MAX_KEY_LENGTH);
        Component first = SnText.color(line);
        assertEquals(0, RenderCache.size());
        assertEquals(RENDERED.serialize(first), RENDERED.serialize(SnText.color(line)));
    }

    @Test
    void reachingTheCapResetsTheMap() {
        for (int i = 0; i < RenderCache.MAX_ENTRIES; i++) {
            SnText.color("&7line " + i);
        }
        assertEquals(RenderCache.MAX_ENTRIES, RenderCache.size());
        SnText.color("&7one more");
        assertEquals(1, RenderCache.size());
    }
}
