package com.sn.lib;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import org.junit.jupiter.api.Test;

import com.sn.lib.action.Requirement;
import com.sn.lib.action.RequirementEngine;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class RequirementEngineTest {

    /** Resolver mock: replaces {@code %key%} tokens from the given values. */
    private static Function<String, String> resolver(Map<String, String> values) {
        return token -> {
            String out = token;
            for (Map.Entry<String, String> entry : values.entrySet()) {
                out = out.replace("%" + entry.getKey() + "%", entry.getValue());
            }
            return out;
        };
    }

    @Test
    void numericAndChainWithinOneLine() {
        Requirement req = RequirementEngine.parse(List.of("%level% > 0 && %level% < 10"));
        assertTrue(req.test(null, resolver(Map.of("level", "5"))));
        assertFalse(req.test(null, resolver(Map.of("level", "0"))));
        assertFalse(req.test(null, resolver(Map.of("level", "10"))));
    }

    @Test
    void linesJoinWithImplicitAnd() {
        Requirement req = RequirementEngine.parse(List.of("%level% > 0", "%level% < 10"));
        assertTrue(req.test(null, resolver(Map.of("level", "5"))));
        assertFalse(req.test(null, resolver(Map.of("level", "15"))));
    }

    @Test
    void allNumericOperators() {
        Function<String, String> five = resolver(Map.of("x", "5"));
        assertTrue(RequirementEngine.parse(List.of("%x% >= 5")).test(null, five));
        assertTrue(RequirementEngine.parse(List.of("%x% <= 5")).test(null, five));
        assertTrue(RequirementEngine.parse(List.of("%x% = 5")).test(null, five));
        assertTrue(RequirementEngine.parse(List.of("%x% == 5")).test(null, five));
        assertTrue(RequirementEngine.parse(List.of("%x% != 6")).test(null, five));
        assertFalse(RequirementEngine.parse(List.of("%x% >= 6")).test(null, five));
        assertFalse(RequirementEngine.parse(List.of("%x% <= 4")).test(null, five));
        assertFalse(RequirementEngine.parse(List.of("%x% != 5")).test(null, five));
    }

    @Test
    void integerAndDecimalCompareNumerically() {
        Requirement req = RequirementEngine.parse(List.of("%x% == 5.0"));
        assertTrue(req.test(null, resolver(Map.of("x", "5"))));
    }

    @Test
    void equalityFallsBackToCaseInsensitiveLexicographic() {
        Requirement eq = RequirementEngine.parse(List.of("%rank% = VIP"));
        assertTrue(eq.test(null, resolver(Map.of("rank", "vip"))));
        assertFalse(eq.test(null, resolver(Map.of("rank", "mvp"))));
        Requirement ne = RequirementEngine.parse(List.of("%rank% != vip"));
        assertFalse(ne.test(null, resolver(Map.of("rank", "VIP"))));
        assertTrue(ne.test(null, resolver(Map.of("rank", "mvp"))));
    }

    @Test
    void nonNumericRelationalIsFalseWithWarn() {
        List<String> warnings = new ArrayList<>();
        Requirement req = RequirementEngine.parse(List.of("%rank% > gold"), warnings::add);
        assertFalse(req.test(null, resolver(Map.of("rank", "vip"))));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains(">"));
        assertTrue(warnings.get(0).contains("vip"));
    }

    @Test
    void andBindsTighterThanOr() {
        Requirement req = RequirementEngine.parse(List.of("%a% = 1 || %b% = 1 && %c% = 1"));
        assertTrue(req.test(null, resolver(Map.of("a", "0", "b", "1", "c", "1"))));
        assertFalse(req.test(null, resolver(Map.of("a", "0", "b", "1", "c", "0"))));
        assertTrue(req.test(null, resolver(Map.of("a", "1", "b", "0", "c", "0"))));
        assertFalse(req.test(null, resolver(Map.of("a", "0", "b", "0", "c", "1"))));
    }

    @Test
    void malformedLineWarnsAndEvaluatesTrue() {
        List<String> warnings = new ArrayList<>();
        Requirement req = RequirementEngine.parse(List.of("sin operador"), warnings::add);
        assertTrue(req.test(null, resolver(Map.of())));
        assertEquals(1, warnings.size());
        assertTrue(warnings.get(0).contains("sin operador"));
    }

    @Test
    void emptyOperandIsMalformed() {
        List<String> warnings = new ArrayList<>();
        Requirement req = RequirementEngine.parse(List.of("> 5"), warnings::add);
        assertTrue(req.test(null, resolver(Map.of())));
        assertEquals(1, warnings.size());
    }

    @Test
    void malformedBranchTurnsWholeLineTrue() {
        List<String> warnings = new ArrayList<>();
        Requirement req = RequirementEngine.parse(List.of("%x% = 0 && garbage"), warnings::add);
        assertTrue(req.test(null, resolver(Map.of("x", "1"))));
        assertEquals(1, warnings.size());
    }

    @Test
    void nullEmptyAndBlankInputAlwaysPass() {
        assertTrue(RequirementEngine.parse(null).test(null, Function.identity()));
        assertTrue(RequirementEngine.parse(List.of()).test(null, Function.identity()));
        assertTrue(RequirementEngine.parse(List.of("  ")).test(null, Function.identity()));
    }

    @Test
    void nullResolverLeavesTokensUntouched() {
        assertTrue(RequirementEngine.parse(List.of("5 > 1")).test(null, null));
        assertFalse(RequirementEngine.parse(List.of("%x% = 1")).test(null, null));
    }

    @Test
    void placeholdersResolveAtTestTimeNotParseTime() {
        Requirement req = RequirementEngine.parse(List.of("%points% >= 100"));
        assertTrue(req.test(null, resolver(Map.of("points", "150"))));
        assertFalse(req.test(null, resolver(Map.of("points", "50"))));
    }
}
