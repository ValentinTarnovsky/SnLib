package com.sn.lib.lang;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.Test;

/**
 * Interactive-tag drift detection: the defense-in-depth scan that warns once per load
 * when a lang value shipped with a {@code <click:...>}/{@code <hover:...>} tag lost it on
 * the live file (admin edit, translation), leaving a button that renders but does
 * nothing. Exercises the pure comparison helpers.
 */
class SnLangInteractiveTagDriftTest {

    private static final String CLICKABLE =
            "&7Round starting! <click:run_command:'/mg join {id}'>&a&l[JOIN]</click>";
    private static final String HOVERABLE =
            "<hover:show_text:'&aClick to join'>&a&l[JOIN]</hover>";
    private static final String PLAIN = "&7Round starting! &a&l[JOIN]";

    @Test
    void detectsLostClickTag() {
        assertTrue(SnLang.lostInteractiveMarker(List.of(CLICKABLE), List.of(PLAIN)));
    }

    @Test
    void detectsLostHoverTag() {
        assertTrue(SnLang.lostInteractiveMarker(List.of(HOVERABLE), List.of(PLAIN)));
    }

    @Test
    void detectsPartialLossWhenOnlyOneTagKindSurvives() {
        assertTrue(SnLang.lostInteractiveMarker(
                List.of(CLICKABLE + HOVERABLE), List.of(HOVERABLE)));
    }

    @Test
    void keptTagsAreNotDrift() {
        assertFalse(SnLang.lostInteractiveMarker(List.of(CLICKABLE), List.of(CLICKABLE)));
        assertFalse(SnLang.lostInteractiveMarker(
                List.of(CLICKABLE), List.of("&7Reworded! <click:run_command:'/mg join'>go</click>")));
    }

    @Test
    void plainReferenceNeverWarns() {
        assertFalse(SnLang.lostInteractiveMarker(List.of(PLAIN), List.of("edited freely")));
        assertFalse(SnLang.lostInteractiveMarker(List.of(PLAIN), null));
    }

    @Test
    void missingLiveValueOfATaggedReferenceIsDrift() {
        assertTrue(SnLang.lostInteractiveMarker(List.of(CLICKABLE), null));
    }

    @Test
    void matchIsCaseInsensitiveAndSpansLines() {
        assertFalse(SnLang.lostInteractiveMarker(
                List.of(CLICKABLE), List.of("first line", "<CLICK:run_command:'/mg join'>x</CLICK>")));
        assertTrue(SnLang.containsMarker(List.of("a", CLICKABLE), "<click:"));
    }

    @Test
    void toleratesNullLinesAndValues() {
        assertFalse(SnLang.lostInteractiveMarker(null, List.of(PLAIN)));
        assertFalse(SnLang.lostInteractiveMarker(Arrays.asList((String) null), List.of(PLAIN)));
        assertTrue(SnLang.lostInteractiveMarker(List.of(CLICKABLE), Arrays.asList((String) null)));
    }
}
