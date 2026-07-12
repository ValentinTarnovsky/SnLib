package com.sn.lib.velocity;

import java.util.HashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiConsumer;

import com.sn.lib.SnExperimental;
import com.sn.lib.velocity.internal.ProxyBridgeRuntime;
import com.sn.lib.velocity.internal.ProxyChannelCore;

/**
 * Entry point of SnBridge for consumer PROXY plugins. Requires SnLib.jar in the proxy's
 * plugins/ folder and {@code "dependencies": [{"id": "snlib"}]} in the consumer's
 * velocity-plugin.json (mirror of {@code depend: [SnLib]} on Paper).
 *
 * <pre>{@code
 * SnProxyChannel bridge = SnProxy.channel(this, "sncredits", 3);
 * bridge.register(OpenConfirm.TYPE, ShopClick.TYPE, SyncConfig.TYPE);
 * bridge.on(ShopClick.TYPE, (src, msg) -> shop.handleClick(src.player(), msg));
 * bridge.respond(RequestConfig.TYPE, SyncConfig.TYPE, (src, req) -> buildConfig());
 * bridge.to("gens").send(OpenConfirm.TYPE, new OpenConfirm(uuid, item, 500.0))
 *     .thenAccept(d -> { if (!d.ok()) logger.warn("gens: {}", d); });
 * }</pre>
 */
@SnExperimental
public final class SnProxy {

    /** Claims kept per namespace: owner identity + wrapper (SnLib classes load once). */
    private static final Map<String, Claim> CHANNELS = new HashMap<>(8);

    private record Claim(Object owner, SnProxyChannel channel) {
    }

    private SnProxy() {
    }

    /**
     * Claims a namespace and returns its typed channel. First-claim-wins across every
     * proxy plugin: claiming a namespace another plugin instance holds is a hard error
     * (never silent handler sharing), mirroring the Paper side. Idempotent for the SAME
     * consumer instance.
     *
     * @param consumerPlugin the consumer's plugin main instance (ownership identity)
     * @param namespace      SAME string the backend side claims: lowercase [a-z0-9_-]+
     * @param msgsetVersion  version of this plugin's message set, negotiated in HELLO
     */
    public static synchronized SnProxyChannel channel(Object consumerPlugin, String namespace,
            int msgsetVersion) {
        if (consumerPlugin == null) {
            throw new IllegalArgumentException("consumerPlugin null: pass the plugin instance");
        }
        validate(namespace);
        Claim existing = CHANNELS.get(namespace);
        if (existing != null) {
            if (existing.owner != consumerPlugin) {
                throw new IllegalStateException("Bridge namespace '" + namespace
                        + "' already claimed by " + existing.owner.getClass().getName()
                        + " (first-claim-wins, pick another namespace)");
            }
            return existing.channel;
        }
        ProxyBridgeRuntime runtime = ProxyBridgeRuntime.get();
        Map<String, BiConsumer<SnProxySource, Object>> handlers = SnProxyChannel.newHandlerTable();
        ProxyChannelCore core = runtime.claim(namespace, msgsetVersion,
                SnProxyChannel.dispatcher(runtime, handlers, namespace));
        SnProxyChannel channel = new SnProxyChannel(runtime, core, handlers);
        CHANNELS.put(namespace, new Claim(consumerPlugin, channel));
        return channel;
    }

    /**
     * Tier 2 verbs: generic actions SnLib runs on a target backend (console, message,
     * title, sound, bossbar, actions), so a proxy-only plugin needs no Paper jar. No
     * namespace claim required; the verbs channel is served by SnLib itself.
     */
    public static SnVerbs verbs() {
        return new SnVerbs(ProxyBridgeRuntime.get());
    }

    /** Aggregated per-backend status table (log it or expose it in a plugin command). */
    public static String statusReport() {
        ProxyBridgeRuntime runtime = ProxyBridgeRuntime.live();
        return runtime == null ? "SnBridge runtime shut down." : runtime.statusReport();
    }

    /** Clears the local wrapper cache; the runtime shutdown already tears cores down. */
    public static synchronized void reset() {
        CHANNELS.clear();
    }

    private static void validate(String namespace) {
        if (namespace == null || namespace.isBlank()
                || !namespace.equals(namespace.toLowerCase(Locale.ROOT))
                || !namespace.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Invalid bridge namespace: '" + namespace
                    + "' (lowercase [a-z0-9_-], no ':' or '/')");
        }
        if (namespace.startsWith("snlib")) {
            throw new IllegalArgumentException("Namespace '" + namespace + "' is reserved for SnLib");
        }
    }
}
