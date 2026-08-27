package com.sn.lib.yml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sn.lib.Ph;

import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * The bind-time {@link Ph} overloads of {@link SnYml#getString} and
 * {@link SnYml#getStringList} (1.32.0): the pairs resolve inside the getter, not as an
 * after-pass by the caller. What this guards is the ORDER - phs before the PAPI stage -
 * which is what lets a bound placeholder sit inside a PAPI token
 * ({@code %math_1_{buff-value}/100%}) and still hand the expansion a finished argument.
 *
 * <p>Headless boundary: these fixtures carry no {@code %} in any RESOLVED value on
 * purpose, because a surviving {@code %} sends {@code resolve} to
 * {@code Bukkit.isPrimaryThread()} and there is no server here (the same boundary every
 * test built on {@link SnYmlTestAccess} respects). The papi leg itself stays untestable
 * headlessly; what is testable - and what broke in the field - is that the pairs are
 * already resolved by the time the getter returns, which this pins.</p>
 */
final class SnYmlPhResolutionTest {

    @TempDir
    static File dir;

    private static SnYml yml;

    @BeforeAll
    static void load() throws IOException {
        File file = new File(dir, "phs.yml");
        Files.write(file.toPath(), List.of(
                "name: \"{pet}\"",
                "lore:",
                "  - \"Owner: {owner}\"",
                "  - \"plain line\""), StandardCharsets.UTF_8);
        yml = SnYmlTestAccess.of(file);
    }

    @Test
    void getStringResolvesBindTimePairs() {
        assertEquals("Rex", yml.getString("name", "", null, Ph.of("pet", "Rex")));
    }

    @Test
    void getStringWithoutPairsLeavesTokenIntact() {
        assertEquals("{pet}", yml.getString("name", "", null));
    }

    @Test
    void getStringListResolvesEveryElement() {
        assertEquals(List.of("Owner: Sn", "plain line"),
                yml.getStringList("lore", List.of(), null, Ph.of("owner", "Sn")));
    }
}
