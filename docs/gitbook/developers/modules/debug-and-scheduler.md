# Debug and Scheduler

Two small modules you reach for constantly: `SnDebug` for runtime-toggleable logging that costs nothing when off, and `SnScheduler` for a Folia-aware wrapper over the Bukkit schedulers. Both are per-context services: you get them from your `Sn` handle inside `onInnerEnable`.

## Debug module (SnDebug)

`sn.debug()` returns the debug service for your plugin's context. It is a thin, category-aware logger with a severity ladder and a runtime on/off switch that survives without a restart.

### The severity ladder

Verbosity is a single escalating threshold: `OFF < INFO < DEBUG < TRACE`. Each output channel only emits once the level reaches its own step.

| Method | Emits when level is at least | Log prefix |
|--------|------------------------------|------------|
| `info(String)` / `info(Supplier<String>)` | `INFO` | `[<Plugin>][INFO]` |
| `log(String)` / `log(Supplier<String>)` | `DEBUG` | `[<Plugin>][DEBUG]` |
| `trace(Supplier<String>)` | `TRACE` | `[<Plugin>][TRACE]` |

A channel flows only while the master toggle is on AND the level reaches its severity. `OFF` silences every channel regardless of the master toggle. The default level is `DEBUG`.

```java
Sn sn = sn();
sn.debug().info("Booting the shop module");         // shows at INFO, DEBUG, TRACE
sn.debug().log("Loaded " + count + " offers");       // shows at DEBUG, TRACE
sn.debug().trace(() -> "raw row = " + row);           // shows only at TRACE
```

### Lazy suppliers: pay nothing when debug is off

The `Supplier<String>` overloads are the point of the module. The lambda is only invoked when the channel would actually emit, so an expensive string is never built while debug is off.

```java
// The string concatenation and expensive() call never run unless debug is on.
sn.debug().log(() -> "state=" + expensive() + " players=" + snapshot());
```

Prefer the supplier form for any message that concatenates, formats, or calls a method to build its text. Reserve the plain `String` overloads for constant literals.

{% hint style="info" %}
The check that guards a supplier is a cheap `volatile` read plus an enum ordinal comparison. Wrapping your log lines in `() -> ...` is effectively free when debug is off, which is the normal production state.
{% endhint %}

### Category filters

Categories let you narrow the firehose to one subsystem. The filter is empty by default, which lets every category through; toggling a category *in* narrows output to only the filtered ones.

```java
sn.debug().log("pathfinding", () -> "node cost = " + cost);
sn.debug().trace("netcode", () -> "packet " + id + " -> " + payload);
```

```java
sn.debug().toggle();                 // flip the master switch, returns the new state
sn.debug().toggle("pathfinding");    // add/remove a category, returns true if now filtered
sn.debug().setLevel(SnDebug.Level.TRACE);
boolean isTracing = sn.debug().tracing();   // guard a very expensive trace block
```

Use `tracing()` (and the internal `enabled()` / `enabled(category)` checks) when you need to guard a whole block, not just one line:

```java
if (sn.debug().tracing()) {
    Map<UUID, Snapshot> dump = buildFullDump();   // only when tracing
    dump.forEach((id, snap) -> sn.debug().trace(() -> id + " => " + snap));
}
```

### Runtime toggle and persistence

Declaring `.debugCommand()` in your `SnSpec` adds a `debug` subcommand on your plugin's own command roots, so an admin can flip debug on a live server without a restart:

```java
@Override protected SnSpec buildSpec() {
    return SnSpec.builder()
            .config("config.yml")
            .debugCommand()      // adds the `debug` sub to your roots
            .build();
}
```

When a yml config module is present, every toggle is persisted back to the config under `debug.enabled`, `debug.level` and `debug.categories`, and restored on the next startup. The write goes through the same coalesced-async save as the rest of the yml module (synchronous during teardown). Without a backing config the toggles still work, but only in memory for the current session.

{% hint style="warning" %}
An invalid `debug.level` in the config does not crash: it logs one WARN and falls back to `DEBUG`.
{% endhint %}

## Scheduler (SnScheduler, Folia-aware)

`sn.scheduler()` returns a task scheduler bound to your plugin. Every scheduling method returns a `TaskHandle` you can cancel, and the wrapper transparently routes to the Bukkit scheduler or, on Folia, to the global-region and async schedulers.

### The surface

| Method | Runs |
|--------|------|
| `sync(Runnable)` | Once, on the main thread (global region on Folia) |
| `async(Runnable)` | Once, off the main thread |
| `syncLater(long delayTicks, Runnable)` | Once, on the main thread after a delay (minimum 1 tick) |
| `asyncLater(long delayTicks, Runnable)` | Once, off the main thread after a delay (minimum 1 tick) |
| `timer(long delayTicks, long periodTicks, Runnable)` | Repeating, on the main thread |
| `timerAsync(long delayTicks, long periodTicks, Runnable)` | Repeating, off the main thread |
| `supplyAsync(Supplier<T>)` | Computes a value off-thread, returns `CompletableFuture<T>` |
| `thenSync(CompletableFuture<T>, Consumer<T>)` | Consumes a future's value back on the main thread |
| `cancelAll()` | Cancels every task scheduled by your plugin |

Delays and periods are in ticks (20 ticks = 1 second); values below 1 are clamped to 1.

```java
SnScheduler s = sn.scheduler();

// One-shot main-thread work
s.sync(() -> player.sendMessage("welcome"));

// Delayed
TaskHandle handle = s.syncLater(40L, () -> arena.start());

// Repeating (first run after 20 ticks, then every 100 ticks)
TaskHandle ticker = s.timer(20L, 100L, this::refreshScoreboards);

// Cancel later
handle.cancel();
if (!ticker.isCancelled()) {
    ticker.cancel();
}
```

`TaskHandle` is the single cancellable handle type across both platforms:

```java
public interface TaskHandle {
    void cancel();
    boolean isCancelled();
}
```

### Off-thread compute and the hop back

The idiomatic async-then-main pattern uses `supplyAsync` to compute off-thread and `thenSync` to consume the result on the main thread:

```java
CompletableFuture<Stats> future = s.supplyAsync(() -> loadStatsFromDisk(uuid));
s.thenSync(future, stats -> {
    // main thread: safe to touch Bukkit here
    player.sendMessage("K/D: " + stats.kd());
});
```

Two guarantees make this safe during a reload or shutdown:

- **`thenSync` guards on `plugin.isEnabled()`**: if your plugin is disabled by the time the future completes, the main-thread hop is silently skipped rather than throwing. It also absorbs the disable race inside the scheduler (`IllegalPluginAccessException`) and logs one WARN instead of crashing.
- **`supplyAsync` never throws from the call site**: a supplier failure, or scheduling against an already-disabled plugin, completes the future exceptionally instead of propagating. An exceptional completion is logged as one WARN and never reaches your consumer.

{% hint style="info" %}
This is the same threading contract used by the database module: async work off the main thread, then an `isEnabled`-guarded hop back. See [the threading model](../threading-model.md) for the project-wide rules on `join()`, PlaceholderAPI and teardown.
{% endhint %}

### Folia: what the claim actually is

SnLib is honest about the limits of its Folia support. When the server is Folia (detected via `SnVersion.isFolia()`):

- `sync` / `syncLater` / `timer` route through the **global region scheduler**;
- `async` / `asyncLater` / `timerAsync` route through the **async scheduler**;
- so scheduling never throws on Folia.

That is the entire claim: **detection plus no-crash**. It is NOT a full region-aware port. The scheduler does not run your task in the correct region for a specific entity or location, and the GUI and item modules are validated on Paper only, not on Folia.

{% hint style="warning" %}
Treat the Folia support as "your scheduling calls will not crash a Folia server," not as "SnLib is a region-threaded library." If you are targeting Folia specifically, validate the GUI and item behavior yourself; those modules are only tested on Paper.
{% endhint %}

## See also

- [Actions and Requirements](actions-and-requirements.md) - the action engine dispatches on the main thread through this same scheduler.
- [Cooldowns, Economy and utils](cooldowns-economy-utils.md) - the cooldown sweep runs on `timerAsync`.
- [Developer overview](../README.md) and [the threading model](../threading-model.md).
