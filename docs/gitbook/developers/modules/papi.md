# PlaceholderAPI

The papi module is a safe bridge to [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/).
It resolves placeholders in your text, lets you register your own expansions
declaratively, and - crucially - degrades cleanly when PlaceholderAPI is not installed. It
is a **softdepend** integration: your plugin works with or without PlaceholderAPI present,
and never crashes because of its absence.

Reach it through the context:

```java
SnPapi papi = sn.papi();
```

## Resolving placeholders

`apply(viewer, text)` resolves PAPI tokens in a string. A null viewer resolves against the
server (so `%server_online%` works); a player viewer resolves per-player.

```java
String raw = "&aHi %player_name%, %server_online% online";
String resolved = sn.papi().apply(player, raw);
```

The important guarantee: when PlaceholderAPI is **not installed**, `apply` returns the
string **untouched**. It does not throw, and it never triggers a `NoClassDefFoundError`.

### Why isolation matters for a softdepend

Every bytecode reference to a PlaceholderAPI class lives in an internal, isolated bridge
class that is only loaded after a runtime presence probe succeeds. The public `SnPapi`
class holds no PlaceholderAPI reference at all. This is the pattern that makes a softdepend
safe:

- If the JVM eagerly touched a PAPI type on a server without the plugin, the classloader
  would raise `NoClassDefFoundError` the moment your code ran - even inside a
  `try/catch`, because the *linkage* happens before your guard.
- By quarantining the PAPI references behind the probe, the class is never linked unless
  PlaceholderAPI is actually present. The `try/catch` around your feature is real, not a
  trap that fires at class-load time.

If PlaceholderAPI is present but somehow becomes inaccessible mid-call (a `LinkageError`),
the module marks itself degraded, logs one warning, and falls back to returning the text
untouched.

There is a list overload too, resolving line by line:

```java
List<String> lore = sn.papi().apply(player, rawLoreLines);
```

## Resolving from async code: `applyOnMain`

PlaceholderAPI resolution is main-thread only. If you call `apply` off the main thread, the
tokens are left untouched and the skip is noted through the debug service. When you *are*
async (a database callback, a Discord webhook, a leaderboard build) and you need real
resolution, use `applyOnMain`, which hops to the main thread and returns an `SnFuture`:

```java
// inside an async callback
sn.papi().applyOnMain(viewer, "%vault_eco_balance% coins")
   .thenSync(text -> board.setLine(0, text));
```

On the main thread it resolves inline and returns an already-completed future; off it, it
schedules the resolution and completes when done. It is **fail-open**: if resolution throws,
or if your plugin disables before the main-thread hop can run, the future completes with
the **original, unresolved text** rather than failing. Null text completes with null.

A list overload resolves an entire list in a single main-thread hop:

```java
sn.papi().applyOnMain(viewer, rawLines)
   .thenSync(lines -> hologram.setLines(lines));
```

{% hint style="info" %}
`applyOnMain` returns the same `SnFuture` type as the [database](database.md) module, so
you consume it the same way: `thenSync` to hop the result back for Bukkit calls,
`exceptionally` to observe a failure. Its canonical consumption is `thenSync`.
{% endhint %}

## Declarative custom expansions

Register your own placeholders without writing a `PlaceholderExpansion` subclass. Start
with `expansion(identifier)`, bind resolvers, and `register()`.

```java
sn.papi().expansion("shop")
        .placeholder("balance", player ->
                String.valueOf(economy.balanceOf(player.getUniqueId())))
        .placeholder("items_sold", player ->
                String.valueOf(stats.itemsSold(player.getUniqueId())))
        .register();
```

That yields `%shop_balance%` and `%shop_items_sold%`.

- `placeholder(param, resolver)` binds an **exact** token. The resolver receives the
  requesting `OfflinePlayer`.
- `prefixed(prefix, resolver)` binds **every** token starting with `prefix`; the resolver
  receives the remainder after the prefix as its second argument. Exact placeholders win
  over prefixed ones.

```java
sn.papi().expansion("shop")
        .placeholder("balance", player -> money(player))
        .prefixed("price_", (player, item) -> String.valueOf(catalog.priceOf(item)))
        .register();
// %shop_balance%   -> exact
// %shop_price_diamond% -> prefixed, item = "diamond"
```

`author(...)` and `version(...)` default to your plugin's `plugin.yml` values; override
them if you want.

### `persist true` semantics

The built expansion reports `persist() = true` to PlaceholderAPI. That means it **survives
a PlaceholderAPI expansion reload** (`/papi reload`) and is removed only by your plugin's
own context teardown - you do not have to re-register it after an admin reloads PAPI.

`register()` also does a **lookup-before-register**: it unregisters any previous expansion
under the same identifier first, so a second enable of your plugin (a reload, a PlugMan
re-add) never fails with a "duplicate expansion" error. It returns `false` with a warning
when PlaceholderAPI is absent or the registration is rejected.

{% hint style="warning" %}
Resolvers run on the main thread inside PlaceholderAPI's parse. They must read
precomputed in-memory state only - never disk, database, or network. There is no supported
async resolver: precompute a cache (for example a leaderboard snapshot) and resolve with
fast, lock-free reads. For the inverse direction - composing text that *contains* PAPI
tokens from an async flow - use `applyOnMain`.
{% endhint %}

## Reactive to live install and removal

The presence probe is cached, but the bridge reacts to PlaceholderAPI toggling on a
running server. If PlaceholderAPI is enabled or disabled live (installed via a plugin
manager, or removed), the bridge invalidates its cached probe and re-checks on the next
call - activating itself when PAPI appears and going dormant (fail-open, text untouched)
when it disappears. No server restart is required either way.

You can also drop the cached probe manually with `sn.papi().invalidate()`, and query the
current state with `sn.papi().available()`.

## See also

- [Language](lang.md) - lang messages run through the same PAPI resolution before render.
- [Text](text.md) - the pipeline PAPI output feeds into for colors and formatting.
- [Database](database.md) - the `SnFuture` type `applyOnMain` shares.
- [Quickstart](../quickstart.md) and the [developer overview](../README.md).
