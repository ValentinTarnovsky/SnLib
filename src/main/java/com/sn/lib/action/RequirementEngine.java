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
 * {@code &&} and {@code ||} (AND binds tighter than OR) and groupable with parentheses.
 * An operand may be quoted with {@code '} or {@code "}: inside the quoted region the
 * connectors, the parentheses and the operator symbols stay literal, and the quotes
 * themselves are stripped from the final operand. The lines of a list are joined with
 * an implicit AND. Parsing happens ONCE at load; placeholders stay as raw tokens and
 * are resolved on every {@link Requirement#test} through the caller's resolver.</p>
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

    private enum TokenType {
        LPAREN, RPAREN, AND, OR, TEXT
    }

    private record Token(TokenType type, String text) {
    }

    /** Internal control-flow signal: any malformation fails the whole line open. */
    private static final class MalformedLineException extends RuntimeException {

        MalformedLineException() {
            super(null, null, false, false);
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
     * One line: tokenizer plus recursive descent. Any malformation (unclosed {@code (},
     * stray {@code )} or leftover tokens, empty parentheses, dangling connector, or a
     * text run that is not a comparison) turns the WHOLE line into an always-true
     * requirement with one WARN, so a broken config never locks players out.
     */
    private static Requirement parseLine(String line, Consumer<String> warn) {
        try {
            List<Token> tokens = tokenize(line);
            int[] pos = {0};
            Requirement expr = parseExpr(tokens, pos, warn);
            if (pos[0] < tokens.size()) {
                throw new MalformedLineException();
            }
            return expr;
        } catch (MalformedLineException e) {
            warn.accept("Malformed requirement: '" + line + "'; evaluates to true");
            return ALWAYS_TRUE;
        }
    }

    /**
     * One-pass character scan producing {@code LPAREN}, {@code RPAREN}, {@code AND}
     * ({@code &&}), {@code OR} ({@code ||}) and TEXT runs. A {@code '} or {@code "}
     * outside quotes opens a quoted region in which nothing is tokenized (connectors
     * and parentheses flow into the TEXT run) until the same closing character; the
     * quotes are kept inside the token (parseComparison strips them). An unclosed
     * quote leniently extends the region to the end of the line. Whitespace is kept
     * inside TEXT runs; runs of only whitespace between structural tokens are dropped.
     */
    private static List<Token> tokenize(String line) {
        List<Token> out = new ArrayList<>();
        StringBuilder text = new StringBuilder();
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (quote != 0) {
                text.append(c);
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                text.append(c);
                continue;
            }
            if (c == '(') {
                flushText(out, text);
                out.add(new Token(TokenType.LPAREN, "("));
                continue;
            }
            if (c == ')') {
                flushText(out, text);
                out.add(new Token(TokenType.RPAREN, ")"));
                continue;
            }
            if (c == '&' && i + 1 < line.length() && line.charAt(i + 1) == '&') {
                flushText(out, text);
                out.add(new Token(TokenType.AND, "&&"));
                i++;
                continue;
            }
            if (c == '|' && i + 1 < line.length() && line.charAt(i + 1) == '|') {
                flushText(out, text);
                out.add(new Token(TokenType.OR, "||"));
                i++;
                continue;
            }
            text.append(c);
        }
        flushText(out, text);
        return out;
    }

    private static void flushText(List<Token> out, StringBuilder text) {
        if (text.isEmpty()) {
            return;
        }
        String run = text.toString();
        text.setLength(0);
        if (!run.isBlank()) {
            out.add(new Token(TokenType.TEXT, run));
        }
    }

    /** {@code expr := and ('||' and)*} */
    private static Requirement parseExpr(List<Token> tokens, int[] pos, Consumer<String> warn) {
        List<Requirement> branches = new ArrayList<>();
        branches.add(parseAnd(tokens, pos, warn));
        while (peek(tokens, pos) == TokenType.OR) {
            pos[0]++;
            branches.add(parseAnd(tokens, pos, warn));
        }
        return branches.size() == 1 ? branches.get(0) : new AnyOf(branches);
    }

    /** {@code and := primary ('&&' primary)*} */
    private static Requirement parseAnd(List<Token> tokens, int[] pos, Consumer<String> warn) {
        List<Requirement> parts = new ArrayList<>();
        parts.add(parsePrimary(tokens, pos, warn));
        while (peek(tokens, pos) == TokenType.AND) {
            pos[0]++;
            parts.add(parsePrimary(tokens, pos, warn));
        }
        return parts.size() == 1 ? parts.get(0) : new AllOf(parts);
    }

    /** {@code primary := '(' expr ')' | comparison} */
    private static Requirement parsePrimary(List<Token> tokens, int[] pos, Consumer<String> warn) {
        if (pos[0] >= tokens.size()) {
            throw new MalformedLineException();
        }
        Token token = tokens.get(pos[0]);
        if (token.type() == TokenType.LPAREN) {
            pos[0]++;
            if (peek(tokens, pos) == TokenType.RPAREN) {
                throw new MalformedLineException();
            }
            Requirement inner = parseExpr(tokens, pos, warn);
            if (peek(tokens, pos) != TokenType.RPAREN) {
                throw new MalformedLineException();
            }
            pos[0]++;
            return inner;
        }
        if (token.type() == TokenType.TEXT) {
            pos[0]++;
            Requirement comparison = parseComparison(token.text(), warn);
            if (comparison == null) {
                throw new MalformedLineException();
            }
            return comparison;
        }
        throw new MalformedLineException();
    }

    private static @Nullable TokenType peek(List<Token> tokens, int[] pos) {
        return pos[0] < tokens.size() ? tokens.get(pos[0]).type() : null;
    }

    /**
     * Null when malformed: no operator outside quotes, or an empty operand on either
     * side. The operator scan tracks quote state so symbols inside {@code '...'} or
     * {@code "..."} stay literal. Each operand is trimmed and then stripped of ONE pair
     * of balanced quotes wrapping the whole operand; the inner content is NOT re-trimmed
     * (a quoted operand keeps its inner and edge spaces).
     */
    private static @Nullable Requirement parseComparison(String raw, Consumer<String> warn) {
        String text = raw.trim();
        if (text.isEmpty()) {
            return null;
        }
        char quote = 0;
        for (int i = 0; i < text.length(); i++) {
            char c = text.charAt(i);
            if (quote != 0) {
                if (c == quote) {
                    quote = 0;
                }
                continue;
            }
            if (c == '\'' || c == '"') {
                quote = c;
                continue;
            }
            for (Op op : Op.values()) {
                if (text.startsWith(op.symbol, i)) {
                    String left = stripQuotes(text.substring(0, i).trim());
                    String right = stripQuotes(text.substring(i + op.symbol.length()).trim());
                    if (left.isEmpty() || right.isEmpty()) {
                        return null;
                    }
                    return new Comparison(left, op, right, warn);
                }
            }
        }
        return null;
    }

    /** Strips ONE pair of balanced quotes wrapping the whole operand; anything else is kept. */
    private static String stripQuotes(String operand) {
        if (operand.length() >= 2) {
            char first = operand.charAt(0);
            char last = operand.charAt(operand.length() - 1);
            if (first == last && (first == '\'' || first == '"')) {
                return operand.substring(1, operand.length() - 1);
            }
        }
        return operand;
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
                    warn.accept("Non-numeric comparison with '" + op.symbol + "': '" + leftValue
                            + "' vs '" + rightValue + "'; evaluates to false");
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
