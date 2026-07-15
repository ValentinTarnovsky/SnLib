package com.sn.lib.command.internal;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure alias-source selection and diffing of {@link AliasReconciler}, the config-driven
 * alias logic without any Bukkit CommandMap. No server is needed.
 */
class AliasReconcilerTest {

    @Test
    void supplierAbsentUsesTheStaticFallback() {
        // supplied == null -> fallback wins; the root name is dropped, "cl" survives.
        assertEquals(List.of("cl"),
                AliasReconciler.resolve(null, List.of("cl", "clan"), "clan", List.of()));
    }

    @Test
    void configIsAuthoritativeOverTheFallback() {
        assertEquals(List.of("x", "y"),
                AliasReconciler.resolve(List.of("x", "y"), List.of("cl"), "clan", List.of()));
    }

    @Test
    void emptySuppliedClearsTheAliases() {
        // An empty (but present) authoritative list means "no aliases".
        assertTrue(AliasReconciler.resolve(List.of(), List.of("cl"), "clan", List.of()).isEmpty());
    }

    @Test
    void resolveExcludesRootNameAndDeclaredAliasesAndDedupes() {
        assertEquals(List.of("cl"),
                AliasReconciler.resolve(List.of("CL", "cl", "CLAN", "alt"),
                        List.of("ignored"), "clan", List.of("alt")));
    }

    @Test
    void resolveLowercasesAndTrims() {
        assertEquals(List.of("cl", "gang"),
                AliasReconciler.resolve(List.of("  CL ", "Gang"), List.of(), "clan", List.of()));
    }

    @Test
    void diffReportsAddedAndRemoved() {
        AliasReconciler.Diff diff = AliasReconciler.diff(List.of("a", "b"), List.of("b", "c"));
        assertEquals(List.of("c"), diff.added());
        assertEquals(List.of("a"), diff.removed());
    }

    @Test
    void diffOnEqualSetsIsEmpty() {
        AliasReconciler.Diff diff = AliasReconciler.diff(List.of("a", "b"), List.of("a", "b"));
        assertTrue(diff.added().isEmpty());
        assertTrue(diff.removed().isEmpty());
    }

    @Test
    void diffIsCaseInsensitive() {
        AliasReconciler.Diff diff = AliasReconciler.diff(List.of("A"), List.of("a", "b"));
        assertEquals(List.of("b"), diff.added());
        assertTrue(diff.removed().isEmpty());
    }
}
