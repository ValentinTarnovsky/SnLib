# Cooldowns, Economy and utils

Three small but heavily used pieces of the library: a boxing-free cooldown store, a backend-agnostic economy bridge, and a catalog of pure and Bukkit-aware utility classes you can call from anywhere.

## Cooldowns

`sn.cooldowns()` returns a per-context cooldown store keyed by a category string and a player UUID. The core call is `tryUse`:

```java
UUID id = player.getUniqueId();
if (sn.cooldowns().tryUse(id, "kit", Duration.ofMinutes(5))) {
    giveKit(player);           // cooldown was armed: the action may run
} else {
    long left = sn.cooldowns().remainingMillis(id, "kit");
    player.sendMessage("Wait " + TimeUtil.humanizeShort(left));
}
```

`tryUse` returns `true` when the action may run (the cooldown was armed or re-armed) and `false` while the player is still cooling down. The full surface:

| Method | Purpose |
|--------|---------|
| `tryUse(UUID, String, Duration)` | Arm the cooldown unless still running; returns whether the action may run |
| `tryUseTicks(UUID, String, long)` | Same, expressed in ticks (1 tick = 50 ms) |
| `remainingMillis(UUID, String)` | Milliseconds left; 0 when expired or never armed |
| `registerSessionCategory(String)` | Mark a category session-scoped (cleared on quit/kick) |
| `clearSession(UUID)` | Drop a player's entries in every session category |
| `clearAll()` | Drop every entry and stop the sweep task |

### No boxing on the hot path

Internally the store is a `Map<String, Map<UUID, long[]>>` where each one-element `long[]` cell holds the expiry epoch in millis. Arming and checking a cooldown never boxes a `Long`. Expired entries are purged lazily on read and by an async sweep every 5 minutes (started on first use, via `timerAsync`), so the maps do not grow without bound.

### Persistence across a relog is deliberate

By default, an unexpired cooldown is **never dropped when a player quits**, so a relog does not reset it. This is a design choice, not an accident: a cooldown exists to rate-limit an action over wall-clock time (a 1-hour kit cooldown means one hour), and letting a player reset it by disconnecting and reconnecting would defeat the entire purpose. Persisting across a disconnect is the correct default.

When you *want* the opposite - a cooldown that only makes sense within a single session and should reset on relog - opt that category in explicitly:

```java
sn.cooldowns().registerSessionCategory("combat-tag");
// entries in "combat-tag" are cleared when the player quits or is kicked;
// every other category survives the relog
```

{% hint style="info" %}
Cooldowns live in memory for the server session, not on disk. "Survives a relog" means "survives the player disconnecting," not "survives a server restart." For durable, restart-proof cooldowns, persist the expiry yourself through the database module.
{% endhint %}

## Economy

`sn.economy()` returns the economy bridge. It auto-detects the best available backend and exposes a single API regardless of which one is active.

### Backend auto-detection

At construction the bridge tries to register a **Vault** backend. Vault's hook lives in an isolated class, so when Vault is absent from the classpath the instantiation throws a linkage error that is caught internally and the bridge simply starts without it - no `NoClassDefFoundError`, no crash. Backends are tried in registration order: Vault first, then the command backend, then any custom one.

When Vault is not present, configure the command-dispatch fallback:

```java
sn.economy().useCommandBackend(
        "eco give %player% %amount%",     // give command template
        "eco take %player% %amount%",     // take command template
        "%vault_eco_balance%");           // balance placeholder
```

The command templates accept the tokens `%player%` and `%amount%`; the balance placeholder is the PAPI placeholder that reports the player's balance, which `tryTake` uses to verify affordability and to confirm the result of a withdrawal.

You can also plug in a fully custom backend by implementing `EconomyBridge.Backend` and calling `registerBackend(name, backend)`.

### Operations

```java
CompletableFuture<Boolean> deposit = sn.economy().give(player, 250.0);
sn.scheduler().thenSync(deposit, ok -> {
    if (ok) player.sendMessage("Paid out 250");
});

sn.economy().tryTake(player, 100.0).thenAccept(taken -> {
    // taken == true only if the player could afford it and the withdrawal succeeded
});
```

| Method | Returns | Threading |
|--------|---------|-----------|
| `getBalance(Player)` | `double` | **Main thread only** |
| `give(Player, double)` | `CompletableFuture<Boolean>` | Callable from any thread |
| `tryTake(Player, double)` | `CompletableFuture<Boolean>` | Callable from any thread |
| `available()` | `boolean` | Any thread |

The write operations (`give`, `tryTake`) complete their future with the *real* success of the operation, and are safe to call from any thread because each backend hops to the main thread on its own. They return `false` on an invalid amount (non-finite or not positive), insufficient funds (for `tryTake`), or with no backend available.

{% hint style="warning" %}
`getBalance(Player)` must be called on the main thread. Off the main thread it returns `0` and logs one WARN per call site rather than reading a stale or unsafe value. If you need a balance from async code, hop back to the main thread first (`sn.scheduler().sync(...)`) or read it via the balance placeholder. With no backend available at all, every operation warns once and reports failure (`0` balance, `false` futures).
{% endhint %}

## Utils

SnLib ships a catalog of standalone helpers. The **pure** ones have no Bukkit dependency and are safe to unit-test and call from any thread; the **Bukkit-aware** ones touch the server API.

### Pure utilities (no Bukkit)

| Class | Purpose |
|-------|---------|
| `SlotParser` | Parse mixed inventory slot definitions (`0`, `"0-8"`, `"0,2,4-6"`, or a list of any) into distinct slot indexes, with a delegable WARN sink |
| `TimeUtil` | Parse compact duration strings like `"1d 2h 30m 15s"` (units `d/h/m/s`, plus `t` ticks and `ms`) and humanize millis back to text |
| `NumberFormatter` | Abbreviate with K/M/B/T/Qa/Qi suffixes (`format`), group with commas (`formatComma`), and the tolerant inverse (`parseFormatted`) |
| `LocationSerializer` | Round-trip a `Location` to and from a compact string form |
| `WeightedRandomPool` | Weighted random selection from a pool of entries |
| `Experience` | Convert between player level, progress and total XP points |
| `MathUtil` | Fair probabilistic rounding (expected value equals the input) and roman numerals (`convertToRoman`) |
| `Page<T>` | Immutable slice of a list for pagination |

```java
int[] slots = SlotParser.parse("0,2,4-6");            // [0, 2, 4, 5, 6]
long ms = TimeUtil.parseMillis("1d 2h 30m 15s");
String pretty = NumberFormatter.format(1_500_000);     // "1.5M"
double back = NumberFormatter.parseFormatted("1.5M");  // 1500000.0
int rolled = MathUtil.fairIntFromDouble(2.3);          // 3 with 30% probability, else 2
```

{% hint style="info" %}
The classes that emit warnings (like `SlotParser`) take an optional `Consumer<String>` warning sink instead of logging themselves, which is what keeps them pure and testable. Pass `sn.debug()::log` or your plugin logger when you want the warnings, or the no-sink overload when you do not.
{% endhint %}

### Bukkit-aware utilities

| Class | Purpose |
|-------|---------|
| `SoundUtil` | Lenient sound-name resolution and playback (accepts naming across versions) |
| `HeadUtil` | Build `PLAYER_HEAD` stacks from base64 / `basehead` / raw payload / skin URL, with a bounded LRU cache and deterministic profile UUIDs |
| `TagIo` | Read and write per-owner persistent data container (PDC) tags on items |
| `InvUtil` | Common inventory operations (giving, space checks, and similar) |
| `ArmourUtil` | `slotOf` / `isArmour` / `isWearingFullSet`, matching by Material name suffix so new server constants never break it |
| `LocationUtil` | World-aware `inCuboid` (delegating to `Cuboid`), `distance2d`, `distance2dSquared`, `distanceToBoxSquared` |
| `PlayerLookup` | Async name-to-UUID lookup against the Mojang API |

```java
ItemStack head = HeadUtil.fromValue("eyJ0ZXh0dXJlcyI6...");   // raw base64 texture
boolean full = ArmourUtil.isWearingFullSet(player);
```

`LocationUtil` pairs naturally with `SnChunkMoveEvent`: do a cheap zone check on chunk crossing instead of on every move.

### PlayerLookup: async UUID resolution done carefully

`PlayerLookup.fetchUuid(String name)` resolves a name to a UUID against the Mojang profiles endpoint and returns a `CompletableFuture<Optional<UUID>>`. It is the correct tool when you need a UUID for a player who may never have joined your server.

```java
PlayerLookup.fetchUuid("Notch").thenAccept(opt ->
        opt.ifPresent(uuid -> grantOfflineReward(uuid)));
```

Several properties make it production-safe:

- **The future completes off the main thread**, never on it. Hop back with `sn.scheduler().thenSync(future, ...)` or `sn.scheduler().sync(...)` before touching Bukkit - the same contract as the database module.
- **A bounded LRU cache** (capacity 512) stores results in access order, so hot names are not re-queried.
- **Misses are cached.** A 204/404 response is cached as `Optional.empty()`, so unknown names do not hammer the API on every lookup.
- **Concurrent lookups of the same name are deduplicated in-flight** - many callers asking for the same name at once share a single HTTP request.
- **Transient failures are not cached.** Network errors and unexpected statuses complete the future exceptionally (`IOException`) so a temporary outage does not poison the cache.
- **Invalid names short-circuit.** A null name or one that does not match `[A-Za-z0-9_]{1,16}` completes immediately with `Optional.empty()`, no HTTP call.

{% hint style="warning" %}
Because the future completes on the HTTP client's executor, treat its callback as async: do not call Bukkit API directly inside `thenAccept`. Route the result through the scheduler first.
{% endhint %}

## See also

- [Debug and Scheduler](debug-and-scheduler.md) - `thenSync` is the standard way to consume these async futures on the main thread.
- [Custom events](custom-events.md) - `SnChunkMoveEvent` pairs with `LocationUtil`; `SnArmourEquipEvent` pairs with `ArmourUtil`.
- [Developer overview](../README.md) and [the threading model](../threading-model.md).
