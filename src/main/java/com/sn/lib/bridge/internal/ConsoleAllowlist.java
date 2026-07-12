package com.sn.lib.bridge.internal;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import org.jetbrains.annotations.Nullable;

/**
 * Backend-authoritative allowlist of the console verb: ANCHORED per-argument patterns,
 * never prefix wildcards ("crates key give <player> vote <int:1..64>" yes,
 * "crates key give *" no). A command matches a pattern only when the token COUNT matches
 * exactly and every token matches its pattern token:
 *
 * <ul>
 *   <li>literal - case-sensitive exact token</li>
 *   <li>{@code <player>} - a valid Minecraft name ([A-Za-z0-9_]{1,16})</li>
 *   <li>{@code <int:min..max>} - integer within the inclusive range</li>
 *   <li>{@code <word>} - any single token (no spaces by construction)</li>
 * </ul>
 *
 * <p>The DEFAULT allowlist is EMPTY: the console verb denies everything until the
 * operator adds patterns to {@code bridge.console-allowlist} on that backend. Pure and
 * immutable; parse failures reject the pattern loudly at load time, never at match
 * time.</p>
 */
public final class ConsoleAllowlist {

    private final List<String> sources;
    private final List<String[]> patterns;

    private ConsoleAllowlist(List<String> sources, List<String[]> patterns) {
        this.sources = sources;
        this.patterns = patterns;
    }

    /**
     * Parses the configured pattern list; invalid patterns are skipped with a report so
     * one typo never silently disables the rest.
     *
     * @param configured raw pattern lines from bridge.console-allowlist
     * @param invalidOut receives "pattern -> reason" lines for the operator log
     */
    public static ConsoleAllowlist parse(List<String> configured, List<String> invalidOut) {
        List<String> sources = new ArrayList<>(configured.size());
        List<String[]> patterns = new ArrayList<>(configured.size());
        for (String line : configured) {
            String trimmed = line == null ? "" : line.trim();
            if (trimmed.isEmpty()) {
                continue;
            }
            String problem = validate(trimmed);
            if (problem != null) {
                invalidOut.add(trimmed + " -> " + problem);
                continue;
            }
            sources.add(trimmed);
            patterns.add(trimmed.split("\\s+"));
        }
        return new ConsoleAllowlist(List.copyOf(sources), List.copyOf(patterns));
    }

    /** The effective (valid) pattern lines, for the audit verb. */
    public List<String> effectivePatterns() {
        return sources;
    }

    /** The first matching pattern, or null when the command is denied. */
    public @Nullable String match(String command) {
        String[] tokens = command.trim().split("\\s+");
        for (int i = 0; i < patterns.size(); i++) {
            if (matches(patterns.get(i), tokens)) {
                return sources.get(i);
            }
        }
        return null;
    }

    private static boolean matches(String[] pattern, String[] tokens) {
        if (pattern.length != tokens.length) {
            return false; // anchored: no prefix matching, ever
        }
        for (int i = 0; i < pattern.length; i++) {
            if (!tokenMatches(pattern[i], tokens[i])) {
                return false;
            }
        }
        return true;
    }

    private static boolean tokenMatches(String patternToken, String token) {
        if (patternToken.equals("<player>")) {
            return token.matches("[A-Za-z0-9_]{1,16}");
        }
        if (patternToken.equals("<word>")) {
            return !token.isEmpty();
        }
        if (patternToken.startsWith("<int:")) {
            long[] range = intRange(patternToken);
            try {
                long value = Long.parseLong(token);
                return value >= range[0] && value <= range[1];
            } catch (NumberFormatException e) {
                return false;
            }
        }
        return patternToken.equals(token);
    }

    private static @Nullable String validate(String line) {
        if (line.contains("*")) {
            return "prefix wildcards are forbidden (anchored patterns only)";
        }
        for (String token : line.split("\\s+")) {
            if (!token.startsWith("<")) {
                continue;
            }
            if (token.equals("<player>") || token.equals("<word>")) {
                continue;
            }
            if (token.startsWith("<int:") && token.endsWith(">")) {
                if (intRangeOrNull(token) == null) {
                    return "malformed range in " + token + " (expected <int:min..max>)";
                }
                continue;
            }
            return "unknown token " + token + " (allowed: <player>, <word>, <int:min..max>)";
        }
        return null;
    }

    private static long[] intRange(String token) {
        long[] range = intRangeOrNull(token);
        // validate() already rejected malformed ranges at load time
        return range == null ? new long[] {Long.MIN_VALUE, Long.MAX_VALUE} : range;
    }

    private static long @Nullable [] intRangeOrNull(String token) {
        String body = token.substring("<int:".length(), token.length() - 1)
                .toLowerCase(Locale.ROOT);
        int split = body.indexOf("..");
        if (split <= 0 || split + 2 >= body.length() + 1) {
            return null;
        }
        try {
            long min = Long.parseLong(body.substring(0, split));
            long max = Long.parseLong(body.substring(split + 2));
            return min <= max ? new long[] {min, max} : null;
        } catch (NumberFormatException e) {
            return null;
        }
    }
}
