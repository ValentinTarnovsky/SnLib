# Database

The db module is a dual SQLite/MySQL database bound to your plugin, pooled through
HikariCP. It has one hard rule: JDBC never touches the main thread. Every operation runs
on a dedicated per-plugin executor and hands results back as an `SnFuture` you consume on
the main thread when you need to touch the Bukkit API.

Declare it in your spec, then reach it through the context:

```java
// SnSpec:
SnSpec.builder().config("config.yml").db().build();

// onInnerEnable:
SnDb db = sn.db();
```

The backend (SQLite file or MySQL server) is read from your config; the module does not
open a connection until the first operation, so constructing it is free.

## Schema bootstrap

Create your tables at enable time with `bootstrap(...)`, then gate the plugin on it. Each
`Schema` renders to one idempotent `CREATE TABLE IF NOT EXISTS`.

```java
db.bootstrap(
        Schema.of("players",
                "uuid VARCHAR(36) PRIMARY KEY",
                "coins BIGINT NOT NULL DEFAULT 0",
                "last_seen BIGINT NOT NULL"),
        Schema.of("homes",
                "uuid VARCHAR(36) NOT NULL",
                "name VARCHAR(32) NOT NULL",
                "world VARCHAR(64) NOT NULL",
                "x DOUBLE NOT NULL, y DOUBLE NOT NULL, z DOUBLE NOT NULL",
                "PRIMARY KEY (uuid, name)"))
   .orDisablePlugin();
```

`orDisablePlugin()` is the enable-time gate: if the bootstrap fails (bad credentials,
unreachable host, malformed DDL), it logs the cause at `SEVERE` and cleanly disables your
plugin instead of leaving it half-initialized against a broken database.

For dialect-specific DDL that `Schema.of` cannot express, use `Schema.raw(table, sql)` and
keep the statement idempotent yourself.

{% hint style="warning" %}
While the bootstrap future is still pending, the module is in its **bootstrap phase**, and
a main-thread `join()` is permitted (see [Joining](#joining-the-only-allowed-blocking)
below). This is one of only two windows where blocking on the main thread is legal.
{% endhint %}

## Reads: `query(...)`

`query(sql, binder, mapper)` runs a prepared statement off the main thread and maps its
result set. It returns an `SnFuture<R>`; consume it with `thenSync` to hop the result back
to the main thread.

```java
db.query("SELECT coins FROM players WHERE uuid = ?",
        st -> st.setString(1, uuid.toString()),
        rs -> rs.next() ? rs.getLong("coins") : 0L)
  .thenSync(coins -> {
        // back on the main thread; safe to touch the Bukkit API
        player.sendMessage("You have " + coins + " coins.");
  });
```

`thenSync` carries an `isEnabled()` guard: if your plugin has disabled by the time the
query completes, the main-thread hop is skipped rather than throwing an
`IllegalPluginAccessException`. Observe failures with `exceptionally`, which unwraps the
`CompletionException` to the real cause:

```java
db.query(sql, binder, mapper)
  .thenSync(result -> render(result))
  .exceptionally(error -> getLogger().warning("Query failed: " + error));
```

### `chainSync` - when your method RETURNS the future

`thenSync`, `exceptionally` and `orDisablePlugin` all return **the same future**. They add one
more dependent to one completion, so they end a chain rather than extending it. That is fine
until a method attaches a consumer and then hands the future to a caller:

```java
// WRONG: the caller's step is a SIBLING of the publish, not its successor
public SnFuture<Integer> setLastResetDate(String date) {
    return writeState(KEY, date).thenSync(ignored -> lastResetDate = date);
}

store.setLastResetDate(today)
     .thenSync(ignored -> announce());   // runs BEFORE lastResetDate is assigned
```

Sibling order is unspecified, and OpenJDK pops dependents last-registered-first, so the
caller's continuation runs FIRST and reads exactly the state the publish was about to install.
Hopping to the main thread does not save you: both tasks are then queued in that same inverted
order.

`chainSync` is the one method that returns a NEW future, settled from inside the main-thread
task after the consumer returned. Fix it in the **producer** and every existing caller becomes
correct without being touched:

```java
public SnFuture<Integer> setLastResetDate(String date) {
    return writeState(KEY, date).chainSync(ignored -> lastResetDate = date);
}
```

Three ways it differs from `thenSync`, all deliberate:

- a failure is **propagated** to the returned future instead of being swallowed into one WARN,
  so terminate the chain with `exceptionally` or `orDisablePlugin`;
- a consumer that **throws** fails the chain instead of only being logged by Bukkit, because a
  link that never settles is a permanent hang for whoever waits above it;
- **never wait on the returned future.** Only a main-thread task can complete it, so `join()`
  and `joinWithin` throw from the main thread rather than deadlocking the server. A producer
  whose future a teardown flush joins must not use `chainSync` at all.

Only chain `chainSync` onto `chainSync`: `a.chainSync(x).thenSync(y).chainSync(z)` puts `z`
back ahead of `y`.

## Writes: `update(...)`

`update(sql, binder)` runs a prepared write off the main thread and completes with the
affected row count. Writes are **never** run on the main thread - there is no synchronous
variant by design.

```java
db.update("UPDATE players SET coins = coins + ? WHERE uuid = ?",
        st -> {
            st.setLong(1, reward);
            st.setString(2, uuid.toString());
        });
```

For multi-statement atomicity, use `transaction(work)`, which commits on success and rolls
back on any failure:

```java
db.transaction(conn -> {
    try (PreparedStatement take = conn.prepareStatement(
            "UPDATE players SET coins = coins - ? WHERE uuid = ?")) {
        take.setLong(1, price);
        take.setString(2, buyer.toString());
        take.executeUpdate();
    }
    try (PreparedStatement give = conn.prepareStatement(
            "UPDATE players SET coins = coins + ? WHERE uuid = ?")) {
        give.setLong(1, price);
        give.setString(2, seller.toString());
        give.executeUpdate();
    }
});
```

A dialect-aware single-row upsert is available through `db.upsert(table)` when you want to
insert-or-update without hand-writing the `ON CONFLICT` / `ON DUPLICATE KEY` clause.

## Per-player cache: `PlayerDataCache<T>`

`PlayerDataCache<T>` is a loader/saver-backed cache keyed by player UUID. SnLib's shared
join listener loads every registered cache on join, and the quit cleanup saves dirty
entries on quit - you never wire the events yourself.

```java
PlayerDataCache<Stats> cache = db.playerCache(
        // loader: runs on your plugin's async pool, may safely block on db queries
        (database, uuid) -> database.query(
                "SELECT kills, deaths FROM stats WHERE uuid = ?",
                st -> st.setString(1, uuid.toString()),
                rs -> rs.next() ? new Stats(rs.getInt("kills"), rs.getInt("deaths"))
                                : new Stats(0, 0)).join(),
        // saver: enqueues an async write, called on quit and on shutdown
        (database, uuid, stats) -> database.update(
                "INSERT INTO stats(uuid, kills, deaths) VALUES(?,?,?) "
                        + "ON DUPLICATE KEY UPDATE kills=?, deaths=?",
                st -> {
                    st.setString(1, uuid.toString());
                    st.setInt(2, stats.kills());
                    st.setInt(3, stats.deaths());
                    st.setInt(4, stats.kills());
                    st.setInt(5, stats.deaths());
                }));
```

Then read and mutate through the cache:

```java
Stats stats = cache.get(uuid);   // null until loaded (or if the loader returned nothing)
stats.addKill();
cache.markDirty(uuid);           // schedules a save on the next quit / shutdown flush
```

Other operations: `invalidate(uuid)` discards an entry without saving (and kills any
in-flight load), and `saveAll()` persists every dirty entry and returns a barrier future.

{% hint style="info" %}
The loader runs on your plugin's async pool, **not** on the database executor. That is why
it may call `.join()` on a query inside the loader without deadlocking. Concurrent loads
of the same player deduplicate into a single in-flight attempt, and a mutation-sequence
guard discards a stale load result if the player quit or the entry was invalidated while
the load was in flight.
{% endhint %}

## Storage backends

The same API runs on two backends, selected by your config:

| | SQLite | MySQL |
|---|--------|-------|
| Pool size | **1** (single connection) | classic Hikari pool (default 4) |
| Journal | `journal_mode=WAL` on first connect | n/a |
| Busy handling | `busy_timeout=5000` | n/a |
| Storage | one file in your data folder | remote server |
| Prepared-statement cache | n/a | `cachePrepStmts`, size 250 |

SQLite is single-writer, so the module pins it to a pool of one and a single-threaded
executor - which also makes its write barrier exact. MySQL gets a real connection pool.

### Connection timeouts

Two optional keys in the same `database` section bound how long the driver may block. Both
have safe defaults, so an existing config keeps working unchanged:

```yaml
database:
  type: mysql
  host: db.example.com
  # How long a single connection attempt may take. Default 10, minimum 1, maximum 3600.
  connect-timeout-seconds: 10
  # How long a read over an OPEN connection may block. Default 30, 0 = unlimited.
  socket-timeout-seconds: 30
```

`connect-timeout-seconds` becomes HikariCP's `connectionTimeout` on **both** backends and
the driver's `connectTimeout` on the MySQL URL. `socket-timeout-seconds` becomes the MySQL
driver's `socketTimeout`; SQLite ignores it, being a local file.

The socket timeout is the one that matters against a host that stops answering without
closing the connection: without it a read waits forever, and no budget above it can help.
Raise it (or set it to `0`) only if you knowingly run queries longer than the cap.

Shrinking the connect budget is safe here: the executor has exactly as many threads as the
pool has connections, so a borrow never queues behind another borrow and the budget only
ever measures the physical connect.

### Driver shading

HikariCP is shaded **with** relocation into `com.sn.lib.libs.hikari`, so it never clashes
with any other plugin's copy. The **JDBC drivers** (SQLite and MySQL), however, are shaded
**without** relocation. They carry JNI and native binaries that cannot be renamed, so
there is exactly **one** copy of each driver on the whole server, provided by SnLib.jar and
shared by every consumer. This is a deliberate design point: you never bundle a JDBC
driver in your own plugin.

## Joining: the only allowed blocking

`SnFuture.join()` blocks the calling thread until the value is ready. It is meant for
**exactly two** windows:

- **bootstrap** - while a `bootstrap(...)` future is still pending (enable time), and
- **teardown** - the shutdown flush.

Calling `join()` on the main thread outside those windows logs one warning with the
offending call frames, so accidental main-thread blocking is caught in review rather than
in production. Off the main thread, `join()` is always fine.

### `joinWithin(Duration)` - the same wait, under a budget

`join()` waits forever. During teardown that is a real risk: one unreachable database and
the server stop hangs for as long as the JDBC driver takes. `joinWithin(timeout)` bounds
it and tells you which of the three things happened:

```java
@Override
protected void onInnerDisable() {
    // Bukkit already cleared the enabled flag, so thenSync would be dropped here:
    // blocking is the only way to observe that the write landed.
    SnFuture<Integer> flush = sn.db().update(SAVE_SQL, st -> bind(st, state));
    try {
        if (!flush.joinWithin(Duration.ofSeconds(3))) {
            getLogger().warning("Shutdown flush still running after 3s; stopping anyway.");
        }
    } catch (CompletionException failed) {
        getLogger().warning("Shutdown flush failed: " + failed.getCause());
    }
}
```

- **`true`** - it settled in time. The value is then available from `join()`, which returns
  immediately on a settled future.
- **throws** - it settled with a failure, exactly like `join()` would have thrown. A throw
  always means *failed*, never *still running*.
- **`false`** - the budget ran out. The work is **not** cancelled and keeps running; a late
  failure only reaches you if you registered `exceptionally` beforehand.

The main-thread rules are identical to `join()`: silent during teardown and bootstrap,
warned anywhere else, and a future that can only complete on the main thread still refuses
to be waited on from it - a budget postpones that deadlock, it does not break it.

{% hint style="warning" %}
A budget on your side is only half of it. If the driver blocks below the future, no
timeout you set can be honoured - that is what the
[connection timeouts](#connection-timeouts) are for.
{% endhint %}

On shutdown the module runs an ordered teardown so no write is lost:

1. `flushPlayerCaches()` saves every dirty cache entry and **joins** the enqueued writes.
2. `shutdown()` rejects new operations, drains pending work for up to 10 seconds, then
   interrupts stragglers with `shutdownNow()` (resetting their context classloader so a
   hung query cannot pin your plugin's classloader), and closes the pool last.

This is why a `markDirty` shortly before a restart still reaches disk: the flush joins it
before the pool closes.

## Threading recap

- Reads and writes run off the main thread, always.
- Results come back as `SnFuture`; hop to the main thread with `thenSync` (guarded by
  `isEnabled()`), observe failures with `exceptionally`.
- `join()` only in bootstrap or teardown; `joinWithin(Duration)` when that wait must not
  outlive the server stop.

For the general async rules this module follows - the scheduler, the `thenSync` guard, and
the join policy - see the [threading model](../threading-model.md).

## See also

- [Threading model](../threading-model.md) - the async contract this module obeys.
- [Commands](commands.md) - trigger reads and writes from command executors.
- [Quickstart](../quickstart.md) and the [developer overview](../README.md).
