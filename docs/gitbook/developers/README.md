# Developers

SnLib is a common library for the Sn family of Minecraft plugins, shipped as a
single standalone plugin. This section is for Java developers who want to build
a new plugin on top of SnLib, or who want to understand SnLib's internals well
enough to contribute to it.

## The standalone hard-depend model

SnLib is not a shaded library. It is a plugin. One `SnLib.jar` lives in the
server's `plugins/` folder and loads at `STARTUP`, before any consumer. Every
Sn plugin that uses it does two things:

1. Declares `depend: [SnLib]` in its own `plugin.yml`, so the server refuses to
   enable the consumer unless `SnLib.jar` is present and loaded first.
2. Compiles against the `com.sn:snlib` Maven artifact with `provided` scope, so
   the SnLib classes are on the compiler's classpath but are never copied into
   the consumer's jar.

Nothing from SnLib is ever shaded into a consumer jar. At runtime the SnLib
classes come from the one `SnLib.jar` installed on the server, loaded by SnLib's
own plugin classloader. A server with fifty Sn plugins has exactly one copy of
the yml engine, the menu engine, the database drivers and every other module,
shared by all of them.

{% hint style="warning" %}
Updating `SnLib.jar` requires a full server restart. `/snlib reload` never
reloads classes, and the library must never be hot-swapped: its classloader is
shared with every consumer on the server, so replacing it live would break all
of them at once.
{% endhint %}

## Dual-platform: one jar, Paper and Velocity

The exact same `SnLib.jar` is both a Paper plugin (via `plugin.yml`) and a
Velocity plugin (via `velocity-plugin.json`, entry point `SnLibVelocity`). Drop
it into a Paper backend's `plugins/` and it provides the full module catalogue
below. Drop the identical file into a Velocity proxy's `plugins/` and it
provides a small platform-neutral base (config, text, scheduler, commands) for
consumer plugins that run on the proxy.

Class loading is lazy and per-platform: on Velocity only the
`com.sn.lib.velocity.*` classes and the shared text pipeline load, and the
Bukkit-bound classes never touch the proxy classloader. A Paper backend and its
Velocity proxy can therefore share a single dependency jar. A Paper-only
consumer never needs the proxy side. See [Velocity base](modules/velocity-base.md)
for the proxy API.

## One classloader, per-plugin contexts

Although the classes are shared, each consumer gets its own isolated state. The
single public entry point is the `com.sn.lib.SnPlugin` base class every consumer
extends. When a consumer enables, SnLib builds it a private context object - a
`Sn` instance - through which the consumer reaches only its own config, its own
menus, its own database and so on. The consumer reaches these modules with
`sn()` from inside its lifecycle methods.

Underneath the per-plugin contexts, the stateful registries are shared but
tenant-aware: every registration (a menu, an item, a command, a hook) is keyed
by the owning `Plugin` instance, so one consumer disabling never disturbs
another. The shared Bukkit listeners (join, click, chunk-move and the rest) are
registered exactly once by SnLib's own bootstrap. This is the multi-tenant
non-interference contract, described in full on
[Multi-tenant contract](multi-tenant-contract.md).

## Module catalogue

Every module below is opt-in. A consumer declares the modules it uses in its
`SnSpec` (see [Quickstart](quickstart.md)), and accessing a module it did not
declare throws `UnsupportedOperationException` naming the missing builder call.
Some modules (utils, selections) are always available with no spec gate.

| Module | What it does |
|--------|--------------|
| [YML config](modules/yml.md) | Managed YAML with tab repair, typed getters, per-getter placeholder resolution, always-merge config auto-update and async coalesced saves. |
| [Text](modules/text.md) | MiniMessage plus `[small]` small caps, `[rgb]` gradients and `[center]` centering, rendered through one fixed-order pipeline. |
| [Menus](modules/menus.md) | File-driven chest GUIs from a `guis/` folder, per-viewer sessions, opt-in pagination, ASCII layouts, a full per-click matrix and NBT anti-theft. |
| [Items](modules/items.md) | Physical items from YML or a programmatic `ItemDef.builder()`: interact callbacks, locking, custom durability, held effects, recipes and cooldowns. |
| [Commands](modules/commands.md) | A root/sub command tree, everything tab-completable and gated by permission, reload-safe per owner. |
| [Database](modules/database.md) | SQLite or MySQL through HikariCP, async query/update, `thenSync` hop to the main thread, player-data caches. |
| [PlaceholderAPI](modules/papi.md) | A fail-open PAPI bridge (untouched text when PAPI is absent) plus declarative expansions and an async main-thread apply. |
| [Lang](modules/lang.md) | Per-language message files with always-merge, per-key fallback to English, and helpers for messages, action bars and titles. |
| [Debug and scheduler](modules/debug-and-scheduler.md) | A runtime-toggled debug logger with levels, and a Folia-aware scheduler whose `thenSync` hops to the main thread under an `isEnabled()` guard. |
| [Actions and requirements](modules/actions-and-requirements.md) | YML `[tag] argument` action lines and a recursive-descent requirement parser over placeholders, with custom tag registration. |
| [Cooldowns, economy and utils](modules/cooldowns-economy-utils.md) | Boxing-free cooldowns, a Vault-or-command economy backend and the pure/Bukkit utility catalogue. |
| [Custom events](modules/custom-events.md) | `SnArmourEquipEvent`, `SnChunkMoveEvent` and `SnSelectionCompleteEvent`, synthesized from real Bukkit sources. |
| [Bossbars, holograms, cron, leaderboards, Discord](modules/bossbars-holograms-cron-leaderboards-discord.md) | Adventure bossbars, TextDisplay holograms, a cron scheduler, snapshot leaderboards and a webhook sender. |
| [Update checker](modules/update-checker.md) | Notify-only GitHub release checking for the consumer's own repo; never downloads or swaps anything. |
| [Region selection](modules/region-selection.md) | Visual cuboid wand selections for any consumer, with an immutable `Cuboid` type. |
| [Velocity base](modules/velocity-base.md) | The proxy-side counterpart: `Snv` context, managed config, scheduler and commands, sharing the same text pipeline as Paper. |

For the administrator-facing view of what these modules produce (config files,
menu YAML, lang files and the `/snlib` command), see the
[admins section](../admins/README.md).

## Where to go next

Start with the [Quickstart](quickstart.md): it walks through building a minimal
SnLib consumer plugin from an empty Maven project to a working `SnPlugin`
subclass. Then read [Compatibility and versioning](compatibility-and-versioning.md)
to understand the runtime floor and the API-level handshake, and
[Threading model](threading-model.md) for the rules a well-behaved consumer must
follow on a server shared with many other plugins.
