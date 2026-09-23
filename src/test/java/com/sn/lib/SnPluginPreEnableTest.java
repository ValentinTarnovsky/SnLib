package com.sn.lib;

import org.junit.jupiter.api.Test;

import com.sn.lib.SnPlugin.PreEnableOutcome;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The decision behind {@link SnPlugin#onPreEnable()}: which outcome each combination of
 * throw, return value and enabled state leads to, through the pure
 * {@link SnPlugin#preEnableOutcome(boolean, boolean, boolean)}. No server is needed.
 */
class SnPluginPreEnableTest {

    @Test
    void theDefaultHookProceeds() {
        // Returned true, still enabled: the context comes next, as in 1.37.0.
        assertEquals(PreEnableOutcome.PROCEED, SnPlugin.preEnableOutcome(false, true, true));
    }

    @Test
    void falseWithoutDisablingIsDisabledByTheLibraryQuietly() {
        assertEquals(PreEnableOutcome.DISABLE_QUIET,
                SnPlugin.preEnableOutcome(false, false, true));
    }

    @Test
    void falseAfterDisablingNeedsNothingMore() {
        assertEquals(PreEnableOutcome.ABORT_QUIET,
                SnPlugin.preEnableOutcome(false, false, false));
    }

    @Test
    void trueAfterDisablingIsStillARefusal() {
        assertEquals(PreEnableOutcome.ABORT_QUIET,
                SnPlugin.preEnableOutcome(false, true, false));
    }

    @Test
    void aThrowAfterDisablingIsOneLine() {
        // The return value is irrelevant once the hook threw.
        assertEquals(PreEnableOutcome.ABORT_ONE_LINE,
                SnPlugin.preEnableOutcome(true, false, false));
        assertEquals(PreEnableOutcome.ABORT_ONE_LINE,
                SnPlugin.preEnableOutcome(true, true, false));
    }

    @Test
    void aThrowWithoutDisablingIsABug() {
        assertEquals(PreEnableOutcome.FAIL_TRACE_AND_DISABLE,
                SnPlugin.preEnableOutcome(true, false, true));
        assertEquals(PreEnableOutcome.FAIL_TRACE_AND_DISABLE,
                SnPlugin.preEnableOutcome(true, true, true));
    }

    @Test
    void onlyOneCombinationProceeds() {
        int proceeding = 0;
        for (boolean threw : new boolean[] {false, true}) {
            for (boolean returned : new boolean[] {false, true}) {
                for (boolean stillEnabled : new boolean[] {false, true}) {
                    if (SnPlugin.preEnableOutcome(threw, returned, stillEnabled)
                            == PreEnableOutcome.PROCEED) {
                        proceeding++;
                    }
                }
            }
        }
        assertEquals(1, proceeding);
    }
}
