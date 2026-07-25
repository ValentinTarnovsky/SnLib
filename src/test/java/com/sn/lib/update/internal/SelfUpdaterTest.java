package com.sn.lib.update.internal;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.jar.JarEntry;
import java.util.jar.JarOutputStream;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Pure unit coverage of the self-updater's decision helpers: no Bukkit, no network, no
 * scheduler. Everything asserted here runs before the installed jar is ever touched.
 */
class SelfUpdaterTest {

    /** Trimmed shape of a real releases/latest payload, including a body full of traps. */
    private static final String RELEASE_JSON = """
            {"url":"https://api.github.com/repos/ValentinTarnovsky/SnLib/releases/1",
             "html_url":"https://github.com/ValentinTarnovsky/SnLib/releases/tag/v1.15.0",
             "tag_name":"v1.15.0","name":"SnLib v1.15.0",
             "author":{"login":"ValentinTarnovsky","html_url":"https://github.com/x"},
             "body":"mentions \\"digest\\" and \\"browser_download_url\\" in prose",
             "assets":[{"id":489092950,"name":"SnLib-1.15.0.jar",
               "digest":"sha256:0282065539a73a822161f899b3eba4b8bbb98ec7c17cb50d604f83380894bb24",
               "size":17800559,
               "browser_download_url":"https://github.com/ValentinTarnovsky/SnLib/releases/download/v1.15.0/SnLib-1.15.0.jar"}]}
            """;

    // --- asset url allowlist -------------------------------------------------------

    @Test
    void allowsOnlyAssetsFromTheOwnRepository() {
        assertTrue(SelfUpdater.isAllowedAssetUrl(
                "https://github.com/ValentinTarnovsky/SnLib/releases/download/v1.16.0/SnLib-1.16.0.jar"));
    }

    @Test
    void rejectsForeignOrDowngradedAssetUrls() {
        assertFalse(SelfUpdater.isAllowedAssetUrl(
                "https://github.com/Attacker/SnLib/releases/download/v1.16.0/SnLib.jar"));
        assertFalse(SelfUpdater.isAllowedAssetUrl(
                "https://evil.example.com/ValentinTarnovsky/SnLib/releases/download/v1.jar"));
        assertFalse(SelfUpdater.isAllowedAssetUrl(
                "http://github.com/ValentinTarnovsky/SnLib/releases/download/v1.16.0/SnLib.jar"));
        assertFalse(SelfUpdater.isAllowedAssetUrl(
                "https://github.com/ValentinTarnovsky/SnClans/releases/download/v1.0.0/x.jar"));
        assertFalse(SelfUpdater.isAllowedAssetUrl(null));
    }

    // --- major gate ----------------------------------------------------------------

    @Test
    void sameMajorGateAcceptsMinorAndPatchOnly() {
        assertTrue(SelfUpdater.isSameMajor("1.16.0", "1.15.0"));
        assertTrue(SelfUpdater.isSameMajor("1.15.1", "1.15.0"));
        assertFalse(SelfUpdater.isSameMajor("2.0.0", "1.15.0"));
        assertFalse(SelfUpdater.isSameMajor("10.0.0", "1.15.0"));
    }

    @Test
    void majorOfRejectsUnparseableVersions() {
        assertEquals("1", SelfUpdater.majorOf("1.15.0"));
        assertEquals("12", SelfUpdater.majorOf(" 12.0.1 "));
        assertNull(SelfUpdater.majorOf("snapshot"));
        assertNull(SelfUpdater.majorOf(null));
        assertFalse(SelfUpdater.isSameMajor("snapshot", "1.15.0"));
    }

    // --- target file name ----------------------------------------------------------

    @Test
    void versionedJarKeepsItsConventionWithTheNewVersion() {
        assertEquals("SnLib-1.16.0.jar",
                SelfUpdater.targetFileName("SnLib-1.15.0.jar", "1.15.0", "1.16.0"));
    }

    @Test
    void unversionedJarKeepsItsExactName() {
        assertEquals("SnLib.jar", SelfUpdater.targetFileName("SnLib.jar", "1.15.0", "1.16.0"));
        assertEquals("library.jar",
                SelfUpdater.targetFileName("library.jar", "1.15.0", "1.16.0"));
        assertEquals("SnLib.jar", SelfUpdater.targetFileName("SnLib.jar", null, "1.16.0"));
    }

    // --- digest --------------------------------------------------------------------

    @Test
    void expectedShaNormalizesThePrefixedApiDigest() {
        String hex = "0282065539a73a822161f899b3eba4b8bbb98ec7c17cb50d604f83380894bb24";
        assertEquals(hex, SelfUpdater.expectedSha256("sha256:" + hex));
        assertEquals(hex, SelfUpdater.expectedSha256(hex.toUpperCase(java.util.Locale.ROOT)));
    }

    @Test
    void expectedShaRejectsOtherAlgorithmsAndBadLengths() {
        assertNull(SelfUpdater.expectedSha256("md5:0282065539a73a822161f899b3eba4b8"));
        assertNull(SelfUpdater.expectedSha256("sha256:abc"));
        assertNull(SelfUpdater.expectedSha256(null));
    }

    @Test
    void sha256MatchesAKnownVector(@TempDir Path dir) throws IOException {
        Path file = dir.resolve("abc.txt");
        Files.write(file, "abc".getBytes(StandardCharsets.UTF_8));
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
                SelfUpdater.sha256(file));
    }

    // --- json ----------------------------------------------------------------------

    @Test
    void assetFieldsAreReadFromInsideTheAssetsArrayOnly() {
        String assets = SelfUpdater.assetsSlice(RELEASE_JSON);
        assertNotNull(assets);
        assertEquals("https://github.com/ValentinTarnovsky/SnLib/releases/download/"
                        + "v1.15.0/SnLib-1.15.0.jar",
                SelfUpdater.jsonString(assets, "browser_download_url"));
        assertEquals("sha256:0282065539a73a822161f899b3eba4b8bbb98ec7c17cb50d604f83380894bb24",
                SelfUpdater.jsonString(assets, "digest"));
        assertTrue(SelfUpdater.isAllowedAssetUrl(
                SelfUpdater.jsonString(assets, "browser_download_url")));
    }

    @Test
    void assetsSliceIsNullWhenTheReleasePublishesNone() {
        assertNull(SelfUpdater.assetsSlice("{\"tag_name\":\"v1.0.0\"}"));
    }

    @Test
    void tagPrefixIsStrippedOnlyBeforeADigit() {
        assertEquals("1.15.0", SelfUpdater.stripTagPrefix("v1.15.0"));
        assertEquals("1.15.0", SelfUpdater.stripTagPrefix(" V1.15.0 "));
        assertEquals("vanilla", SelfUpdater.stripTagPrefix("vanilla"));
        assertEquals("v1.15.0", SelfUpdater.jsonString(RELEASE_JSON, "tag_name"));
    }

    // --- jar verification ----------------------------------------------------------

    @Test
    void acceptsAJarDeclaringSnLibAtTheExpectedVersion(@TempDir Path dir) throws IOException {
        Path jar = jarWith(dir.resolve("good.jar"),
                "name: SnLib\nmain: com.sn.lib.SnLibPlugin\nversion: 1.16.0\n");
        assertNull(SelfUpdater.verifyJar(jar, "1.16.0"));
    }

    @Test
    void rejectsAJarOfAnotherPluginOrVersion(@TempDir Path dir) throws IOException {
        Path wrongName = jarWith(dir.resolve("name.jar"),
                "name: NotSnLib\nmain: com.sn.lib.SnLibPlugin\nversion: 1.16.0\n");
        assertNotNull(SelfUpdater.verifyJar(wrongName, "1.16.0"));

        Path wrongMain = jarWith(dir.resolve("main.jar"),
                "name: SnLib\nmain: com.evil.Payload\nversion: 1.16.0\n");
        assertNotNull(SelfUpdater.verifyJar(wrongMain, "1.16.0"));

        Path wrongVersion = jarWith(dir.resolve("version.jar"),
                "name: SnLib\nmain: com.sn.lib.SnLibPlugin\nversion: 1.15.0\n");
        assertNotNull(SelfUpdater.verifyJar(wrongVersion, "1.16.0"));
    }

    @Test
    void rejectsAJarWithoutADescriptorAndANonJarFile(@TempDir Path dir) throws IOException {
        Path noDescriptor = jarWith(dir.resolve("empty.jar"), null);
        assertNotNull(SelfUpdater.verifyJar(noDescriptor, "1.16.0"));

        Path notAJar = dir.resolve("plain.jar");
        Files.write(notAJar, "this is not a zip".getBytes(StandardCharsets.UTF_8));
        assertNotNull(SelfUpdater.verifyJar(notAJar, "1.16.0"));
    }

    @Test
    void yamlValueReadsQuotedAndPlainScalars() {
        assertEquals("SnLib", SelfUpdater.yamlValue("name: SnLib\n", "name"));
        assertEquals("SnLib", SelfUpdater.yamlValue("name: \"SnLib\"\n", "name"));
        assertEquals("SnLib", SelfUpdater.yamlValue("name: 'SnLib'\n", "name"));
        assertNull(SelfUpdater.yamlValue("description: x\n", "name"));
        assertNull(SelfUpdater.yamlValue("  name: indented\n", "name"));
    }

    /** Writes a jar carrying {@code plugin.yml} with the given content, or no entry at all. */
    private static Path jarWith(Path path, String descriptor) throws IOException {
        try (OutputStream out = Files.newOutputStream(path);
             JarOutputStream jar = new JarOutputStream(out)) {
            if (descriptor != null) {
                jar.putNextEntry(new JarEntry("plugin.yml"));
                jar.write(descriptor.getBytes(StandardCharsets.UTF_8));
                jar.closeEntry();
            }
        }
        return path;
    }
}
