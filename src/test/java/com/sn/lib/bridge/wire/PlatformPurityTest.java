package com.sn.lib.bridge.wire;

import java.io.IOException;
import java.net.URISyntaxException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * CI gate: com.sn.lib.bridge.wire is shared verbatim by the Paper and Velocity sides, so
 * its bytecode must reference NEITHER platform (nor Adventure). A platform import here
 * would crash the other side's classloader at runtime; this test makes it a build break.
 */
class PlatformPurityTest {

    private static final String[] FORBIDDEN = {
            "org/bukkit", "io/papermc", "com/velocitypowered", "net/kyori",
    };

    @Test
    void wireCoreReferencesNoPlatform() throws IOException, URISyntaxException {
        Path classesDir = Path.of(SnBuf.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path wireDir = classesDir.resolve("com/sn/lib/bridge/wire");
        assertTrue(Files.isDirectory(wireDir), "no existe " + wireDir + " (compilar antes de testear)");

        List<String> offenders = new ArrayList<>();
        try (var stream = Files.walk(wireDir)) {
            for (Path clazz : stream.filter(p -> p.toString().endsWith(".class")).toList()) {
                // Constant-pool strings are plain ASCII inside the class bytes: a scan suffices
                String pool = new String(Files.readAllBytes(clazz), StandardCharsets.ISO_8859_1);
                for (String bad : FORBIDDEN) {
                    if (pool.contains(bad)) {
                        offenders.add(clazz.getFileName() + " referencia " + bad);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "bridge.wire debe ser 100% neutral de plataforma, pero: " + offenders);
    }
}
