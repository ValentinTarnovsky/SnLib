# Configuration Files

Every Sn plugin stores its settings in YAML files (`config.yml` and often a few others). SnLib gives all of them the same managed behavior, so once you understand how one Sn plugin treats its config, you understand all of them. The short version: your edits are safe, new settings appear automatically when a plugin updates, and a broken file never crashes the server.

## Managed YAML with always-merge

There is no `config-version` number to track, and you never have to delete your config after an update to "get the new options". Instead, on every startup, each Sn plugin structurally compares the config file on your disk against the fresh copy bundled inside its jar.

- Any key that exists in the bundled default but is missing from your file gets inserted at its correct position.
- Every value you have already set is preserved exactly as you left it.
- Every comment you wrote, and every comment in the default, is preserved.
- Any extra key you added that is not in the default is left untouched.

The result is that when a plugin author adds a new option in an update, that option simply appears in your file the next time the server starts, sitting in the right place with its explanatory comment, and set to its default value. You did not have to do anything, and none of your existing settings changed. This is called "always-merge": the defaults are merged in, never forced over the top of what you have.

{% hint style="info" %}
Because keys are inserted at their anchored position rather than appended to the bottom, your config file stays readable and organized after an update instead of accumulating a messy pile of new keys at the end.
{% endhint %}

## Automatic backups before a merge

Right before a plugin actually changes a file by inserting missing keys, it first saves a timestamped copy of the current file:

```
old-config-20260713-142530.yml
```

The name follows the pattern `old-<name>-<timestamp>.yml`, and only the last 3 backups per file are kept, so they do not pile up forever. Two things are worth knowing:

- A backup is only made when there is genuinely something to insert. If your file already has every key and nothing changes, no backup is created, nothing is written, and nothing is logged.
- These backups sit right next to the config file, so if an update ever surprises you, the exact state of your file before the merge is one file away.

## A corrupted file never crashes the server

YAML is whitespace-sensitive, and it is easy to break a file with a stray tab or a misplaced colon. If your config file no longer parses as valid YAML, an Sn plugin does not throw an exception and refuse to start. Instead it does the safe thing:

1. It moves your broken file aside, renaming it to `<name>.backup-N` (for example `config.backup-1`). The number is the first free integer starting from 1, so an earlier backup is never overwritten.
2. It regenerates a clean, working file from the defaults bundled in the jar.
3. It logs a single warning in the console telling you exactly what happened and where the old file went.

The warning reads roughly:

```
[update-configs] config.yml does not parse as YAML: backed up at config.backup-1 and regenerated from the jar
```

You end up with a working server and a preserved copy of your broken file. Open the `.backup-N` file, find the mistake (comparing it against the freshly regenerated one usually makes it obvious), and reapply your settings. See [Troubleshooting](troubleshooting.md) for the full recovery steps.

## The `update-configs` master switch

Every Sn plugin's own config contains a master switch that controls this whole merging behavior:

```yaml
update-configs: true
```

- When `true` (the default), missing keys are merged into your files as described above.
- When `false`, the plugin will not touch your files to add keys. Instead, on startup it counts how many keys are missing and logs a warning telling you, but it leaves the files exactly as they are.

Setting it to `false` is useful if you want full manual control over your files and prefer to be told about new options rather than having them added for you. You still get the corruption protection either way.

{% hint style="warning" %}
There is one deliberate exception. The plugin's own config file (`config.yml`) is always exempt from this gate and is always merged, even when `update-configs` is `false`. This is necessary because the `update-configs` key itself lives in that file - if the plugin refused to merge its own config, a brand-new install (or a config missing the key entirely) could never receive the switch that turns the behavior on or off. So the gate governs every other managed file, but never the config that holds the gate.
{% endhint %}

## Placeholders inside config values

Anywhere an Sn plugin reads a text value from config, you can put placeholders inside the string and they will be resolved when the value is used:

- SnLib's own local placeholders, provided by the plugin.
- [PlaceholderAPI](permissions-and-updates.md) placeholders (the `%...%` style), when PlaceholderAPI is installed on your server.

This lets you write things like player names, counts, balances, or server stats directly into messages and titles in your config, without any code changes. If PlaceholderAPI is not installed, its placeholders are simply left as-is rather than causing an error.
