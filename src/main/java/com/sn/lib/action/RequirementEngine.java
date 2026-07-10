package com.sn.lib.action;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.logging.Logger;

import org.bukkit.entity.Player;
import org.jetbrains.annotations.Nullable;

/**
 * Parses placeholder requirement expressions into immutable {@link Requirement} trees.
 *
 * <p>Grammar per line: comparisons {@code left OP right} with operators {@code >},
 * {@code <}, {@code >=}, {@code <=}, {@code =}, {@code ==} and {@code !=}, joined by
 * {@code &&} and {@code ||}; AND binds tighter than OR. The lines of a list are joined
 * with an implicit AND. Parsing happens ONCE at load; placeholders stay as raw tokens
 * and are resolved on every {@link Requirement#test} through the caller's resolver.</p>
 *
 * <p>Coercion at evaluation time: when both sides parse as doubles the comparison is
 * numeric; otherwise {@code =}/{@code !=} compare lexicographically case-insensitive,
 * and the relational operators evaluate to false with a debug WARN. A malformed line
 * WARNs quoting the line and yields a requirement that evaluates to true.</p>
 *
 * <p>WARNs are delegable: callers may pass their own sink (plugin logger, SnDebug);
 * without one, messages go to the shared logger deduplicated by content.</p>
 */
public final class RequirementEngine {

    private static final Logger LOGGER = Logger.getLogger("SnLib");

    /** Server-wide static allowed by the SnLib contract: log dedup only, no consumer data. */
    private static final Set<String> WARNED = ConcurrentHashMap.newKeySet();

    private static final Requirement ALWAYS_TRUE = (player, resolver) -> true;

    /** Comparison operators; two-character symbols are declared first so the scan prefers them. */
    private enum Op {
        GE(">="), LE("<="), EQ_STRICT("=="), NE("!="), GT(">"), LT("<"), EQ("=");

        private final String symbol;

        Op(String symbol) {
            this.symbol = symbol;
        }
    }

    private RequirementEngine() {
    }

    /** Parses {@code lines} (implicit AND) sending warnings to the shared logger. */
    public static Requirement parse(List<String> lines) {
        return parse(lines, null);
    }

    /**
     * Parses {@code lines} into an immutable requirement tree, one expression per line,
     * joined with an implicit AND. Null, empty or blank input yields a requirement that
     * always passes.
     *
     * @param warn sink for parse and evaluation warnings; null uses the shared logger
     */
    public static Requirement parse(@Nullable List<String> lines, @Nullable Consumer<String> warn) {
        Consumer<String> sink = warn != null ? warn : RequirementEngine::warnOnce;
        if (lines == null || lines.isEmpty()) {
            return ALWAYS_TRUE;
        }
        List<Requirement> parts = new ArrayList<>(lines.size());
        for (String line : lines) {
            if (line == null || line.isBlank()) {
                continue;
            }
            parts.add(parseLine(line.trim(), sink));
        }
        if (parts.isEmpty()) {
            return ALWAYS_TRUE;
        }
        return parts.size() == 1 ? parts.get(0) : new AllOf(parts);
    }

    /**
     * One line: {@code ||} branches of {@code &&} chains (AND binds tighter). Any
     * malformed comparison turns the WHOLE line into an always-true requirement with one
     * WARN, so a broken config never locks players out.
     */
    private static Requirement parseLine(String line, Consumer<String> warn) {
        List<Requirement> orParts = new ArrayList<>();
        for (String orPart : split(line, "||")) {
            List<Requirement> andParts = new ArrayList<>();
            for (String andPart : split(orPart, "&&")) {
                Requirement comparison = parseComparison(andPart, warn);
                if (comparison == null) {
                    warn.accept("Requirement malformado: '" + line + "'; se evalua como true");
                    return ALWAYS_TRUE;
                }
                andParts.add(comparison);
            }
            orParts.add(andParts.size() == 1 ? andParts.get(0) : new AllOf(andParts));
        }
        return orParts.size() == 1 ? orParts.get(0) : new AnyOf(orParts);
    }

    /** Null when malformed: no operator, or an empty operand on either side. */
    private static @Nullable Requirement parseComparison(String raw, Consumer<String> warn) {
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        for (int i = 0; i < text.length(); i++) {
            for (Op op : Op.values()) {
                if (text.startsWith(op.symbol, i)) {
                    String left = text.substring(0, i).trim();
                    String right = text.substring(i + op.symbol.length()).trim();
                    if (left.isEmpty() || right.isEmpty()) {
                        return null;
                    }
                    return new Comparison(left, op, right, warn);
                }
            }
        }
        return null;
    }

    private static String resolve(String token, @Nullable Function<String, String> resolver) {
        if (resolver == null) {
            return token;
        }
        String resolved = resolver.apply(token);
        return resolved == null ? token : resolved;
    }

    private static @Nullable Double toDouble(String value) {
        try {
            return Double.valueOf(value.trim());
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static void warnOnce(String message) {
        if (WARNED.add(message)) {
            LOGGER.warning("[SnLib] " + message);
        }
    }

    /** Splits on the literal connector; the grammar has no quoting or parentheses. */
    private static List<String> split(String text, String connector) {
        List<String> out = new ArrayList<>();
        int start = 0;
        int index;
        while ((index = text.indexOf(connector, start)) >= 0) {
            out.add(text.substring(start, index));
            start = index + connector.length();
        }
        out.add(text.substring(start));
        return out;
    }

    private record AllOf(List<Requirement> parts) implements Requirement {

        private AllOf(List<Requirement> parts) {
            this.parts = List.copyOf(parts);
        }

        @Override
        public boolean test(@Nullable Player player, @Nullable Function<String, String> resolver) {
            for (Requirement part : parts) {
                if (!part.test(player, resolver)) {
                    return false;
                }
            }
            return true;
        }
    }

    private record AnyOf(List<Requirement> parts) implements Requirement {

        private AnyOf(List<Requirement> parts) {
            this.parts = List.copyOf(parts);
        }

        @Override
        public boolean test(@Nullable Player player, @Nullable Function<String, String> resolver) {
            for (Requirement part : parts) {
                if (part.test(player, resolver)) {
                    return true;
                }
            }
            return false;
        }
    }

    /** Leaf node: operand tokens stay raw and are resolved on every evaluation. */
    private record Comparison(String left, Op op, String right, Consumer<String> warn)
            implements Requirement {

        @Override
        public boolean test(@Nullable Player player, @Nullable Function<String, String> resolver) {
            String leftValue = resolve(left, resolver);
            String rightValue = resolve(right, resolver);
            Double leftNumber = toDouble(leftValue);
            Double rightNumber = toDouble(rightValue);
            if (leftNumber != null && rightNumber != null) {
                return testNumeric(leftNumber, rightNumber);
            }
            return switch (op) {
                case EQ, EQ_STRICT -> leftValue.equalsIgnoreCase(rightValue);
                case NE -> !leftValue.equalsIgnoreCase(rightValue);
                case GT, LT, GE, LE -> {
                    warn.accept("Comparacion no numerica con '" + op.symbol + "': '" + leftValue
                            + "' vs '" + rightValue + "'; se evalua como false");
                    yield false;
                }
            };
        }

        private boolean testNumeric(double leftNumber, double rightNumber) {
            int cmp = Double.compare(leftNumber, rightNumber);
            return switch (op) {
                case GT -> cmp > 0;
                case LT -> cmp < 0;
                case GE -> cmp >= 0;
                case LE -> cmp <= 0;
                case EQ, EQ_STRICT -> cmp == 0;
                case NE -> cmp != 0;
            };
        }
    }
}
