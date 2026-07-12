package com.sn.lib.velocity.internal;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;
import org.slf4j.Logger;

/**
 * Resolves the bridge HMAC key on the proxy. Order: a dedicated secret file
 * {@code plugins/snlib/hmac-secret.txt} when present (mirror of the backend's
 * {@code bridge.hmac-secret} opt-out), otherwise the modern-forwarding secret file
 * referenced by {@code forwarding-secret-file} in {@code velocity.toml} (default
 * {@code forwarding.secret}). Velocity's public API does not expose the secret, so it is
 * read from disk once at proxy init.
 *
 * <p>The toml line is extracted with a targeted regex instead of a TOML dependency: the
 * key is a plain top-level string in every real velocity.toml.</p>
 */
final class ProxySecretResolver {

    private static final Pattern SECRET_FILE_LINE = Pattern.compile(
            "^\\s*forwarding-secret-file\\s*=\\s*\"([^\"]+)\"", Pattern.MULTILINE);

    private ProxySecretResolver() {
    }

    static byte @Nullable [] resolve(Path proxyRoot, Path dataDirectory, Logger logger) {
        Path dedicated = dataDirectory.resolve("hmac-secret.txt");
        if (Files.isRegularFile(dedicated)) {
            // Fail-closed: si el operador OPTO por un secreto dedicado, un archivo vacio
            // o ilegible apaga el bridge con evidencia, jamas cae en silencio al
            // forwarding secret (correria con la clave equivocada sin que nadie lo vea)
            try {
                String secret = Files.readString(dedicated, StandardCharsets.UTF_8).trim();
                if (secret.isBlank()) {
                    logger.error("SnBridge desactivado: {} existe pero esta VACIO"
                            + " (completarlo o borrarlo para volver al forwarding secret)",
                            dedicated);
                    return null;
                }
                logger.info("SnBridge: usando secreto HMAC dedicado de {}", dedicated);
                return secret.getBytes(StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.error("SnBridge desactivado: no se pudo leer {}: {}", dedicated,
                        e.getMessage());
                return null;
            }
        }
        Path toml = proxyRoot.resolve("velocity.toml");
        String secretFileName = "forwarding.secret";
        if (Files.isRegularFile(toml)) {
            try {
                Matcher matcher = SECRET_FILE_LINE.matcher(
                        Files.readString(toml, StandardCharsets.UTF_8));
                if (matcher.find()) {
                    secretFileName = matcher.group(1);
                }
            } catch (IOException e) {
                logger.error("SnBridge: no se pudo leer velocity.toml: {}", e.getMessage());
            }
        }
        Path secretFile = proxyRoot.resolve(secretFileName);
        if (!Files.isRegularFile(secretFile)) {
            logger.error("SnBridge desactivado: no existe {} (configurar forwarding moderno o"
                    + " crear plugins/snlib/hmac-secret.txt)", secretFile);
            return null;
        }
        try {
            String secret = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
            if (secret.isBlank()) {
                logger.error("SnBridge desactivado: {} esta vacio", secretFile);
                return null;
            }
            return secret.getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("SnBridge desactivado: fallo leyendo {}: {}", secretFile, e.getMessage());
            return null;
        }
    }
}
