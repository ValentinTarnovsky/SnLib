package com.sn.lib.bridge.internal;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.util.logging.Logger;

import org.bukkit.configuration.file.YamlConfiguration;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.yml.SnYml;

/**
 * Resolves the bridge HMAC key. Order: the dedicated {@code bridge.hmac-secret} from
 * {@code plugins/SnLib/config.yml} when set (operator opted out of coupling to the
 * forwarding secret), otherwise the Velocity modern-forwarding secret the backend
 * already has in {@code config/paper-global.yml} ({@code proxies.velocity.secret}).
 * No public API exposes that secret, so it is read from disk, once, at enable time.
 *
 * <p>Null result = bridge stays off (one severe log; sends resolve EXPIRED_TTL with a
 * clear detail instead of silently half-working).</p>
 */
final class SecretResolver {

    private SecretResolver() {
    }

    static byte @Nullable [] resolve(SnYml config, Logger logger) {
        String dedicated = config.getString("bridge.hmac-secret", "");
        if (dedicated != null && !dedicated.isBlank()) {
            logger.info("SnBridge: usando secreto HMAC dedicado de plugins/SnLib/config.yml");
            return dedicated.getBytes(StandardCharsets.UTF_8);
        }
        File paperGlobal = new File("config", "paper-global.yml");
        if (!paperGlobal.isFile()) {
            logger.severe("SnBridge desactivado: no existe config/paper-global.yml y no hay"
                    + " bridge.hmac-secret dedicado en plugins/SnLib/config.yml");
            return null;
        }
        String secret = YamlConfiguration.loadConfiguration(paperGlobal)
                .getString("proxies.velocity.secret", "");
        if (secret == null || secret.isBlank()) {
            logger.severe("SnBridge desactivado: proxies.velocity.secret vacio en"
                    + " config/paper-global.yml (configurar forwarding moderno o un"
                    + " bridge.hmac-secret dedicado)");
            return null;
        }
        return secret.getBytes(StandardCharsets.UTF_8);
    }
}
