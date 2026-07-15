package com.sn.lib.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.function.Supplier;

import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.command.internal.BukkitCommandRegistry;
import com.sn.lib.lang.SnLang;
import com.sn.lib.yml.SnYml;
import com.sn.lib.yml.YmlManager;

/**
 * Command module of a consumer context, reached through {@code sn.commands()}.
 *
 * <p>Every root built here injects a {@code reload} subcommand (permission
 * {@code <plugin>.admin.reload}, delegates to the context reload manager through
 * {@code Sn.reloadAll()} and confirms with {@code snlib.reload-done}) and a generated
 * {@code help} subcommand; both are
 * replaceable by declaring subcommands with those names and removable via
 * {@link RootBuilder#withoutDefaults()}. When the spec declared {@code debugCommand()},
 * a {@code debug} subcommand (permission {@code <plugin>.admin.debug}) toggles the
 * runtime debug service; it is gated by the spec, not by the defaults opt-out.</p>
 *
 * <p>Registration is reload-safe and keyed by the owning plugin: re-registering a root
 * with the same name replaces the previous tree, and the context teardown (or the tenant
 * sweep when the consumer disables) unregisters every root and refreshes the client
 * command trees, leaving no ghost commands.</p>
 */
public final class SnCommands {

    /** Conventional config key of the root alias list read by {@link RootBuilder#aliasesFromConfig()}. */
    public static final String CONFIG_ALIASES_KEY = "command.aliases";

    private final Sn ctx;
    private final @Nullable SnLang lang;
    private final boolean debugCommand;

    /**
     * Creates the module; instantiated by the context.
     *
     * @param ctx          owning context
     * @param lang         lang module of the context, or null when not declared (the
     *                     shared {@code snlib.*} default templates render instead)
     * @param debugCommand whether the spec declared the runtime debug command
     */
    public SnCommands(Sn ctx, @Nullable SnLang lang, boolean debugCommand) {
        this.ctx = ctx;
        this.lang = lang;
        this.debugCommand = debugCommand;
    }

    /** Starts a root command tree named {@code name}. */
    public RootBuilder root(String name) {
        Objects.requireNonNull(name, "name");
        if (name.isBlank()) {
            throw new IllegalArgumentException("Empty command name");
        }
        return new RootBuilder(name);
    }

    /**
     * Unregisters every root of the owning plugin and refreshes the client command
     * trees; invoked by the context teardown.
     */
    public void unregisterAll() {
        BukkitCommandRegistry.unregisterAll(ctx.plugin());
    }

    /**
     * Re-registers every root of the owning plugin and refreshes the client command
     * trees; the re-register step of the context reload flow. Each root re-sources its
     * dynamic aliases (the config binding re-reads {@link #CONFIG_ALIASES_KEY} from the
     * just-reloaded config).
     */
    public void reregisterAll() {
        BukkitCommandRegistry.reregisterAll(ctx.plugin());
    }

    /** Config module of the owning plugin, or null when the config module was not declared. */
    private @Nullable YmlManager ymlOrNull() {
        try {
            return ctx.yml();
        } catch (UnsupportedOperationException undeclared) {
            return null;
        }
    }

    /**
     * Root alias list read from the config, or null when it cannot act as the authority:
     * the config module is absent, or the key is not set. A set key returns its list as-is
     * (an empty list is authoritative and clears the aliases).
     */
    private @Nullable Collection<String> configAliases(String key) {
        YmlManager manager = ymlOrNull();
        if (manager == null) {
            return null;
        }
        SnYml config = manager.config();
        if (!config.isSet(key)) {
            return null;
        }
        return config.getStringList(key, List.of());
    }

    /** Builder of one root command tree. */
    public final class RootBuilder {

        private final String name;
        private final List<String> aliases = new ArrayList<>();
        private final List<SubCommandBuilder> subs = new ArrayList<>();

        private @Nullable String permission;
        private String description = "";
        private boolean withoutDefaults;
        private @Nullable Supplier<Collection<String>> aliasSupplier;

        RootBuilder(String name) {
            this.name = name.trim().toLowerCase(Locale.ROOT);
        }

        /**
         * Adds static aliases for the root command. When an alias supplier or the config
         * binding is also set, these act as the FALLBACK used only while the authoritative
         * source has no opinion.
         */
        public RootBuilder aliases(String... aliases) {
            for (String alias : aliases) {
                this.aliases.add(alias.trim().toLowerCase(Locale.ROOT));
            }
            return this;
        }

        /**
         * Supplies the root aliases dynamically, re-evaluated on every register pass (so a
         * reload re-sources them): a non-null result is authoritative and an empty list
         * clears the aliases, while a null result falls back to the static
         * {@link #aliases(String...)} / plugin.yml aliases. Aliases that disappear between
         * passes are unregistered.
         */
        public RootBuilder aliases(Supplier<Collection<String>> supplier) {
            this.aliasSupplier = Objects.requireNonNull(supplier, "supplier");
            return this;
        }

        /**
         * Sources the root aliases from the plugin config list at the conventional key
         * {@link #CONFIG_ALIASES_KEY}. The config is AUTHORITATIVE when the key is set (even
         * to an empty list); when the key is absent, or the config module was not declared,
         * the static {@link #aliases(String...)} / plugin.yml aliases apply.
         */
        public RootBuilder aliasesFromConfig() {
            return aliasesFromConfig(CONFIG_ALIASES_KEY);
        }

        /** {@link #aliasesFromConfig()} against a custom config key. */
        public RootBuilder aliasesFromConfig(String key) {
            Objects.requireNonNull(key, "key");
            this.aliasSupplier = () -> configAliases(key);
            return this;
        }

        /**
         * Root permission, inherited by every subcommand without its own. Without one
         * the root is public.
         */
        public RootBuilder permission(String permission) {
            this.permission = permission;
            return this;
        }

        /** Description of the root command. */
        public RootBuilder description(String description) {
            this.description = description == null ? "" : description;
            return this;
        }

        /** Starts a subcommand; finish it with {@link SubCommandBuilder#and()}. */
        public SubCommandBuilder sub(String name) {
            Objects.requireNonNull(name, "name");
            if (name.isBlank()) {
                throw new IllegalArgumentException("Empty subcommand name");
            }
            SubCommandBuilder sub = new SubCommandBuilder(this, name);
            subs.add(sub);
            return sub;
        }

        /**
         * Skips the default {@code reload} and {@code help} subcommands. The consumer
         * MUST then provide its own reload and help subcommands: sn-core declares both
         * mandatory on every root command.
         */
        public RootBuilder withoutDefaults() {
            this.withoutDefaults = true;
            return this;
        }

        /** Builds the tree, injects the applicable defaults and registers it. */
        public RootCommand register() {
            List<RootCommand.Sub> built = new ArrayList<>(subs.size());
            for (SubCommandBuilder sub : subs) {
                built.add(sub.build());
            }
            RootCommand command = new RootCommand(ctx, lang, name, aliases, description,
                    permission, built, !withoutDefaults, debugCommand);
            BukkitCommandRegistry.bindAliasSupplier(command, aliasSupplier);
            command.register();
            return command;
        }
    }
}
