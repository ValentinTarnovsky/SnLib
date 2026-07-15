package com.sn.lib.command;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.Bukkit;
import org.bukkit.OfflinePlayer;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;
import org.bukkit.util.StringUtil;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Ph;
import com.sn.lib.util.TimeUtil;

/**
 * Factory of typed {@link Arg} implementations for {@link SubCommandBuilder#arg}.
 *
 * <p>Every arg built here carries default example suggestions and accepts the
 * {@link SnArg#suggestCurrent(Supplier)} decorator, which prepends the current actual
 * value: with an empty partial (or a prefix match) the actual/example values come
 * first; a non-empty partial filters the remaining options through
 * {@code StringUtil.copyPartialMatches} and sorts them. The vanilla client filters
 * suggestions by the typed prefix, so a non-matching example never reaches the
 * screen.</p>
 *
 * <p>Free-form {@link #string()} / {@link #greedy()} args carry no fixed example; their
 * lone suggestion is an angle-bracket HINT derived from the declared argument name (for
 * example {@code <target>}), or from the explicit hint of {@link #string(String)} /
 * {@link #greedy(String)}. Hint convention: the value is wrapped in {@code <>} unless it
 * already is, so both {@code string("amount")} and {@code string("<amount>")} suggest
 * {@code <amount>}. A hint is only offered while the partial is empty (or itself starts
 * with {@code <}); once the sender types a real value the free-form arg suggests nothing
 * and accepts whatever is typed.</p>
 */
public final class Args {

    /** Cap of list-backed suggestions (online players, oneOf options). */
    private static final int SUGGESTION_CAP = 100;

    private Args() {
    }

    /**
     * Online player by exact name; rejects with {@code snlib.player-not-found} and
     * suggests up to 100 online names.
     */
    public static SnArg<Player> onlinePlayer() {
        return new SnArg<Player>(List.of(), false) {
            @Override
            public Player parse(String raw) throws ArgParseException {
                Player player = Bukkit.getPlayerExact(raw);
                if (player == null) {
                    throw new ArgParseException("snlib.player-not-found", Ph.of("value", raw));
                }
                return player;
            }

            @Override
            protected List<String> options(CommandSender sender) {
                return onlineNames();
            }
        };
    }

    /**
     * Player UUID resolved strictly without blocking: exact online match first, then
     * the local offline-player cache. A name absent from both rejects with
     * {@code snlib.player-not-found}. {@code Bukkit.getOfflinePlayer(String)} is never
     * used here because it may perform a blocking profile lookup on the main thread;
     * remote resolution belongs to the consumer via the async scheduler.
     */
    public static SnArg<UUID> offlinePlayerUuid() {
        return new SnArg<UUID>(List.of(), false) {
            @Override
            public UUID parse(String raw) throws ArgParseException {
                Player online = Bukkit.getPlayerExact(raw);
                if (online != null) {
                    return online.getUniqueId();
                }
                OfflinePlayer cached = Bukkit.getOfflinePlayerIfCached(raw);
                if (cached != null) {
                    return cached.getUniqueId();
                }
                throw new ArgParseException("snlib.player-not-found", Ph.of("value", raw));
            }

            @Override
            protected List<String> options(CommandSender sender) {
                return onlineNames();
            }
        };
    }

    /**
     * One value of a dynamic option set, matched case-insensitively and returned in its
     * canonical form; rejects with {@code snlib.invalid-value} and suggests up to 100
     * of the current options.
     */
    public static SnArg<String> oneOf(Supplier<Collection<String>> options) {
        Objects.requireNonNull(options, "options");
        return oneOf(sender -> options.get());
    }

    /**
     * Sender-aware {@link #oneOf(Supplier)}: the option set is computed per invoking sender
     * (for example the caller's clan members), so both the suggestions and the parse-time
     * validation are scoped to that sender. Matched case-insensitively and returned in its
     * canonical form; rejects with {@code snlib.invalid-value} and suggests up to 100
     * options. The parse fallback without a sender queries the function with {@code null}.
     */
    public static SnArg<String> oneOf(Function<CommandSender, Collection<String>> options) {
        Objects.requireNonNull(options, "options");
        return new SnArg<String>(List.of(), false) {
            @Override
            public String parse(String raw) throws ArgParseException {
                return parse(null, raw);
            }

            @Override
            public String parse(CommandSender sender, String raw) throws ArgParseException {
                Collection<String> current = options.apply(sender);
                if (current != null) {
                    for (String option : current) {
                        if (option != null && option.equalsIgnoreCase(raw)) {
                            return option;
                        }
                    }
                }
                throw new ArgParseException("snlib.invalid-value", Ph.of("value", raw));
            }

            @Override
            protected List<String> options(CommandSender sender) {
                return capped(options.apply(sender));
            }
        };
    }

    /**
     * Free-form single token whose only role is to SUGGEST a dynamic set: it parses any
     * input as-is (no restriction), so the handler keeps its own not-found handling. Use it
     * when the valid set is dynamic and the handler already resolves and validates the token
     * itself. Suggests up to 100 of the current options.
     */
    public static SnArg<String> suggesting(Supplier<Collection<String>> options) {
        Objects.requireNonNull(options, "options");
        return suggesting(sender -> options.get());
    }

    /**
     * Sender-aware {@link #suggesting(Supplier)}: the suggested set is computed per invoking
     * sender while the token is still accepted as-is at parse.
     */
    public static SnArg<String> suggesting(Function<CommandSender, Collection<String>> options) {
        Objects.requireNonNull(options, "options");
        return new SnArg<String>(List.of(), false) {
            @Override
            public String parse(String raw) {
                return raw;
            }

            @Override
            protected List<String> options(CommandSender sender) {
                return capped(options.apply(sender));
            }
        };
    }

    /**
     * Integer within {@code [min, max]}; a non-number rejects with
     * {@code snlib.invalid-number} and an out-of-range value with
     * {@code snlib.out-of-range}. Suggests both bounds as examples.
     */
    public static SnArg<Integer> intRange(int min, int max) {
        return new SnArg<Integer>(List.of(String.valueOf(min), String.valueOf(max)), false) {
            @Override
            public Integer parse(String raw) throws ArgParseException {
                int value;
                try {
                    value = Integer.parseInt(raw.trim());
                } catch (NumberFormatException e) {
                    throw new ArgParseException("snlib.invalid-number", Ph.of("value", raw));
                }
                if (value < min || value > max) {
                    throw new ArgParseException("snlib.out-of-range", Ph.of("value", raw),
                            Ph.of("min", min), Ph.of("max", max));
                }
                return value;
            }
        };
    }

    /**
     * Double within {@code [min, max]}; a non-number rejects with
     * {@code snlib.invalid-number} and an out-of-range value with
     * {@code snlib.out-of-range}. Suggests both bounds as examples.
     */
    public static SnArg<Double> doubleRange(double min, double max) {
        return new SnArg<Double>(List.of(String.valueOf(min), String.valueOf(max)), false) {
            @Override
            public Double parse(String raw) throws ArgParseException {
                double value;
                try {
                    value = Double.parseDouble(raw.trim().replace(',', '.'));
                } catch (NumberFormatException e) {
                    throw new ArgParseException("snlib.invalid-number", Ph.of("value", raw));
                }
                if (Double.isNaN(value) || value < min || value > max) {
                    throw new ArgParseException("snlib.out-of-range", Ph.of("value", raw),
                            Ph.of("min", min), Ph.of("max", max));
                }
                return value;
            }
        };
    }

    /**
     * Compact duration such as {@code "1d 2h 30m 15s"}, parsed to milliseconds through
     * {@link TimeUtil#parseMillis(String)}; a zero or unparseable duration rejects with
     * {@code snlib.invalid-value}.
     */
    public static SnArg<Long> duration() {
        return new SnArg<Long>(List.of("30m"), false) {
            @Override
            public Long parse(String raw) throws ArgParseException {
                long millis = TimeUtil.parseMillis(raw);
                if (millis <= 0L) {
                    throw new ArgParseException("snlib.invalid-value", Ph.of("value", raw));
                }
                return millis;
            }

            @Override
            protected List<String> options(CommandSender sender) {
                return List.of("30s", "5m", "1h", "1d");
            }
        };
    }

    /**
     * Boolean accepting {@code true/yes/on} and {@code false/no/off}; anything else
     * rejects with {@code snlib.invalid-value}.
     */
    public static SnArg<Boolean> bool() {
        return new SnArg<Boolean>(List.of(), false) {
            @Override
            public Boolean parse(String raw) throws ArgParseException {
                switch (raw.trim().toLowerCase(Locale.ROOT)) {
                    case "true", "yes", "on" -> {
                        return Boolean.TRUE;
                    }
                    case "false", "no", "off" -> {
                        return Boolean.FALSE;
                    }
                    default -> throw new ArgParseException("snlib.invalid-value",
                            Ph.of("value", raw));
                }
            }

            @Override
            protected List<String> options(CommandSender sender) {
                return List.of("true", "false");
            }
        };
    }

    /**
     * Free-form single token, returned as-is. Un-hinted: its lone suggestion is the
     * angle-bracket hint {@code <argName>} derived from the declared argument name.
     */
    public static SnArg<String> string() {
        return stringArg(false, null);
    }

    /**
     * Free-form single token with an explicit suggestion hint, returned as-is. The hint is
     * shown in angle brackets ({@code <hint>}); a value that is already bracketed is kept
     * verbatim.
     */
    public static SnArg<String> string(String hint) {
        return stringArg(false, hint);
    }

    /**
     * Free text consuming every remaining token as one space-joined value. Only
     * meaningful as the LAST declared argument of a subcommand. Un-hinted: its lone
     * suggestion is the angle-bracket hint {@code <argName>} derived from the declared name.
     */
    public static SnArg<String> greedy() {
        return stringArg(true, null);
    }

    /**
     * Greedy free text with an explicit suggestion hint. The hint is shown in angle
     * brackets ({@code <hint>}); a value that is already bracketed is kept verbatim.
     */
    public static SnArg<String> greedy(String hint) {
        return stringArg(true, hint);
    }

    /** Builds a free-form string arg carrying the angle-bracket hint affordance. */
    private static SnArg<String> stringArg(boolean greedy, @Nullable String hint) {
        SnArg<String> arg = new SnArg<String>(List.of(), greedy) {
            @Override
            public String parse(String raw) {
                return raw;
            }
        };
        arg.hintMode = true;
        arg.hint = hint;
        return arg;
    }

    /** Non-null options of the collection capped at {@link #SUGGESTION_CAP}, order kept. */
    private static List<String> capped(Collection<String> options) {
        List<String> out = new ArrayList<>();
        if (options == null) {
            return out;
        }
        for (String option : options) {
            if (out.size() >= SUGGESTION_CAP) {
                break;
            }
            if (option != null) {
                out.add(option);
            }
        }
        return out;
    }

    /** Online player names capped at {@link #SUGGESTION_CAP}. */
    private static List<String> onlineNames() {
        List<String> names = new ArrayList<>();
        for (Player player : Bukkit.getOnlinePlayers()) {
            if (names.size() >= SUGGESTION_CAP) {
                break;
            }
            names.add(player.getName());
        }
        return names;
    }

    /**
     * Arg produced by this factory: default example suggestions plus the
     * {@link #suggestCurrent(Supplier)} decorator that prepends the current actual
     * value to the suggestions.
     *
     * @param <T> parsed value type
     */
    public abstract static class SnArg<T> implements Arg<T> {

        private final List<String> examples;
        private final boolean greedy;
        private @Nullable Supplier<String> current;
        // Set by the free-form string()/greedy() factories of the enclosing Args class.
        boolean hintMode;
        @Nullable String hint;

        protected SnArg(List<String> examples, boolean greedy) {
            this.examples = List.copyOf(examples);
            this.greedy = greedy;
        }

        /**
         * Prepends the supplied current actual value to the suggestions, before the
         * examples and the base options.
         */
        public final SnArg<T> suggestCurrent(Supplier<String> current) {
            this.current = current;
            return this;
        }

        /** Whether this arg consumes every remaining token as one value. */
        public final boolean greedy() {
            return greedy;
        }

        /** Base options for the sender; empty when only the examples apply. */
        protected List<String> options(CommandSender sender) {
            return List.of();
        }

        @Override
        public final List<String> suggest(CommandSender sender, String partial) {
            return suggest(sender, partial, null);
        }

        @Override
        public final List<String> suggest(CommandSender sender, String partial,
                @Nullable String argName) {
            String prefix = partial == null ? "" : partial;
            List<String> out = new ArrayList<>();
            String actual = currentValue();
            if (actual != null && !actual.isEmpty() && prefixMatches(actual, prefix)) {
                out.add(actual);
            }
            String hintToken = hintToken(argName);
            if (hintToken != null && prefixMatches(hintToken, prefix)
                    && !containsIgnoreCase(out, hintToken)) {
                out.add(hintToken);
            }
            for (String example : examples) {
                if (prefixMatches(example, prefix) && !containsIgnoreCase(out, example)) {
                    out.add(example);
                }
            }
            List<String> base = new ArrayList<>(options(sender));
            List<String> matched = prefix.isEmpty()
                    ? base
                    : StringUtil.copyPartialMatches(prefix, base, new ArrayList<>());
            Collections.sort(matched);
            for (String option : matched) {
                if (!containsIgnoreCase(out, option)) {
                    out.add(option);
                }
            }
            return out;
        }

        /**
         * Angle-bracket hint of a free-form arg: the explicit hint when set, otherwise the
         * declared argument name; null when this arg is not in hint mode.
         */
        private @Nullable String hintToken(@Nullable String argName) {
            if (!hintMode) {
                return null;
            }
            String base = hint != null && !hint.isBlank()
                    ? hint
                    : (argName != null && !argName.isBlank() ? argName : "value");
            return bracket(base);
        }

        /** Wraps the value in angle brackets unless it is already bracketed. */
        private static String bracket(String value) {
            String trimmed = value.trim();
            if (trimmed.length() >= 2 && trimmed.charAt(0) == '<'
                    && trimmed.charAt(trimmed.length() - 1) == '>') {
                return trimmed;
            }
            return "<" + trimmed + ">";
        }

        /** Current value from the decorator supplier; a failing supplier yields none. */
        private @Nullable String currentValue() {
            if (current == null) {
                return null;
            }
            try {
                return current.get();
            } catch (Throwable t) {
                return null;
            }
        }

        private static boolean prefixMatches(String value, String prefix) {
            return prefix.isEmpty() || StringUtil.startsWithIgnoreCase(value, prefix);
        }

        private static boolean containsIgnoreCase(List<String> list, String value) {
            for (String item : list) {
                if (item.equalsIgnoreCase(value)) {
                    return true;
                }
            }
            return false;
        }
    }
}
