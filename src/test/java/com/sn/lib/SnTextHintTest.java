package com.sn.lib;

import org.junit.jupiter.api.Test;

import com.sn.lib.text.SnText;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The {@code {key:hint}} format hint of {@link SnText#applyLocals}.
 *
 * <p>Every test here is a rule the hint must not break. The feature exists so a server owner
 * can restyle a number in a language file without the plugin being rebuilt, which means it
 * runs over EVERY message, lore line and menu title of every consumer: a false positive is
 * not a cosmetic bug, it is corrupted text in a plugin whose author never opted in.</p>
 */
class SnTextHintTest {

    @Test
    void rendersEachHint() {
        Ph amount = Ph.of("amount", "1500000");
        assertEquals("1.5M", SnText.applyLocals("{amount:short}", amount));
        assertEquals("1,500,000", SnText.applyLocals("{amount:grouped}", amount));
        assertEquals("1500000", SnText.applyLocals("{amount:raw}", amount));
    }

    @Test
    void leavesUnhintedTokensExactlyAsBefore() {
        assertEquals("1500000", SnText.applyLocals("{amount}", Ph.of("amount", "1500000")));
    }

    @Test
    void rendersHintsOnPercentTokensToo() {
        assertEquals("1.5M", SnText.applyLocals("%amount:short%", Ph.of("amount", "1500000")));
    }

    /** Rule 2: an unknown suffix is not a hint, so the token survives for the next stage. */
    @Test
    void leavesUnknownHintsIntactForTheNextStage() {
        assertEquals("{amount:pretty}",
                SnText.applyLocals("{amount:pretty}", Ph.of("amount", "1500000")));
        assertEquals("<t:1700000000:R>",
                SnText.applyLocals("<t:1700000000:R>", Ph.of("amount", "1500000")));
    }

    /** Rule 3: an unknown key keeps the whole token, hint included. */
    @Test
    void leavesUnknownKeysIntact() {
        assertEquals("{missing:short}",
                SnText.applyLocals("{missing:short}", Ph.of("amount", "1500000")));
    }

    /** Rule 4: a non-numeric value is returned unchanged, never blanked. */
    @Test
    void returnsNonNumericValuesUnchanged() {
        assertEquals("Steve", SnText.applyLocals("{player:short}", Ph.of("player", "Steve")));
        assertEquals("", SnText.applyLocals("{empty:short}", Ph.of("empty", "")));
    }

    /** Rule 5: overflow to infinity must not reach BigDecimal.valueOf. */
    @Test
    void returnsNonFiniteValuesUnchanged() {
        assertEquals("1e400", SnText.applyLocals("{amount:short}", Ph.of("amount", "1e400")));
    }

    /** Rule 1: a key that itself contains a colon still resolves as its own key. */
    @Test
    void prefersTheWholeTokenOverAHintSplit() {
        assertEquals("kept", SnText.applyLocals("{odd:short}", Ph.of("odd:short", "kept")));
    }

    /** An already-grouped value re-renders, which is what makes unmodified plugins work. */
    @Test
    void reparsesValuesTheCallerAlreadyFormatted() {
        assertEquals("1.5M", SnText.applyLocals("{amount:short}", Ph.of("amount", "1,500,000")));
        assertEquals("1,500,000", SnText.applyLocals("{amount:grouped}", Ph.of("amount", "1.5M")));
    }

    @Test
    void rendersHintsInsideSurroundingText() {
        assertEquals("Tenes 2.45M monedas y 64 items",
                SnText.applyLocals("Tenes {points:short} monedas y {count} items",
                        Ph.of("points", "2450000"), Ph.of("count", "64")));
    }

    @Test
    void keepsNegatives() {
        assertEquals("-1.5K", SnText.applyLocals("{v:short}", Ph.of("v", "-1500")));
        assertEquals("-1,500", SnText.applyLocals("{v:grouped}", Ph.of("v", "-1500")));
    }

    /**
     * {@code raw} does NOT round. {@code short} and {@code grouped} round HALF_UP to two
     * decimals because they are presentation; {@code raw} is the escape hatch an owner reaches
     * for precisely when the exact figure matters, so rounding it would defeat its only job.
     */
    @Test
    void rawKeepsFullPrecisionWhileTheOthersRound() {
        Ph value = Ph.of("v", "1234.567");
        assertEquals("1234.567", SnText.applyLocals("{v:raw}", value));
        assertEquals("1,234.57", SnText.applyLocals("{v:grouped}", value));
        assertEquals("1.23K", SnText.applyLocals("{v:short}", value));
    }
}
