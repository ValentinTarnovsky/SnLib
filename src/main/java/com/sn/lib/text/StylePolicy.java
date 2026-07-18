package com.sn.lib.text;

import java.util.ArrayList;
import java.util.Collections;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

import org.bukkit.configuration.ConfigurationSection;
import org.jetbrains.annotations.Nullable;

/**
 * Reusable gate for player-supplied styled text: which styling forms a piece of input is
 * allowed to carry, and what to do when it carries a disallowed one.
 *
 * <p>Capabilities are grouped by input form. The legacy {@code &} codes map to the fine
 * grained capabilities: {@code &0}-{@code &f} to {@link Capability#LEGACY_COLOR},
 * {@code &#RRGGBB} to {@link Capability#HEX}, and {@code &l &o &n &m &k} to
 * {@link Capability#BOLD}/{@link Capability#ITALIC}/{@link Capability#UNDERLINE}/
 * {@link Capability#STRIKETHROUGH}/{@link Capability#OBFUSCATED}. MiniMessage tags are
 * governed by {@link Capability#MINIMESSAGE} as a whole (a {@code <red>} color tag or a
 * {@code <bold>} decoration tag is MiniMessage usage, not legacy color or bold usage), and
 * gradient forms - the {@code [rgb]} prefix tag and the MiniMessage {@code <gradient>} /
 * {@code <rainbow>} tags - are governed specifically by {@link Capability#GRADIENT}.</p>
 *
 * <p>SAFETY: only the COSMETIC MiniMessage subset can ever be acceptable (colors,
 * decorations, gradient, rainbow, reset). Interactive and metadata tags - click, hover,
 * insertion, font, keybind, translatable, selector and anything else - are a
 * {@link Capability#MINIMESSAGE} violation ALWAYS, even when {@code MINIMESSAGE} is allowed.
 * Render vetted text through {@link SnText#cosmetic(String)}, which resolves only that same
 * cosmetic subset.</p>
 *
 * <p>A disabled policy (or any policy that allows nothing) is plain-text-only: every form of
 * styling is a violation.</p>
 */
public final class StylePolicy {

    /** The styling forms a policy can allow or disallow. */
    public enum Capability {
        /** Legacy color codes {@code &0}-{@code &f}. */
        LEGACY_COLOR,
        /** Legacy hex color {@code &#RRGGBB}. */
        HEX,
        /** Legacy bold {@code &l}. */
        BOLD,
        /** Legacy italic {@code &o}. */
        ITALIC,
        /** Legacy underline {@code &n}. */
        UNDERLINE,
        /** Legacy strikethrough {@code &m}. */
        STRIKETHROUGH,
        /** Legacy obfuscated {@code &k}. */
        OBFUSCATED,
        /** Cosmetic MiniMessage tags (colors and decorations); non-cosmetic tags are never allowed. */
        MINIMESSAGE,
        /** Gradient forms: the {@code [rgb]} prefix tag and MiniMessage {@code <gradient>}/{@code <rainbow>}. */
        GRADIENT
    }

    /** What to do with an input that carries a disallowed capability. */
    public enum OnDisallowed {
        /** Drop all styling and keep the plain visible text. */
        REJECT,
        /** Remove only the disallowed styling, keep the allowed styling and the text. */
        STRIP
    }

    private static final Set<String> COLOR_NAMES = Set.of(
            "black", "dark_blue", "dark_green", "dark_aqua", "dark_red", "dark_purple",
            "gold", "gray", "grey", "dark_gray", "dark_grey", "blue", "green", "aqua",
            "red", "light_purple", "yellow", "white", "color", "colour", "c");

    private static final Set<String> DECORATION_NAMES = Set.of(
            "bold", "b", "italic", "i", "em", "underlined", "u",
            "strikethrough", "st", "obfuscated", "obf");

    private final boolean enabled;
    private final EnumSet<Capability> allowed;
    private final OnDisallowed onDisallowed;

    private StylePolicy(boolean enabled, EnumSet<Capability> allowed, OnDisallowed onDisallowed) {
        this.enabled = enabled;
        this.allowed = EnumSet.noneOf(Capability.class);
        if (enabled) {
            this.allowed.addAll(allowed);
        }
        this.onDisallowed = onDisallowed;
    }

    /**
     * Reads the policy from the {@code path} subsection of {@code section}. Recognized keys:
     * {@code enabled} (boolean, default true), {@code allow-legacy-colors},
     * {@code allow-hex}, {@code allow-bold}, {@code allow-italic}, {@code allow-underline},
     * {@code allow-strikethrough}, {@code allow-obfuscated}, {@code allow-minimessage},
     * {@code allow-gradient} (booleans, each default false - deny by default), and
     * {@code on-disallowed} ({@code reject} or {@code strip}, default {@code reject}). A null
     * section, a missing subsection or {@code enabled: false} yields a {@link #disabled()}
     * plain-text-only policy.
     */
    public static StylePolicy fromConfig(@Nullable ConfigurationSection section, String path) {
        ConfigurationSection sub = section == null ? null : section.getConfigurationSection(path);
        if (sub == null || !sub.getBoolean("enabled", true)) {
            return disabled();
        }
        EnumSet<Capability> allowed = EnumSet.noneOf(Capability.class);
        addIf(sub, "allow-legacy-colors", allowed, Capability.LEGACY_COLOR);
        addIf(sub, "allow-hex", allowed, Capability.HEX);
        addIf(sub, "allow-bold", allowed, Capability.BOLD);
        addIf(sub, "allow-italic", allowed, Capability.ITALIC);
        addIf(sub, "allow-underline", allowed, Capability.UNDERLINE);
        addIf(sub, "allow-strikethrough", allowed, Capability.STRIKETHROUGH);
        addIf(sub, "allow-obfuscated", allowed, Capability.OBFUSCATED);
        addIf(sub, "allow-minimessage", allowed, Capability.MINIMESSAGE);
        addIf(sub, "allow-gradient", allowed, Capability.GRADIENT);
        return new StylePolicy(true, allowed, parseOnDisallowed(sub.getString("on-disallowed", "reject")));
    }

    /** A disabled policy: plain text only, any styling is a violation. */
    public static StylePolicy disabled() {
        return new StylePolicy(false, EnumSet.noneOf(Capability.class), OnDisallowed.REJECT);
    }

    /** Programmatic policy builder; starts enabled, allowing nothing, rejecting on disallowed. */
    public static Builder builder() {
        return new Builder();
    }

    /** Whether the policy is enabled; a disabled policy allows no styling at all. */
    public boolean isEnabled() {
        return enabled;
    }

    /** Whether the policy allows no styling, so only plain text passes without violation. */
    public boolean isPlainOnly() {
        return allowed.isEmpty();
    }

    /** Whether the given capability is allowed by this policy. */
    public boolean isAllowed(Capability capability) {
        return capability != null && allowed.contains(capability);
    }

    /** Immutable view of the allowed capabilities. */
    public Set<Capability> allowed() {
        return Collections.unmodifiableSet(
                allowed.isEmpty() ? EnumSet.noneOf(Capability.class) : EnumSet.copyOf(allowed));
    }

    /** The configured disallowed-handling mode. */
    public OnDisallowed onDisallowed() {
        return onDisallowed;
    }

    /**
     * The disallowed capabilities the input actually uses, in {@link Capability} declaration
     * order; empty when the input is acceptable. A non-cosmetic MiniMessage tag always
     * contributes {@link Capability#MINIMESSAGE}, even when {@code MINIMESSAGE} is allowed.
     */
    public List<Capability> violations(String input) {
        List<Capability> out = new ArrayList<>();
        if (input == null || input.isEmpty()) {
            return out;
        }
        Usage u = analyze(input);
        if (u.legacyColor && !allowed.contains(Capability.LEGACY_COLOR)) {
            out.add(Capability.LEGACY_COLOR);
        }
        if (u.hex && !allowed.contains(Capability.HEX)) {
            out.add(Capability.HEX);
        }
        if (u.bold && !allowed.contains(Capability.BOLD)) {
            out.add(Capability.BOLD);
        }
        if (u.italic && !allowed.contains(Capability.ITALIC)) {
            out.add(Capability.ITALIC);
        }
        if (u.underline && !allowed.contains(Capability.UNDERLINE)) {
            out.add(Capability.UNDERLINE);
        }
        if (u.strike && !allowed.contains(Capability.STRIKETHROUGH)) {
            out.add(Capability.STRIKETHROUGH);
        }
        if (u.obfuscated && !allowed.contains(Capability.OBFUSCATED)) {
            out.add(Capability.OBFUSCATED);
        }
        if (u.nonCosmeticMini || (u.cosmeticMini && !allowed.contains(Capability.MINIMESSAGE))) {
            out.add(Capability.MINIMESSAGE);
        }
        if (u.gradient && !allowed.contains(Capability.GRADIENT)) {
            out.add(Capability.GRADIENT);
        }
        return out;
    }

    /** Whether the input carries no disallowed capability. */
    public boolean accepts(String input) {
        return violations(input).isEmpty();
    }

    /**
     * Sanitizes the input per {@link #onDisallowed()}: an acceptable input is returned
     * unchanged; otherwise {@code STRIP} removes only the disallowed styling
     * ({@link #strip(String)}) and {@code REJECT} drops all styling to plain visible text
     * ({@link SnText#plain(String)}). Null in, null out.
     */
    public String apply(String input) {
        if (input == null) {
            return null;
        }
        if (violations(input).isEmpty()) {
            return input;
        }
        return onDisallowed == OnDisallowed.STRIP ? strip(input) : SnText.plain(input);
    }

    /**
     * Removes the disallowed styling while keeping the allowed styling and all visible text.
     * Section-sign codes are normalized to the {@code &} form first. Non-cosmetic MiniMessage
     * tags are always removed; the reset code {@code &r} and {@code <reset>} tag are always
     * kept. Null in, null out.
     */
    public String strip(String input) {
        if (input == null) {
            return null;
        }
        String s = SnText.normalizePapiOutput(input);
        StringBuilder out = new StringBuilder(s.length());
        int i = appendLeadingTags(s, out);
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '<') {
                out.append('\\').append('<');
                i += 2;
                continue;
            }
            if (c == '&' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '#' && isHex(s, i + 2)) {
                    if (allowed.contains(Capability.HEX)) {
                        out.append(s, i, i + 8);
                    }
                    i += 8;
                    continue;
                }
                char code = Character.toLowerCase(next);
                if (code == 'r') {
                    out.append(s, i, i + 2);
                    i += 2;
                    continue;
                }
                Capability cap = codeCapability(code);
                if (cap != null) {
                    if (allowed.contains(cap)) {
                        out.append(s, i, i + 2);
                    }
                    i += 2;
                    continue;
                }
                out.append(c);
                i++;
                continue;
            }
            if (c == '<') {
                int end = tagEnd(s, i);
                if (end > 0) {
                    if (keepTag(classifyTag(s.substring(i + 1, end - 1)))) {
                        out.append(s, i, end);
                    }
                    i = end;
                    continue;
                }
            }
            out.append(c);
            i++;
        }
        return out.toString();
    }

    private boolean keepTag(TagKind kind) {
        return switch (kind) {
            case RESET -> true;
            case COSMETIC -> allowed.contains(Capability.MINIMESSAGE);
            case GRADIENT -> allowed.contains(Capability.GRADIENT);
            case NON_COSMETIC -> false;
        };
    }

    /** Appends the leading prefix-tag run, dropping {@code [rgb]} when gradient is disallowed. */
    private int appendLeadingTags(String s, StringBuilder out) {
        int i = 0;
        while (true) {
            if (regionTag(s, i, "[center]")) {
                out.append(s, i, i + 8);
                i += 8;
            } else if (regionTag(s, i, "[rgb]")) {
                if (allowed.contains(Capability.GRADIENT)) {
                    out.append(s, i, i + 5);
                }
                i += 5;
            } else if (regionTag(s, i, "[small]")) {
                out.append(s, i, i + 7);
                i += 7;
            } else {
                return i;
            }
        }
    }

    // ------------------------------------------------------------------
    // Detection
    // ------------------------------------------------------------------

    /** Which styling forms an input uses, split for the MiniMessage cosmetic-subset rule. */
    private static final class Usage {
        private boolean legacyColor;
        private boolean hex;
        private boolean bold;
        private boolean italic;
        private boolean underline;
        private boolean strike;
        private boolean obfuscated;
        private boolean gradient;
        private boolean cosmeticMini;
        private boolean nonCosmeticMini;
    }

    private static Usage analyze(String input) {
        Usage u = new Usage();
        String s = SnText.normalizePapiOutput(input);
        boolean[] rgb = {false};
        int i = consumeLeadingTags(s, rgb);
        if (rgb[0]) {
            u.gradient = true;
        }
        while (i < s.length()) {
            char c = s.charAt(i);
            if (c == '\\' && i + 1 < s.length() && s.charAt(i + 1) == '<') {
                i += 2;
                continue;
            }
            if (c == '&' && i + 1 < s.length()) {
                char next = s.charAt(i + 1);
                if (next == '#' && isHex(s, i + 2)) {
                    u.hex = true;
                    i += 8;
                    continue;
                }
                char code = Character.toLowerCase(next);
                if (code == 'r') {
                    i += 2;
                    continue;
                }
                Capability cap = codeCapability(code);
                if (cap != null) {
                    markUsage(u, cap);
                    i += 2;
                    continue;
                }
            }
            if (c == '<') {
                int end = tagEnd(s, i);
                if (end > 0) {
                    switch (classifyTag(s.substring(i + 1, end - 1))) {
                        case RESET -> { }
                        case COSMETIC -> u.cosmeticMini = true;
                        case GRADIENT -> u.gradient = true;
                        case NON_COSMETIC -> u.nonCosmeticMini = true;
                    }
                    i = end;
                    continue;
                }
            }
            i++;
        }
        return u;
    }

    private static void markUsage(Usage u, Capability cap) {
        switch (cap) {
            case LEGACY_COLOR -> u.legacyColor = true;
            case BOLD -> u.bold = true;
            case ITALIC -> u.italic = true;
            case UNDERLINE -> u.underline = true;
            case STRIKETHROUGH -> u.strike = true;
            case OBFUSCATED -> u.obfuscated = true;
            default -> { }
        }
    }

    private enum TagKind {
        RESET,
        COSMETIC,
        GRADIENT,
        NON_COSMETIC
    }

    /** Classifies a MiniMessage tag by the name inside the angle brackets. */
    private static TagKind classifyTag(String inner) {
        String name = inner;
        if (!name.isEmpty() && name.charAt(0) == '/') {
            name = name.substring(1);
        }
        if (!name.isEmpty() && name.charAt(0) == '!') {
            name = name.substring(1);
        }
        int colon = name.indexOf(':');
        if (colon >= 0) {
            name = name.substring(0, colon);
        }
        name = name.toLowerCase(Locale.ROOT);
        if (name.isEmpty()) {
            return TagKind.NON_COSMETIC;
        }
        if (name.equals("reset")) {
            return TagKind.RESET;
        }
        if (name.equals("gradient") || name.equals("rainbow")) {
            return TagKind.GRADIENT;
        }
        if (name.charAt(0) == '#' || COLOR_NAMES.contains(name) || DECORATION_NAMES.contains(name)) {
            return TagKind.COSMETIC;
        }
        return TagKind.NON_COSMETIC;
    }

    /** Capability of a simple legacy code, or null for reset {@code r} and unknown codes. */
    private static Capability codeCapability(char code) {
        if ((code >= '0' && code <= '9') || (code >= 'a' && code <= 'f')) {
            return Capability.LEGACY_COLOR;
        }
        return switch (code) {
            case 'l' -> Capability.BOLD;
            case 'o' -> Capability.ITALIC;
            case 'n' -> Capability.UNDERLINE;
            case 'm' -> Capability.STRIKETHROUGH;
            case 'k' -> Capability.OBFUSCATED;
            default -> null;
        };
    }

    private static int consumeLeadingTags(String s, boolean[] rgb) {
        int i = 0;
        while (true) {
            if (regionTag(s, i, "[center]")) {
                i += 8;
            } else if (regionTag(s, i, "[rgb]")) {
                rgb[0] = true;
                i += 5;
            } else if (regionTag(s, i, "[small]")) {
                i += 7;
            } else {
                return i;
            }
        }
    }

    /** End index (exclusive) of the {@code <...>} tag starting at {@code i}, or -1 if none. */
    private static int tagEnd(String s, int i) {
        if (i + 1 >= s.length() || !isTagStart(s.charAt(i + 1))) {
            return -1;
        }
        int close = s.indexOf('>', i + 1);
        return close < 0 ? -1 : close + 1;
    }

    private static boolean isTagStart(char c) {
        return c == '/' || c == '#' || c == '!' || c == '_'
                || (c >= 'a' && c <= 'z') || (c >= 'A' && c <= 'Z');
    }

    private static boolean regionTag(String s, int i, String tag) {
        return s.regionMatches(true, i, tag, 0, tag.length());
    }

    private static boolean isHex(String s, int from) {
        if (from + 6 > s.length()) {
            return false;
        }
        for (int j = from; j < from + 6; j++) {
            char c = s.charAt(j);
            if (!((c >= '0' && c <= '9') || (c >= 'a' && c <= 'f') || (c >= 'A' && c <= 'F'))) {
                return false;
            }
        }
        return true;
    }

    private static void addIf(ConfigurationSection sub, String key, EnumSet<Capability> allowed,
            Capability capability) {
        if (sub.getBoolean(key, false)) {
            allowed.add(capability);
        }
    }

    private static OnDisallowed parseOnDisallowed(String raw) {
        return raw != null && raw.trim().equalsIgnoreCase("strip") ? OnDisallowed.STRIP : OnDisallowed.REJECT;
    }

    /** Programmatic {@link StylePolicy} builder. */
    public static final class Builder {

        private boolean enabled = true;
        private final EnumSet<Capability> allowed = EnumSet.noneOf(Capability.class);
        private OnDisallowed onDisallowed = OnDisallowed.REJECT;

        private Builder() {
        }

        /** Sets whether the policy is enabled; a disabled policy allows no styling. */
        public Builder enabled(boolean enabled) {
            this.enabled = enabled;
            return this;
        }

        /** Allows the given capabilities. */
        public Builder allow(Capability... capabilities) {
            if (capabilities != null) {
                for (Capability capability : capabilities) {
                    if (capability != null) {
                        allowed.add(capability);
                    }
                }
            }
            return this;
        }

        /** Allows every capability. */
        public Builder allowAll() {
            allowed.addAll(EnumSet.allOf(Capability.class));
            return this;
        }

        /** Disallows the given capabilities. */
        public Builder disallow(Capability... capabilities) {
            if (capabilities != null) {
                for (Capability capability : capabilities) {
                    if (capability != null) {
                        allowed.remove(capability);
                    }
                }
            }
            return this;
        }

        /** Sets the disallowed-handling mode. */
        public Builder onDisallowed(OnDisallowed onDisallowed) {
            if (onDisallowed != null) {
                this.onDisallowed = onDisallowed;
            }
            return this;
        }

        /** Builds the immutable policy. */
        public StylePolicy build() {
            return new StylePolicy(enabled, allowed, onDisallowed);
        }
    }
}
