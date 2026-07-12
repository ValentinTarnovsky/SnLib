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
            // Fail-closed: if the operator OPTED for a dedicated secret, an empty or
            // unreadable file turns the bridge off with evidence, never silently falling
            // back to the forwarding secret (it would run with the wrong key unnoticed)
            try {
                String secret = Files.readString(dedicated, StandardCharsets.UTF_8).trim();
                if (secret.isBlank()) {
                    logger.error("SnBridge disabled: {} exists but is EMPTY"
                            + " (fill it in or delete it to fall back to the forwarding secret)",
                            dedicated);
                    return null;
                }
                logger.info("SnBridge: using dedicated HMAC secret from {}", dedicated);
                return secret.getBytes(StandardCharsets.UTF_8);
            } catch (IOException e) {
                logger.error("SnBridge disabled: could not read {}: {}", dedicated,
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
                logger.error("SnBridge: could not read velocity.toml: {}", e.getMessage());
            }
        }
        Path secretFile = proxyRoot.resolve(secretFileName);
        if (!Files.isRegularFile(secretFile)) {
            logger.error("SnBridge disabled: {} does not exist (configure modern forwarding or"
                    + " create plugins/snlib/hmac-secret.txt)", secretFile);
            return null;
        }
        try {
            String secret = Files.readString(secretFile, StandardCharsets.UTF_8).trim();
            if (secret.isBlank()) {
                logger.error("SnBridge disabled: {} is empty", secretFile);
                return null;
            }
            return secret.getBytes(StandardCharsets.UTF_8);
        } catch (IOException e) {
            logger.error("SnBridge disabled: failed reading {}: {}", secretFile, e.getMessage());
            return null;
        }
    }
}
