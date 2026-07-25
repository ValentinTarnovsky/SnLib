package com.sn.lib.command;

import java.lang.reflect.Proxy;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.bukkit.command.CommandSender;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Seeding and resolution of the translatable command descriptions and argument labels
 * ({@link CommandLang}). Both halves are exercised through their pure seams: the generated
 * block is asserted as real parsed YAML, and the apply side runs against a map-backed
 * resolver instead of a lang module, so no Bukkit server is involved.
 */
class CommandLangTest {

    // ------------------------------------------------------------------ fixtures

    /** The {@code /clan} tree: a leaf with two args, an argless leaf and a nested group. */
    private static List<RootCommand.Sub> clanSubs() {
        RootCommand.Sub create = new SubCommandBuilder(null, "create")
                .description("Creates a clan")
                .arg("name", Args.string())
                .argOptional("tag", Args.string())
                .executes(context -> { })
                .build();
        RootCommand.Sub list = new SubCommandBuilder(null, "list")
                .description("Lists the clans")
                .executes(context -> { })
                .build();
        RootCommand.Sub admin = new SubCommandBuilder(null, "admin")
                .description("Admin tools")
                .sub("disband", disband -> disband
                        .description("Disbands a clan")
                        .arg("clan", Args.string())
                        .executes(context -> { }))
                .build();
        return List.of(create, list, admin);
    }

    private static CommandSender sender() {
        return (CommandSender) Proxy.newProxyInstance(
                CommandSender.class.getClassLoader(),
                new Class<?>[] {CommandSender.class},
                (proxy, method, args) -> {
                    if (method.getName().equals("hasPermission")) {
                        return true;
                    }
                    Class<?> ret = method.getReturnType();
                    if (ret == boolean.class) {
                        return false;
                    }
                    return ret.isPrimitive() ? 0 : null;
                });
    }

    private static YamlConfiguration parsed(List<String> lines) {
        YamlConfiguration cfg = new YamlConfiguration();
        try {
            cfg.loadFromString(String.join("\n", lines));
        } catch (Exception e) {
            throw new AssertionError("generated block does not parse as YAML", e);
        }
        return cfg;
    }

    /** The block as SnLang would index it: the generated lines under the top-level key. */
    private static YamlConfiguration seeded() {
        List<String> lines = new java.util.ArrayList<>();
        lines.add(CommandLang.ROOT_KEY + ":");
        lines.addAll(CommandLang.rootLines("clan", "Main command of SnClans", clanSubs()));
        return parsed(lines);
    }

    // ---------------------------------------------------------------- seeding

    @Test
    void theGeneratedBlockParsesAndMirrorsTheTree() {
        YamlConfiguration cfg = seeded();
        assertEquals("Main command of SnClans", cfg.getString("commands.clan.description"));
        assertEquals("Creates a clan",
                cfg.getString("commands.clan.subcommands.create.description"));
        assertEquals("Lists the clans",
                cfg.getString("commands.clan.subcommands.list.description"));
        assertEquals("Admin tools",
                cfg.getString("commands.clan.subcommands.admin.description"));
        assertEquals("Disbands a clan", cfg.getString(
                "commands.clan.subcommands.admin.subcommands.disband.description"));
    }

    @Test
    void argumentLabelsSeedToTheirOwnIdentifier() {
        YamlConfiguration cfg = seeded();
        assertEquals("name", cfg.getString("commands.clan.subcommands.create.args.name"));
        assertEquals("tag", cfg.getString("commands.clan.subcommands.create.args.tag"));
        assertEquals("clan", cfg.getString(
                "commands.clan.subcommands.admin.subcommands.disband.args.clan"));
    }

    @Test
    void anArglessNodeSeedsNoArgsSection() {
        assertTrue(seeded().getConfigurationSection(
                "commands.clan.subcommands.list.args") == null);
    }

    @Test
    void quotesAndBackslashesInADescriptionStaySurvivable() {
        RootCommand.Sub quoted = new SubCommandBuilder(null, "say")
                .description("Says \"hi\" with a \\ backslash")
                .executes(context -> { })
                .build();
        YamlConfiguration cfg = parsed(java.util.stream.Stream.concat(
                java.util.stream.Stream.of(CommandLang.ROOT_KEY + ":"),
                CommandLang.rootLines("clan", "Root", List.of(quoted)).stream()).toList());
        assertEquals("Says \"hi\" with a \\ backslash",
                cfg.getString("commands.clan.subcommands.say.description"));
    }

    // ---------------------------------------------------------------- applying

    /** A resolver over the seeded key space, standing in for the lang module. */
    private static java.util.function.Function<String, String> resolver(String... pairs) {
        Map<String, String> values = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            values.put(pairs[i], pairs[i + 1]);
        }
        return values::get;
    }

    @Test
    void translatedDescriptionsAndLabelsReachTheGeneratedHelp() {
        List<RootCommand.Sub> subs = clanSubs();
        String[] rootDescription = {"Main command of SnClans"};
        CommandLang.apply(resolver(
                "commands.clan.description", "Comando principal de SnClans",
                "commands.clan.subcommands.create.description", "Crea un clan",
                "commands.clan.subcommands.create.args.name", "nombre",
                "commands.clan.subcommands.create.args.tag", "etiqueta",
                "commands.clan.subcommands.admin.subcommands.disband.description",
                "Disuelve un clan"),
                "clan", subs, value -> rootDescription[0] = value);

        assertEquals("Comando principal de SnClans", rootDescription[0]);
        assertEquals(List.of(
                "/clan create <nombre> [etiqueta]",
                "/clan list",
                "/clan admin disband <clan>"),
                RootCommand.collectHelp(sender(), subs, "/clan", null).stream()
                        .map(RootCommand.HelpLine::usage).toList());
        assertEquals(List.of("Crea un clan", "Lists the clans", "Disuelve un clan"),
                RootCommand.collectHelp(sender(), subs, "/clan", null).stream()
                        .map(RootCommand.HelpLine::description).toList());
    }

    @Test
    void anUntranslatedEntryKeepsTheValueDeclaredInCode() {
        List<RootCommand.Sub> subs = clanSubs();
        // Only the description is translated: the two argument labels stay identifiers.
        CommandLang.apply(resolver(
                "commands.clan.subcommands.create.description", "Crea un clan"),
                "clan", subs, value -> { });
        assertEquals("/clan create <name> [tag]",
                RootCommand.usageOf(subs.get(0), "/clan create"));
        assertEquals("Lists the clans", subs.get(1).labels().description());
    }

    @Test
    void aBlankValueIsIgnoredRatherThanRenderingEmpty() {
        List<RootCommand.Sub> subs = clanSubs();
        String[] rootDescription = {"Main command of SnClans"};
        CommandLang.apply(resolver(
                "commands.clan.description", "   ",
                "commands.clan.subcommands.create.description", "  ",
                "commands.clan.subcommands.create.args.name", ""),
                "clan", subs, value -> rootDescription[0] = value);
        assertEquals("Main command of SnClans", rootDescription[0]);
        assertEquals("Creates a clan", subs.get(0).labels().description());
        assertEquals("/clan create <name> [tag]",
                RootCommand.usageOf(subs.get(0), "/clan create"));
    }

    @Test
    void translatedLabelsAlsoDriveTheTabHintSoBothStayConsistent() {
        List<RootCommand.Sub> subs = clanSubs();
        CommandLang.apply(resolver(
                "commands.clan.subcommands.create.args.name", "nombre"),
                "clan", subs, value -> { });
        // The free-form arg suggests its bracketed label, the same token the usage renders.
        assertEquals(List.of("<nombre>"),
                RootCommand.tab(sender(), null, subs, new String[] {"create", ""}));
        assertTrue(RootCommand.usageOf(subs.get(0), "/clan create").contains("<nombre>"));
    }

    @Test
    void aTranslatedLabelNeverChangesTheParsedArgumentKey() {
        List<RootCommand.Sub> subs = clanSubs();
        CommandLang.apply(resolver(
                "commands.clan.subcommands.create.args.name", "nombre"),
                "clan", subs, value -> { });
        RootCommand.Resolution resolution = RootCommand.resolve(sender(), null, subs, "/clan",
                new String[] {"create", "Alpha"});
        assertTrue(resolution instanceof RootCommand.Run);
        // The identifier, not the label, stays the context.get key.
        assertEquals("Alpha", ((RootCommand.Run) resolution).context().get("name"));
    }

    @Test
    void reapplyingAfterAReloadReplacesThePreviousTranslation() {
        List<RootCommand.Sub> subs = clanSubs();
        CommandLang.apply(resolver(
                "commands.clan.subcommands.create.description", "Crea un clan"),
                "clan", subs, value -> { });
        assertEquals("Crea un clan", subs.get(0).labels().description());
        // A reload that emptied the key falls back to the declared default, not to the
        // previously applied translation.
        CommandLang.apply(resolver(), "clan", subs, value -> { });
        assertEquals("Creates a clan", subs.get(0).labels().description());
    }
}
