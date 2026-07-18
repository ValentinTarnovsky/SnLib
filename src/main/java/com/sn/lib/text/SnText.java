package com.sn.lib.text;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.minimessage.MiniMessage;
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver;
import net.kyori.adventure.text.minimessage.tag.standard.StandardTags;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;

import com.sn.lib.Ph;

/**
 * Text pipeline shared by every SnLib module.
 *
 * <p>FIXED pipeline order: locals -> PAPI -> {@code [small]} -> {@code [rgb]} -> legacy
 * color conversion -> {@code [center]}. {@code [center]} is applied over the legacy-colored string (with the
 * gradient's {@code &#RRGGBB} already interpolated) as the LAST step of the legacy phase,
 * BEFORE rendering to a {@link Component}: "last" means last in the legacy phase, never
 * after the Component render, because {@link CenterUtil} can only measure legacy strings.
 * Locals and PAPI are resolved by the caller (SnYml getters) before these methods run.</p>
 *
 * <p>Legacy {@code &X} / {@code &#RRGGBB} codes are converted to MiniMessage tags and the
 * whole string goes through MiniMessage at the end, so legacy and MiniMessage input render
 * together. A literal {@code <} that cannot start a tag is escaped as {@code \<}. A legacy
 * COLOR code ({@code &0}-{@code &f}, {@code &#RRGGBB}) resets the decorations opened by
 * earlier legacy format codes, matching vanilla and Adventure's
 * {@code LegacyComponentSerializer.legacyAmpersand()}; author-written MiniMessage tags keep
 * pure MiniMessage semantics (a MiniMessage color tag never triggers the reset).</p>
 *
 * <p>{@code [rgb]} targets titles and short lines: it emits one hex code per visible
 * character. SnLang caches statically resolved lines so that cost is paid once.</p>
 */
public final class SnText {

    private static final MiniMessage MINI = MiniMessage.miniMessage();

    /**
     * Cosmetic-only MiniMessage: colors, decorations, gradient, rainbow and reset are the
     * ONLY resolved tags. Interactive and metadata tags (click, hover, insertion, font,
     * keybind, translatable, selector, ...) are not resolved, so they never fire from
     * player-supplied text and render as inert literal text instead.
     */
    private static final MiniMessage COSMETIC = MiniMessage.builder()
            .tags(TagResolver.builder()
                    .resolver(StandardTags.color())
                    .resolver(StandardTags.decorations())
                    .resolver(StandardTags.gradient())
                    .resolver(StandardTags.rainbow())
                    .resolver(StandardTags.reset())
                    .build())
            .build();

    private static final PlainTextComponentSerializer PLAIN = PlainTextComponentSerializer.plainText();

    /** Full legacy serializer: hex colors emitted in the {@code §x§R§R...} bungee form. */
    private static final LegacyComponentSerializer LEGACY_SECTION = LegacyComponentSerializer.builder()
            .character(LegacyComponentSerializer.SECTION_CHAR)
            .hexColors()
            .useUnusualXRepeatedCharacterHexFormat()
            .build();

    private static final String CENTER_TAG = "[center]";
    private static final String RGB_TAG = "[rgb]";
    private static final String SMALL_TAG = "[small]";
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
     * Full render: section-sign safety, then {@code [small]}/{@code [rgb]}/{@code [center]}
     * prefix tags, then legacy code conversion, then MiniMessage deserialization. Null input
     * renders as the empty component.
     *
     * <p>Section safety is the FIRST step: any {@code §} code (a simple {@code §X} or the
     * {@code §x§R§R§G§G§B§B} bungee-hex form) is normalized back to the {@code &} form the
     * pipeline understands. MiniMessage 4.25 hard-rejects a raw section sign in its input,
     * so a pre-rendered or PAPI-expanded value carrying {@code §} would otherwise crash the
     * whole render; this normalization makes {@code §} content render like its {@code &}
     * equivalent. A {@code §}-free input is passed through untouched and renders exactly as
     * before.</p>
     */
    public static Component color(String s) {
        if (s == null) {
            return Component.empty();
        }
        return MINI.deserialize(legacyToMini(consumeCenterMark(applyPrefixTags(normalizeSectionSigns(s)))));
    }

    /** MiniMessage-only render, no prefix tags and no legacy conversion. */
    public static Component mini(String s) {
        return s == null ? Component.empty() : MINI.deserialize(s);
    }

    /**
     * Same pipeline as {@link #color(String)} but only the COSMETIC MiniMessage subset is
     * honored: colors, decorations, gradient, rainbow and reset. Interactive and metadata
     * tags (click, hover, insertion, font, keybind, translatable, selector, ...) are left
     * unresolved and render as inert literal text, so this is the safe render for
     * player-supplied styled text vetted through {@link StylePolicy}. Null input renders as
     * the empty component.
     */
    public static Component cosmetic(String s) {
        if (s == null) {
            return Component.empty();
        }
        return COSMETIC.deserialize(legacyToMini(consumeCenterMark(applyPrefixTags(normalizeSectionSigns(s)))));
    }

    /**
     * Visible text only: every form of styling (legacy {@code &}/{@code §} codes, hex,
     * MiniMessage tags, gradients) is removed and the fully rendered glyphs are returned.
     * Null in, null out.
     */
    public static String plain(String s) {
        if (s == null) {
            return null;
        }
        return PLAIN.serialize(color(s));
    }

    /** Codepoint count of {@link #plain(String)}; null and empty count as zero. */
    public static int visibleLength(String s) {
        if (s == null || s.isEmpty()) {
            return 0;
        }
        String plain = plain(s);
        return plain.codePointCount(0, plain.length());
    }

    /**
     * FULL render serialized to section-sign codes: legacy, hex, MiniMessage and gradients
     * are all resolved to a {@link Component} first (so a gradient or {@code <gradient>} tag
     * becomes one hex code per glyph) and then serialized through
     * {@link LegacyComponentSerializer} with hex in the {@code §x§R§R...} bungee form. For
     * PAPI and legacy string sinks that cannot take a Component. Null in, null out.
     */
    public static String section(String s) {
        if (s == null) {
            return null;
        }
        return LEGACY_SECTION.serialize(color(s));
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
     * codes, so PAPI-colored values survive the MiniMessage conversion. Delegates to the
     * shared section-safety normalizer; the same conversion runs as the first step of
     * {@link #color(String)}.
     */
    public static String normalizePapiOutput(String s) {
        return normalizeSectionSigns(s);
    }

    /**
     * Shared section-safety normalizer: rewrites {@code §x§R§R§G§G§B§B} bungee-hex sequences
     * to {@code &#RRGGBB} and simple {@code §X} codes to {@code &X}. A {@code §}-free string
     * (the common case) is returned unchanged, by identity.
     */
    private static String normalizeSectionSigns(String s) {
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
     * Programmatic small caps transform (scoreboards, tab, names) without the
     * {@code [small]} tag: delegates to {@link SmallCapsUtil#applySmallTag(String)}. The
     * mapping is 1:1 char to char; legacy color codes, section-sign sequences and
     * MiniMessage tags are skipped verbatim. Null and empty pass through, and the SAME
     * instance is returned when nothing changes.
     */
    public static String smallCaps(String s) {
        return SmallCapsUtil.applySmallTag(s);
    }

    /**
     * Consumes {@code [center]}, {@code [rgb]} and {@code [small]} prefix tags,
     * case-insensitive and in any order, at the start of the line. Fixed internal
     * application order: {@code [small]} runs BEFORE {@code [rgb]} so the gradient colors
     * the final glyphs and the small pass operates on the short string (not on the string
     * inflated 9x by the gradient hex codes); the gradient's visible count is unchanged
     * because the small mapping is 1:1 and never touches spaces, so every tag permutation
     * renders identically. {@code [small]} also runs before {@code [center]} so centering
     * measures the final glyphs: {@code [center]} is re-emitted as a single normalized
     * leading mark consumed by the final legacy phase.
     */
    public static String applyPrefixTags(String line) {
        if (line == null) {
            return null;
        }
        boolean center = false;
        boolean rgb = false;
        boolean small = false;
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
            } else if (rest.regionMatches(true, 0, SMALL_TAG, 0, SMALL_TAG.length())) {
                small = true;
                rest = rest.substring(SMALL_TAG.length());
                consumed = true;
            }
        }
        if (small) {
            rest = SmallCapsUtil.applySmallTag(rest);
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
     *
     * <p>A legacy COLOR code ({@code &0}-{@code &f}, {@code &#RRGGBB}) resets the decorations
     * opened by earlier legacy format codes ({@code &k}-{@code &o}): the color tag is emitted
     * and every still-active legacy decoration is negated right after it (e.g. {@code &l&c}
     * becomes {@code <bold><red><!bold>}). MiniMessage color tags do not close decorations on
     * their own, so without this the bold from a legacy {@code &l} would bleed across later
     * legacy colors. Only decorations opened by legacy codes enter the tracking set, so
     * author-written MiniMessage tags keep pure MiniMessage semantics. {@code &r} maps to
     * {@code <reset>} and drops the whole set.</p>
     */
    private static String legacyToMini(String s) {
        StringBuilder out = new StringBuilder(s.length() + 16);
        Set<String> legacyDecorations = new LinkedHashSet<>();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '&' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '#' && isHex(s, i + 2)) {
                    out.append("<#").append(s, i + 2, i + 8).append('>');
                    negateLegacyDecorations(out, legacyDecorations);
                    i += 7;
                    continue;
                }
                char code = Character.toLowerCase(next);
                String tag = MINI_TAGS.get(code);
                if (tag != null) {
                    if (code == 'r') {
                        out.append('<').append(tag).append('>');
                        legacyDecorations.clear();
                    } else if (isLegacyFormatCode(code)) {
                        out.append('<').append(tag).append('>');
                        legacyDecorations.add(tag);
                    } else {
                        out.append('<').append(tag).append('>');
                        negateLegacyDecorations(out, legacyDecorations);
                    }
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

    /**
     * Emits a MiniMessage negation tag ({@code <!name>}) for every decoration opened by a
     * legacy format code and clears the set, so a following legacy color starts unformatted.
     */
    private static void negateLegacyDecorations(StringBuilder out, Set<String> decorations) {
        if (decorations.isEmpty()) {
            return;
        }
        for (String decoration : decorations) {
            out.append("<!").append(decoration).append('>');
        }
        decorations.clear();
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

    /** Legacy decoration codes {@code &k}-{@code &o}: obfuscated, bold, strike, underline, italic. */
    private static boolean isLegacyFormatCode(char c) {
        return c >= 'k' && c <= 'o';
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
