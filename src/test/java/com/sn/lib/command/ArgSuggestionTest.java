package com.sn.lib.command;

import java.lang.reflect.Proxy;
import java.util.ArrayList;
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

    /**
     * The 1.34.1 regression case, verbatim: SnPets hands {@code oneOf} its 165 pet ids in file
     * order (11 families x 15 tiers, A..Z), and until 1.34.1 the cap was applied BEFORE the
     * prefix filter - the first 100 ids were kept, {@code Orbes_4} was the last of them, and
     * {@code p} matched nothing while {@code Pase_15} typed by hand still parsed.
     */
    @Test
    void anOptionPastTheCapIsReachedByItsPrefix() {
        List<String> ids = new ArrayList<>();
        for (String family : List.of("Azada", "Enchant", "Essence", "Experiencia", "Global",
                "Money", "Orbes", "Pase", "Pico", "Shard", "Tokens")) {
            for (int tier = 1; tier <= 15; tier++) {
                ids.add(family + "_" + tier);
            }
        }
        var arg = Args.oneOf(() -> ids);

        List<String> p = arg.suggest(null, "p", "pet");
        assertEquals(30, p.size());
        assertTrue(p.stream().allMatch(id -> id.startsWith("Pase_") || id.startsWith("Pico_")));
        assertEquals(List.of("Pase_15"), arg.suggest(null, "pase_15", "pet"));
        assertEquals(List.of("Tokens_1", "Tokens_10", "Tokens_11", "Tokens_12", "Tokens_13",
                "Tokens_14", "Tokens_15"), arg.suggest(null, "tokens_1", "pet"));
        // ...and an empty prefix lists every one of the 165, which sits under the cap.
        assertEquals(165, arg.suggest(null, "", "pet").size());
    }

    /** The cap bounds an EMPTY prefix over a pathological set and never hides a match. */
    @Test
    void theCapBoundsAnEmptyPrefixButNeverHidesAMatch() {
        List<String> many = new ArrayList<>();
        for (int i = 0; i < 700; i++) {
            many.add(String.format("opt%04d", i));
        }
        many.add("zzz-last");
        var arg = Args.suggesting(() -> many);

        assertEquals(500, arg.suggest(null, "", "x").size());
        assertEquals(List.of("zzz-last"), arg.suggest(null, "zz", "x"));
        assertEquals(List.of("opt0699"), arg.suggest(null, "opt0699", "x"));
        // A null option is dropped rather than thrown on by the filter or the sort.
        List<String> withNull = new ArrayList<>(List.of("b", "a"));
        withNull.add(null);
        assertEquals(List.of("a", "b"), Args.oneOf(() -> withNull).suggest(null, "", "x"));
    }
}
