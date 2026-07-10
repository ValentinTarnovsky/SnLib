package com.sn.lib.yml;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/**
 * Pure text preprocessor that repairs tab-indented YAML before parsing.
 *
 * <p>SnakeYAML rejects tabs used as indentation. This class rewrites each tab
 * found in the leading whitespace run of a line into two spaces, leaving the
 * rest of the line byte-for-byte intact (tabs inside quoted or plain values
 * are preserved). Content lines of block scalars ({@code |} or {@code >},
 * with optional chomping/indentation modifiers) are never touched, since tabs
 * there are literal content.</p>
 *
 * <p>Line endings are normalized CRLF to LF. Indentation depth is measured in
 * columns with a tab counting as two columns, matching the replacement width.
 * {@link #preprocess(String)} never throws: it returns the repaired text plus
 * the 1-based numbers of the fixed lines so the caller can emit ONE warning.
 * No Bukkit types are referenced; the class is safe for plain unit tests.</p>
 */
public final class YamlPreprocessor {

    private YamlPreprocessor() {
    }

    /**
     * Outcome of {@link #preprocess(String)}.
     *
     * @param cleanText  repaired YAML text, LF line endings
     * @param fixedLines 1-based numbers of lines whose indentation tabs were
     *                   replaced; empty when the input needed no repair
     */
    public record Result(String cleanText, List<Integer> fixedLines) {

        public Result {
            fixedLines = List.copyOf(fixedLines);
        }
    }

    /**
     * Repairs indentation tabs in {@code rawText}.
     *
     * @param rawText raw YAML text; null is treated as empty
     * @return repaired text plus the lines that were fixed; never null, never throws
     */
    public static Result preprocess(String rawText) {
        if (rawText == null) {
            return new Result("", List.of());
        }
        String normalized = rawText.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        StringBuilder out = new StringBuilder(normalized.length() + 16);
        List<Integer> fixedLines = new ArrayList<>();
        boolean enBlockScalar = false;
        int scalarIndent = -1;
        for (int index = 0; index < lines.length; index++) {
            String line = lines[index];
            if (index > 0) {
                out.append('\n');
            }
            if (enBlockScalar) {
                if (isBlank(line) || indentColumns(line) > scalarIndent) {
                    out.append(line);
                    continue;
                }
                enBlockScalar = false;
            }
            String cleaned = fixIndentTabs(line, index + 1, fixedLines);
            out.append(cleaned);
            if (startsBlockScalar(cleaned)) {
                enBlockScalar = true;
                scalarIndent = indentColumns(cleaned);
            }
        }
        return new Result(out.toString(), fixedLines);
    }

    /**
     * Reads {@code file} as UTF-8. Malformed byte sequences decode to the
     * replacement character instead of failing; a leading BOM is stripped.
     *
     * @throws IOException on I/O failure
     */
    public static String read(Path file) throws IOException {
        String text = new String(Files.readAllBytes(file), StandardCharsets.UTF_8);
        if (!text.isEmpty() && text.charAt(0) == 0xFEFF) {
            return text.substring(1);
        }
        return text;
    }

    /**
     * Replaces every tab in the leading whitespace run with two spaces; the
     * remainder of the line is copied verbatim.
     */
    private static String fixIndentTabs(String line, int lineNumber, List<Integer> fixedLines) {
        int i = 0;
        boolean touched = false;
        StringBuilder fixed = new StringBuilder(line.length() + 8);
        while (i < line.length()) {
            char c = line.charAt(i);
            if (c == '\t') {
                fixed.append("  ");
                touched = true;
            } else if (c == ' ') {
                fixed.append(' ');
            } else {
                break;
            }
            i++;
        }
        if (!touched) {
            return line;
        }
        fixedLines.add(lineNumber);
        return fixed.append(line, i, line.length()).toString();
    }

    /**
     * True when the line, with any comment removed, ends in a block scalar
     * indicator: {@code |} or {@code >} plus optional chomping ({@code +}/{@code -})
     * and/or a single-digit indentation modifier, in either order.
     */
    private static boolean startsBlockScalar(String line) {
        String effective = rstrip(stripComment(line));
        if (effective.isEmpty()) {
            return false;
        }
        int tokenStart = effective.length();
        while (tokenStart > 0) {
            char c = effective.charAt(tokenStart - 1);
            if (c == ' ' || c == '\t') {
                break;
            }
            tokenStart--;
        }
        return isBlockScalarIndicator(effective.substring(tokenStart));
    }

    private static boolean isBlockScalarIndicator(String token) {
        if (token.isEmpty()) {
            return false;
        }
        char first = token.charAt(0);
        if (first != '|' && first != '>') {
            return false;
        }
        String modifiers = token.substring(1);
        return modifiers.isEmpty()
                || modifiers.matches("[0-9][+-]?|[+-][0-9]?");
    }

    /**
     * Cuts the line at the first {@code #} that sits outside quotes and is at
     * line start or preceded by whitespace.
     */
    private static String stripComment(String line) {
        boolean inSingle = false;
        boolean inDouble = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (inSingle) {
                if (c == '\'') {
                    inSingle = false;
                }
            } else if (inDouble) {
                if (c == '\\') {
                    i++;
                } else if (c == '"') {
                    inDouble = false;
                }
            } else if (c == '\'') {
                inSingle = true;
            } else if (c == '"') {
                inDouble = true;
            } else if (c == '#' && (i == 0 || line.charAt(i - 1) == ' ' || line.charAt(i - 1) == '\t')) {
                return line.substring(0, i);
            }
        }
        return line;
    }

    /** Leading whitespace width in columns: space counts 1, tab counts 2. */
    private static int indentColumns(String line) {
        int columns = 0;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == ' ') {
                columns++;
            } else if (c == '\t') {
                columns += 2;
            } else {
                break;
            }
        }
        return columns;
    }

    private static boolean isBlank(String line) {
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c != ' ' && c != '\t') {
                return false;
            }
        }
        return true;
    }

    private static String rstrip(String s) {
        int end = s.length();
        while (end > 0 && (s.charAt(end - 1) == ' ' || s.charAt(end - 1) == '\t')) {
            end--;
        }
        return s.substring(0, end);
    }
}
