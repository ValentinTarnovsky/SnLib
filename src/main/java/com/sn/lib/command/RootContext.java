package com.sn.lib.command;

import java.util.function.IntConsumer;

import org.bukkit.command.CommandSender;

/**
 * Handle passed to the bare-root hook declared through
 * {@link SnCommands.RootBuilder#onEmpty}: the invoking sender plus the ability to render the
 * generated help, so a hook can print its own banner and still fall through to the standard
 * help ({@code root.help()}).
 */
public final class RootContext {

    private final CommandSender sender;
    private final String label;
    private final IntConsumer help;

    RootContext(CommandSender sender, String label, IntConsumer help) {
        this.sender = sender;
        this.label = label;
        this.help = help;
    }

    /** Sender that ran the bare root command. */
    public CommandSender sender() {
        return sender;
    }

    /**
     * Root label the sender typed, without the leading slash: the root name, or the alias
     * they reached it through ({@code c} for a bare {@code /c} on a root named
     * {@code clan}). Echo it in a banner instead of hardcoding the root name so the hook
     * follows the alias the sender is actually using.
     */
    public String label() {
        return label;
    }

    /** Renders the generated help to the sender: the default bare-root behavior. */
    public void help() {
        help.accept(1);
    }

    /** Renders the given 1-based page of the generated help to the sender. */
    public void help(int page) {
        help.accept(page);
    }
}
