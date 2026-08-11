package com.sn.lib.papi;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Supplier;

import org.bukkit.OfflinePlayer;

/**
 * Declarative builder of a PlaceholderAPI expansion, obtained through
 * {@link SnPapi#expansion(String)}.
 *
 * <p>The built expansion reports {@code persist() = true} (it survives PlaceholderAPI
 * expansion reloads and is removed only by the context teardown) and null-checks the
 * requesting {@link OfflinePlayer} before touching a PLAYER-BOUND resolver: a null player
 * leaves those tokens unresolved.</p>
 *
 * <p><b>Global placeholders.</b> Not every placeholder describes a player. A leaderboard
 * position, a server-wide counter and an event countdown are global data that happens to be
 * exposed through a per-player API, and the callers that ask for them - a global hologram,
 * a Discord bridge, a console task, {@code /papi parse --null} - supply no player at all.
 * {@link #global(String, Supplier)} and {@link #globalPrefixed(String, Function)} declare
 * exactly that: the resolver takes no {@link OfflinePlayer}, so it cannot dereference one,
 * and it answers a null requester like any other. Everything bound through
 * {@link #placeholder(String, Function)} or {@link #prefixed(String, BiFunction)} keeps the
 * null short-circuit, so a resolver written against a non-null player stays safe.</p>
 *
 * <p>Cache-only contract: resolvers run on the main thread inside PAPI's parse, so they
 * must read precomputed in-memory state and never touch disk, database or network.</p>
 *
 * <p>Async resolvers are NOT supported by design: PlaceholderAPI's parse is synchronous
 * and main-thread by PAPI's own contract, so there is no way to await I/O inside a
 * resolver. The supported pattern is to precompute a cache (e.g. LeaderboardCache) and
 * resolve with lock-free reads. For the inverse path (composing text WITH PAPI tokens
 * from async flows: db, leaderboards, Discord) see {@code SnPapi#applyOnMain}.</p>
 */
public final class ExpansionBuilder {

    private final SnPapi papi;
    private final String identifier;
    private final Map<String, Function<OfflinePlayer, String>> exact = new LinkedHashMap<>();
    private final Map<String, BiFunction<OfflinePlayer, String, String>> prefixed = new LinkedHashMap<>();

    /** Keys of {@link #exact} and {@link #prefixed} whose resolver accepts a null requester. */
    private final Set<String> globalExact = new LinkedHashSet<>();
    private final Set<String> globalPrefixed = new LinkedHashSet<>();

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
     * exact placeholders win over prefixed ones. The resolver receives the requesting
     * player and never sees a null one: a null requester leaves the token unresolved.
     */
    public ExpansionBuilder placeholder(String param, Function<OfflinePlayer, String> resolver) {
        String key = param.toLowerCase(Locale.ROOT);
        exact.put(key, resolver);
        globalExact.remove(key);
        return this;
    }

    /**
     * Binds every {@code %<identifier>_<prefix><rest>%} to the resolver, which receives
     * the rest after the prefix as its second argument. Prefixes are tried in
     * registration order, after the exact placeholders. As with
     * {@link #placeholder(String, Function)}, a null requester leaves the token unresolved.
     */
    public ExpansionBuilder prefixed(String prefix, BiFunction<OfflinePlayer, String, String> resolver) {
        String key = prefix.toLowerCase(Locale.ROOT);
        prefixed.put(key, resolver);
        globalPrefixed.remove(key);
        return this;
    }

    /**
     * Binds {@code %<identifier>_<param>%} to a resolver that needs no player, so it answers
     * a null requester too - a global hologram, a Discord bridge, a console task. Matching
     * and precedence are those of {@link #placeholder(String, Function)}, which this shares
     * a keyspace with: the later of the two declarations for one param wins outright.
     */
    public ExpansionBuilder global(String param, Supplier<String> resolver) {
        String key = param.toLowerCase(Locale.ROOT);
        exact.put(key, player -> resolver.get());
        globalExact.add(key);
        return this;
    }

    /**
     * Prefixed counterpart of {@link #global(String, Supplier)}: binds every
     * {@code %<identifier>_<prefix><rest>%} to a resolver that receives only the rest after
     * the prefix, and answers a null requester. Shares its keyspace with
     * {@link #prefixed(String, BiFunction)}; the later declaration for one prefix wins.
     */
    public ExpansionBuilder globalPrefixed(String prefix, Function<String, String> resolver) {
        String key = prefix.toLowerCase(Locale.ROOT);
        prefixed.put(key, (player, rest) -> resolver.apply(rest));
        globalPrefixed.add(key);
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
                new LinkedHashMap<>(exact), new LinkedHashMap<>(prefixed),
                Set.copyOf(globalExact), Set.copyOf(globalPrefixed));
    }
}
