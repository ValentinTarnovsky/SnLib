package com.sn.lib.command;

import java.lang.reflect.Proxy;
import java.util.List;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure suggestion and per-sender parse behavior of the {@link Args} factory. No Bukkit
 * server is needed: the suggestion pipeline only touches {@code org.bukkit.util.StringUtil}
 * and the {@link CommandSender} is a do-nothing dynamic proxy used solely for identity.
 */
class ArgSuggestionTest {

    /** A distinct, method-inert CommandSender used only to distinguish per-sender option sets. */
    private static CommandSender stubSender() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) {
                        return false;
                    }
                    if (ret.isPrimitive()) {
                        return 0;
                    }
                    return null;
                });
    }

    @Test
    void stringDefaultHintUsesArgNameNotText() {
        List<String> out = Args.string().suggest(null, "", "target");
        assertEquals(List.of("<target>"), out);
        assertFalse(out.contains("text"));
    }

    @Test
    void greedyDefaultHintUsesArgName() {
        assertEquals(List.of("<message>"), Args.greedy().suggest(null, "", "message"));
    }

    @Test
    void explicitHintOverridesArgNameAndIsBracketed() {
        assertEquals(List.of("<player name>"),
                Args.string("player name").suggest(null, "", "target"));
    }

    @Test
    void explicitHintAlreadyBracketedIsNotDoubled() {
        assertEquals(List.of("<amount>"), Args.string("<amount>").suggest(null, "", "x"));
        assertEquals(List.of("<amount>"), Args.greedy("<amount>").suggest(null, "", "x"));
    }

    @Test
    void hintIsHiddenOnceARealValueIsTyped() {
        assertTrue(Args.string().suggest(null, "fo", "target").isEmpty());
    }

    @Test
    void suggestingSuggestsSetButParseAcceptsAnything() throws Exception {
        var arg = Args.suggesting(() -> List.of("alpha", "beta"));
        assertEquals(List.of("alpha", "beta"), arg.suggest(null, "", "clan"));
        assertEquals(List.of("alpha"), arg.suggest(null, "al", "clan"));
        // No parse restriction: an unknown token is returned as-is, not rejected.
        assertEquals("does-not-exist", arg.parse("does-not-exist"));
    }

    @Test
    void oneOfFunctionScopesSuggestionsAndParseToTheSender() throws Exception {
        CommandSender caller = stubSender();
        var arg = Args.oneOf(sender -> sender == caller ? List.of("Alice", "Bob") : List.of("Zoe"));

        // Per-sender suggestions.
        assertEquals(List.of("Alice", "Bob"), arg.suggest(caller, "", "member"));
        assertEquals(List.of("Zoe"), arg.suggest(null, "", "member"));

        // Per-sender parse: canonical form for a member of the caller's set...
        assertEquals("Alice", arg.parse(caller, "alice"));
        // ...and rejection for a value valid for a DIFFERENT sender only.
        assertThrows(Arg.ArgParseException.class, () -> arg.parse(caller, "Zoe"));
    }

    @Test
    void oneOfSupplierStillCanonicalizesAndRejects() throws Exception {
        var arg = Args.oneOf(() -> List.of("Admin"));
        assertEquals("Admin", arg.parse("admin"));
        assertThrows(Arg.ArgParseException.class, () -> arg.parse("nope"));
        assertEquals(List.of("Admin"), arg.suggest(null, "", "role"));
    }
}
