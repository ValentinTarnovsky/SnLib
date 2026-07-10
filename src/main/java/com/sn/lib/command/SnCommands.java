package com.sn.lib.command;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Objects;

import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;
import com.sn.lib.command.internal.BukkitCommandRegistry;
import com.sn.lib.lang.SnLang;

/**
 * Command module of a consumer context, reached through {@code sn.commands()}.
 *
 * <p>Every root built here injects a {@code reload} subcommand (permission
 * {@code <plugin>.admin.reload}, runs the context reload and confirms with
 * {@code snlib.reload-done}) and a generated {@code help} subcommand; both are
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
            throw new IllegalArgumentException("Nombre de comando vacio");
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

    /** Builder of one root command tree. */
    public final class RootBuilder {

        private final String name;
        private final List<String> aliases = new ArrayList<>();
        private final List<SubCommandBuilder> subs = new ArrayList<>();

        private @Nullable String permission;
        private String description = "";
        private boolean withoutDefaults;

        RootBuilder(String name) {
            this.name = name.trim().toLowerCase(Locale.ROOT);
        }

        /** Adds aliases for the root command. */
        public RootBuilder aliases(String... aliases) {
            for (String alias : aliases) {
                this.aliases.add(alias.trim().toLowerCase(Locale.ROOT));
            }
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
                throw new IllegalArgumentException("Nombre de subcomando vacio");
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
            command.register();
            return command;
        }
    }
}
