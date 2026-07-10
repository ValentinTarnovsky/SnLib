package com.sn.lib;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.yaml.snakeyaml.Yaml;

import com.sn.lib.yml.YamlPreprocessor;
import com.sn.lib.yml.YamlPreprocessor.Result;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class YamlPreprocessorTest {

    private static String loadFixture() throws IOException {
        try (InputStream in = YamlPreprocessorTest.class.getResourceAsStream("/yml/tabs-broken.yml")) {
            assertNotNull(in, "fixture /yml/tabs-broken.yml missing from test classpath");
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        }
    }

    @Test
    void rawFixtureIsRejectedBySnakeYaml() throws IOException {
        String raw = loadFixture();
        assertThrows(Exception.class, () -> new Yaml().load(raw),
                "tab-indented raw text should not parse without preprocessing");
    }

    @Test
    void preprocessedFixtureParsesAndPreservesValues() throws IOException {
        Result result = YamlPreprocessor.preprocess(loadFixture());
        Map<?, ?> data = new Yaml().load(result.cleanText());

        Map<?, ?> root = (Map<?, ?>) data.get("root");
        assertEquals("Sn\tLib", root.get("name"), "tab inside quoted value must be preserved");
        assertEquals(7, root.get("id"));

        Map<?, ?> mixed = (Map<?, ?>) data.get("mixed");
        assertEquals("two words", mixed.get("deep"), "mixed tab/space indentation must repair");

        assertEquals(List.of("first", "second\tentry"), data.get("items"));

        assertEquals("keep\tme\n\tstarts with tab\nlast line\n", data.get("block"),
                "block scalar content must be preserved byte for byte");
        assertEquals("alpha beta", data.get("folded"));
        assertEquals("done", data.get("after"));
        assertEquals("ok", data.get("tail"));
    }

    @Test
    void reportsFixedLinesOneBased() throws IOException {
        Result result = YamlPreprocessor.preprocess(loadFixture());
        assertEquals(List.of(3, 4, 6, 8, 9), result.fixedLines());
    }

    @Test
    void rewritesIndentTabsButNotBlockScalarContent() throws IOException {
        Result result = YamlPreprocessor.preprocess(loadFixture());
        List<String> lines = result.cleanText().lines().toList();

        assertEquals("  name: \"Sn\tLib\"", lines.get(2));
        assertEquals("  id: 7", lines.get(3));
        assertEquals("     deep: \"two words\"", lines.get(5));
        assertEquals("  - \"first\"", lines.get(7));
        assertEquals("  - \"second\tentry\"", lines.get(8));
        assertEquals("  keep\tme", lines.get(10), "block scalar line untouched");
        assertEquals("  \tstarts with tab", lines.get(11), "leading tab in block scalar untouched");
        assertEquals("  last line", lines.get(12));
    }

    @Test
    void normalizesCrlfToLf() {
        Result result = YamlPreprocessor.preprocess("a:\r\n\tb: 2\r\n");
        assertEquals("a:\n  b: 2\n", result.cleanText());
        assertEquals(List.of(2), result.fixedLines());

        Map<?, ?> data = new Yaml().load(result.cleanText());
        assertEquals(2, ((Map<?, ?>) data.get("a")).get("b"));
    }

    @Test
    void isIdempotentOnCleanText() throws IOException {
        Result first = YamlPreprocessor.preprocess(loadFixture());
        Result second = YamlPreprocessor.preprocess(first.cleanText());
        assertEquals(first.cleanText(), second.cleanText());
        assertTrue(second.fixedLines().isEmpty(), "clean text needs no further fixes");
    }

    @Test
    void neverThrowsOnDegenerateInput() {
        assertEquals("", YamlPreprocessor.preprocess(null).cleanText());
        assertTrue(YamlPreprocessor.preprocess(null).fixedLines().isEmpty());
        assertEquals("", YamlPreprocessor.preprocess("").cleanText());
        assertEquals("  : weird", YamlPreprocessor.preprocess("\t: weird").cleanText());
    }

    @Test
    void readsUtf8AndStripsBom(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("bom.yml");
        byte[] bom = {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF};
        byte[] body = "key: value\n".getBytes(StandardCharsets.UTF_8);
        byte[] bytes = new byte[bom.length + body.length];
        System.arraycopy(bom, 0, bytes, 0, bom.length);
        System.arraycopy(body, 0, bytes, bom.length, body.length);
        Files.write(file, bytes);

        assertEquals("key: value\n", YamlPreprocessor.read(file));
    }
}
