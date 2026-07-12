package com.sn.lib.discord;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentLinkedDeque;
import java.util.concurrent.atomic.AtomicBoolean;

import org.bukkit.plugin.IllegalPluginAccessException;
import org.jetbrains.annotations.Nullable;

import com.sn.lib.Sn;

/**
 * Discord webhook dispatcher of a consumer context, reached through {@code sn.discord()}.
 *
 * <p>Zero external dependencies: payloads POST through the JDK {@link HttpClient}.
 * Delivery is fire-and-forget over a FIFO queue processed OFF the main thread (enqueue
 * from any thread is non-blocking): an HTTP 429 re-queues the message at the front and
 * waits out the {@code Retry-After} the endpoint asked for, any other failure drops the
 * message with ONE warn per endpoint, and the webhook token is stripped from every log
 * line. The context teardown calls {@link #drain()}, which flushes whatever is still
 * queued synchronously on the teardown thread under a short deadline, so queued webhooks
 * are never lost in silence.</p>
 */
public final class DiscordWebhook {

    private static final Duration CONNECT_TIMEOUT = Duration.ofSeconds(5);
    private static final Duration SEND_TIMEOUT = Duration.ofSeconds(10);
    private static final long DRAIN_DEADLINE_MILLIS = 3000L;
    private static final int MAX_EMBEDS = 10;

    private final Sn ctx;
    private final ConcurrentLinkedDeque<Pending> queue = new ConcurrentLinkedDeque<>();
    private final AtomicBoolean working = new AtomicBoolean();
    private final Set<String> warnedEndpoints = ConcurrentHashMap.newKeySet();

    private final Object clientLock = new Object();
    private volatile @Nullable HttpClient client;

    public DiscordWebhook(Sn ctx) {
        this.ctx = ctx;
    }

    /** Starts a message for the webhook URL; nothing queues until {@link Message#send()}. */
    public Message message(String webhookUrl) {
        return new Message(webhookUrl);
    }

    /** Queues a plain-content message; shortcut for {@code message(url).content(text).send()}. */
    public void send(String webhookUrl, String content) {
        message(webhookUrl).content(content).send();
    }

    /** Starts a standalone embed to attach through {@link Message#embed(Embed)}. */
    public Embed embed() {
        return new Embed();
    }

    /**
     * Best-effort synchronous flush of the queue on the calling thread, invoked by the
     * context teardown after the scheduler is cancelled. Runs under a short deadline:
     * a 429 whose Retry-After fits before the deadline is waited out once, anything
     * undeliverable in time is dropped with a WARN counting the losses. Also releases
     * the HTTP client.
     */
    public void drain() {
        long deadline = System.currentTimeMillis() + DRAIN_DEADLINE_MILLIS;
        int dropped = 0;
        Pending pending;
        while ((pending = queue.pollFirst()) != null) {
            long remaining = deadline - System.currentTimeMillis();
            if (remaining <= 0L) {
                dropped++;
                continue;
            }
            long retryAfterMillis = deliver(pending, Duration.ofMillis(remaining));
            if (retryAfterMillis <= 0L) {
                continue;
            }
            if (System.currentTimeMillis() + retryAfterMillis >= deadline) {
                dropped++;
                continue;
            }
            try {
                Thread.sleep(retryAfterMillis);
                queue.addFirst(pending);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                dropped++;
            }
        }
        if (dropped > 0) {
            ctx.plugin().getLogger().warning("Webhook drain cut the flush short: " + dropped
                    + " message(s) dropped by the "
                    + DRAIN_DEADLINE_MILLIS + "ms deadline");
        }
        HttpClient current = client;
        client = null;
        if (current != null) {
            current.shutdown();
        }
    }

    private void enqueue(Pending pending) {
        queue.addLast(pending);
        pump();
    }

    /** Arms the async worker unless one is already draining or the teardown owns the queue. */
    private void pump() {
        if (ctx.isShuttingDown()) {
            return;
        }
        if (!working.compareAndSet(false, true)) {
            return;
        }
        try {
            ctx.scheduler().async(this::work);
        } catch (IllegalPluginAccessException e) {
            working.set(false);
        }
    }

    /** Async worker: sends FIFO; on 429 re-queues at the front and re-arms after the wait. */
    private void work() {
        while (!ctx.isShuttingDown()) {
            Pending pending = queue.pollFirst();
            if (pending == null) {
                working.set(false);
                // A message enqueued between the poll and the flag flip must not stall.
                if (!queue.isEmpty()) {
                    pump();
                }
                return;
            }
            long retryAfterMillis = deliver(pending, SEND_TIMEOUT);
            if (retryAfterMillis > 0L) {
                queue.addFirst(pending);
                try {
                    ctx.scheduler().asyncLater((retryAfterMillis + 49L) / 50L, this::work);
                } catch (IllegalPluginAccessException e) {
                    working.set(false);
                }
                return;
            }
        }
        working.set(false);
    }

    /**
     * Sends one payload; returns 0 when consumed (delivered or dropped with its WARN) or
     * the millis the endpoint asked to wait before retrying (HTTP 429).
     */
    private long deliver(Pending pending, Duration timeout) {
        HttpRequest request;
        try {
            request = HttpRequest.newBuilder(URI.create(pending.url()))
                    .timeout(timeout)
                    .header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString(pending.json(), StandardCharsets.UTF_8))
                    .build();
        } catch (IllegalArgumentException e) {
            warnOnce(pending.url(), "invalid URL: " + e.getMessage());
            return 0L;
        }
        try {
            HttpResponse<Void> response =
                    client().send(request, HttpResponse.BodyHandlers.discarding());
            int status = response.statusCode();
            if (status == 429) {
                return retryAfterMillis(response);
            }
            if (status < 200 || status >= 300) {
                warnOnce(pending.url(), "the endpoint responded HTTP " + status);
            }
            return 0L;
        } catch (IOException e) {
            warnOnce(pending.url(), "network failure: " + e);
            return 0L;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return 0L;
        }
    }

    /** Millis to wait from the Retry-After header (seconds, decimals allowed); floor 1s. */
    private static long retryAfterMillis(HttpResponse<?> response) {
        String header = response.headers().firstValue("Retry-After").orElse("1");
        try {
            return Math.max(1000L, (long) Math.ceil(Double.parseDouble(header) * 1000.0));
        } catch (NumberFormatException e) {
            return 1000L;
        }
    }

    /** One WARN per endpoint (fire-and-forget); later failures of the endpoint stay silent. */
    private void warnOnce(String url, String reason) {
        String endpoint = sanitize(url);
        if (warnedEndpoints.add(endpoint)) {
            ctx.plugin().getLogger().warning("Discord webhook " + endpoint + " failed ("
                    + reason + "); later errors of this endpoint are omitted from the log");
        }
    }

    /** Drops the trailing token segment so webhook secrets never reach the console. */
    private static String sanitize(String url) {
        int lastSlash = url.lastIndexOf('/');
        return lastSlash > "https://".length() ? url.substring(0, lastSlash) + "/***" : url;
    }

    private HttpClient client() {
        HttpClient current = client;
        if (current == null) {
            synchronized (clientLock) {
                current = client;
                if (current == null) {
                    current = HttpClient.newBuilder().connectTimeout(CONNECT_TIMEOUT).build();
                    client = current;
                }
            }
        }
        return current;
    }

    /** Appends {@code "key":"escaped"} with its separating comma; null values are omitted. */
    private static void appendField(StringBuilder json, String key, @Nullable String value) {
        if (value == null) {
            return;
        }
        if (json.length() > 1) {
            json.append(',');
        }
        json.append('"').append(key).append("\":\"").append(escape(value)).append('"');
    }

    /** JSON string escaping: quotes, backslashes and control characters. */
    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length() + 8);
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        return out.toString();
    }

    /** Builder of one webhook payload; {@link #send()} queues it FIFO and returns at once. */
    public final class Message {

        private final String url;
        private final List<Embed> embeds = new ArrayList<>();
        private @Nullable String content;
        private @Nullable String username;
        private @Nullable String avatarUrl;

        private Message(String url) {
            this.url = url;
        }

        /** Plain text content of the message. */
        public Message content(String content) {
            this.content = content;
            return this;
        }

        /** Overrides the webhook display name for this message. */
        public Message username(String username) {
            this.username = username;
            return this;
        }

        /** Overrides the webhook avatar for this message. */
        public Message avatarUrl(String avatarUrl) {
            this.avatarUrl = avatarUrl;
            return this;
        }

        /** Attaches an embed; Discord accepts up to 10 per message, extras are ignored. */
        public Message embed(Embed embed) {
            if (embed != null && embeds.size() < MAX_EMBEDS) {
                embeds.add(embed);
            }
            return this;
        }

        /** Queues the message for asynchronous delivery; empty payloads are discarded. */
        public void send() {
            if ((content == null || content.isEmpty()) && embeds.isEmpty()) {
                return;
            }
            enqueue(new Pending(url, toJson()));
        }

        private String toJson() {
            StringBuilder json = new StringBuilder(128).append('{');
            appendField(json, "content", content);
            appendField(json, "username", username);
            appendField(json, "avatar_url", avatarUrl);
            if (!embeds.isEmpty()) {
                if (json.length() > 1) {
                    json.append(',');
                }
                json.append("\"embeds\":[");
                for (int i = 0; i < embeds.size(); i++) {
                    if (i > 0) {
                        json.append(',');
                    }
                    embeds.get(i).appendJson(json);
                }
                json.append(']');
            }
            return json.append('}').toString();
        }
    }

    /** Builder of one Discord embed; attach through {@link Message#embed(Embed)}. */
    public static final class Embed {

        private final List<Field> fields = new ArrayList<>();
        private @Nullable String title;
        private @Nullable String description;
        private @Nullable Integer color;
        private @Nullable String footer;
        private @Nullable String timestamp;

        private Embed() {
        }

        /** Embed title. */
        public Embed title(String title) {
            this.title = title;
            return this;
        }

        /** Embed description. */
        public Embed description(String description) {
            this.description = description;
            return this;
        }

        /** Accent color as {@code 0xRRGGBB}. */
        public Embed color(int rgb) {
            this.color = rgb & 0xFFFFFF;
            return this;
        }

        /** Adds a name/value field, optionally rendered inline. */
        public Embed field(String name, String value, boolean inline) {
            fields.add(new Field(name == null ? "" : name, value == null ? "" : value, inline));
            return this;
        }

        /** Footer text. */
        public Embed footer(String footer) {
            this.footer = footer;
            return this;
        }

        /** Stamps the embed with the current instant. */
        public Embed timestampNow() {
            this.timestamp = Instant.now().toString();
            return this;
        }

        void appendJson(StringBuilder out) {
            StringBuilder body = new StringBuilder(64).append('{');
            appendField(body, "title", title);
            appendField(body, "description", description);
            if (color != null) {
                if (body.length() > 1) {
                    body.append(',');
                }
                body.append("\"color\":").append(color);
            }
            if (footer != null) {
                if (body.length() > 1) {
                    body.append(',');
                }
                body.append("\"footer\":{\"text\":\"").append(escape(footer)).append("\"}");
            }
            appendField(body, "timestamp", timestamp);
            if (!fields.isEmpty()) {
                if (body.length() > 1) {
                    body.append(',');
                }
                body.append("\"fields\":[");
                for (int i = 0; i < fields.size(); i++) {
                    Field field = fields.get(i);
                    if (i > 0) {
                        body.append(',');
                    }
                    body.append("{\"name\":\"").append(escape(field.name()))
                            .append("\",\"value\":\"").append(escape(field.value()))
                            .append("\",\"inline\":").append(field.inline()).append('}');
                }
                body.append(']');
            }
            out.append(body.append('}'));
        }

        private record Field(String name, String value, boolean inline) {
        }
    }

    private record Pending(String url, String json) {
    }
}
