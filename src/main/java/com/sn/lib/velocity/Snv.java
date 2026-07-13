package com.sn.lib.velocity;

import java.nio.file.Path;

import com.velocitypowered.api.command.Command;
import com.velocitypowered.api.command.CommandManager;
import com.velocitypowered.api.command.CommandMeta;
import com.velocitypowered.api.proxy.ProxyServer;

import net.kyori.adventure.text.Component;
import org.slf4j.Logger;

import com.sn.lib.text.SnText;

/**
 * Per-plugin context for a consumer Velocity plugin: the small counterpart of the Paper
 * {@code Sn}. Created once in the consumer's {@code ProxyInitializeEvent} handler; it groups
 * the proxy, logger, data directory, a managed YAML {@link SnvConfig}, a {@link SnvScheduler}
 * and command registration, and shares the SAME text pipeline ({@link SnText}) as the Paper
 * side so {@code &} / {@code [rgb]} / MiniMessage render identically on both platforms.
 *
 * <pre>{@code
 * @Subscribe
 * void onInit(ProxyInitializeEvent e) {
 *     Snv snv = Snv.create(this, proxy, logger, dataDir); // loads + merges config.yml
 *     Component motd = snv.color(snv.config().getString("motd", "&aHello"));
 *     snv.command("mycmd", new MyCommand(snv), "mc");
 *     snv.scheduler().repeat(this::tick, Duration.ofSeconds(1));
 * }
 * }</pre>
 *
 * <p>No cross-server messaging: SnLib on Velocity is a base for consistency across a
 * developer's Paper and Velocity plugins, nothing more.</p>
 */
public final class Snv {

    private final Object plugin;
    private final ProxyServer proxy;
    private final Logger logger;
    private final Path dataDir;
    private final SnvScheduler scheduler;
    private volatile SnvConfig config;

    private Snv(Object plugin, ProxyServer proxy, Logger logger, Path dataDir, SnvConfig config) {
        this.plugin = plugin;
        this.proxy = proxy;
        this.logger = logger;
        this.dataDir = dataDir;
        this.config = config;
        this.scheduler = new SnvScheduler(plugin, proxy);
    }

    /**
     * Creates the context and loads {@code config.yml} from {@code dataDir}, merging any
     * missing keys from the {@code config.yml} bundled in the consumer's jar (managed config,
     * same idea as the Paper side). {@code plugin} is the consumer's plugin main instance,
     * used for scheduler/command ownership and for resolving the bundled defaults.
     */
    public static Snv create(Object plugin, ProxyServer proxy, Logger logger, Path dataDir) {
        SnvConfig config = SnvConfig.load(dataDir.resolve("config.yml"),
                bundledConfig(plugin), logger);
        return new Snv(plugin, proxy, logger, dataDir, config);
    }

    public ProxyServer proxy() {
        return proxy;
    }

    public Logger logger() {
        return logger;
    }

    public Path dataDir() {
        return dataDir;
    }

    public SnvConfig config() {
        return config;
    }

    public SnvScheduler scheduler() {
        return scheduler;
    }

    /** Reloads {@code config.yml} from disk, re-merging the bundled defaults. */
    public void reloadConfig() {
        this.config = SnvConfig.load(dataDir.resolve("config.yml"), bundledConfig(plugin), logger);
    }

    /** Renders text through the shared SnLib pipeline ({@code &}, {@code [rgb]}, MiniMessage). */
    public Component color(String text) {
        return SnText.color(text);
    }

    /** Registers a command (with optional aliases) under the consumer plugin. */
    public void command(String name, Command command, String... aliases) {
        CommandManager manager = proxy.getCommandManager();
        CommandMeta meta = manager.metaBuilder(name).aliases(aliases).plugin(plugin).build();
        manager.register(meta, command);
    }

    private static java.io.InputStream bundledConfig(Object plugin) {
        return plugin.getClass().getClassLoader().getResourceAsStream("config.yml");
    }
}
