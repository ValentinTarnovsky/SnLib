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

    /** Claims kept per namespace so a double channel() returns the same wrapper. */
    private static final Map<String, SnProxyChannel> CHANNELS = new HashMap<>(8);

    private SnProxy() {
    }

    /**
     * Claims a namespace and returns its typed channel. First-claim-wins across every
     * proxy plugin; idempotent per namespace (the consumer keeps its own reference, the
     * ownership check lives in the shared runtime).
     *
     * @param consumerPlugin the consumer's plugin main instance (for diagnostics)
     * @param namespace      SAME string the backend side claims: lowercase [a-z0-9_-]+
     * @param msgsetVersion  version of this plugin's message set, negotiated in HELLO
     */
    public static synchronized SnProxyChannel channel(Object consumerPlugin, String namespace,
            int msgsetVersion) {
        validate(namespace);
        SnProxyChannel existing = CHANNELS.get(namespace);
        if (existing != null) {
            return existing;
        }
        ProxyBridgeRuntime runtime = ProxyBridgeRuntime.get();
        Map<String, BiConsumer<SnProxySource, Object>> handlers = SnProxyChannel.newHandlerTable();
        ProxyChannelCore core = runtime.claim(namespace, msgsetVersion,
                SnProxyChannel.dispatcher(runtime, handlers, namespace));
        SnProxyChannel channel = new SnProxyChannel(runtime, core, handlers);
        CHANNELS.put(namespace, channel);
        return channel;
    }

    /** Aggregated per-backend status table (log it or expose it in a plugin command). */
    public static String statusReport() {
        ProxyBridgeRuntime runtime = ProxyBridgeRuntime.live();
        return runtime == null ? "SnBridge runtime apagado." : runtime.statusReport();
    }

    /** Clears the local wrapper cache; the runtime shutdown already tears cores down. */
    public static synchronized void reset() {
        CHANNELS.clear();
    }

    private static void validate(String namespace) {
        if (namespace == null || namespace.isBlank()
                || !namespace.equals(namespace.toLowerCase(Locale.ROOT))
                || !namespace.matches("[a-z0-9_-]+")) {
            throw new IllegalArgumentException("Namespace de bridge invalido: '" + namespace
                    + "' (minusculas [a-z0-9_-], sin ':' ni '/')");
        }
        if (namespace.startsWith("snlib")) {
            throw new IllegalArgumentException("Namespace '" + namespace + "' reservado para SnLib");
        }
    }
}
