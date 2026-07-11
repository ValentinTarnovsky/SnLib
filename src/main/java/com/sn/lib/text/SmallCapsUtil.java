package com.sn.lib.text;

/**
 * Small caps glyph substitution behind the {@code [small]} prefix tag.
 *
 * <p>Pure string transform, no Bukkit. Letters {@code a-z} and {@code A-Z} map 1:1 to
 * Unicode small capital glyphs (case does not exist in small caps, so both cases map to
 * the same glyph). Accented vowels of both cases lose the accent; the enye keeps the
 * default lowercase glyph {@code U+00F1} because the small enye glyph renders poorly in
 * Minecraft. Legacy {@code &X} / {@code &#RRGGBB} codes, section-sign codes (including
 * the 14-char bungee hex sequence) and MiniMessage tags are copied verbatim, so string
 * arguments inside tags ({@code hover:show_text:'...'}) are never transformed. Every
 * mapping is one BMP char to one BMP char, so the output always has the same length as
 * the input.</p>
 */
public final class SmallCapsUtil {

    private static final char SECTION = '\u00A7';

    /**
     * Small cap glyph per letter, index = letter - 'a':
     * a=U+1D00 b=U+0299 c=U+1D04 d=U+1D05 e=U+1D07 f=U+A730 g=U+0262 h=U+029C i=U+026A
     * j=U+1D0A k=U+1D0B l=U+029F m=U+1D0D n=U+0274 o=U+1D0F p=U+1D18 q=U+01EB r=U+0280
     * s=U+A731 t=U+1D1B u=U+1D1C v=U+1D20 w=U+1D21 x=x (maps to itself) y=U+028F z=U+1D22.
     * All 26 codepoints are BMP, one Java char each, so charAt indexing is safe.
     */
    private static final String SMALL =
            "\u1D00\u0299\u1D04\u1D05\u1D07\uA730\u0262\u029C\u026A\u1D0A\u1D0B\u029F"
                    + "\u1D0D\u0274\u1D0F\u1D18\u01EB\u0280\uA731\u1D1B\u1D1C\u1D20\u1D21x\u028F\u1D22";

    private SmallCapsUtil() {
    }

    /**
     * Applies the small caps mapping to a line already stripped of its {@code [small]}
     * prefix. One pass, no regex. Legacy {@code &X} / {@code &#RRGGBB} codes, section-sign
     * codes and MiniMessage tags are copied verbatim; a {@code <} with no closing
     * {@code >} in the rest of the line is treated as a literal and still maps. Null and
     * empty pass through, and the SAME instance is returned when no character mapped.
     *
     * @param line raw line, possibly containing color codes and MiniMessage tags
     * @return line with letters replaced by small cap glyphs, same length as the input
     */
    public static String applySmallTag(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        StringBuilder out = new StringBuilder(line.length());
        boolean changed = false;
        for (int i = 0; i < line.length(); i++) {
            int code = codeLength(line, i);
            if (code > 0) {
                out.append(line, i, i + code);
                i += code - 1;
                continue;
            }
            code = sectionCodeLength(line, i);
            if (code > 0) {
                out.append(line, i, i + code);
                i += code - 1;
                continue;
            }
            char c = line.charAt(i);
            if (c == '<' && canStartTag(line, i + 1)) {
                int close = line.indexOf('>', i + 1);
                if (close > 0) {
                    out.append(line, i, close + 1);
                    i = close;
                    continue;
                }
            }
            char mapped = mapChar(c);
            if (mapped != c) {
                changed = true;
            }
            out.append(mapped);
        }
        return changed ? out.toString() : line;
    }

    /** True for the 25 non-ASCII glyphs of the dictionary; consumed by CenterUtil widths. */
    static boolean isSmallGlyph(char c) {
        switch (c) {
            case '\u1D00': case '\u0299': case '\u1D04': case '\u1D05': case '\u1D07':
            case '\uA730': case '\u0262': case '\u029C': case '\u026A': case '\u1D0A':
            case '\u1D0B': case '\u029F': case '\u1D0D': case '\u0274': case '\u1D0F':
            case '\u1D18': case '\u01EB': case '\u0280': case '\uA731': case '\u1D1B':
            case '\u1D1C': case '\u1D20': case '\u1D21': case '\u028F': case '\u1D22':
                return true;
            default:
                return false;
        }
    }

    private static char mapChar(char c) {
        if (c >= 'a' && c <= 'z') {
            return SMALL.charAt(c - 'a');
        }
        if (c >= 'A' && c <= 'Z') {
            return SMALL.charAt(c - 'A');
        }
        switch (c) {
            case '\u00E1': case '\u00C1':
                return '\u1D00';
            case '\u00E9': case '\u00C9':
                return '\u1D07';
            case '\u00ED': case '\u00CD':
                return '\u026A';
            case '\u00F3': case '\u00D3':
                return '\u1D0F';
            case '\u00FA': case '\u00DA': case '\u00FC': case '\u00DC':
                return '\u1D1C';
            case '\u00D1':
                return '\u00F1';
            default:
                return c;
        }
    }

    /** Length of the legacy code starting at {@code i}: 8 for {@code &#RRGGBB}, 2 for {@code &X}, 0 if none. */
    private static int codeLength(String s, int i) {
        if (s.charAt(i) != '&' || i + 1 >= s.length()) {
            return 0;
        }
        char next = s.charAt(i + 1);
        if (next == '#' && isHex(s, i + 2)) {
            return 8;
        }
        char c = Character.toLowerCase(next);
        if ((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'k' && c <= 'o')
                || c == 'r' || c == 'x') {
            return 2;
        }
        return 0;
    }

    /**
     * Length of the section-sign code starting at {@code i}: 14 for the full bungee hex
     * sequence, 2 for a single code, 0 if none. Programmatic callers of
     * {@link SnText#smallCaps(String)} may pass already-sectioned strings.
     */
    private static int sectionCodeLength(String s, int i) {
        if (s.charAt(i) != SECTION || i + 1 >= s.length()) {
            return 0;
        }
        char next = s.charAt(i + 1);
        if ((next == 'x' || next == 'X') && isBungeeHex(s, i + 2)) {
            return 14;
        }
        if (isCodeChar(Character.toLowerCase(next))) {
            return 2;
        }
        return 0;
    }

    /** Tag-start heuristic: names, close tags, hex colors and negated decorations. */
    private static boolean canStartTag(String s, int next) {
        if (next >= s.length()) {
            return false;
        }
        char c = s.charAt(next);
        return c == '/' || c == '#' || c == '!' || c == '_'
                || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean isCodeChar(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'k' && c <= 'o')
                || c == 'r' || c == 'x';
    }

    private static boolean isBungeeHex(String s, int from) {
        if (from + 12 > s.length()) {
            return false;
        }
        for (int i = 0; i < 6; i++) {
            if (s.charAt(from + i * 2) != SECTION || !isHexDigit(s.charAt(from + i * 2 + 1))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHex(String s, int from) {
        if (from + 6 > s.length()) {
            return false;
        }
        for (int i = from; i < from + 6; i++) {
            if (!isHexDigit(s.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    private static boolean isHexDigit(char c) {
        return (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F');
    }
}
