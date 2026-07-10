package com.sn.lib.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;

import com.sn.lib.Ph;

/**
 * Text pipeline shared by every SnLib module.
 *
 * <p>FIXED pipeline order: locals -> PAPI -> {@code [rgb]} -> legacy color conversion ->
 * {@code [center]}. {@code [center]} is applied over the legacy-colored string (with the
 * gradient's {@code &#RRGGBB} already interpolated) as the LAST step of the legacy phase,
 * BEFORE rendering to a {@link Component}: "last" means last in the legacy phase, never
 * after the Component render, because {@link CenterUtil} can only measure legacy strings.
 * Locals and PAPI are resolved by the caller (SnYml getters) before these methods run.</p>
 *
 * <p>Legacy {@code &X} / {@code &#RRGGBB} codes are converted to MiniMessage tags and the
 * whole string goes through MiniMessage at the end, so legacy and MiniMessage input render
 * together. A literal {@code <} that cannot start a tag is escaped as {@code \<}.</p>
 *
 * <p>{@code [rgb]} targets titles and short lines: it emits one hex code per visible
 * character. SnLang caches statically resolved lines so that cost is paid once.</p>
 */
public final class SnText {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    private static final String CENTER_TAG = "[center]";
    private static final String RGB_TAG = "[rgb]";
    private static final char SECTION = (char) 0xA7;

    private static final Map<Character, String> MINI_TAGS = Map.ofEntries(
            Map.entry('0', "black"), Map.entry('1', "dark_blue"), Map.entry('2', "dark_green"),
            Map.entry('3', "dark_aqua"), Map.entry('4', "dark_red"), Map.entry('5', "dark_purple"),
            Map.entry('6', "gold"), Map.entry('7', "gray"), Map.entry('8', "dark_gray"),
            Map.entry('9', "blue"), Map.entry('a', "green"), Map.entry('b', "aqua"),
            Map.entry('c', "red"), Map.entry('d', "light_purple"), Map.entry('e', "yellow"),
            Map.entry('f', "white"), Map.entry('k', "obfuscated"), Map.entry('l', "bold"),
            Map.entry('m', "strikethrough"), Map.entry('n', "underlined"), Map.entry('o', "italic"),
            Map.entry('r', "reset"));

    private SnText() {
    }

    /**
     * Full render: {@code [rgb]}/{@code [center]} prefix tags, then legacy code conversion,
     * then MiniMessage deserialization. Null input renders as the empty component.
     */
    public static Component color(String s) {
        if (s == null) {
            return Component.empty();
        }
        return MINI.deserialize(legacyToMini(consumeCenterMark(applyPrefixTags(s))));
    }

    /** MiniMessage-only render, no prefix tags and no legacy conversion. */
    public static Component mini(String s) {
        return s == null ? Component.empty() : MINI.deserialize(s);
    }

    /**
     * Same legacy phase as {@link #color(String)} but the output stays a legacy string with
     * section-sign codes ({@code &#RRGGBB} becomes the bungee hex sequence). For API that
     * still requires legacy strings; MiniMessage tags are left untouched.
     */
    public static String colorLegacy(String s) {
        if (s == null) {
            return null;
        }
        return toSectionCodes(consumeCenterMark(applyPrefixTags(s)));
    }

    /**
     * Converts PlaceholderAPI output back to the {@code &} form the pipeline understands:
     * bungee hex sequences become {@code &#RRGGBB} and section-sign codes become {@code &}
     * codes, so PAPI-colored values survive the MiniMessage conversion.
     */
    public static String normalizePapiOutput(String s) {
        if (s == null || s.indexOf(SECTION) < 0) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == SECTION && i + 1 < s.length()) {
                char next = Character.toLowerCase(s.charAt(i + 1));
                if (next == 'x' && isBungeeHex(s, i + 2)) {
                    out.append("&#");
                    for (int j = 0; j < 6; j++) {
                        out.append(s.charAt(i + 3 + j * 2));
                    }
                    i += 13;
                    continue;
                }
                if (isCodeChar(next)) {
                    out.append('&').append(next);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
    }

    /** Resolves local placeholders from pairs; see {@link #applyLocals(String, Function)}. */
    public static String applyLocals(String s, Ph... phs) {
        if (s == null || s.isEmpty() || phs == null || phs.length == 0) {
            return s;
        }
        Map<String, String> byKey = new HashMap<>(phs.length * 2);
        for (Ph ph : phs) {
            if (ph != null) {
                byKey.put(ph.key(), ph.value());
            }
        }
        return applyLocals(s, byKey::get);
    }

    /**
     * One-pass scanner over {@code %key%} and {@code {key}} tokens. A null from the resolver
     * leaves the token intact, so unresolved PAPI tokens survive untouched; replacement
     * values are not re-scanned.
     */
    public static String applyLocals(String s, Function<String, String> resolver) {
        if (s == null || s.isEmpty() || resolver == null) {
            return s;
        }
        StringBuilder out = new StringBuilder(s.length());
        int i = 0;
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '%' || c == '{') {
                int end = s.indexOf(c == '%' ? '%' : '}', i + 1);
                if (end > i + 1) {
                    String value = resolver.apply(s.substring(i + 1, end));
                    if (value != null) {
                        out.append(value);
                        i = end + 1;
                        continue;
                    }
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    /** Applies {@link #color(String)} to every line; null lists yield an empty list. */
    public static List<Component> colorList(List<String> lines) {
        if (lines == null || lines.isEmpty()) {
            return new ArrayList<>();
        }
        List<Component> out = new ArrayList<>(lines.size());
        for (String line : lines) {
            out.add(color(line));
        }
        return out;
    }

    /**
     * Consumes {@code [center]} and {@code [rgb]} prefix tags, in any order, at the start of
     * the line. {@code [rgb]} is applied immediately (gradient interpolated into
     * {@code &#RRGGBB} codes over the remaining content); {@code [center]} is re-emitted as
     * a single normalized leading mark consumed by the final legacy phase.
     */
    public static String applyPrefixTags(String line) {
        if (line == null) {
            return null;
        }
        boolean center = false;
        boolean rgb = false;
        String rest = line;
        boolean consumed = true;
        while (consumed) {
            consumed = false;
            if (rest.regionMatches(true, 0, CENTER_TAG, 0, CENTER_TAG.length())) {
                center = true;
                rest = rest.substring(CENTER_TAG.length());
                consumed = true;
            } else if (rest.regionMatches(true, 0, RGB_TAG, 0, RGB_TAG.length())) {
                rgb = true;
                rest = rest.substring(RGB_TAG.length());
                consumed = true;
            }
        }
        if (rgb) {
            rest = RgbGradientUtil.applyRgbTag(rest);
        }
        return center ? CENTER_TAG + rest : rest;
    }

    private static String consumeCenterMark(String line) {
        if (line.startsWith(CENTER_TAG)) {
            return CenterUtil.center(line.substring(CENTER_TAG.length()));
        }
        return line;
    }

    /**
     * Legacy to MiniMessage: {@code &#RRGGBB} becomes {@code <#RRGGBB>}, {@code &X} becomes
     * its named tag, and a {@code <} that cannot start a tag is escaped.
     */
    private static String legacyToMini(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '#' && isHex(s, i + 2)) {
                    out.append("<#").append(s, i + 2, i + 8).append('>');
                    i += 7;
                    continue;
                }
                String tag = MINI_TAGS.get(Character.toLowerCase(next));
                if (tag != null) {
                    out.append('<').append(tag).append('>');
                    i++;
                    continue;
                }
            }
            if (c == '<' && !canStartTag(s, i + 1)) {
                out.append('\\');
            }
            out.append(c);
        }
        return out.toString();
    }

    private static String toSectionCodes(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '#' && isHex(s, i + 2)) {
                    out.append(SECTION).append('x');
                    for (int j = i + 2; j < i + 8; j++) {
                        out.append(SECTION).append(Character.toLowerCase(s.charAt(j)));
                    }
                    i += 7;
                    continue;
                }
                char code = Character.toLowerCase(next);
                if (isCodeChar(code)) {
                    out.append(SECTION).append(code);
                    i++;
                    continue;
                }
            }
            out.append(c);
        }
        return out.toString();
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
