# Configuration Files

Every Sn plugin stores its settings in YAML files: `config.yml` and often a few more. SnLib gives all of them the same managed behavior, so one plugin's config habits are every plugin's. The guiding principle: your edits are safe, new options appear by themselves, and a broken file never crashes the server.

> Your edits are never overwritten. A managed file only ever gains keys you were missing; it never changes a value you set.

## Managed YAML with always-merge

There is no `config-version` number to track, and you never delete a config to "get the new options". On every startup, each Sn plugin structurally compares the file on your disk against the fresh copy inside its jar.

- Any key present in the bundled default but missing from your file is inserted at its correct position.
- Every value you set is preserved exactly as you left it.
- Every comment is preserved: yours and the plugin's alike.
- Any extra key you added that the default does not know is left untouched.

When a plugin update adds an option, that option simply appears in your file on the next start. It sits in the right place, carries its explanatory comment, and holds its default value. Nothing you set changes. This is always-merge: defaults are merged in, never forced over the top.

{% hint style="info" %}
New keys are inserted at their anchored position, next to the siblings they ship with, never appended to the bottom. Your file stays organized after years of updates instead of growing a pile of strays at the end.
{% endhint %}

## The four file modes

Not every file is merged. The plugin author picks a mode per file. You never configure this, but the mode tells you what to expect from each file.

| Mode | Seeded when absent | Merged on start and reload | Ever deletes your keys |
| --- | --- | --- | --- |
| Managed | Yes | Yes | Never |
| Managed with pruning | Yes | Yes | Yes: keys the plugin removed from its default disappear from your file too |
| Seed once | Yes | Never | Never |
| Data | Never | Never | Never |

Most config, lang and menu files are managed. Data files are the plugin's own runtime storage: stats, arenas, player records; leave those to the plugin. Pruning is rare and deliberate, and [extensible sections](#sections-that-are-yours-sn-extensible) are never pruned.

## Tab indentation is repaired

YAML rejects tabs as indentation, and a single tab normally kills the whole file. SnLib repairs each leading tab to two spaces in memory before parsing, then logs one warning naming the fixed lines:

```
Indentation tabs fixed in config.yml (lines [12, 13])
```

The file on disk keeps its tabs until you replace them. Fix the listed lines to silence the warning.

## Automatic backups before a merge

Right before a merge writes a file, the current file is copied to a timestamped backup next to it:

```
old-config-20260713-142530.yml
```

The name follows `old-<name>-<timestamp>.yml`, and only the last 3 backups per file are kept. A backup is only made when there is genuinely something to write. If your file already has every key, nothing is written, backed up or logged.

## A corrupted file never crashes the server

If a file no longer parses as YAML even after tab repair, the plugin does not throw and refuse to start. It does the safe thing:

1. It moves your broken file aside as `<name>.backup-N`, for example `config.yml.backup-1`. The number is the first free one, so earlier backups survive.
2. It regenerates a clean, working file from the defaults bundled in the jar.
3. It logs one warning telling you what happened and where the old file went:

```
[update-configs] config.yml does not parse as YAML: backed up to config.yml.backup-1 and regenerated from the jar
```

You end up with a working server and your broken file preserved. Open the backup, find the mistake, and reapply your settings. See [Troubleshooting](troubleshooting.md) for the recovery steps.

## Sections that are yours: `# sn:extensible`

Merging back a key you deleted is right for a plugin setting. It is wrong for entries you are meant to invent: point types, arenas, rewards, catalogue ids. There the shipped entries are examples, and deleting the ones you do not want must stick.

Those sections carry a marker comment directly above them:

```yaml
# Point types. Each entry is a type clans accumulate.
# sn:extensible
points:
  kills:
    display: "&#ff7e75Kills"
  mobkills:
    display: "&#8354f2Mob Kills"
```

Inside a marked section the plugin stops managing anything:

- Delete `mobkills` and it stays deleted, on every restart, forever.
- Add your own entries and they survive every update.
- The rule covers everything nested inside. Deleting one field of an entry you kept also sticks.

Some files are extensible from top to bottom: an items file, a catalogue, where every top-level key is an entry id. Those carry `# sn:extensible-root` in the file header instead, and the whole file follows the same rules.

> The section heading itself is still schema. Delete `points:` entirely and the block returns with its examples. To keep zero entries, write `points: {}` instead.

### Marking the file on disk (1.19.0)

The marker works from either side, and the two sides only ever add protection:

- The plugin shipping it in its jar is the author declaring those entries yours. That is binding: deleting the comment from your file does not switch the merge back on.
- You writing it into your own file freezes that section yourself, even where the author still manages it. Put it on its own comment line, directly above the section key.

```yaml
# sn:extensible
worlds:
  spawn:
    safe: true
```

Your own marker is honored silently. Keys the plugin would have inserted are withheld without any console warning, because you asked for exactly that. The withheld count is still recorded at the FINE log level for diagnosis.

### Caveats

| Caveat | What it means for you |
| --- | --- |
| A shipped marker is permanent | Deleting the comment from your file never re-enables the merge for that section. |
| New keys stop arriving | A setting added inside your frozen section never reaches your file. Add its key yourself, or remove your marker. |
| Deleting the section revives it | The heading is schema. Keep the section empty (`{}`) instead of deleting it. |
| A marker above a plain value protects nothing | It guards the entries of a section. On a `key: value` line it does nothing, and the plugin warns naming the key. |
| The marker must own its comment line | Write `# sn:extensible` alone on the line, directly above the key. Capitalization does not matter; extra words on the line do. |
| Never mark `regions:` in a menu file | Region declarations are plugin schema, not entries you invent. To turn a region off, see [Pagination, Regions and Templates](pagination-regions-templates.md). |

## The `update-configs` master switch

Every Sn plugin's config carries one master switch for the merging behavior:

```yaml
update-configs: true
```

When `true`, the default, missing keys merge into your files as described above. When `false`, the plugin leaves your files alone and logs what it is holding back instead:

```
[update-configs] update-configs is false: 4 keys missing in messages_en.yml
```

Set it to `false` when you want full manual control and prefer being told about new options. Corruption protection and tab repair stay active either way.

{% hint style="warning" %}
The plugin's own `config.yml` is always exempt from this gate and always merges. The `update-configs` key itself lives in that file. If the gate could block it, a config missing the key could never receive the switch that controls the gate.
{% endhint %}

## Reload vs restart

Config edits never need a restart. Every Sn plugin has a `reload` subcommand that re-runs the merge and re-reads its files from disk. `/snlib reload <plugin>` does the same for any consumer; `/snlib reload` alone covers only SnLib's own config. See [The /snlib Command](snlib-command.md) for the exact scope.

> A reload re-reads files, never code. A new jar, SnLib's or any plugin's, only becomes active after a full server restart.

See [Installation and Requirements](installation.md) for why the jar rule can never be relaxed.

## Full example: SnLib's own config.yml

SnLib itself creates exactly one file, `plugins/SnLib/config.yml`. It is managed like everything else, and it is small:

```yaml
# Master gate of the always-merge updater:
# false skips every yml merge except this file.
update-configs: true

# Runtime debug output of the library itself.
# Also toggleable live with /snlib debug, no restart needed.
debug:
  # Master toggle of the debug output.
  enabled: false
  # Verbosity threshold: OFF, INFO, DEBUG or TRACE.
  level: DEBUG
  # Category filter; an empty list lets every category through.
  categories: []

# Anonymous usage metrics via bStats (https://bstats.org); set false to opt out.
bstats: true

# Keeps SnLib.jar itself up to date: downloads its own newer release,
# verifies it, stages it on disk and reports that a restart is pending.
# It never swaps code at runtime and never touches other plugins' jars.
auto-update:
  # Master toggle of the self-updater.
  enabled: true
  # Hours between checks; the first runs 2 minutes after startup. Clamped to 1-168.
  interval-hours: 12
  # Only install releases within the installed major version.
  # A major jump is reported, never installed on its own.
  same-major-only: true
```

| Block | What it controls | Where it is explained |
| --- | --- | --- |
| `update-configs` | The merge gate for every managed file | This page, above |
| `debug` | SnLib's own log verbosity and categories | [The /snlib Command](snlib-command.md) |
| `bstats` | Anonymous usage metrics opt-out | This row is its full documentation |
| `auto-update` | The self-updater that keeps `SnLib.jar` current | [Updates](updates.md) |

SnLib re-reads the `auto-update` block on every check pass, so an edit plus a reload applies before the next pass.

## Blocks you will meet in a consumer's config.yml

Consumer plugins add their own blocks on top of this shared machinery. These appear across the whole fleet, and each one has its own page:

| Block | What it does | Owner page |
| --- | --- | --- |
| `lang` | Picks the server-wide language for the plugin's messages | [Language Files](language-files.md) |
| `command.aliases` | Renames, adds or removes the plugin's command aliases | [Customizing Commands](customizing-commands.md) |
| `database` | SQLite or MySQL connection settings | [Database Connection](database.md) |
| `update-check` | Update notices for that plugin, plus a token for private repos | [Updates](updates.md) |
| Selection wand section | The region selection tool some plugins offer | [Physical Items](physical-items.md) |

{% hint style="warning" %}
Consumer plugins running on a Velocity proxy merge their configs without preserving comments. Your values survive there; your comments do not. Paper and Spigot servers are unaffected.
{% endhint %}

{% hint style="info" %}
Every text value in every managed file accepts colors and placeholders, from menu titles to lang messages. See [Text, Colors and Numbers](text-formatting.md).
{% endhint %}

## Related pages

- [Language Files](language-files.md): the lang folder follows these same merge rules, plus prefix and translation behavior.
- [The /snlib Command](snlib-command.md): the reload subcommand and the `debug` block that lives in this file.
- [Updates](updates.md): the full story of the `auto-update` block and per-plugin update notices.
- [Troubleshooting](troubleshooting.md): symptom-first fixes for corrupt files, returning keys and held-back merges.
