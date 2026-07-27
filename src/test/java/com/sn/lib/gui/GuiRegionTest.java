package com.sn.lib.gui;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.bukkit.configuration.ConfigurationSection;
import org.bukkit.configuration.MemoryConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Parse-level cover of the {@code regions:} section: geometry, the slots/key precedence and
 * the WARN-versus-silent split that the 1.19.1 rule demands (a layout the owner emptied is
 * configuration, a declaration the owner could not have meant is a warning).
 */
class GuiRegionTest {

    /** The SnClans permissions mask: 3 role cells, a toggle block with a filler in the middle. */
    private static final List<String> MASK = List.of(
            "fffffffff",
            "fffrrrfff",
            "ftttttttf",
            "ftttftttf",
            "ffffxffff");

    @Test
    void scalarShorthandResolvesRowMajorAndMatchesGuiMask() {
        Warnings warnings = new Warnings();
        Map<String, int[]> regions = parse(section(root -> {
            root.set("regions.roles", "r");
            root.set("regions.toggles", "t");
        }), MASK, warnings);

        assertArrayEquals(new int[] {12, 13, 14}, regions.get("roles"));
        assertArrayEquals(new int[] {19, 20, 21, 22, 23, 24, 25, 28, 29, 30, 32, 33, 34},
                regions.get("toggles"));
        // Same geometry the code-built helper produces, so a menu and a coded mask agree.
        assertArrayEquals(GuiMask.slots('t', MASK), regions.get("toggles"));
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    @Test
    void regionIdsKeepFileOrder() {
        Map<String, int[]> regions = parse(section(root -> {
            root.set("regions.toggles", "t");
            root.set("regions.roles", "r");
        }), MASK, new Warnings());

        assertEquals(List.of("toggles", "roles"), new ArrayList<>(regions.keySet()));
    }

    @Test
    void spacesAreNeverCellsAndARepeatedCharAccumulates() {
        Map<String, int[]> regions = parse(section(root -> root.set("regions.gapped", "g")),
                List.of("g g g g g"), new Warnings());

        assertArrayEquals(new int[] {0, 2, 4, 6, 8}, regions.get("gapped"));
    }

    @Test
    void slotsWinOverKeyAndKeepFirstSeenOrderWithDedup() {
        Warnings warnings = new Warnings();
        Map<String, int[]> regions = parse(section(root -> {
            root.set("regions.toggles.slots", List.of("34-32", "19-21", "19"));
            root.set("regions.toggles.key", "t");
        }), MASK, warnings);

        // SlotParser normalises a descending range and dedups through a LinkedHashSet, so
        // the owner's order survives and a repeated slot can never eat an entry.
        assertArrayEquals(new int[] {32, 33, 34, 19, 20, 21}, regions.get("toggles"));
        assertEquals(1, warnings.messages.size());
        assertTrue(warnings.messages.get(0).contains("declares both 'slots' and 'key'"),
                warnings.messages.get(0));
    }

    @Test
    void keyAbsentFromLayoutIsADeclaredEmptyRegionAndSilent() {
        Warnings warnings = new Warnings();
        Map<String, int[]> regions = parse(section(root -> root.set("regions.roles", "z")),
                MASK, warnings);

        // Declared (so bindEach stays silent) but empty: this is the owner REMOVING it.
        assertTrue(regions.containsKey("roles"));
        assertEquals(0, regions.get("roles").length);
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    @Test
    void keyWithoutLayoutWarnsOnce() {
        Warnings warnings = new Warnings();
        Map<String, int[]> regions = parse(section(root -> root.set("regions.roles", "r")),
                List.of(), warnings);

        assertEquals(0, regions.get("roles").length);
        assertEquals(1, warnings.messages.size());
        assertTrue(warnings.messages.get(0).contains("the menu has no layout"),
                warnings.messages.get(0));
    }

    @Test
    void multiCharacterKeyWarnsOnce() {
        Warnings warnings = new Warnings();
        Map<String, int[]> regions = parse(section(root -> root.set("regions.roles", "rr")),
                MASK, warnings);

        assertEquals(0, regions.get("roles").length);
        assertEquals(1, warnings.messages.size());
        assertTrue(warnings.messages.get(0).contains("must be 1 character"),
                warnings.messages.get(0));
    }

    @Test
    void aSectionWithOtherKeysButNeitherRealOneWarnsOnce() {
        Warnings warnings = new Warnings();
        Map<String, int[]> regions = parse(section(root ->
                root.set("regions.roles.update-interval", 20)), MASK, warnings);

        assertEquals(0, regions.get("roles").length);
        assertEquals(1, warnings.messages.size());
        assertTrue(warnings.messages.get(0).contains("declares neither 'slots' nor 'key'"),
                warnings.messages.get(0));
    }

    @Test
    void blankingTheValueIsASilentWayToEmptyARegion() {
        Warnings warnings = new Warnings();
        Map<String, int[]> regions = parse(section(root -> root.set("regions.roles", "")),
                MASK, warnings);

        // Declared but empty, and NOT a warning: writing an emptiness is the owner turning
        // the region off, exactly like taking its letter out of the layout (1.19.1 rule).
        assertTrue(regions.containsKey("roles"));
        assertEquals(0, regions.get("roles").length);
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    @Test
    void anEmptySlotsListIsASilentWayToEmptyARegion() {
        Warnings warnings = new Warnings();
        Map<String, int[]> regions = parse(section(root ->
                root.set("regions.toggles.slots", List.of())), MASK, warnings);

        assertTrue(regions.containsKey("toggles"));
        assertEquals(0, regions.get("toggles").length);
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    @Test
    void overlapWithPagedKeyAndWithAnEarlierRegionWarnsOncePerRegion() {
        Warnings warnings = new Warnings();
        MemoryConfiguration root = new MemoryConfiguration();
        root.set("regions.first", "t");
        root.set("regions.second", "t");
        // 19 and 20 are 't' cells, so 'first' collides with the paged key and 'second'
        // collides with 'first' - one line each, never one per cell.
        Map<String, int[]> regions = GuiDef.parseRegions(root, GuiDef.layoutKeys(MASK), true,
                new int[] {19, 20}, warnings);

        assertEquals(13, regions.get("second").length);
        assertEquals(3, warnings.messages.size());
        assertTrue(warnings.messages.get(0).contains("covers paged-key cell 19"),
                warnings.messages.get(0));
        assertTrue(warnings.messages.get(2).contains("overlaps region 'first' on slot 19"),
                warnings.messages.get(2));
    }

    @Test
    void regionsAsAScalarWarnsAndYieldsNoRegions() {
        Warnings warnings = new Warnings();
        Map<String, int[]> regions = parse(section(root -> root.set("regions", "t")), MASK,
                warnings);

        assertTrue(regions.isEmpty());
        assertEquals(1, warnings.messages.size());
        assertTrue(warnings.messages.get(0).contains("must be a section"),
                warnings.messages.get(0));
    }

    @Test
    void absentRegionsSectionIsSilentAndEmpty() {
        Warnings warnings = new Warnings();
        Map<String, int[]> regions = parse(new MemoryConfiguration(), MASK, warnings);

        assertTrue(regions.isEmpty());
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    @Test
    void numericScalarKeyIsReadAsItsCharacter() {
        Warnings warnings = new Warnings();
        Map<String, int[]> regions = parse(section(root -> root.set("regions.roles", 1)),
                List.of("111111111"), warnings);

        assertEquals(9, regions.get("roles").length);
        assertTrue(warnings.messages.isEmpty(), () -> "unexpected warnings: " + warnings.messages);
    }

    private static Map<String, int[]> parse(ConfigurationSection root, List<String> mask,
                                            Warnings warnings) {
        return GuiDef.parseRegions(root, GuiDef.layoutKeys(mask), !mask.isEmpty(), new int[0],
                warnings);
    }

    private static ConfigurationSection section(java.util.function.Consumer<MemoryConfiguration> filler) {
        MemoryConfiguration root = new MemoryConfiguration();
        filler.accept(root);
        return root;
    }

    /** Capturing warning sink: the tests assert both the text AND that nothing fired. */
    private static final class Warnings implements java.util.function.Consumer<String> {

        private final List<String> messages = new ArrayList<>();

        @Override
        public void accept(String message) {
            messages.add(message);
        }
    }
}
