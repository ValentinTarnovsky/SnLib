package com.sn.lib.util;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.regex.Pattern;

import org.jetbrains.annotations.Nullable;

/**
 * Async name-to-UUID lookup against the Mojang profiles endpoint (no Bukkit, no NMS).
 *
 * <p>Threading contract: the returned future completes on the HTTP client's executor,
 * NEVER on the main thread; consumers hop back with
 * {@code ctx.scheduler().thenSync(future, ...)} or {@code ctx.scheduler().sync(...)}
 * (same contract as SnDb). SnLib itself never calls this internally; it complements the
 * deliberate gap of {@code Args.offlinePlayerUuid}, which stays cache-only by design.</p>
 *
 * <p>Results are cached in a bounded access-order LRU (cap {@value #CACHE_CAP}) that
 * also stores misses (204/404 as {@code Optional.empty()}) so unknown names are not
 * re-queried; transient failures (other statuses, network errors) complete the future
 * exceptionally with {@link IOException} and are NOT cached. Concurrent lookups of the
 * same name are deduplicated in-flight. Server-wide static justified: the name-to-UUID
 * mapping is content-addressed and identical for every consumer.
 * {@link #clearCache()} is invoked by the SnLib plugin teardown.</p>
 */
public final class PlayerLookup {

    private static final int CACHE_CAP = 512;
    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration REQUEST_TIMEOUT = Duration.ofSeconds(10);
    private static final String ENDPOINT = "https://api.mojang.com/users/profiles/minecraft/";
    private static final Pattern NAME_PATTERN = Pattern.compile("^[A-Za-z0-9_]{1,16}$");

    /**
     * Access-order LRU keyed by lowercase name, misses included; every access must
     * synchronize on the map itself (pattern of {@link HeadUtil}).
     */
    private static final Map<String, Optional<UUID>> CACHE =
            new LinkedHashMap<>(64, 0.75f, true) {
                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Optional<UUID>> eldest) {
                    return size() > CACHE_CAP;
                }
            };

    /** In-flight dedupe: one HTTP request per name no matter how many concurrent callers. */
    private static final ConcurrentHashMap<String, CompletableFuture<Optional<UUID>>> IN_FLIGHT =
            new ConcurrentHashMap<>();

    private static final Object CLIENT_LOCK = new Object();
    private static volatile @Nullable HttpClient client;

    private PlayerLookup() {
    }

    /**
     * Resolves the UUID of {@code name} against
     * {@code https://api.mojang.com/users/profiles/minecraft/<name>}.
     *
     * <p>Invalid names (null or not matching {@code [A-Za-z0-9_]{1,16}}) complete
     * immediately with {@code Optional.empty()} without HTTP or caching. Cache hits
     * (including cached misses) complete immediately. The future completes off the main
     * thread; it completes exceptionally with {@link IOException} on transient failures
     * (unexpected status, unparseable body, network error), which are never cached.</p>
     */
    public static CompletableFuture<Optional<UUID>> fetchUuid(String name) {
        if (!validName(name)) {
            return CompletableFuture.completedFuture(Optional.empty());
        }
        String key = name.toLowerCase(Locale.ROOT);
        synchronized (CACHE) {
            Optional<UUID> cached = CACHE.get(key);
            if (cached != null) {
                return CompletableFuture.completedFuture(cached);
            }
        }
        CompletableFuture<Optional<UUID>> future =
                IN_FLIGHT.computeIfAbsent(key, PlayerLookup::request);
        future.whenComplete((value, error) -> IN_FLIGHT.remove(key, future));
        return future;
    }

    /** Empties the LRU and releases the HTTP client; called by the SnLib plugin on disable. */
    public static void clearCache() {
        synchronized (CACHE) {
            CACHE.clear();
        }
        HttpClient current;
        synchronized (CLIENT_LOCK) {
            current = client;
            client = null;
        }
        if (current != null) {
            current.shutdown();
        }
    }

    /** True when {@code name} is a well-formed Minecraft name: {@code [A-Za-z0-9_]{1,16}}. */
    static boolean validName(@Nullable String name) {
        return name != null && NAME_PATTERN.matcher(name).matches();
    }

    /**
     * UUID from the {@code "id"} field of a Mojang profile body: requires exactly 32 hex
     * chars and inserts dashes in the 8-4-4-4-12 layout. Any other shape returns null.
     */
    static @Nullable UUID parseUuid(@Nullable String body) {
        String id = jsonString(body, "id");
        if (id == null || id.length() != 32) {
            return null;
        }
        for (int i = 0; i < id.length(); i++) {
            char c = id.charAt(i);
            boolean hex = (c >= '0' && c <= '9') || (c >= 'a' && c <= 'f')
                    || (c >= 'A' && c <= 'F');
            if (!hex) {
                return null;
            }
        }
        return UUID.fromString(id.substring(0, 8) + "-" + id.substring(8, 12) + "-"
                + id.substring(12, 16) + "-" + id.substring(16, 20) + "-" + id.substring(20));
    }

    private static CompletableFuture<Optional<UUID>> request(String key) {
        CompletableFuture<Optional<UUID>> future = new CompletableFuture<>();
        HttpRequest request = HttpRequest.newBuilder(URI.create(ENDPOINT + key))
                .timeout(REQUEST_TIMEOUT)
                .header("Accept", "application/json")
                .header("User-Agent", "SnLib-PlayerLookup")
                .GET()
                .build();
        client().sendAsync(request, HttpResponse.BodyHandlers.ofString())
                .whenComplete((response, error) -> {
                    if (error != null) {
                        future.completeExceptionally(
                                new IOException("Mojang lookup failed: " + error, error));
                        return;
                    }
                    int status = response.statusCode();
                    if (status == 200) {
                        UUID uuid = parseUuid(response.body());
                        if (uuid == null) {
                            future.completeExceptionally(new IOException(
                                    "Mojang lookup failed: 200 response without a parseable id"));
                            return;
                        }
                        Optional<UUID> hit = Optional.of(uuid);
                        cache(key, hit);
                        future.complete(hit);
                        return;
                    }
                    if (status == 204 || status == 404) {
                        Optional<UUID> miss = Optional.empty();
                        cache(key, miss);
                        future.complete(miss);
                        return;
                    }
                    future.completeExceptionally(
                            new IOException("Mojang lookup failed: HTTP " + status));
                });
        return future;
    }

    private static void cache(String key, Optional<UUID> value) {
        synchronized (CACHE) {
            CACHE.put(key, value);
        }
    }

    private static HttpClient client() {
        HttpClient current = client;
        if (current == null) {
            synchronized (CLIENT_LOCK) {
                current = client;
                if (current == null) {
                    current = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
                    client = current;
                }
            }
        }
        return current;
    }

    /**
     * First string value of {@code "field"} in a JSON body, scanned by hand without a
     * JSON library (technique of {@code UpdateChecker.jsonString}, duplicated following
     * the self-contained-utils precedent): finds the first quoted occurrence of the field
     * name, skips spaces and the colon, requires an opening quote and reads to the
     * closing quote unescaping {@code \"}, {@code \\} and {@code \/} (other escapes are
     * kept verbatim). Any unexpected shape returns null.
     */
    private static @Nullable String jsonString(@Nullable String body, String field) {
        if (body == null || field == null) {
            return null;
        }
        int at = body.indexOf('"' + field + '"');
        if (at < 0) {
            return null;
        }
        int i = at + field.length() + 2;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        if (i >= body.length() || body.charAt(i) != ':') {
            return null;
        }
        i++;
        while (i < body.length() && Character.isWhitespace(body.charAt(i))) {
            i++;
        }
        if (i >= body.length() || body.charAt(i) != '"') {
            return null;
        }
        i++;
        StringBuilder out = new StringBuilder(32);
        while (i < body.length()) {
            char c = body.charAt(i);
            if (c == '"') {
                return out.toString();
            }
            if (c == '\\') {
                if (i + 1 >= body.length()) {
                    return null;
                }
                char next = body.charAt(i + 1);
                switch (next) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    default -> out.append(c).append(next);
                }
                i += 2;
                continue;
            }
            out.append(c);
            i++;
        }
        return null;
    }
}
