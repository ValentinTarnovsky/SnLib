package com.sn.lib.papi;

import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.function.BiFunction;
import java.util.function.Function;

import org.bukkit.OfflinePlayer;

/**
 * Declarative builder of a PlaceholderAPI expansion, obtained through
 * {@link SnPapi#expansion(String)}.
 *
 * <p>The built expansion reports {@code persist() = true} (it survives PlaceholderAPI
 * expansion reloads and is removed only by the context teardown) and null-checks the
 * requesting {@link OfflinePlayer} before touching any resolver: a null player leaves
 * the token unresolved.</p>
 *
 * <p>Cache-only contract: resolvers run on the main thread inside PAPI's parse, so they
 * must read precomputed in-memory state and never touch disk, database or network.</p>
 */
public final class ExpansionBuilder {

    private final SnPapi papi;
    private final String identifier;
    private final Map<String, Function<OfflinePlayer, String>> exact = new LinkedHashMap<>();
    private final Map<String, BiFunction<OfflinePlayer, String, String>> prefixed = new LinkedHashMap<>();

    private String author;
    private String version;

    ExpansionBuilder(SnPapi papi, String identifier, String author, String version) {
        this.papi = papi;
        this.identifier = identifier;
        this.author = author;
        this.version = version;
    }

    /**
     * Binds {@code %<identifier>_<param>%} to the resolver. Matching is case-insensitive;
     * exact placeholders win over prefixed ones.
     */
    public ExpansionBuilder placeholder(String param, Function<OfflinePlayer, String> resolver) {
        exact.put(param.toLowerCase(Locale.ROOT), resolver);
        return this;
    }

    /**
     * Binds every {@code %<identifier>_<prefix><rest>%} to the resolver, which receives
     * the rest after the prefix as its second argument. Prefixes are tried in
     * registration order, after the exact placeholders.
     */
    public ExpansionBuilder prefixed(String prefix, BiFunction<OfflinePlayer, String, String> resolver) {
        prefixed.put(prefix.toLowerCase(Locale.ROOT), resolver);
        return this;
    }

    /** Expansion author reported to PlaceholderAPI; defaults to the plugin's authors. */
    public ExpansionBuilder author(String author) {
        this.author = author;
        return this;
    }

    /** Expansion version reported to PlaceholderAPI; defaults to the plugin's version. */
    public ExpansionBuilder version(String version) {
        this.version = version;
        return this;
    }

    /**
     * Registers the expansion with PlaceholderAPI, unregistering any previous one under
     * the same identifier first (lookup-before-register: a second enable of the consumer
     * never fails). The registered instance is tracked in the context for unregistration
     * on shutdown. Returns false with a WARN when PlaceholderAPI is absent or the
     * registration is rejected.
     */
    public boolean register() {
        return papi.registerExpansion(identifier, author, version,
                new LinkedHashMap<>(exact), new LinkedHashMap<>(prefixed));
    }
}
