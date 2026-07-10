package com.sn.lib.text;

/**
 * Character-by-character RGB gradient behind the {@code [rgb]} prefix tag.
 *
 * <p>Pure string transform, no Bukkit. Seven color anchors are interpolated across the
 * visible characters of a line, emitting one {@code &#RRGGBB} code per character.
 * Pre-existing COLOR codes ({@code &0}-{@code &f}, {@code &#RRGGBB}) are discarded because
 * the gradient overrides them; FORMAT codes ({@code &l &o &n &m &k}) accumulate and are
 * re-applied after every emitted hex; {@code &r} resets the accumulated format. Spaces are
 * appended as-is and do not consume a gradient position.</p>
 */
public final class RgbGradientUtil {

    /** Seven anchors: index 0 colors the first visible character, index 6 the last. */
    private static final int[] ANCHORS = {
            0xF300F3, 0x5555FF, 0x55FFFF, 0x55FF55, 0xFCFF21, 0xFF9B00, 0xFF5327
    };

    private static final int SEGMENTS = ANCHORS.length - 1;

    private RgbGradientUtil() {
    }

    /**
     * Applies the gradient to a line already stripped of its {@code [rgb]} prefix.
     *
     * <p>With {@code n} visible non-space characters, character {@code i} gets the color at
     * {@code t = i / (n - 1)} over the anchor chain ({@code t = 0} for {@code n <= 1}), so
     * the first character is exactly {@code ANCHORS[0]} and the last exactly
     * {@code ANCHORS[6]}.</p>
     *
     * @param line raw line, possibly containing legacy codes
     * @return line with one interpolated {@code &#RRGGBB} per visible character
     */
    public static String applyRgbTag(String line) {
        if (line == null || line.isEmpty()) {
            return line;
        }
        int visibles = countVisibleNonSpace(line);
        StringBuilder out = new StringBuilder(line.length() * 9);
        StringBuilder format = new StringBuilder(4);
        int position = 0;
        for (int i = 0; i < line.length(); i++) {
            int code = codeLength(line, i);
            if (code > 0) {
                if (code == 2) {
                    char c = Character.toLowerCase(line.charAt(i + 1));
                    if (isFormatChar(c)) {
                        if (format.indexOf("&" + c) < 0) {
                            format.append('&').append(c);
                        }
                    } else if (c == 'r') {
                        format.setLength(0);
                    }
                }
                i += code - 1;
                continue;
            }
            char c = line.charAt(i);
            if (c == ' ') {
                out.append(c);
                continue;
            }
            out.append(hexAt(position, visibles)).append(format).append(c);
            position++;
        }
        return out.toString();
    }

    private static int countVisibleNonSpace(String line) {
        int count = 0;
        for (int i = 0; i < line.length(); i++) {
            int code = codeLength(line, i);
            if (code > 0) {
                i += code - 1;
                continue;
            }
            if (line.charAt(i) != ' ') {
                count++;
            }
        }
        return count;
    }

    private static String hexAt(int index, int total) {
        double t = total <= 1 ? 0.0 : index / (double) (total - 1);
        double segment = t * SEGMENTS;
        int idx = Math.min((int) segment, SEGMENTS - 1);
        double fraction = segment - idx;
        int from = ANCHORS[idx];
        int to = ANCHORS[idx + 1];
        int r = lerp((from >> 16) & 0xFF, (to >> 16) & 0xFF, fraction);
        int g = lerp((from >> 8) & 0xFF, (to >> 8) & 0xFF, fraction);
        int b = lerp(from & 0xFF, to & 0xFF, fraction);
        return String.format("&#%02X%02X%02X", r, g, b);
    }

    private static int lerp(int from, int to, double fraction) {
        return (int) Math.round(from + (to - from) * fraction);
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

    private static boolean isFormatChar(char c) {
        return c >= 'k' && c <= 'o';
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
