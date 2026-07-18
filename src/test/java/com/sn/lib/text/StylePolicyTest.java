package com.sn.lib.text;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import com.sn.lib.text.StylePolicy.Capability;
import com.sn.lib.text.StylePolicy.OnDisallowed;

/**
 * Matrix of allow toggles x input forms for {@link StylePolicy}: capability detection, strip
 * vs reject semantics, the cosmetic MiniMessage subset (non-cosmetic tags are always a
 * violation), gradient governed separately, and config parsing.
 */
class StylePolicyTest {

    private static StylePolicy allowing(Capability... caps) {
        return StylePolicy.builder().allow(caps).build();
    }

    // ------------------------------------------------------------------
    // Plain-only / disabled
    // ------------------------------------------------------------------

    @Test
    void disabledPolicyIsPlainOnly() {
        StylePolicy p = StylePolicy.disabled();
        assertFalse(p.isEnabled());
        assertTrue(p.isPlainOnly());
        assertTrue(p.violations("plain text").isEmpty());
        assertEquals(List.of(Capability.LEGACY_COLOR), p.violations("&aHi"));
        assertEquals(List.of(Capability.MINIMESSAGE), p.violations("<red>Hi</red>"));
    }

    @Test
    void enabledButNothingAllowedIsPlainOnly() {
        StylePolicy p = StylePolicy.builder().enabled(true).build();
        assertTrue(p.isPlainOnly());
        assertTrue(p.violations("plain").isEmpty());
    }

    // ------------------------------------------------------------------
    // Per-form detection
    // ------------------------------------------------------------------

    @Test
    void detectsEachLegacyForm() {
        StylePolicy plain = StylePolicy.disabled();
        assertEquals(List.of(Capability.LEGACY_COLOR), plain.violations("&aHi"));
        assertEquals(List.of(Capability.HEX), plain.violations("&#ff0000Hi"));
        assertEquals(List.of(Capability.BOLD), plain.violations("&lHi"));
        assertEquals(List.of(Capability.ITALIC), plain.violations("&oHi"));
        assertEquals(List.of(Capability.UNDERLINE), plain.violations("&nHi"));
        assertEquals(List.of(Capability.STRIKETHROUGH), plain.violations("&mHi"));
        assertEquals(List.of(Capability.OBFUSCATED), plain.violations("&kHi"));
    }

    @Test
    void resetCodeIsNeverAViolation() {
        assertTrue(StylePolicy.disabled().violations("&rHi").isEmpty());
        assertTrue(StylePolicy.disabled().violations("<reset>Hi").isEmpty());
    }

    @Test
    void detectsMiniMessageAndGradientForms() {
        StylePolicy plain = StylePolicy.disabled();
        assertEquals(List.of(Capability.MINIMESSAGE), plain.violations("<bold>Hi</bold>"));
        assertEquals(List.of(Capability.GRADIENT), plain.violations("<gradient:red:blue>Hi</gradient>"));
        assertEquals(List.of(Capability.GRADIENT), plain.violations("<rainbow>Hi</rainbow>"));
        assertEquals(List.of(Capability.GRADIENT), plain.violations("[rgb]Hi"));
    }

    @Test
    void sectionSignInputIsNormalizedBeforeDetection() {
        assertEquals(List.of(Capability.LEGACY_COLOR), StylePolicy.disabled().violations("§aHi"));
        assertEquals(List.of(Capability.HEX), StylePolicy.disabled().violations("§x§8§3§5§4§f§2Hi"));
    }

    @Test
    void violationsAreOrderedByCapabilityDeclaration() {
        StylePolicy plain = StylePolicy.disabled();
        assertEquals(List.of(Capability.LEGACY_COLOR, Capability.HEX, Capability.MINIMESSAGE),
                plain.violations("&aA&#ff0000B<italic>C</italic>"));
    }

    // ------------------------------------------------------------------
    // Allow toggles
    // ------------------------------------------------------------------

    @Test
    void allowedCapabilityIsNotAViolation() {
        StylePolicy legacyOnly = allowing(Capability.LEGACY_COLOR);
        assertTrue(legacyOnly.violations("&aHi").isEmpty());
        assertTrue(legacyOnly.accepts("&aHi"));
        assertEquals(List.of(Capability.HEX), legacyOnly.violations("&#ff0000Hi"));
        assertEquals(List.of(Capability.HEX), legacyOnly.violations("&aHi &#ff0000x"));
    }

    @Test
    void gradientIsGovernedIndependentlyOfMiniMessage() {
        StylePolicy gradientOnly = allowing(Capability.GRADIENT);
        assertTrue(gradientOnly.violations("<gradient:red:blue>Hi</gradient>").isEmpty());
        assertTrue(gradientOnly.violations("[rgb]Hi").isEmpty());
        // A plain color tag still needs MINIMESSAGE.
        assertEquals(List.of(Capability.MINIMESSAGE), gradientOnly.violations("<red>Hi</red>"));

        StylePolicy miniOnly = allowing(Capability.MINIMESSAGE);
        assertTrue(miniOnly.violations("<red>Hi</red>").isEmpty());
        assertEquals(List.of(Capability.GRADIENT), miniOnly.violations("<gradient:red:blue>Hi</gradient>"));
    }

    // ------------------------------------------------------------------
    // Cosmetic subset safety
    // ------------------------------------------------------------------

    @Test
    void nonCosmeticTagIsAlwaysAViolationEvenWithMiniMessageAllowed() {
        StylePolicy miniAllowed = allowing(Capability.MINIMESSAGE);
        assertEquals(List.of(Capability.MINIMESSAGE),
                miniAllowed.violations("<click:run_command:/op me>hi</click>"));
        assertEquals(List.of(Capability.MINIMESSAGE),
                miniAllowed.violations("<hover:show_text:'x'>hi</hover>"));
        assertEquals(List.of(Capability.MINIMESSAGE),
                miniAllowed.violations("<insert:secret>hi</insert>"));
    }

    @Test
    void allowAllStillRejectsNonCosmeticTags() {
        StylePolicy all = StylePolicy.builder().allowAll().build();
        assertTrue(all.violations("&a&l<red><bold><gradient:red:blue>Hi</gradient>").isEmpty());
        assertEquals(List.of(Capability.MINIMESSAGE), all.violations("<click:run_command:/x>hi</click>"));
    }

    // ------------------------------------------------------------------
    // strip semantics
    // ------------------------------------------------------------------

    @Test
    void stripRemovesOnlyDisallowedLegacyStyling() {
        StylePolicy legacyOnly = StylePolicy.builder()
                .allow(Capability.LEGACY_COLOR)
                .onDisallowed(OnDisallowed.STRIP)
                .build();
        assertEquals("&aHi There Bold", legacyOnly.strip("&aHi &#ff0000There &lBold"));
    }

    @Test
    void stripKeepsCosmeticTagsWhenMiniMessageAllowed() {
        StylePolicy miniAllowed = allowing(Capability.MINIMESSAGE);
        assertEquals("<red>hi</red>", miniAllowed.strip("<red>hi</red>"));
    }

    @Test
    void stripAlwaysRemovesNonCosmeticTags() {
        StylePolicy miniAllowed = allowing(Capability.MINIMESSAGE);
        assertEquals("hi", miniAllowed.strip("<click:run_command:/op me>hi</click>"));
    }

    @Test
    void stripDropsRgbButKeepsOtherPrefixTagsWhenGradientDisallowed() {
        StylePolicy legacyOnly = allowing(Capability.LEGACY_COLOR);
        assertEquals("hi", legacyOnly.strip("[rgb]hi"));
        assertEquals("[small]hi", legacyOnly.strip("[small]hi"));
        assertEquals("[center]&ahi", legacyOnly.strip("[center]&ahi"));
    }

    @Test
    void stripKeepsResetCode() {
        assertEquals("&rhi", StylePolicy.disabled().strip("&r&ahi"));
    }

    // ------------------------------------------------------------------
    // apply: reject vs strip
    // ------------------------------------------------------------------

    @Test
    void applyReturnsInputUnchangedWhenAcceptable() {
        StylePolicy legacyOnly = allowing(Capability.LEGACY_COLOR);
        assertEquals("&aHi", legacyOnly.apply("&aHi"));
    }

    @Test
    void applyStripSemanticsRemovesDisallowedOnly() {
        StylePolicy strip = StylePolicy.builder()
                .allow(Capability.LEGACY_COLOR)
                .onDisallowed(OnDisallowed.STRIP)
                .build();
        assertEquals("&aHi x", strip.apply("&aHi &#ff0000x"));
    }

    @Test
    void applyRejectSemanticsFallsBackToPlainText() {
        StylePolicy reject = StylePolicy.builder()
                .allow(Capability.LEGACY_COLOR)
                .onDisallowed(OnDisallowed.REJECT)
                .build();
        assertEquals("Hi x", reject.apply("&aHi &#ff0000x"));
    }

    @Test
    void nullPassesThrough() {
        assertEquals(null, StylePolicy.disabled().apply(null));
        assertEquals(null, StylePolicy.disabled().strip(null));
        assertTrue(StylePolicy.disabled().violations(null).isEmpty());
    }

    // ------------------------------------------------------------------
    // Config parsing
    // ------------------------------------------------------------------

    @Test
    void fromConfigReadsAllowTogglesAndMode() {
        MemoryConfiguration root = new MemoryConfiguration();
        ConfigurationSection sub = root.createSection("nick-style");
        sub.set("enabled", true);
        sub.set("allow-legacy-colors", true);
        sub.set("allow-hex", false);
        sub.set("allow-gradient", true);
        sub.set("on-disallowed", "strip");

        StylePolicy p = StylePolicy.fromConfig(root, "nick-style");
        assertTrue(p.isEnabled());
        assertTrue(p.isAllowed(Capability.LEGACY_COLOR));
        assertFalse(p.isAllowed(Capability.HEX));
        assertTrue(p.isAllowed(Capability.GRADIENT));
        assertEquals(OnDisallowed.STRIP, p.onDisallowed());
        assertEquals(List.of(Capability.HEX), p.violations("&aHi &#ff0000x"));
    }

    @Test
    void fromConfigDefaultsDenyAndReject() {
        MemoryConfiguration root = new MemoryConfiguration();
        root.createSection("style").set("enabled", true);
        StylePolicy p = StylePolicy.fromConfig(root, "style");
        assertTrue(p.isEnabled());
        assertTrue(p.isPlainOnly(), "unspecified allow-* default to deny");
        assertEquals(OnDisallowed.REJECT, p.onDisallowed());
    }

    @Test
    void fromConfigDisabledOrMissingSectionYieldsPlainOnly() {
        MemoryConfiguration root = new MemoryConfiguration();
        root.createSection("off").set("enabled", false);
        assertTrue(StylePolicy.fromConfig(root, "off").isPlainOnly());
        assertFalse(StylePolicy.fromConfig(root, "off").isEnabled());
        assertTrue(StylePolicy.fromConfig(root, "missing").isPlainOnly());
        assertTrue(StylePolicy.fromConfig(null, "any").isPlainOnly());
    }

    @Test
    void fromConfigParsesRealYaml() {
        YamlConfiguration yaml = new YamlConfiguration();
        assertDoesNotThrowLoad(yaml, String.join("\n",
                "chat:",
                "  style:",
                "    enabled: true",
                "    allow-legacy-colors: true",
                "    allow-minimessage: true",
                "    on-disallowed: strip"));
        StylePolicy p = StylePolicy.fromConfig(yaml.getConfigurationSection("chat"), "style");
        assertTrue(p.isAllowed(Capability.LEGACY_COLOR));
        assertTrue(p.isAllowed(Capability.MINIMESSAGE));
        assertFalse(p.isAllowed(Capability.HEX));
        assertEquals(OnDisallowed.STRIP, p.onDisallowed());
    }

    private static void assertDoesNotThrowLoad(YamlConfiguration yaml, String content) {
        try {
            yaml.loadFromString(content);
        } catch (Exception e) {
            throw new AssertionError("YAML load failed: " + e.getMessage(), e);
        }
    }

    // ------------------------------------------------------------------
    // Accessors
    // ------------------------------------------------------------------

    @Test
    void allowedViewIsImmutableAndAccurate() {
        StylePolicy p = allowing(Capability.LEGACY_COLOR, Capability.HEX);
        assertTrue(p.allowed().contains(Capability.LEGACY_COLOR));
        assertTrue(p.allowed().contains(Capability.HEX));
        assertFalse(p.allowed().contains(Capability.BOLD));
        assertEquals(2, p.allowed().size());
    }
}
