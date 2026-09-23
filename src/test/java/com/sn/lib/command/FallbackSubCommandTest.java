package com.sn.lib.command;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The root fallback declared through {@link SnCommands.RootBuilder#fallbackSub(String)}:
 * pure resolution, tab completion, short-path usage and help, and the register-time
 * validation, exercised through the real {@link SubCommandBuilder} and the {@code static}
 * helpers of {@link RootCommand}, like {@link NestedCommandTest}. No Bukkit server is needed.
 */
class FallbackSubCommandTest {

    // ------------------------------------------------------------------ fixtures

    /** A permission-aware CommandSender: {@code hasPermission(String)} answers from a set. */
    private static CommandSender senderWith(String... permissions) {
        Set<String> held = new HashSet<>(Arrays.asList(permissions));
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission") && args != null
                            && args.length == 1 && args[0] instanceof String permission) {
                        return held.contains(permission);
                    }
                    if (method.getName().equals("getName")) {
                        return "tester";
                    }
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

    /** A free-form arg whose suggestions ignore the partial, so the root filter is visible. */
    private static Arg<String> unfiltered(String... options) {
        return new Arg<>() {
            @Override
            public String parse(String raw) {
                return raw;
            }

            @Override
            public List<String> suggest(CommandSender sender, String partial) {
                return List.of(options);
            }
        };
    }

    /**
     * The {@code /trade} tree: the {@code request} fallback candidate (permission
     * {@code trade.request}, a player plus an optional greedy note), two plain leaves
     * ({@code accept}, and {@code deny} with the alias {@code no}) and an {@code admin} group
     * behind {@code trade.admin}.
     */
    private static List<RootCommand.Sub> tradeSubs() {
        RootCommand.Sub request = new SubCommandBuilder(null, "request")
                .permission("trade.request")
                .description("Sends a trade request")
                .arg("player", unfiltered("Steve", "Alex", "Accept", "Amy"))
                .argOptional("note", Args.greedy())
                .executes(context -> { })
                .build();
        RootCommand.Sub accept = new SubCommandBuilder(null, "accept")
                .description("Accepts a request")
                .arg("player", Args.string())
                .executes(context -> { })
                .build();
        RootCommand.Sub deny = new SubCommandBuilder(null, "deny")
                .aliases("no")
                .description("Denies a request")
                .arg("player", Args.string())
                .executes(context -> { })
                .build();
        RootCommand.Sub admin = new SubCommandBuilder(null, "admin")
                .permission("trade.admin")
                .description("Admin tools")
                .sub("cancel", cancel -> cancel
                        .description("Cancels a trade")
                        .arg("player", Args.string())
                        .executes(context -> { }))
                .build();
        return List.of(request, accept, deny, admin);
    }

    /** The {@code /pay} tree: a {@code send} fallback with two required arguments. */
    private static List<RootCommand.Sub> paySubs() {
        RootCommand.Sub send = new SubCommandBuilder(null, "send")
                .description("Sends money")
                .arg("player", Args.string())
                .arg("amount", Args.intRange(1, 64))
                .executes(context -> { })
                .build();
        return List.of(send);
    }

    private static RootCommand.Sub fallbackOf(List<RootCommand.Sub> subs, String name) {
        return RootCommand.fallbackOf("test", subs, name);
    }

    private static RootCommand.Resolution resolve(CommandSender sender,
            List<RootCommand.Sub> subs, String name, String rootPath, String... args) {
        return RootCommand.resolve(sender, null, subs, fallbackOf(subs, name), rootPath, args);
    }

    private static List<String> tab(CommandSender sender, List<RootCommand.Sub> subs,
            String name, String... args) {
        return RootCommand.tab(sender, null, subs, fallbackOf(subs, name), args);
    }

    private static RootCommand.Run asRun(RootCommand.Resolution resolution) {
        assertTrue(resolution instanceof RootCommand.Run,
                () -> "expected Run but was " + resolution.getClass().getSimpleName());
        return (RootCommand.Run) resolution;
    }

    private static void assertMessage(RootCommand.Resolution resolution, String key) {
        assertTrue(resolution instanceof RootCommand.Message,
                () -> "expected Message but was " + resolution.getClass().getSimpleName());
        assertEquals(key, ((RootCommand.Message) resolution).key());
    }

    private static String firstPh(RootCommand.Resolution resolution) {
        return ((RootCommand.Message) resolution).phs()[0].value();
    }

    // ------------------------------------------------------------- dispatch

    @Test
    void unmatchedFirstTokenRunsTheFallbackWithEveryToken() {
        RootCommand.Run run = asRun(resolve(senderWith("trade.request"), tradeSubs(), "request",
                "/trade", "Steve"));
        assertEquals("request", run.sub().name);
        assertEquals("Steve", run.context().get("player"));
        assertEquals("Steve", run.context().raw(0));
        assertEquals("trade", run.context().label());
        assertEquals("/trade request", run.path(), "the logged path still names the leaf");
    }

    @Test
    void fallbackParsesItsLaterArgumentsRelativeToTheFirstToken() {
        RootCommand.Run run = asRun(resolve(senderWith("trade.request"), tradeSubs(), "request",
                "/trade", "Steve", "one", "diamond"));
        assertEquals("Steve", run.context().get("player"));
        assertEquals("one diamond", run.context().get("note"));
    }

    @Test
    void declaredSubcommandWinsOverTheFallback() {
        CommandSender sender = senderWith("trade.request");
        RootCommand.Run accept = asRun(resolve(sender, tradeSubs(), "request", "/trade",
                "accept", "Steve"));
        assertEquals("accept", accept.sub().name);
        assertEquals("/trade accept", accept.path());
        RootCommand.Run upper = asRun(resolve(sender, tradeSubs(), "request", "/trade",
                "ACCEPT", "Steve"));
        assertEquals("accept", upper.sub().name);
        RootCommand.Run literal = asRun(resolve(sender, tradeSubs(), "request", "/trade",
                "request", "accept"));
        assertEquals("request", literal.sub().name);
        assertEquals("accept", literal.context().get("player"),
                "a player named like a subcommand is reached through the explicit path");
    }

    @Test
    void declaredAliasWinsOverTheFallback() {
        RootCommand.Run run = asRun(resolve(senderWith("trade.request"), tradeSubs(), "request",
                "/trade", "no", "Steve"));
        assertEquals("deny", run.sub().name);
        assertEquals("Steve", run.context().get("player"));
    }

    @Test
    void declaredSubcommandTheSenderCannotUseDoesNotFallThrough() {
        assertMessage(resolve(senderWith("trade.request"), tradeSubs(), "request", "/trade",
                "admin", "cancel", "Steve"), "snlib.no-permission");
    }

    @Test
    void senderWithoutTheFallbackPermissionGetsUnknownSubcommand() {
        RootCommand.Resolution resolution = resolve(senderWith(), tradeSubs(), "request",
                "/trade", "Steve");
        assertMessage(resolution, "snlib.unknown-subcommand");
        assertEquals("Steve", firstPh(resolution));
    }

    @Test
    void rootPermissionStillGatesTheFallback() {
        List<RootCommand.Sub> subs = tradeSubs();
        assertMessage(RootCommand.resolve(senderWith("trade.request"), "trade.use", subs,
                fallbackOf(subs, "request"), "/trade", new String[] {"Steve"}),
                "snlib.no-permission");
        assertTrue(RootCommand.resolve(senderWith("trade.use", "trade.request"), "trade.use",
                subs, fallbackOf(subs, "request"), "/trade", new String[] {"Steve"})
                instanceof RootCommand.Run);
    }

    @Test
    void bareRootIsUnchanged() {
        assertTrue(resolve(senderWith("trade.request"), tradeSubs(), "request", "/trade")
                instanceof RootCommand.Empty);
    }

    @Test
    void withoutAFallbackAnUnmatchedTokenIsStillUnknown() {
        RootCommand.Resolution resolution = RootCommand.resolve(senderWith("trade.request"),
                null, tradeSubs(), null, "/trade", new String[] {"Steve"});
        assertMessage(resolution, "snlib.unknown-subcommand");
        assertEquals("Steve", firstPh(resolution));
    }

    // ---------------------------------------------------------------- tab

    @Test
    void firstTokenOffersNamesThenFallbackSuggestions() {
        assertEquals(List.of("accept", "deny", "request", "Steve", "Alex", "Amy"),
                tab(senderWith("trade.request"), tradeSubs(), "request", ""),
                "Accept repeats the accept subcommand and is offered once");
    }

    @Test
    void firstTokenFiltersTheUnionByThePrefix() {
        assertEquals(List.of("accept", "Alex", "Amy"),
                tab(senderWith("trade.request"), tradeSubs(), "request", "a"));
        assertEquals(List.of("Steve"),
                tab(senderWith("trade.request"), tradeSubs(), "request", "st"));
        assertEquals(List.of("accept", "admin", "Alex", "Amy"),
                tab(senderWith("trade.request", "trade.admin"), tradeSubs(), "request", "a"));
    }

    @Test
    void firstTokenOmitsFallbackSuggestionsWithoutItsPermission() {
        assertEquals(List.of("accept", "deny"),
                tab(senderWith(), tradeSubs(), "request", ""));
        assertEquals(List.of("accept"),
                tab(senderWith(), tradeSubs(), "request", "a"));
    }

    @Test
    void laterTokensCompleteTheFallbackShiftedByTheMissingName() {
        CommandSender sender = senderWith("trade.request");
        assertEquals(List.of("<note>"), tab(sender, tradeSubs(), "request", "Steve", ""));
        assertEquals(List.of("<note>"),
                tab(sender, tradeSubs(), "request", "Steve", "one", ""),
                "a greedy last argument keeps completing");
        assertEquals(List.of(), tab(senderWith(), tradeSubs(), "request", "Steve", ""));
    }

    @Test
    void laterTokensUnderADeclaredSubcommandAreUnchanged() {
        CommandSender sender = senderWith("trade.request");
        List<RootCommand.Sub> subs = tradeSubs();
        for (String[] args : List.of(new String[] {"accept", ""}, new String[] {"request", "St"},
                new String[] {"request", "Steve", ""}, new String[] {"admin", ""})) {
            assertEquals(RootCommand.tab(sender, null, subs, args),
                    RootCommand.tab(sender, null, subs, fallbackOf(subs, "request"), args));
        }
        assertEquals(List.of("<player>"), tab(sender, subs, "request", "accept", ""));
        assertEquals(List.of(), tab(sender, subs, "request", "admin", ""));
    }

    @Test
    void withoutAFallbackTheFirstTokenOffersNamesOnly() {
        assertEquals(List.of("accept", "deny", "request"),
                RootCommand.tab(senderWith("trade.request"), null, tradeSubs(), null,
                        new String[] {""}));
    }

    // ------------------------------------------------------- short-path usage

    @Test
    void arityErrorThroughTheFallbackRendersTheShortPath() {
        RootCommand.Resolution shortcut = resolve(senderWith(), paySubs(), "send", "/pay",
                "Steve");
        assertMessage(shortcut, "snlib.usage");
        assertEquals("/pay <player> <amount>", firstPh(shortcut));
        RootCommand.Resolution explicit = resolve(senderWith(), paySubs(), "send", "/pay",
                "send", "Steve");
        assertMessage(explicit, "snlib.usage");
        assertEquals("/pay send <player> <amount>", firstPh(explicit));
    }

    @Test
    void shortPathFollowsTheAliasTheSenderTyped() {
        RootCommand.Resolution resolution = resolve(senderWith(), paySubs(), "send", "/p",
                "Steve");
        assertEquals("/p <player> <amount>", firstPh(resolution));
    }

    @Test
    void argumentErrorsThroughTheFallbackKeepTheirOwnMessage() {
        assertMessage(resolve(senderWith(), paySubs(), "send", "/pay", "Steve", "999"),
                "snlib.out-of-range");
        assertEquals(12, asRun(resolve(senderWith(), paySubs(), "send", "/pay",
                "Steve", "12")).context().getInt("amount"));
    }

    @Test
    void conditionFailureThroughTheFallbackRendersTheShortPath() {
        RootCommand.Sub send = new SubCommandBuilder(null, "send")
                .arg("player", Args.string())
                .when(0, token -> !token.startsWith("-"))
                .executes(context -> { })
                .build();
        RootCommand.Resolution resolution = resolve(senderWith(), List.of(send), "send",
                "/pay", "-Steve");
        assertMessage(resolution, "snlib.usage");
        assertEquals("/pay <player>", firstPh(resolution));
    }

    @Test
    void explicitUsageStaysLiteralThroughTheFallback() {
        RootCommand.Sub send = new SubCommandBuilder(null, "send")
                .usage("/{label} send <player> <amount>")
                .arg("player", Args.string())
                .arg("amount", Args.intRange(1, 64))
                .executes(context -> { })
                .build();
        assertEquals("/pay send <player> <amount>",
                firstPh(resolve(senderWith(), List.of(send), "send", "/pay", "Steve")));
    }

    // --------------------------------------------------------------- help

    @Test
    void helpRendersTheFallbackOnceInItsShortForm() {
        List<RootCommand.Sub> subs = tradeSubs();
        List<String> usages = RootCommand.collectHelp(senderWith("trade.request"), subs,
                fallbackOf(subs, "request"), "/trade", null).stream()
                .map(RootCommand.HelpLine::usage).toList();
        assertEquals(List.of("/trade <player> [note...]", "/trade accept <player>",
                "/trade deny <player>"), usages);
    }

    @Test
    void helpShortFormFollowsTheLabelAndThePermission() {
        List<RootCommand.Sub> subs = tradeSubs();
        RootCommand.Sub fallback = fallbackOf(subs, "request");
        RootCommand.HelpLine line = RootCommand.collectHelp(senderWith("trade.request"), subs,
                fallback, "/t", "trade.use").get(0);
        assertEquals("/t <player> [note...]", line.usage());
        assertEquals("Sends a trade request", line.description());
        assertEquals("trade.request", line.permission());
        assertEquals(List.of("/trade accept <player>", "/trade deny <player>"),
                RootCommand.collectHelp(senderWith(), subs, fallback, "/trade", null).stream()
                        .map(RootCommand.HelpLine::usage).toList());
    }

    @Test
    void helpWithoutAFallbackKeepsTheFullPath() {
        assertEquals("/trade request <player> [note...]",
                RootCommand.collectHelp(senderWith("trade.request"), tradeSubs(), "/trade", null)
                        .get(0).usage());
    }

    // --------------------------------------------------------- validation

    @Test
    void fallbackResolvesByNameOrAlias() {
        List<RootCommand.Sub> subs = tradeSubs();
        assertNull(RootCommand.fallbackOf("trade", subs, null));
        assertSame(subs.get(0), RootCommand.fallbackOf("trade", subs, "request"));
        assertSame(subs.get(2), RootCommand.fallbackOf("trade", subs, "no"));
    }

    @Test
    void fallbackNamingNoDeclaredSubcommandIsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RootCommand.fallbackOf("trade", tradeSubs(), "nope"));
        assertTrue(e.getMessage().contains("'nope'") && e.getMessage().contains("/trade"),
                e.getMessage());
    }

    @Test
    void fallbackNamingAGroupIsRejected() {
        IllegalStateException e = assertThrows(IllegalStateException.class,
                () -> RootCommand.fallbackOf("trade", tradeSubs(), "admin"));
        assertTrue(e.getMessage().contains("group"), e.getMessage());
    }

    @Test
    void registerValidatesTheFallbackBeforeBuildingTheRoot() {
        // A null context proves the check runs before anything touches the plugin or Bukkit.
        SnCommands commands = new SnCommands(null, null, false);
        SnCommands.RootBuilder group = commands.root("trade")
                .sub("admin")
                    .sub("cancel", cancel -> cancel.executes(context -> { }))
                .and()
                .fallbackSub("admin");
        assertThrows(IllegalStateException.class, group::register);
        SnCommands.RootBuilder unknown = commands.root("trade")
                .sub("request").arg("player", Args.string()).executes(context -> { })
                .and()
                .fallbackSub("help");
        assertThrows(IllegalStateException.class, unknown::register,
                "an injected default is not a declared subcommand");
    }

    @Test
    void fallbackSubRejectsABlankName() {
        SnCommands.RootBuilder root = new SnCommands(null, null, false).root("trade");
        assertThrows(IllegalArgumentException.class, () -> root.fallbackSub("  "));
        assertThrows(NullPointerException.class, () -> root.fallbackSub(null));
    }
}
