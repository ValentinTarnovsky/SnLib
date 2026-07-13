# Threading model

SnLib serves every Sn plugin on a server from one shared classloader. On a busy
server that can be fifty or more consumers running against a single instance of
the yml engine, the database module and the scheduler. That shared context is the
reason the threading rules below are strict: if one consumer blocks the main
thread, or leaks an async task past its own shutdown, the damage is not contained
to that consumer. It degrades or crashes work for every other plugin on the
server. These rules exist to make each consumer a good tenant.

## PlaceholderAPI resolves only on the main thread

PlaceholderAPI expansions are not guaranteed thread-safe, so SnLib resolves PAPI
tokens on the main thread only. If you ask SnLib to resolve text off the primary
thread, it leaves the PAPI tokens untouched and logs a debug note, applying only
the local (non-PAPI) placeholders. It never resolves PAPI off-main and never
throws for trying.

When you genuinely need PAPI-resolved text starting from an async context, use
the explicit async bridge (`SnPapi.applyOnMain`, and its list overload): it hops
the resolution to the main thread and returns a future. It is fail-open, so if
the plugin is disabling the text comes back untouched rather than throwing. See
[PlaceholderAPI](modules/papi.md) for the full API.

## `join()` only during bootstrap or teardown

`join()` on SnLib's async future type (`SnFuture`) blocks the calling thread until
the async work completes. Blocking is only acceptable in two windows: during
bootstrap (`onEnable`) and during teardown (`onDisable`). Anywhere else, a
`join()` risks stalling the main thread during normal gameplay.

This is actively verified, not just documented. At the start of teardown SnLib
sets a `shuttingDown` flag before anything else runs, and `SnFuture.join` checks
that flag: it accepts the teardown thread. Combined with the bootstrap window,
that keeps `join()` legal exactly where blocking is safe and rejects it where it
is not. During teardown the database module joins its pending writes and then
calls `shutdownNow` after a timeout, so a clean shutdown never loses a write nor
hangs forever.

## Synchronous I/O only in `onEnable` and the reload path

Blocking file or database I/O on the main thread is allowed in exactly two
places:

1. `onEnable`, where the server is starting up and a short blocking load is
   acceptable.
2. Inside the reload command path. This is a deliberate, documented exception:
   an operator running `/plugin reload` has asked for a synchronous refresh and
   is willing to pay a brief pause for it.

Everywhere else, I/O must be async. Config saves in particular are async with
write coalescing: at most one pending write per file, so a burst of `set()` calls
collapses into a single write rather than queuing many. The one place this flips
is teardown: once `shuttingDown` is set, `SnYml.save()` switches to synchronous
inline writes and `flush()` drains whatever is pending, so nothing is lost when
the scheduler is about to stop. See [YML config](modules/yml.md) for the save
semantics.

## `thenSync` runs behind an `isEnabled()` guard

The recommended pattern for "compute async, apply on the main thread" is
`supplyAsync(...).thenSync(...)`, or `sn.db().query(...).thenSync(...)`. The
`thenSync` callback hops back to the main thread to apply its result - but it does
so behind a `plugin.isEnabled()` guard, so a plugin that disabled mid-flight never
throws an `IllegalPluginAccessException` when its callback finally fires.

Concretely, `thenSync`:

- Skips the hop entirely if the owning plugin is already disabled, logging one
  warning: `Hop to main discarded: plugin disabled during scheduling`.
- Absorbs the race where the plugin disables inside the scheduler by catching
  `IllegalPluginAccessException`.
- Logs one warning (`Async task finished with an error: ...`) on an exceptional
  completion and never passes the exception on to your callback.

The result is that an async result applied through `thenSync` is safe against the
plugin disabling at any moment during the async work. This is what lets a consumer
fire async queries freely without hand-writing disable-race guards. See
[Debug and scheduler](modules/debug-and-scheduler.md) and
[Database](modules/database.md) for the scheduler and query APIs.

## Why these rules matter on a shared server

Each rule maps to a specific way one consumer could hurt the others:

- A blocking `join()` or synchronous I/O during gameplay freezes the main thread,
  which freezes the entire server: every plugin, every player.
- An async task that outlives its owner's shutdown, or a `thenSync` callback that
  fires after disable, throws inside SnLib's shared scheduler and can surface as
  console spam or worse for an unrelated plugin.
- Off-main PAPI resolution can corrupt PlaceholderAPI's own state, which is shared
  by every consumer that uses placeholders.

Following these rules keeps a consumer's failures contained to itself. The
[multi-tenant contract](multi-tenant-contract.md) covers the state-isolation side
of the same goal.
