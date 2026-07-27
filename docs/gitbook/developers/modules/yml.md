# Configuration (yml)

The `yml` module is SnLib's configuration layer. It has two faces that a consumer developer meets as one feature:

- **`SnYml`** - the reading API: tab-tolerant loading, typed getters with defaults, and placeholder resolution per getter.
- **`YamlUpdater`** - the always-merge auto-update system: it keeps every user's file in sync with the copy bundled in your jar, with no `config-version` key to maintain.

You reach the module through `sn.yml()` once your `SnSpec` declares `config(...)`.

```java
@Override protected SnSpec buildSpec() {
    return SnSpec.builder()
            .config("config.yml")   // enables sn.yml() and the always-merge updater
            .build();
}

@Override protected void onInnerEnable() {
    SnYml cfg = sn().yml().config();
    int maxUses = cfg.getInt("max-uses", 10);
}
```

{% hint style="info" %}
`sn.yml()` is only available when the spec declares `config(...)`. Calling it without that builder throws `UnsupportedOperationException` naming the missing builder.
{% endhint %}

## The four YML kinds

`YmlManager` (the object behind `sn.yml()`) mounts each file in one of four modes. The mode is decided by the FIRST call that mounts a given path; re-mounting the same path in a different mode returns the existing instance and logs one warning.

| Call | Seeded from jar when absent | Merged on every start | Pruned | Use it for |
|------|:---:|:---:|:---:|------|
| `sn.yml().config()` | yes | yes (always, gate-exempt) | no | the main config declared in the spec |
| `sn.yml().managed(name)` | yes | yes (gated by `update-configs`) | no | secondary configs you ship in the jar |
| `sn.yml().seedOnly(name)` | yes | no | no | starter presets the user is meant to edit freely |
| `sn.yml().data(name)` | no | no | no | runtime state your plugin writes itself |

```java
SnYml cfg  = sn.yml().config();                 // config.yml from the spec
SnYml shop = sn.yml().managed("shop.yml");       // ships in the jar, merge-always
SnYml seed = sn.yml().seedOnly("presets.yml");   // copied once, then left alone
SnYml data = sn.yml().data("state.yml");         // never seeded, never merged
```

Choosing between them:

- **`config()`** is your main file. It is always merged so that new keys you add in a release reach the user, and it is exempt from the `update-configs` gate (see below) so the gate key itself can arrive through a merge.
- **`managed(name)`** is for any other file you bundle as a jar resource and want kept up to date. New keys are inserted; user edits and extra keys are preserved.
- **`seedOnly(name)`** copies the jar resource once if the file is missing, then never touches it again. Good for example files or presets the user fully owns.
- **`data(name)`** is never seeded and never merged. Use it for files your plugin creates and writes at runtime (persistent state, caches). There is also `load(name)` for arbitrary reads with the same never-seed/never-merge semantics.

Opt in to pruning with `managedPruning(name)`: it behaves like `managed` but ALSO removes keys that no longer exist in the jar resource. The plain merge never deletes anything, so reach for pruning only when you deliberately want stale keys gone.

## Typed getters

Every getter takes a key and a default. A missing key returns the default silently. A value of the wrong type (or a string that cannot be parsed to the requested number/boolean) returns the default and logs ONE warning naming the file, key, bad value and fallback. Getters never throw and never print a stack trace on bad user input.

```java
int    maxUses = cfg.getInt("max-uses", 10);        // non-numeric -> 10 + WARN
double rate    = cfg.getDouble("rate", 1.5);
long   seed    = cfg.getLong("seed", 0L);
boolean debug  = cfg.getBoolean("debug", false);    // only true/false parse
String title   = cfg.getString("title", "Shop");
List<String> lines = cfg.getStringList("lore", List.of());
```

Extra reading helpers:

- `getSection(key)` returns the raw `ConfigurationSection` (values read from it bypass placeholder resolution).
- `isSet(key)` returns `true` when the key exists in the file, keeping an explicit `0`/`false`/empty value distinguishable from an absent key.

{% hint style="info" %}
Tabs used for indentation are repaired automatically before parsing, with a single warning listing the fixed lines. Block scalars are left untouched. A file that fails to parse entirely keeps its previous in-memory content rather than wiping it.
{% endhint %}

## Placeholder resolution per getter

Every string getter resolves placeholders in this order: local placeholders first, then PlaceholderAPI (PAPI) tokens.

Register local placeholders on the file:

```java
cfg.placeholder("server", () -> Bukkit.getServer().getName());
String motd = cfg.getString("motd", "Welcome to %server%");
```

PAPI behavior depends on the thread and on whether you pass a viewer:

- **Without a viewer** (`getString(key, def)`): PAPI resolves against a null player, so server-scoped tokens like `%server_online%` work.
- **With a viewer** (`getString(key, def, player)`): PAPI resolves per player.
- **Off the main thread**: PAPI tokens are left completely untouched (only local placeholders apply), and the skip is recorded through the debug service. This keeps async callers from ever triggering a PAPI lookup off-thread.

```java
String perViewer = cfg.getString("greeting", "Hi", player);   // %player_name% resolves
String serverMsg = cfg.getString("status", "n/a");            // %server_online% resolves
```

{% hint style="warning" %}
PAPI runs on the main thread only. If you read a config value from an async task, any `%token%` in it stays literal. Read placeholder-bearing values on the main thread, or resolve them later with the [PAPI module](../README.md).
{% endhint %}

### This applies everywhere, not just `config.yml`

Placeholder resolution is a property of `SnYml` itself, not of any particular file. Every managed file - the main config, `managed()`/`seedOnly()`/`data()` files, `items.yml`, every `guis/*.yml`, every `lang/messages_*.yml` - is read through the same `getString`/`getStringList` getters, so a `%placeholder%` works identically no matter which file or which module owns it. A menu's `title`, an item's `display-name` or a `lore` line, a lang message: all resolve local placeholders and PAPI the same way, because under the hood they are all just calls to this module.

{% hint style="info" %}
Appearance fields in menus and items go a step further: `GuiItemDef` and `ItemDef` keep their `SnYml` section and re-read it on every render, so `display-name`, `lore` and every other string resolve PER VIEWER, live, through this same pipeline (locals, PAPI, then `[small]`/`[rgb]`/`[center]`/MiniMessage - see [Text rendering](text.md)). Action lines (`click-actions`, `right-click-actions`, ...) and requirement expressions (`click-requirements`, `interact-requirements`, ...) are the one exception: they are parsed ONCE from the raw section, bypassing this resolution, and their own `%placeholder%` tokens are resolved later by the action/requirement engines at the moment they run - see [Actions and Requirements](actions-and-requirements.md). Either way, the practical result is the same: you can put a placeholder in any string field of any Sn YAML file and it works.
{% endhint %}

## Reload hooks

Register a callback to re-cache derived state whenever the file is reloaded:

```java
cfg.onReload(this::recache);
```

Hooks fire after `reload()` re-reads the file from disk. A hook that throws is caught and logged, never propagated.

## Writing and saving

`set(key, value)` changes the in-memory value; `save()` persists it.

```java
cfg.set("last-run", System.currentTimeMillis());
cfg.save();
```

`setComments(key, lines)` sets the block comments rendered above a key (one list entry per line, no leading `#`; null clears them), and `setInlineComments(key, lines)` the inline comments after its value. Comments round-trip through load and `save()`, so setup code that writes keys at runtime (for example a wizard writing into a seedOnly file) can ship the same per-key documentation as a shipped resource:

```java
cfg.set("maps.arena2.end-teleport", "");
cfg.setComments("maps.arena2.end-teleport",
        java.util.List.of("Everyone is teleported here when the round ends.",
                "Leave blank to send each player back to where they were."));
cfg.save();
```

`save()` is asynchronous with coalescing: it snapshots the serialized text on the calling thread and writes it off-thread, keeping at most one pending write per file (a newer save replaces the pending snapshot). This means you can call `save()` freely without piling up disk I/O.

During teardown the behavior changes deliberately. Once the owning context is shutting down, `save()` writes SYNCHRONOUSLY on the calling thread, and the context's teardown calls `flush()` to drain any write that was still pending. A snapshot older than one already written never overwrites newer state, so the async drain and the synchronous teardown save cannot race into a stale file.

{% hint style="info" %}
You never call `flush()` yourself. It runs as part of the library's ordered teardown so no coalesced write is lost when the server stops.
{% endhint %}

## Always-merge auto-update

Every managed file (including `config()`) is reconciled against the copy bundled in your jar on every startup and on every reload. There is NO `config-version` key. The updater compares the jar resource to the file on disk structurally and inserts only what is missing.

What the merge guarantees:

- **User values are preserved.** Only keys absent from the disk file are added; existing values are never rewritten.
- **Extra keys are preserved.** Keys the user added that are not in the jar resource stay put (unless you opted into pruning).
- **Comments are preserved.** The merge is line-based, so comments and blank lines survive.
- **Anchored insertion.** A missing key is inserted right after the nearest preceding sibling that both files share; failing that, right before the nearest following shared sibling; failing that, at the end of the parent section. New keys land where they belong structurally, not appended blindly at the bottom.

### Owner-owned sections (`# sn:extensible`)

The guarantees above are right for keys YOUR plugin owns. They are wrong for a section whose **entries belong to the server owner** - a `points:` map of scoring types, a `worlds:` map of RTP destinations, a catalogue of rewards. There the entries you ship are examples, not schema, and re-inserting one the owner deleted is a bug: additions survive forever but deletions come back on every boot.

Declare those sections with a `# sn:extensible` comment line in the jar resource, on its own line, inside the key's comment block:

```yaml
# Point types. Each entry is a type clans accumulate.
# sn:extensible
points:
  kills:
    display: "&#ff7e75Kills"
    triggers:
      player-kill: 1
  mobkills:
    display: "&#8354f2Mob Kills"
    triggers:
      mob-kill: 1
```

The updater then **never inserts and never prunes anything below that key**:

- An entry the owner deletes (`mobkills`) stays deleted.
- An entry the owner adds (`raids`) survives, as it already did.
- The rule covers the whole subtree, so deleting `points.kills.triggers.death` sticks too - one marker per user-owned section is enough.
- The marked key itself is still schema: deleting `points:` entirely restores the block with its examples. To keep zero entries, leave the section empty (`points: {}`) - that is preserved.
- `managedPruning` honors it as well: owner entries are data, not stale keys.

For a file whose EVERY top-level key is an entry id (an items file, a catalogue), put `# sn:extensible-root` in the file header instead, before the first key:

```yaml
# Custom items shipped with the plugin.
# sn:extensible-root

epic_sword:
  material: DIAMOND_SWORD
```

It works in any yml the updater touches: the main config, `managed`/`managedPruning` files, `guis/*.yml`, `lang/`, `items.yml`.

{% hint style="warning" %}
The flip side of the subtree rule: a sub-key you add later inside a shipped entry does NOT reach servers that already have that entry. That is intentional - entries the owner created themselves could never receive it either - so read every sub-key of an entry with a default, always.
{% endhint %}

### Who may declare it (since 1.19.0)

Both sides may, and the two only ever **add** protection:

| Declared in | Means | Effect |
|---|---|---|
| the jar **resource** | you, the author, stating the entries are the owner's | binding: the owner cannot switch merging back on by deleting the comment from their file |
| the **disk** file | the server owner freezing that subtree in their own copy | the keys you would have inserted there are withheld, and SnLib reports it once per boot |

There is no way to *remove* protection, only to add it, so an author-declared section stays declared no matter what the disk file says. Before 1.19.0 only the resource counted and a marker typed on disk was inert.

The owner-declared case is reported so a frozen section never fails silently:

```
[update-configs] main.yml: 3 key(s) not inserted because the file declares sn:extensible at 'items'
```

It fires only while keys are actually being withheld, so a file the owner froze after it was already complete logs nothing. Design consequence for you: **read every key with a default anyway**. An owner can freeze any section of any file, so "the merge guarantees this key exists" was never a safe assumption, and it is now clearly not one.

A marker on a key that holds a plain value (`# sn:extensible` above `max-uses: 10`) protects nothing and is always a mistake; SnLib logs one WARN naming the key, for the resource and for the disk file alike (a finding present in both is reported once, not twice). An empty section (`cores: {}`) is a legitimate empty catalogue and is never reported.

### Backups

Before writing a merge (and only when there is something to insert), the disk file is copied to `old-<name>-<timestamp>.yml` next to it. The updater keeps the last 3 such backups per file and deletes older ones.

### Corrupt-file recovery

If the disk file does not parse as YAML, it is moved aside to `<name>.backup-N` (N increments so nothing is overwritten), a fresh copy is seeded from the jar, and a warning is logged. The caller never crashes.

### The `update-configs` master switch

The consumer's config carries a boolean master gate:

```yaml
# Master gate of the always-merge updater: false skips every yml merge except this file.
update-configs: true
```

When `update-configs: false`, managed files are NOT modified. Instead the updater counts the missing keys and logs a warning per file, so you know an update is pending without touching anything. The gate is read straight from disk before any merge; an absent key or file counts as `true`.

{% hint style="warning" %}
The consumer's own config file is EXEMPT from this gate: it is always merged. That exemption exists so the `update-configs` key itself can arrive through a merge on the first start after an upgrade. Every OTHER managed file honors the switch.
{% endhint %}

### Pruning (opt-in)

The default merge never deletes. To also remove keys that no longer exist in the jar resource, mount the file with `managedPruning`:

```java
SnYml strictShop = sn.yml().managedPruning("shop.yml");
```

Pruning removes each disk key (and its comments) whose path is absent from the resource. Use it only when you want the file to track the jar exactly; for user-facing configs the non-deleting default is almost always what you want. Pruning stops at every `# sn:extensible` key, so a strict file can still hold a section of owner-owned entries.

{% hint style="info" %}
Config I/O is synchronous by design, but it only runs inside `onEnable` and inside the reload command, never during gameplay. This is the one documented exception to SnLib's async-I/O rule.
{% endhint %}

## Related pages

- [Text rendering](text.md) - every string a getter returns can carry `[small]`, `[rgb]`, `[center]`, legacy and MiniMessage markup.
- [Menus](menus.md) and [Items](items.md) - both are backed by managed YML files that ride this same merge system.
- Back to the [developer guide](../README.md) or the [quickstart](../quickstart.md).
