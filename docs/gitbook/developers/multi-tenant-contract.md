# Multi-tenant contract

SnLib's classes are shared by every consumer on the server, but each consumer's
state is not. The multi-tenant non-interference contract is the guarantee that
one plugin's registrations, reloads and disables never touch another plugin's
state, nor SnLib's own. This page explains how that isolation is keyed, what the
few deliberate server-wide exceptions are, and how the shared Bukkit listeners
stay single-instance.

## Every stateful registration is keyed by owner

Everything a consumer registers inside SnLib is stored against the owning
`Plugin` instance in a `TenantRegistry`. That covers, among others:

- The per-plugin `Sn` contexts themselves.
- Menus (GUIs) and their open inventories.
- Registered items.
- Commands.
- Soft-dependency hooks.
- Bossbars, holograms, cron jobs and leaderboards.

Because the owning `Plugin` is the key, two consumers that register a menu under
the same id never collide: they are different keys in the registry. A lookup for
one owner never returns another owner's value.

## Disable sweeps the whole key in one shot

When a consumer disables, SnLib sweeps its entire key. `TenantRegistry.sweepOwner`
(paired with detaching the context from the global registry) removes that owner's
entry from every tenant registry at once, and a registry can carry a sweep
callback so that removing a value also releases its resources - force-disabling
that owner's hooks, closing its still-open inventories, and so on, even if the
consumer's own `onInnerDisable()` did not clean up.

Crucially this sweep is triggered by the disable itself, not by cooperative
cleanup, so it works even when a consumer is unloaded externally by a
PlugMan-style tool rather than through a normal server shutdown. In every case
the sweep removes only that one owner's key. It never touches another consumer's
state, and it never touches SnLib's own state.

{% hint style="success" %}
This is what makes `/myplugin reload` and even an external unload of a single
consumer safe on a live server full of other Sn plugins: the blast radius is
exactly one owner key. A consumer reloading or disabling closes its own open
GUIs natively and unregisters its own commands, and the players and state of
every other consumer are untouched.
{% endhint %}

## The narrow server-wide exception

A small number of statics in SnLib are genuinely server-wide and are not keyed by
owner. This is deliberate and narrowly scoped: each of them describes the
**server**, not any one consumer, so there is nothing to key by owner. They are:

- Server-version detection (`SnVersion` / `SnCompat`) - a property of the running
  server.
- The warn-once deduplication used to collapse repeated warnings - a global
  dedup set.
- Content-addressed caches such as the head/lookup caches in `HeadUtil` and
  `PlayerLookup` - keyed by content (a base64 texture, a name), shared safely
  because the same input always yields the same output regardless of which
  consumer asked.

{% hint style="warning" %}
Treat these as a specific, justified exception, not a pattern to imitate. The
test that admits a static into this set is that it describes the server or is a
pure content-addressed cache, with no per-consumer meaning. Anything that holds a
consumer's own state must go through a `TenantRegistry` keyed by owner. When in
doubt, key it by owner.
{% endhint %}

## Shared listeners live once in the ListenerHub

The Bukkit listeners SnLib needs - player join, inventory click, chunk-move, the
armour-equip synthesizer, the item-interact dispatcher, the update-check join
notice, the selection wand and the rest - are not registered per consumer. They
are registered exactly once, by SnLib's own bootstrap plugin (`SnLibPlugin`), and
they live in a single `ListenerHub`. Each listener is a single instance for the
whole server with no per-consumer state; when it needs to act for a specific
consumer it dispatches through the owner-keyed registries described above.

Registering these listeners once, rather than once per consumer, is what keeps a
click or a move from being handled fifty times on a fifty-plugin server. A
consumer never registers these shared listeners itself; it registers its own
behavior (a menu's click actions, an item's interact callback) into the
owner-keyed registries, and the single shared listener routes real Bukkit events
into it.

## The result

Put together: state is keyed by owner and swept as a unit on disable, the only
un-keyed statics are server-wide by nature, and the shared listeners are
single-instance. A consumer can enable, reload and disable - or be force-unloaded
- at any time, and the only state that changes is its own. This isolation is the
counterpart to the [threading rules](threading-model.md): threading keeps one
consumer's work from blocking the server, and this contract keeps one consumer's
state from leaking into another's.
