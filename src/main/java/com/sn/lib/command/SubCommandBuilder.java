package com.sn.lib.command;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.function.Consumer;
import java.util.function.Predicate;

import org.jetbrains.annotations.Nullable;

/**
 * Builder of one subcommand inside a {@link SnCommands.RootBuilder} chain; {@link #and()}
 * returns to the root builder to declare the next subcommand or register the tree.
 */
public final class SubCommandBuilder {

    private final SnCommands.RootBuilder parent;
    private final String name;
    private final List<String> aliases = new ArrayList<>();
    private final Map<String, Arg<?>> args = new LinkedHashMap<>();
    private final List<RootCommand.Condition> conditions = new ArrayList<>();

    private @Nullable String permission;
    private @Nullable String usage;
    private String description = "";
    private boolean visible = true;
    private int requiredArgs;
    private boolean optionalDeclared;
    private @Nullable Consumer<CommandContext> executor;

    SubCommandBuilder(SnCommands.RootBuilder parent, String name) {
        this.parent = parent;
        this.name = name;
    }

    /** Adds aliases for this subcommand. */
    public SubCommandBuilder aliases(String... aliases) {
        for (String alias : aliases) {
            this.aliases.add(alias.trim().toLowerCase(Locale.ROOT));
        }
        return this;
    }

    /** Permission of this subcommand; without one it inherits the root permission. */
    public SubCommandBuilder permission(String permission) {
        this.permission = permission;
        return this;
    }

    /** Usage line shown on argument errors; without one it is generated from the args. */
    public SubCommandBuilder usage(String usage) {
        this.usage = usage;
        return this;
    }

    /** Description of this subcommand. */
    public SubCommandBuilder description(String description) {
        this.description = description == null ? "" : description;
        return this;
    }

    /** Whether this subcommand appears in tab completion and the generated help. */
    public SubCommandBuilder visible(boolean visible) {
        this.visible = visible;
        return this;
    }

    /** Declares the next positional argument; declaration order is parse order. */
    public SubCommandBuilder arg(String name, Arg<?> arg) {
        if (optionalDeclared) {
            throw new IllegalStateException(
                    "Argumento obligatorio '" + name + "' despues de uno opcional");
        }
        declare(name, arg);
        requiredArgs++;
        return this;
    }

    /**
     * Declares an OPTIONAL trailing positional argument: it suggests and parses when the
     * token is present but its absence never rejects the invocation. Optionals go last;
     * a required {@link #arg} after one is an error.
     */
    public SubCommandBuilder argOptional(String name, Arg<?> arg) {
        declare(name, arg);
        optionalDeclared = true;
        return this;
    }

    private void declare(String name, Arg<?> arg) {
        Objects.requireNonNull(name, "name");
        Objects.requireNonNull(arg, "arg");
        if (args.put(name, arg) != null) {
            throw new IllegalArgumentException("Argumento duplicado: '" + name + "'");
        }
    }

    /**
     * Declarative condition over the raw token at {@code index} (0-based among the
     * subcommand arguments): a failing token rejects the invocation with the usage
     * message before any typed parsing runs.
     */
    public SubCommandBuilder when(int index, Predicate<String> condition) {
        if (index < 0) {
            throw new IllegalArgumentException("Indice de condicion negativo: " + index);
        }
        Objects.requireNonNull(condition, "condition");
        conditions.add(new RootCommand.Condition(index, condition));
        return this;
    }

    /** Handler run once permission, argument count, conditions and typed parsing all pass. */
    public SubCommandBuilder executes(Consumer<CommandContext> executor) {
        this.executor = Objects.requireNonNull(executor, "executor");
        return this;
    }

    /** Returns to the root builder. */
    public SnCommands.RootBuilder and() {
        return parent;
    }

    RootCommand.Sub build() {
        return new RootCommand.Sub(name, aliases, permission, usage, description,
                visible, args, requiredArgs, conditions, executor);
    }
}
