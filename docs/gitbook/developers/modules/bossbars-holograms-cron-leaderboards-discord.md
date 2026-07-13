# Bossbars, Holograms, Cron, Leaderboards, Discord

Four small, independent modules that share the same design contract as the rest of SnLib: everything is keyed by owning plugin in a tenant registry, so when your plugin is disabled (reload, `/reload`, PlugMan, or shutdown) the library sweeps every bar, hologram, cron job, leaderboard task and queued webhook you created, even if your code never called a cleanup method. You reach each module from the `Sn` context: `sn.bossbars()`, `sn.holograms()`, `sn.cron()`, `sn.leaderboards()`, `sn.discord()`.

{% hint style="info" %}
None of these modules need an `SnSpec` declaration to be reached, with one caveat: cron `catchUp(true)` persistence needs the `yml` module (declare a `config(...)` in your spec). Without it the job still runs, but it WARNs once and nothing persists.
{% endhint %}

## Bossbars (`sn.bossbars()`)

Boss bars are Adventure `BossBar` instances shown per player through the Audience API, with zero packet manipulation. Titles render through the full `SnText` pipeline, so `[rgb]`, `[center]` and MiniMessage all work in a bar title.

### Creating a bar

`create(id)` returns a fluent builder; nothing is registered until you call `build()`. Building under an id that already exists replaces (and hides) the previous bar.

```java
Sn sn = sn();

sn.bossbars().create("raid")
        .text("[rgb]&lRaid in progress")
        .progress(1.0f)                       // clamped to 0..1
        .color(BossBar.Color.RED)             // Adventure enum, default WHITE
        .overlay(BossBar.Overlay.NOTCHED_10)  // default PROGRESS
        .build();
```

A freshly built bar has **no viewers**: it is registered but invisible until you show it.

### Controlling a built bar

All the per-instance operations take the id. An unknown id logs one WARN and no-ops (it never throws), so a typo is a log line, not a crash.

```java
sn.bossbars().show(player, "raid");         // add this viewer
sn.bossbars().hide(player, "raid");         // remove only this viewer
sn.bossbars().setText("raid", "&eWave 2");  // re-render the title through SnText
sn.bossbars().setProgress("raid", 0.5f);    // clamped 0..1
sn.bossbars().exists("raid");               // true/false, never WARNs
sn.bossbars().remove("raid");               // hide from everyone, stop timer, unregister
```

### Animated timer

`timer(id, duration, countdown)` animates the progress linearly across the duration. With `countdown = true` it drains from 1 to 0; with `false` it fills from 0 to 1. A new timer replaces the previous one, and when the duration elapses the timer stops with the bar left at its final progress (still visible until you hide it). `setProgress` still works while a timer runs, but the timer overwrites it on its next tick.

```java
// 30-second countdown bar for a raid phase
sn.bossbars().create("raid").text("&cPhase ends in 30s").build();
sn.bossbars().show(player, "raid");
sn.bossbars().timer("raid", Duration.ofSeconds(30), true);

// later, stop the animation but keep the current fill
sn.bossbars().cancelTimer("raid");
```

### Automatic cleanup

- A player who quits or is kicked is dropped from every bar of your context automatically.
- `hideAll()` hides every bar of your context from all viewers and stops their timers; the bars stay registered and can be re-shown.
- The context teardown calls `hideAll()` for you and the tenant sweep removes the entries, so a forgotten bar never leaks across a reload.

## Holograms (`sn.holograms()`)

Holograms are real `TextDisplay` entities (the 1.19.4+ display-entity API), with zero NMS and zero packets. Every entity carries a PDC marker under the namespaced key `snlib:snlib_hologram` whose value is `<plugin>:<id>`. Lines render through the `SnText` pipeline with PAPI resolved server-side (null viewer).

### Spawning and updating

```java
Location loc = new Location(world, 0.5, 100, 0.5);

sn.holograms().spawn("top", loc, List.of(
        "[center][rgb]&lTop Killers",
        "&71. &fSteve &8- &a412",
        "&72. &fAlex  &8- &a389"));

// replace the text later (re-renders immediately)
sn.holograms().setLines("top", List.of("&eRefreshing..."));

// billboard mode: default CENTER (classic always-face-player hologram)
sn.holograms().setBillboard("top", Display.Billboard.VERTICAL);
```

Spawning under an id that already exists replaces the previous hologram. A re-spawn on every enable is the **expected model**: the previous run's entity is purged as an orphan (see below), so you do not accumulate duplicates.

### PAPI-driven refresh

`refreshEvery(id, intervalTicks)` re-renders the hologram on an interval, resolving PAPI tokens each time. An interval of 0 or less cancels the refresh. There is one refresh task per hologram; a new interval replaces the previous task.

```java
sn.holograms().spawn("clock", loc, List.of("&7Online: &a%server_online%"));
sn.holograms().refreshEvery("clock", 100L);  // every 5 seconds
```

### Per-player visibility

```java
sn.holograms().hideFrom(player, "top");   // hide from one viewer only
sn.holograms().showTo(player, "top");     // show again (holograms are visible by default)
```

{% hint style="warning" %}
Per-viewer visibility is **not persistent**: it resets when the entity re-binds after a chunk reload or a re-spawn. If you need durable per-player visibility, re-apply `hideFrom` when you re-spawn.
{% endhint %}

### Orphan cleanup

Because holograms are persistent entities, a crash (or a delete that could not reach an unloaded chunk) can leave a `TextDisplay` behind. SnLib handles this automatically:

- The internal **chunk-load listener** inspects marked displays as their chunk loads. A marker no live registration claims is an orphan and gets removed; a marker a live registration claims re-binds to that hologram's fresh entity instance and gets its current text and billboard re-applied.
- The **startup scan** does the same sweep over already-loaded worlds at SnLib startup, so leftovers from a previous run are purged on the first tick.

Displays **without** the library marker are foreign and always left alone. `delete(id)` removes a hologram; when the entity sits in an unloaded chunk it cannot be touched immediately, so its now-dead marker guarantees the orphan purge removes it on the next chunk load. `deleteAll()` removes every hologram of your context, and the context teardown calls it for you.

## Cron (`sn.cron()`)

A calendar scheduler: pair an id with a cron expression and a task run on the main thread at every matching instant. The delay to the next run is computed with `ZonedDateTime` and scheduled through the context's Folia-aware scheduler; the job re-schedules itself after each run, so wall-clock drift never accumulates.

### Scheduling

```java
sn.cron().schedule("payout", "0 4 * * *", () -> economy.payEveryone());
```

The task runs on the **main thread**, so it is safe to touch Bukkit API directly. An invalid expression WARNs and schedules nothing. Scheduling under an existing id replaces the previous job. `cancel(id)` cancels and forgets a job.

### Expression syntax

The parser accepts a 5-field cron subset plus two shortcuts. Fields, in order: `minute hour day-of-month month day-of-week`.

| Syntax | Meaning |
|--------|---------|
| `*` | any value |
| `1,15` | a list |
| `1-5` | a range |
| `*/10` | a step (also over a range, `10-30/5`) |
| `daily HH:mm` | shortcut, time optional (default `00:00`) |
| `hourly :mm` | shortcut, minute optional (default `:00`) |

Day-of-week is `0-7` where both `0` and `7` mean Sunday. When day-of-month **and** day-of-week are both restricted, a day matching **either** runs (standard cron OR semantics).

```java
sn.cron().schedule("open",   "daily 09:00",   () -> shop.open());
sn.cron().schedule("tick",   "hourly :30",    () -> arena.rotate());
sn.cron().schedule("weekday","0 8 * * 1-5",   () -> broadcast("Good morning"));
```

The computation runs over `ZonedDateTime`, so it is **DST-safe**: a wall-clock time erased by a spring-forward gap is skipped to the next matching day, and a day absent from a month (the 31st in a 30-day month, February 29th outside leap years) waits for the next month that has it.

### Catch-up for missed runs

By default, a job that would have fired while the server was offline simply does not fire. Opt in to catch-up with the builder:

```java
sn.cron().create("payout", "0 4 * * *")
        .catchUp(true)
        .schedule(() -> economy.payEveryone());
```

With `catchUp(true)`, the job persists its last-run instant to a `cron-data.yml` data file and, when scheduled again (typically the next startup), fires **once immediately** if a run was missed while offline. A fresh install never fires retroactively: the first time it only records the current instant as a baseline. The last-run write flips to a synchronous write during teardown, so a run recorded while the server is shutting down is never lost.

{% hint style="warning" %}
`catchUp(true)` requires the `yml` module. Declare a `config(...)` in your `SnSpec`; without it the job still runs but WARNs once and the last-run does not persist.
{% endhint %}

## Leaderboards (`sn.leaderboards()`)

A leaderboard is an id paired with an asynchronous query fired on a fixed interval. Each refresh folds the fresh result into an immutable `Snapshot` swapped behind a volatile reference, which makes every read a **lock-free** cache lookup, safe to call from a PlaceholderAPI resolver.

### Registering a board

```java
sn.leaderboards().register("kills", Duration.ofMinutes(5),
        () -> sn.db().query(
                "SELECT uuid, name, kills FROM stats ORDER BY kills DESC LIMIT 10",
                st -> {},
                rs -> {
                    List<LeaderboardCache.Entry> rows = new ArrayList<>();
                    while (rs.next()) {
                        rows.add(new LeaderboardCache.Entry(
                                UUID.fromString(rs.getString("uuid")),
                                rs.getString("name"),
                                rs.getDouble("kills")));
                    }
                    return rows;
                }));
```

The supplier runs on the main thread and must only **dispatch** the async work; an `SnDb` query already does that (its `SnFuture` completes off-thread). The interval is clamped to a one-second minimum, so a leaderboard is never a per-tick loop. Until the first query completes, every read sees an empty snapshot. Registering under an existing id replaces the previous board; `unregister(id)` cancels the refresh and forgets it.

### Reading a board

```java
List<LeaderboardCache.Entry> top10 = sn.leaderboards().getTop("kills", 10);
int rank  = sn.leaderboards().positionOf("kills", player.getUniqueId()); // 1-based, 0 = unranked
double v  = sn.leaderboards().valueOf("kills", player.getUniqueId());    // 0 when unranked
```

Entries sort by value descending with the name as a stable tie-break; positions are 1-based and 0 means not ranked. The `Snapshot` never mutates: the cache swaps whole snapshots, so readers never lock.

### PAPI placeholders (opt-in)

`exposePlaceholders(identifier)` registers a PlaceholderAPI expansion exposing every board of the cache. Resolvers only read the in-memory snapshots.

```java
sn.leaderboards().exposePlaceholders("mystats");
```

| Placeholder | Resolves to |
|-------------|-------------|
| `%mystats_top_<id>_<n>_name%` | display name at rank `n` of board `<id>` (empty string past the end) |
| `%mystats_top_<id>_<n>_value%` | value at rank `n` (integers render without a trailing `.0`) |
| `%mystats_pos_<id>%` | the viewing player's 1-based position on board `<id>` |

For example, with the `kills` board: `%mystats_top_kills_1_name%`, `%mystats_top_kills_1_value%`, `%mystats_pos_kills%`. Returns `false` with a WARN when PlaceholderAPI is absent or rejects the expansion.

## Discord (`sn.discord()`)

Fire-and-forget Discord webhook delivery with zero external dependencies: payloads POST through the JDK `HttpClient`. Enqueueing from any thread is non-blocking; a single FIFO worker processes the queue off the main thread.

### Sending a message

`message(url)` returns a builder; nothing queues until `send()`. An empty payload (no content and no embeds) is silently discarded.

```java
String hook = sn.yml().config().getString("discord.webhook", "");

sn.discord().message(hook)
        .username("Server")
        .content("**Alex** just won the event!")
        .embed(sn.discord().embed()
                .title("Event finished")
                .description("Winner: Alex")
                .color(0x00FF88)
                .field("Players", "42", true)
                .field("Duration", "12m", true)
                .footer("MyServer")
                .timestampNow())
        .send();

// shortcut for a plain-content message
sn.discord().send(hook, "Server started");
```

Discord accepts up to 10 embeds per message; extras are ignored. Embed color is `0xRRGGBB`.

### Rate limits and delivery guarantees

- Delivery is FIFO over a single async worker.
- An HTTP 429 re-queues the message at the **front** of the queue and waits out the `Retry-After` the endpoint asked for (floor 1 second) before retrying, so you never lose a message to a rate limit.
- Any other failure (network error, non-2xx) drops that message with **one** WARN per endpoint; later failures of the same endpoint stay silent.
- The webhook token is stripped from every log line, so a secret URL never reaches the console.

### Teardown flush

The context teardown calls `drain()`, which flushes whatever is still queued synchronously on the teardown thread under a short (3-second) deadline. A 429 whose `Retry-After` fits before the deadline is waited out once; anything undeliverable in time is dropped with a WARN counting the losses. This is best-effort: it means queued webhooks are not silently abandoned on shutdown, but a very slow endpoint during a shutdown can still lose messages by design (the server must not hang on a webhook).

## See also

- [Update checker](update-checker.md) - the other `HttpClient`-based module, notify-only.
- [Region selection](region-selection.md) - another always-available, spec-less module.
- [Text rendering](text.md) - the `SnText` pipeline every title and line above runs through.
- Back to the [developer guide](../README.md).
