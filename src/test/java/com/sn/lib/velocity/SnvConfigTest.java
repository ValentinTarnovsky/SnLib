package com.sn.lib.velocity;

import java.io.ByteArrayInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure test of the Velocity base's managed config (no Bukkit/Velocity runtime needed):
 * defaults merge, dot-path getters and file creation.
 */
class SnvConfigTest {

    private static final Logger LOG = LoggerFactory.getLogger(SnvConfigTest.class);

    private static InputStream defaults(String yaml) {
        return new ByteArrayInputStream(yaml.getBytes(StandardCharsets.UTF_8));
    }

    @Test
    void mergesMissingDefaultsPreservingUserValues(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("config.yml");
        Files.writeString(file, "motd: '&aHi'\n");
        SnvConfig cfg = SnvConfig.load(file,
                defaults("motd: '&cDefault'\nmax-players: 100\nmessages:\n  join: '&eWelcome'\n"), LOG);

        assertEquals("&aHi", cfg.getString("motd", ""));          // user value kept
        assertEquals(100, cfg.getInt("max-players", 0));           // missing scalar merged
        assertEquals("&eWelcome", cfg.getString("messages.join", "")); // missing nested merged
        assertTrue(Files.readString(file).contains("max-players")); // file rewritten with merge
    }

    @Test
    void dotPathGettersAcrossTypes(@TempDir Path dir) {
        SnvConfig cfg = SnvConfig.load(dir.resolve("c.yml"),
                defaults("a:\n  b:\n    n: 7\n    flag: true\n    ratio: 1.5\n  list: [x, y, z]\n"), LOG);

        assertEquals(7, cfg.getInt("a.b.n", 0));
        assertTrue(cfg.getBoolean("a.b.flag", false));
        assertEquals(1.5, cfg.getDouble("a.b.ratio", 0), 1e-9);
        assertEquals(List.of("x", "y", "z"), cfg.getStringList("a.list"));
        assertTrue(cfg.contains("a.b.n"));
        assertFalse(cfg.contains("a.b.missing"));
        assertEquals("fallback", cfg.getString("no.such.path", "fallback"));
    }

    @Test
    void createsFileFromDefaultsWhenAbsent(@TempDir Path dir) {
        Path file = dir.resolve("new.yml");
        SnvConfig cfg = SnvConfig.load(file, defaults("greeting: hello\n"), LOG);

        assertEquals("hello", cfg.getString("greeting", ""));
        assertTrue(Files.exists(file));
    }
}
