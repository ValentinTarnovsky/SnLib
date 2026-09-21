package com.sn.lib.lang;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Set;

import org.bukkit.configuration.InvalidConfigurationException;
import org.bukkit.configuration.file.YamlConfiguration;
import org.junit.jupiter.api.Test;

/**
 * Unit tests for the extra language folders of {@link SnLang#addSource}: how a folder's keys
 * are laid over the loaded language under their namespace (added, colliding, clashing) and
 * how the folder and namespace arguments are validated.
 */
class SnLangSourceTest {

    @Test
    void layerKeysServesEveryLeafUnderTheNamespace() throws InvalidConfigurationException {
        YamlConfiguration target = yaml("prefix: \"&7[X] \"\nsnlib:\n  usage: \"u\"\n");
        YamlConfiguration source = yaml("join: \"hi\"\nnested:\n  a: \"b\"\nlines:\n  - \"one\"\n  - \"two\"\n");

        SnLang.Layered result = SnLang.layerKeys(target, source, "party");

        assertEquals(Set.of("party.join", "party.nested.a", "party.lines"), result.added());
        assertTrue(result.collisions().isEmpty());
        assertFalse(result.clashed());
        assertEquals("hi", target.getString("party.join"));
        assertEquals("b", target.getString("party.nested.a"));
        assertEquals(List.of("one", "two"), target.getStringList("party.lines"));
        assertEquals("u", target.getString("snlib.usage"), "the global keys stay untouched");
    }

    @Test
    void layerKeysKeepsTheGlobalValueOnACollision() throws InvalidConfigurationException {
        YamlConfiguration target = yaml("party:\n  join: \"global\"\n");
        YamlConfiguration source = yaml("join: \"module\"\nleave: \"bye\"\n");

        SnLang.Layered result = SnLang.layerKeys(target, source, "party");

        assertEquals(Set.of("party.leave"), result.added());
        assertEquals(List.of("party.join"), result.collisions());
        assertEquals("global", target.getString("party.join"));
        assertEquals("bye", target.getString("party.leave"));
    }

    @Test
    void layerKeysSkipsTheSourceWhenTheNamespaceIsAMessageKey() throws InvalidConfigurationException {
        YamlConfiguration target = yaml("party: \"a global message\"\n");
        YamlConfiguration source = yaml("join: \"hi\"\n");

        SnLang.Layered result = SnLang.layerKeys(target, source, "party");

        assertTrue(result.clashed());
        assertTrue(result.added().isEmpty());
        assertEquals("a global message", target.getString("party"));
    }

    @Test
    void layerKeysNeverTurnsAMessageIntoASection() throws InvalidConfigurationException {
        YamlConfiguration target = yaml("party:\n  menu: \"&aOpen party menu\"\n");
        YamlConfiguration source = yaml("menu:\n  title: \"Party\"\nleave: \"bye\"\n");

        SnLang.Layered result = SnLang.layerKeys(target, source, "party");

        assertEquals(List.of("party.menu.title"), result.collisions());
        assertEquals(Set.of("party.leave"), result.added());
        assertEquals("&aOpen party menu", target.getString("party.menu"),
                "the global message must survive, not become a section");
        assertFalse(target.isConfigurationSection("party.menu"));
    }

    @Test
    void layerKeysSkipsADottedNamespaceThatPassesThroughAMessage()
            throws InvalidConfigurationException {
        YamlConfiguration target = yaml("modules: \"a global message\"\n");

        SnLang.Layered result = SnLang.layerKeys(target, yaml("join: \"hi\"\n"), "modules.party");

        assertTrue(result.clashed());
        assertEquals("a global message", target.getString("modules"));
    }

    @Test
    void layerKeysAcceptsADottedNamespace() throws InvalidConfigurationException {
        YamlConfiguration target = new YamlConfiguration();

        SnLang.Layered result = SnLang.layerKeys(target, yaml("join: \"hi\"\n"), "modules.party");

        assertEquals(Set.of("modules.party.join"), result.added());
        assertEquals("hi", target.getString("modules.party.join"));
    }

    @Test
    void normalizeFolderStripsSlashesAndNormalizesSeparators() {
        assertEquals("modules/party/lang", SnLang.normalizeFolder("\\modules\\party\\lang\\"));
        assertEquals("modules/party/lang", SnLang.normalizeFolder(" /modules/party/lang/ "));
        assertEquals("modules/party/lang", SnLang.normalizeFolder("./modules//party/./lang"));
    }

    @Test
    void normalizeFolderRejectsBlankAndTheGlobalFolder() {
        assertThrows(IllegalArgumentException.class, () -> SnLang.normalizeFolder(null));
        assertThrows(IllegalArgumentException.class, () -> SnLang.normalizeFolder(" / "));
        assertThrows(IllegalArgumentException.class, () -> SnLang.normalizeFolder("lang/"));
        assertThrows(IllegalArgumentException.class, () -> SnLang.normalizeFolder("LANG"));
        assertThrows(IllegalArgumentException.class, () -> SnLang.normalizeFolder("lang/."));
        assertThrows(IllegalArgumentException.class,
                () -> SnLang.normalizeFolder("modules/../lang"));
    }

    @Test
    void requireNamespaceRejectsBlankEdgeDotsAndWhitespace() {
        assertEquals("party", SnLang.requireNamespace(" party "));
        assertThrows(IllegalArgumentException.class, () -> SnLang.requireNamespace(null));
        assertThrows(IllegalArgumentException.class, () -> SnLang.requireNamespace(" "));
        assertThrows(IllegalArgumentException.class, () -> SnLang.requireNamespace(".party"));
        assertThrows(IllegalArgumentException.class, () -> SnLang.requireNamespace("party."));
        assertThrows(IllegalArgumentException.class, () -> SnLang.requireNamespace("my party"));
    }

    private static YamlConfiguration yaml(String text) throws InvalidConfigurationException {
        YamlConfiguration cfg = new YamlConfiguration();
        cfg.loadFromString(text);
        return cfg;
    }
}
