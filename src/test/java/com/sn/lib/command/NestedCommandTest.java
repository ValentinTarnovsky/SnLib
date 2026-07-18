package com.sn.lib.command;

import java.lang.reflect.Proxy;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;

import org.bukkit.command.CommandSender;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure resolution, tab-completion and generated-help behavior of the {@link RootCommand}
 * tree, exercised through the real {@link SubCommandBuilder} and the {@code static}
 * package-private helpers ({@link RootCommand#resolve}, {@link RootCommand#tab},
 * {@link RootCommand#collectHelp}, {@link RootCommand#usageOf}, {@link RootCommand#groupUsage}).
 * No Bukkit server is needed: these helpers only touch {@code org.bukkit.util.StringUtil}
 * and the {@link CommandSender}, a permission-aware dynamic proxy. The message-sending and
 * executor-invoking effects that need the {@code Sn} context live in {@code execute()} and
 * are exercised at that integration boundary, not here.
 */
class NestedCommandTest {

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

    /**
     * The {@code /clan} tree: two top-level leaves, one {@code admin} group (permission
     * {@code clan.admin}) whose children mix an own-permission leaf ({@code disband}), a
     * permissionless leaf ({@code promote}) and a deeper {@code region} group.
     */
    private static List<RootCommand.Sub> clanSubs() {
        RootCommand.Sub info = new SubCommandBuilder(null, "info")
                .description("Shows clan info")
                .argOptional("clan", Args.suggesting(() -> List.of("Alpha", "Beta")))
                .executes(context -> { })
                .build();
        RootCommand.Sub create = new SubCommandBuilder(null, "create")
                .description("Creates a clan")
                .arg("name", Args.string())
                .executes(context -> { })
                .build();
        RootCommand.Sub admin = new SubCommandBuilder(null, "admin")
                .permission("clan.admin")
                .description("Admin tools")
                .sub("disband", disband -> disband
                        .permission("clan.admin.disband")
                        .aliases("del")
                        .description("Disbands a clan")
                        .arg("clan", Args.suggesting(() -> List.of("Alpha", "Beta")))
                        .executes(context -> { }))
                .sub("promote", promote -> promote
                        .description("Promotes a member")
                        .arg("player", Args.string())
                        .executes(context -> { }))
                .sub("region", region -> region
                        .permission("clan.admin.region")
                        .description("Region tools")
                        .sub("clear", clear -> clear
                                .description("Clears the region")
                                .executes(context -> { })))
                .build();
        return List.of(info, create, admin);
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

    private static List<String> helpUsages(CommandSender sender, List<RootCommand.Sub> subs,
            String inherited) {
        return RootCommand.collectHelp(sender, subs, "/clan", inherited).stream()
                .map(RootCommand.HelpLine::usage).toList();
    }

    // ------------------------------------------------------- nested dispatch

    @Test
    void nestedLeafResolvesToTheDeepChildWithFullPath() {
        CommandSender sender = senderWith("clan.admin", "clan.admin.disband");
        RootCommand.Run run = asRun(RootCommand.resolve(sender, null, clanSubs(), "/clan",
                new String[] {"admin", "disband", "Alpha"}));
        assertEquals("disband", run.sub().name);
        assertEquals("Alpha", run.context().get("clan"));
        assertEquals("/clan admin disband", run.path());
    }

    @Test
    void childAliasResolvesTheSameLeaf() {
        CommandSender sender = senderWith("clan.admin", "clan.admin.disband");
        RootCommand.Run run = asRun(RootCommand.resolve(sender, null, clanSubs(), "/clan",
                new String[] {"admin", "del", "Alpha"}));
        assertEquals("disband", run.sub().name);
        assertEquals("/clan admin disband", run.path());
    }

    @Test
    void depthThreeGroupResolvesTheLeaf() {
        CommandSender sender = senderWith("clan.admin", "clan.admin.region");
        RootCommand.Run run = asRun(RootCommand.resolve(sender, null, clanSubs(), "/clan",
                new String[] {"admin", "region", "clear"}));
        assertEquals("clear", run.sub().name);
        assertEquals("/clan admin region clear", run.path());
    }

    @Test
    void bareGroupShowsFullPathUsageOfPermittedChildren() {
        CommandSender sender =
                senderWith("clan.admin", "clan.admin.disband", "clan.admin.region");
        RootCommand.Resolution resolution = RootCommand.resolve(sender, null, clanSubs(),
                "/clan", new String[] {"admin"});
        assertMessage(resolution, "snlib.usage");
        assertEquals("/clan admin <disband|promote|region>", firstPh(resolution));
    }

    @Test
    void bareGroupUsageOmitsChildrenTheSenderCannotUse() {
        CommandSender sender = senderWith("clan.admin");
        RootCommand.Resolution resolution = RootCommand.resolve(sender, null, clanSubs(),
                "/clan", new String[] {"admin"});
        assertMessage(resolution, "snlib.usage");
        assertEquals("/clan admin <promote>", firstPh(resolution));
    }

    @Test
    void unknownChildReportsTheFullPath() {
        CommandSender sender = senderWith("clan.admin");
        RootCommand.Resolution resolution = RootCommand.resolve(sender, null, clanSubs(),
                "/clan", new String[] {"admin", "nope"});
        assertMessage(resolution, "snlib.unknown-subcommand");
        assertEquals("/clan admin nope", firstPh(resolution));
    }

    // -------------------------------------------------- permission chains

    @Test
    void groupPermissionGatesTheWholeSubtree() {
        RootCommand.Resolution resolution = RootCommand.resolve(senderWith(), null, clanSubs(),
                "/clan", new String[] {"admin", "disband", "Alpha"});
        assertMessage(resolution, "snlib.no-permission");
    }

    @Test
    void leafPermissionIsRequiredEvenWithTheGroupPermission() {
        CommandSender sender = senderWith("clan.admin");
        RootCommand.Resolution resolution = RootCommand.resolve(sender, null, clanSubs(),
                "/clan", new String[] {"admin", "disband", "Alpha"});
        assertMessage(resolution, "snlib.no-permission");
    }

    @Test
    void leafWithoutOwnPermissionInheritsTheGroupPermission() {
        CommandSender sender = senderWith("clan.admin");
        RootCommand.Run run = asRun(RootCommand.resolve(sender, null, clanSubs(), "/clan",
                new String[] {"admin", "promote", "Bob"}));
        assertEquals("promote", run.sub().name);
        assertEquals("Bob", run.context().get("player"));
    }

    @Test
    void deepLeafRequiresEveryPermissionOnThePath() {
        // Holds the group but not the region group permission: the region subtree is closed.
        CommandSender sender = senderWith("clan.admin");
        RootCommand.Resolution resolution = RootCommand.resolve(sender, null, clanSubs(),
                "/clan", new String[] {"admin", "region", "clear"});
        assertMessage(resolution, "snlib.no-permission");
    }

    // -------------------------------------------------------- nested tab

    @Test
    void rootTabHidesAGroupWithoutItsPermission() {
        assertEquals(List.of("create", "info"),
                RootCommand.tab(senderWith(), null, clanSubs(), new String[] {""}));
        assertEquals(List.of("admin", "create", "info"),
                RootCommand.tab(senderWith("clan.admin"), null, clanSubs(), new String[] {""}));
    }

    @Test
    void groupTabFiltersChildrenByPermission() {
        assertEquals(List.of("promote"),
                RootCommand.tab(senderWith("clan.admin"), null, clanSubs(),
                        new String[] {"admin", ""}));
        assertEquals(List.of("disband", "promote", "region"),
                RootCommand.tab(
                        senderWith("clan.admin", "clan.admin.disband", "clan.admin.region"),
                        null, clanSubs(), new String[] {"admin", ""}));
    }

    @Test
    void groupTabIsEmptyWithoutTheGroupPermission() {
        assertEquals(List.of(),
                RootCommand.tab(senderWith(), null, clanSubs(), new String[] {"admin", ""}));
    }

    @Test
    void nestedLeafArgumentSuggestionsResolveRelativeToTheLeaf() {
        CommandSender sender = senderWith("clan.admin", "clan.admin.disband");
        assertEquals(List.of("Alpha", "Beta"),
                RootCommand.tab(sender, null, clanSubs(), new String[] {"admin", "disband", ""}));
        assertEquals(List.of("Alpha"),
                RootCommand.tab(sender, null, clanSubs(),
                        new String[] {"admin", "disband", "Al"}));
    }

    @Test
    void depthThreeGroupTabSuggestsItsLeaf() {
        CommandSender sender = senderWith("clan.admin", "clan.admin.region");
        assertEquals(List.of("clear"),
                RootCommand.tab(sender, null, clanSubs(), new String[] {"admin", "region", ""}));
    }

    // ------------------------------------------------- full-path usage / help

    @Test
    void nestedLeafUsageShowsTheFullPath() {
        RootCommand.Sub admin = clanSubs().get(2);
        RootCommand.Sub disband = admin.children.get(0);
        assertEquals("/clan admin disband <clan>",
                RootCommand.usageOf(disband, "/clan admin disband"));
    }

    @Test
    void helpFlattensGroupsIntoFullPathLeafEntries() {
        CommandSender sender =
                senderWith("clan.admin", "clan.admin.disband", "clan.admin.region");
        assertEquals(List.of(
                "/clan info [clan]",
                "/clan create <name>",
                "/clan admin disband <clan>",
                "/clan admin promote <player>",
                "/clan admin region clear"), helpUsages(sender, clanSubs(), null));
    }

    @Test
    void helpHidesLeavesTheSenderCannotReach() {
        CommandSender sender = senderWith("clan.admin");
        assertEquals(List.of(
                "/clan info [clan]",
                "/clan create <name>",
                "/clan admin promote <player>"), helpUsages(sender, clanSubs(), null));
    }

    @Test
    void helpEffectivePermissionNarrowsAlongThePath() {
        CommandSender sender =
                senderWith("clan.admin", "clan.admin.disband", "clan.admin.region");
        Map<String, String> effective = new LinkedHashMap<>();
        for (RootCommand.HelpLine line : RootCommand.collectHelp(sender, clanSubs(), "/clan",
                "clan.base")) {
            effective.put(line.usage(), String.valueOf(line.permission()));
        }
        assertEquals("clan.base", effective.get("/clan info [clan]"));
        assertEquals("clan.base", effective.get("/clan create <name>"));
        assertEquals("clan.admin.disband", effective.get("/clan admin disband <clan>"));
        assertEquals("clan.admin", effective.get("/clan admin promote <player>"));
        assertEquals("clan.admin.region", effective.get("/clan admin region clear"));
    }

    // ----------------------------------------------- bare-root onEmpty hook

    @Test
    void zeroArgumentsResolveToEmpty() {
        assertTrue(RootCommand.resolve(senderWith(), null, clanSubs(), "/clan", new String[] {})
                instanceof RootCommand.Empty);
    }

    @Test
    void rootContextHelpTriggersTheRenderer() {
        CommandSender sender = senderWith();
        int[] rendered = {-1};
        RootContext context = new RootContext(sender, page -> rendered[0] = page);
        assertSame(sender, context.sender());
        context.help();
        assertEquals(1, rendered[0]);
        context.help(3);
        assertEquals(3, rendered[0]);
    }

    @Test
    void onEmptyHookCanRunItsActionAndStillTriggerHelp() {
        CommandSender sender = senderWith();
        int[] rendered = {-1};
        boolean[] ran = {false};
        Consumer<RootContext> hook = context -> {
            ran[0] = true;
            context.help();
        };
        hook.accept(new RootContext(sender, page -> rendered[0] = page));
        assertTrue(ran[0]);
        assertEquals(1, rendered[0]);
    }

    @Test
    void andIsRejectedOnANestedChildBuilder() {
        SubCommandBuilder orphan = new SubCommandBuilder(null, "admin");
        assertThrows(IllegalStateException.class, orphan::and);
    }

    // ------------------------------------------------- flat-tree regression

    private static List<RootCommand.Sub> flatSubs() {
        RootCommand.Sub create = new SubCommandBuilder(null, "create")
                .description("Creates a clan")
                .arg("name", Args.string())
                .executes(context -> { })
                .build();
        RootCommand.Sub top = new SubCommandBuilder(null, "top")
                .description("Top clans")
                .argOptional("page", Args.string())
                .executes(context -> { })
                .build();
        return List.of(create, top);
    }

    @Test
    void flatDispatchIsUnchanged() {
        RootCommand.Run run = asRun(RootCommand.resolve(senderWith(), null, flatSubs(), "/clan",
                new String[] {"create", "Alpha"}));
        assertEquals("create", run.sub().name);
        assertEquals("Alpha", run.context().get("name"));
        assertEquals("/clan create", run.path());
    }

    @Test
    void flatUnknownStillUsesTheBareToken() {
        RootCommand.Resolution resolution = RootCommand.resolve(senderWith(), null, flatSubs(),
                "/clan", new String[] {"nope"});
        assertMessage(resolution, "snlib.unknown-subcommand");
        assertEquals("nope", firstPh(resolution));
    }

    @Test
    void flatArityFailureShowsTheUsage() {
        RootCommand.Resolution resolution = RootCommand.resolve(senderWith(), null, flatSubs(),
                "/clan", new String[] {"create"});
        assertMessage(resolution, "snlib.usage");
        assertEquals("/clan create <name>", firstPh(resolution));
    }

    @Test
    void flatTabIsUnchanged() {
        assertEquals(List.of("create", "top"),
                RootCommand.tab(senderWith(), null, flatSubs(), new String[] {""}));
        assertEquals(List.of("create"),
                RootCommand.tab(senderWith(), null, flatSubs(), new String[] {"c"}));
        assertEquals(List.of("<name>"),
                RootCommand.tab(senderWith(), null, flatSubs(), new String[] {"create", ""}));
    }

    @Test
    void flatHelpIsUnchanged() {
        assertEquals(List.of("/clan create <name>", "/clan top [page]"),
                helpUsages(senderWith(), flatSubs(), null));
    }

    @Test
    void rootPermissionGatesDispatchAndTab() {
        assertMessage(RootCommand.resolve(senderWith(), "clan.use", flatSubs(), "/clan",
                new String[] {"create", "Alpha"}), "snlib.no-permission");
        assertTrue(RootCommand.resolve(senderWith("clan.use"), "clan.use", flatSubs(), "/clan",
                new String[] {"create", "Alpha"}) instanceof RootCommand.Run);
        assertEquals(List.of(),
                RootCommand.tab(senderWith(), "clan.use", flatSubs(), new String[] {""}));
    }

    @Test
    void leafConditionRejectsABadTokenWithTheUsage() {
        RootCommand.Sub set = new SubCommandBuilder(null, "set")
                .description("Sets a value")
                .arg("value", Args.string())
                .when(0, token -> token.equals("ok"))
                .executes(context -> { })
                .build();
        List<RootCommand.Sub> subs = List.of(set);
        assertMessage(RootCommand.resolve(senderWith(), null, subs, "/clan",
                new String[] {"set", "bad"}), "snlib.usage");
        assertTrue(RootCommand.resolve(senderWith(), null, subs, "/clan",
                new String[] {"set", "ok"}) instanceof RootCommand.Run);
    }

    @Test
    void greedyLeafJoinsRemainingTokensAndRendersEllipsisUsage() {
        RootCommand.Sub say = new SubCommandBuilder(null, "say")
                .description("Broadcasts")
                .arg("message", Args.greedy())
                .executes(context -> { })
                .build();
        RootCommand.Run run = asRun(RootCommand.resolve(senderWith(), null, List.of(say), "/clan",
                new String[] {"say", "hello", "there", "world"}));
        assertEquals("hello there world", run.context().get("message"));
        assertEquals("/clan say <message...>", RootCommand.usageOf(say, "/clan say"));
    }

    @Test
    void leafParseErrorBecomesItsLangMessage() {
        RootCommand.Sub level = new SubCommandBuilder(null, "level")
                .description("Sets a level")
                .arg("n", Args.intRange(1, 10))
                .executes(context -> { })
                .build();
        assertMessage(RootCommand.resolve(senderWith(), null, List.of(level), "/clan",
                new String[] {"level", "999"}), "snlib.out-of-range");
    }
}
