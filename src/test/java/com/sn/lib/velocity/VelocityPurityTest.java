package com.sn.lib.velocity;

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
 * CI gate: com.sn.lib.velocity runs inside the Velocity classloader, where any Bukkit
 * reference is a NoClassDefFoundError at runtime. This scan makes it a build break, the
 * mirror of the wire core's PlatformPurityTest.
 */
class VelocityPurityTest {

    private static final String[] FORBIDDEN = {"org/bukkit", "io/papermc"};

    @Test
    void velocityPackageReferencesNoBukkit() throws IOException, URISyntaxException {
        Path classesDir = Path.of(SnLibVelocity.class.getProtectionDomain()
                .getCodeSource().getLocation().toURI());
        Path velocityDir = classesDir.resolve("com/sn/lib/velocity");
        assertTrue(Files.isDirectory(velocityDir), "does not exist: " + velocityDir);

        List<String> offenders = new ArrayList<>();
        try (var stream = Files.walk(velocityDir)) {
            for (Path clazz : stream.filter(p -> p.toString().endsWith(".class")).toList()) {
                String pool = new String(Files.readAllBytes(clazz), StandardCharsets.ISO_8859_1);
                for (String bad : FORBIDDEN) {
                    if (pool.contains(bad)) {
                        offenders.add(clazz.getFileName() + " references " + bad);
                    }
                }
            }
        }
        assertTrue(offenders.isEmpty(),
                "com.sn.lib.velocity must be 100% Bukkit-free, but: " + offenders);
    }
}
