package com.sn.lib.hologram;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;

import org.bukkit.Color;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.entity.Display;
import org.bukkit.entity.TextDisplay;
import org.junit.jupiter.api.Test;

/**
 * {@link HologramStyle}: the vanilla default, every background form, the clamps and the yml
 * reader's fallback and warning wording.
 */
class HologramStyleTest {

    @Test
    void defaultIsTheVanillaTextDisplay() {
        HologramStyle style = HologramStyle.DEFAULT;
        assertEquals(Display.Billboard.CENTER, style.billboard());
        assertEquals(false, style.shadow());
        assertEquals(false, style.seeThrough());
        assertEquals(0x40000000, style.background().asARGB());
        assertEquals(TextDisplay.TextAlignment.CENTER, style.alignment());
        assertEquals(200, style.lineWidth());
        assertEquals(255, style.textOpacity());
        assertEquals(1.0F, style.scale());
        assertEquals(1.0F, style.viewRange());
    }

    @Test
    void parsesEveryBackgroundForm() {
        assertEquals(HologramStyle.DEFAULT_BACKGROUND, HologramStyle.parseBackground("default"));
        assertEquals(0, HologramStyle.parseBackground("none").asARGB());
        assertEquals(0, HologramStyle.parseBackground(" Transparent ").asARGB());
        assertEquals(0xFFFF0000, HologramStyle.parseBackground("#ff0000").asARGB());
        assertEquals(0x80112233, HologramStyle.parseBackground("#80112233").asARGB());
        assertEquals(0xFF0A141E, HologramStyle.parseBackground("10, 20, 30").asARGB());
        assertEquals(0x280A141E, HologramStyle.parseBackground("10, 20, 30, 40").asARGB());
    }

    @Test
    void rejectsMalformedBackgrounds() {
        assertNull(HologramStyle.parseBackground(null));
        assertNull(HologramStyle.parseBackground("red"));
        assertNull(HologramStyle.parseBackground("#12345"));
        assertNull(HologramStyle.parseBackground("#zzzzzz"));
        assertNull(HologramStyle.parseBackground("1, 2"));
        assertNull(HologramStyle.parseBackground("1, 2, 300"));
        assertNull(HologramStyle.parseBackground("1, 2, x"));
    }

    @Test
    void clampsNumbersAndReplacesNulls() {
        HologramStyle style = new HologramStyle(null, true, true, null, null, 0, 999, 50.0F, 0.0F);
        assertEquals(Display.Billboard.CENTER, style.billboard());
        assertEquals(HologramStyle.DEFAULT_BACKGROUND, style.background());
        assertEquals(TextDisplay.TextAlignment.CENTER, style.alignment());
        assertEquals(1, style.lineWidth());
        assertEquals(255, style.textOpacity());
        assertEquals(10.0F, style.scale());
        assertEquals(0.1F, style.viewRange());
        assertEquals(0, new HologramStyle(null, false, false, null, null, 1, -5, Float.NaN, 1.0F).textOpacity());
        assertEquals(1.0F, new HologramStyle(null, false, false, null, null, 1, 0, Float.NaN, 1.0F).scale());
    }

    @Test
    void readsEveryKey() {
        MemoryConfiguration yml = new MemoryConfiguration();
        yml.set("billboard", "fixed");
        yml.set("shadow", true);
        yml.set("see-through", true);
        yml.set("background", "none");
        yml.set("alignment", "LEFT");
        yml.set("line-width", 120);
        yml.set("text-opacity", 200);
        yml.set("scale", 1.5D);
        yml.set("view-range", 2.0D);
        List<String> warnings = new ArrayList<>();
        HologramStyle style = HologramStyle.read(yml, HologramStyle.DEFAULT, warnings::add);
        assertTrue(warnings.isEmpty());
        assertEquals(new HologramStyle(Display.Billboard.FIXED, true, true, HologramStyle.NO_BACKGROUND,
                TextDisplay.TextAlignment.LEFT, 120, 200, 1.5F, 2.0F), style);
    }

    @Test
    void missingKeysKeepTheFallback() {
        HologramStyle fallback = new HologramStyle(Display.Billboard.VERTICAL, true, false,
                Color.fromARGB(0xFF00FF00), TextDisplay.TextAlignment.RIGHT, 80, 100, 2.0F, 3.0F);
        List<String> warnings = new ArrayList<>();
        assertSame(fallback, HologramStyle.read(null, fallback, warnings::add));
        assertEquals(fallback, HologramStyle.read(new MemoryConfiguration(), fallback, warnings::add));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void invalidValuesKeepTheFallbackWithOneWarningEach() {
        MemoryConfiguration yml = new MemoryConfiguration();
        yml.set("billboard", "SIDEWAYS");
        yml.set("alignment", "MIDDLE");
        yml.set("background", "red");
        List<String> warnings = new ArrayList<>();
        HologramStyle style = HologramStyle.read(yml, HologramStyle.DEFAULT, warnings::add);
        assertEquals(HologramStyle.DEFAULT, style);
        assertEquals(List.of(
                "billboard 'SIDEWAYS' is not one of FIXED, VERTICAL, HORIZONTAL, CENTER; using CENTER",
                "background 'red' is not default, none, #RRGGBB, #AARRGGBB, \"R, G, B\" or \"R, G, B, A\";"
                        + " keeping the previous value",
                "alignment 'MIDDLE' is not one of CENTER, LEFT, RIGHT; using CENTER"), warnings);
    }

    @Test
    void withBillboardChangesOnlyTheBillboard() {
        HologramStyle fixed = HologramStyle.DEFAULT.withBillboard(Display.Billboard.FIXED);
        assertEquals(Display.Billboard.FIXED, fixed.billboard());
        assertEquals(HologramStyle.DEFAULT, fixed.withBillboard(Display.Billboard.CENTER));
    }
}
