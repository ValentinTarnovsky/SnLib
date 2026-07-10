package com.sn.lib.command;

import java.util.List;

import org.bukkit.command.CommandSender;

import com.sn.lib.Ph;

/**
 * Typed command argument: parses one raw token into {@code T} and provides its tab
 * suggestions. Implementations come from the {@code Args} factory or from the consumer.
 *
 * @param <T> parsed value type
 */
public interface Arg<T> {

    /**
     * Parses the raw token into the typed value.
     *
     * @throws ArgParseException when the token is invalid; carries the lang key and
     *         local placeholders the command flow sends back to the sender
     */
    T parse(String raw) throws ArgParseException;

    /** Tab suggestions for the partial token, resolved for the given sender. */
    List<String> suggest(CommandSender sender, String partial);

    /** Rejection of a raw token, expressed as a lang key plus its local placeholders. */
    class ArgParseException extends Exception {

        private final String langKey;
        private final Ph[] phs;

        /**
         * @param langKey message key ({@code snlib.*} or a consumer key)
         * @param phs     local placeholders for the message
         */
        public ArgParseException(String langKey, Ph... phs) {
            super(langKey);
            this.langKey = langKey;
            this.phs = phs == null ? new Ph[0] : phs.clone();
        }

        /** Lang key of the error message. */
        public String langKey() {
            return langKey;
        }

        /** Local placeholders of the error message. */
        public Ph[] phs() {
            return phs.clone();
        }
    }
}
