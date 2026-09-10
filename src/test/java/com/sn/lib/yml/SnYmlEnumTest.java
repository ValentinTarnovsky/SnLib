package com.sn.lib.yml;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.List;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.sn.lib.debug.SnDebug;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

/**
 * {@link SnYml#getEnum} over a REAL parsed file (1.35.0), which is the only way this is
 * worth testing: the whole defect lives in the parser, not in the getter. YAML 1.1 resolves
 * an unquoted {@code OFF} to the boolean {@code false} before any Sn code sees it, so a
 * hand-built fixture holding the string "OFF" would prove nothing at all - it would test a
 * value the owner's file can never produce.
 *
 * <p>The field case: a server with {@code debug.level: OFF} got the boolean {@code false},
 * which failed {@code Level.valueOf} and fell back to {@code DEBUG} - the LOUDEST level,
 * from the owner asking for silence - behind two WARN lines that named 'false', a value
 * absent from the file.</p>
 *
 * <p>Headless boundary: every fixture here resolves to a value the enum names, so no path
 * under test reaches {@code warnInvalid} - which would dereference the null context of
 * {@link SnYmlTestAccess}. The miss paths are covered against the static helpers instead,
 * which is where that decision actually lives.</p>
 */
final class SnYmlEnumTest {

    /** The enum the defect was found on; its OFF is the constant YAML eats. */
    private enum Plain { FIRST, SECOND }

    /** Both boolean sides collide here, and neither maps to the enum's first choice. */
    private enum Answer { YES, NO }

    @TempDir
    static File dir;

    private static SnYml yml;

    @BeforeAll
    static void load() throws IOException {
        File file = new File(dir, "enums.yml");
        Files.write(file.toPath(), List.of(
                "unquoted-off: OFF",
                "unquoted-lower-off: off",
                "unquoted-no: no",
                "unquoted-on: ON",
                "quoted-off: \"OFF\"",
                "plain-name: TRACE",
                "lower-name: trace",
                "padded-name: \"  Info  \""), StandardCharsets.UTF_8);
        yml = SnYmlTestAccess.of(file);
    }

    @Test
    void unquotedOffIsTheOffConstantAndNotTheDefault() {
        assertSame(SnDebug.Level.OFF, yml.getEnum("unquoted-off", SnDebug.Level.class, SnDebug.Level.DEBUG));
        assertSame(SnDebug.Level.OFF, yml.getEnum("unquoted-lower-off", SnDebug.Level.class, SnDebug.Level.DEBUG));
    }

    @Test
    void quotedOffReadsTheSameAsUnquoted() {
        assertSame(SnDebug.Level.OFF, yml.getEnum("quoted-off", SnDebug.Level.class, SnDebug.Level.DEBUG));
    }

    @Test
    void aBooleanTokenPicksTheConstantOfItsOwnSide() {
        // no -> false -> the enum has no OFF, so NO; ON -> true -> the enum has no ON, so YES.
        assertSame(Answer.NO, yml.getEnum("unquoted-no", Answer.class, Answer.YES));
        assertSame(Answer.YES, yml.getEnum("unquoted-on", Answer.class, Answer.NO));
    }

    @Test
    void plainNamesStillReadCaseInsensitivelyAndTrimmed() {
        assertSame(SnDebug.Level.TRACE, yml.getEnum("plain-name", SnDebug.Level.class, SnDebug.Level.DEBUG));
        assertSame(SnDebug.Level.TRACE, yml.getEnum("lower-name", SnDebug.Level.class, SnDebug.Level.DEBUG));
        assertSame(SnDebug.Level.INFO, yml.getEnum("padded-name", SnDebug.Level.class, SnDebug.Level.DEBUG));
    }

    @Test
    void anAbsentKeyReturnsTheDefaultSilently() {
        assertSame(SnDebug.Level.INFO, yml.getEnum("no-such-key", SnDebug.Level.class, SnDebug.Level.INFO));
    }

    @Test
    void aBooleanNoSideOfTheEnumNamesIsAMiss() {
        // Plain declares neither OFF/NO/FALSE nor ON/YES/TRUE: both booleans are unusable.
        assertNull(SnYml.firstConstant(Plain.class, SnYml.YAML_FALSE_NAMES));
        assertNull(SnYml.firstConstant(Plain.class, SnYml.YAML_TRUE_NAMES));
    }

    @Test
    void theFalseSideIsTriedInOffNoFalseOrder() {
        assertSame(SnDebug.Level.OFF, SnYml.firstConstant(SnDebug.Level.class, SnYml.YAML_FALSE_NAMES));
        assertSame(Answer.NO, SnYml.firstConstant(Answer.class, SnYml.YAML_FALSE_NAMES));
    }

    @Test
    void aNameTheEnumDoesNotDeclareIsAMiss() {
        assertNull(SnYml.constant(SnDebug.Level.class, "LOUD"));
        assertNull(SnYml.constant(SnDebug.Level.class, ""));
        assertEquals(SnDebug.Level.DEBUG, SnYml.constant(SnDebug.Level.class, "debug"));
    }
}
