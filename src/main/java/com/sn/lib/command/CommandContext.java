package com.sn.lib.command;

import java.util.Map;

import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Parsed invocation of a subcommand: the sender plus every declared argument already
 * parsed by its {@link Arg}, keyed by the name given in the builder.
 */
public final class CommandContext {

    private final CommandSender sender;
    private final Map<String, Object> values;
    private final String[] raw;

    CommandContext(CommandSender sender, Map<String, Object> values, String[] raw) {
        this.sender = sender;
        this.values = values;
        this.raw = raw;
    }

    /** Command sender, player or console. */
    public CommandSender sender() {
        return sender;
    }

    /**
     * Sender as a player.
     *
     * @throws IllegalStateException when the sender is not a player
     */
    public Player player() {
        if (sender instanceof Player player) {
            return player;
        }
        throw new IllegalStateException("El sender de este comando no es un jugador");
    }

    /**
     * Parsed value of a declared argument.
     *
     * @throws IllegalArgumentException when no argument with that name was declared
     */
    @SuppressWarnings("unchecked")
    public <T> T get(String name) {
        Object value = values.get(name);
        if (value == null) {
            throw new IllegalArgumentException("Argumento no declarado: '" + name + "'");
        }
        return (T) value;
    }

    /** Parsed value as an int; accepts any numeric parse result. */
    public int getInt(String name) {
        Object value = get(name);
        if (value instanceof Number number) {
            return number.intValue();
        }
        return Integer.parseInt(value.toString().trim());
    }

    /** Parsed value as a double; accepts any numeric parse result. */
    public double getDouble(String name) {
        Object value = get(name);
        if (value instanceof Number number) {
            return number.doubleValue();
        }
        return Double.parseDouble(value.toString().trim());
    }

    /** Parsed value as a player. */
    public Player player(String name) {
        return get(name);
    }

    /** Raw token at {@code index} among the subcommand arguments, or null when absent. */
    public @Nullable String raw(int index) {
        return index >= 0 && index < raw.length ? raw[index] : null;
    }
}
