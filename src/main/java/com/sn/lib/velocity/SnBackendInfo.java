package com.sn.lib.velocity;

import java.util.Map;

import com.sn.lib.SnExperimental;

/**
 * Negotiation snapshot of one backend as seen from the proxy: the msgset it announced in
 * HELLO, its SnLib version, and its capability vocabulary versions (verb name to version).
 * Public so {@code SnProxyChannel.capabilities} never leaks an internal type.
 *
 * @param msgset       message-set version the backend announced
 * @param libVersion   SnLib version string of the backend (diagnostics only)
 * @param capabilities verb vocabulary versions the backend serves (empty for plain channels)
 */
@SnExperimental
public record SnBackendInfo(int msgset, String libVersion, Map<String, Integer> capabilities) {

    public SnBackendInfo {
        capabilities = Map.copyOf(capabilities);
    }
}
