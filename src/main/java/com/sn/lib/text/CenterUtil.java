package com.sn.lib.text;

/**
 * Chat centering against the 154px half-width of the default chat window, using the
 * vanilla font width table (DefaultFontInfo).
 *
 * <p>Pure string transform: it measures the visible pixels of a legacy-colored string
 * (skipping {@code &X}, section-sign codes and {@code &#RRGGBB} hex while measuring,
 * with bold width opened by {@code &l} and cleared by any COLOR code ({@code &0}-{@code &f},
 * {@code &#RRGGBB}) or {@code &r}, matching the vanilla reset the render applies) and
 * prepends the spaces needed to center it. It can only measure legacy strings, never
 * Components, which is why the pipeline applies it as the last step of the legacy phase.</p>
 */
public final class CenterUtil {

    /** Half of the default chat window width in font pixels. */
    private static final int CENTER_PX = 154;

    private static final char SECTION = '\u00A7';

    private CenterUtil() {
    }

    /**
     * Prepends the spaces required to center the line at {@value #CENTER_PX}px.
     *
     * @param legacyColored final legacy-colored string (gradient hex already interpolated)
     * @return the centered line, or the input unchanged when empty or already wider
     */
    public static String center(String legacyColored) {
        if (legacyColored == null || legacyColored.isEmpty()) {
            return legacyColored;
        }
        int px = measure(legacyColored);
        int toCompensate = CENTER_PX - px / 2;
        if (toCompensate <= 0) {
            return legacyColored;
        }
        int spaceWidth = width(' ', false);
        StringBuilder out = new StringBuilder(legacyColored.length() + toCompensate / spaceWidth + 1);
        for (int compensated = 0; compensated < toCompensate; compensated += spaceWidth) {
            out.append(' ');
        }
        return out.append(legacyColored).toString();
    }

    private static int measure(String s) {
        int px = 0;
        boolean bold = false;
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if ((c == '&' || c == SECTION) && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (c == '&' && next == '#' && isHex(s, i + 2)) {
                    // A legacy hex COLOR resets vanilla formatting: bold stops here too.
                    bold = false;
                    i += 7;
                    continue;
                }
                char code = Character.toLowerCase(next);
                if (isCodeChar(code)) {
                    if (code == 'l') {
                        bold = true;
                    } else if (clearsBold(code)) {
                        // A COLOR code (&0-&f) or &r resets vanilla formatting, matching the
                        // legacy-to-MiniMessage render where a color negates active decorations.
                        bold = false;
                    }
                    i++;
                    continue;
                }
            }
            px += width(c, bold);
        }
        return px;
    }

    /** A COLOR code ({@code &0}-{@code &f}) or {@code &r} clears bold under vanilla semantics. */
    private static boolean clearsBold(char code) {
        return (code >= '0' && code <= '9') || (code >= 'a' && code <= 'f') || code == 'r';
    }

    /** Advance in pixels: table width plus the 1px glyph gap; bold adds 1px except for spaces. */
    private static int width(char c, boolean bold) {
        int base = baseWidth(c) + 1;
        return bold && c != ' ' ? base + 1 : base;
    }

    /**
     * Vanilla DefaultFontInfo widths; small caps glyphs measure like uppercase (base 5,
     * except the narrow small i U+026A which measures like 'I', base 3); unknown glyphs
     * fall back to 4.
     */
    private static int baseWidth(char c) {
        switch (c) {
            case 'i': case '!': case ':': case ';': case '\'': case '.': case ',': case '|':
                return 1;
            case 'l': case '`':
                return 2;
            case 'I': case 't': case '[': case ']': case '"': case ' ':
                return 3;
            case 'f': case 'k': case '(': case ')': case '{': case '}': case '<': case '>':
                return 4;
            case '@':
                return 6;
            default:
                // Small caps advance ~6px like uppercase (base 5 + 1 gap), except the
                // narrow small i. 5 and 3 are reasonable approximations (exact advances
                // depend on the client's accented font bitmaps), adjustable here only.
                if (SmallCapsUtil.isSmallGlyph(c)) {
                    return c == '\u026A' ? 3 : 5;
                }
                if (c >= '!' && c <= '~') {
                    return 5;
                }
                return 4;
        }
    }

    private static boolean isCodeChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'k' && c <= 'o')
                || c == 'r' || c == 'x';
    }

    private static boolean isHex(String s, int from) {
        if (from + 6 > s.length()) {
            return false;
        }
        for (int i = from; i < from + 6; i++) {
            char c = s.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
            if (!hex) {
                return false;
            }
        }
        return true;
    }
}
