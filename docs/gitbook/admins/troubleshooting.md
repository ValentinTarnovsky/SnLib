# Troubleshooting

Every entry here has the same shape: the symptom you see, what it means, and the fix. Most lines below are warnings, not errors: the plugin keeps running while you decide. Placeholders in `<angle brackets>` stand for the real names and numbers your console prints.

## A plugin disables itself asking to update SnLib

Right after an Sn plugin enables, it disables itself with a line like:

```
Requires SnLib API level <required> (installed: <installed>). Update SnLib.jar (restart required): https://github.com/ValentinTarnovsky/SnLib/releases
```

**What it means:** your `SnLib.jar` is older than the version the plugin was built against. The plugin disables itself cleanly instead of crashing the server. This is the [version handshake](installation.md) working as intended.

**The fix:** download the latest `SnLib.jar` from that releases page, replace the old jar, and restart the server fully. Run `/snlib version` before and after to confirm. Newer SnLib stays compatible with your other Sn plugins.

## A warning about an unrecognized Minecraft version

At startup you see:

```
[SnLib] '<version>': unrecognized version, assuming compat target
```

**What it means:** you run a Minecraft version newer than the one this SnLib build knows. SnLib degrades gracefully and starts normally.

**The fix:** nothing. It logs once and is safe to ignore. Include it as context when you report a real problem on a brand-new version.

## A config file got renamed to `.backup-N`

A fresh default file appeared and the console warned:

```
[update-configs] config.yml does not parse as YAML: backed up to config.yml.backup-1 and regenerated from the jar
```

**What it means:** your file stopped being valid YAML, so the plugin moved it aside and reseeded a clean default instead of crashing.

**The fix:** open the `.backup-N` file, which is your original preserved exactly. Find the formatting mistake, then reapply your settings into the working file. See [Configuration Files](configuration-files.md) for the full behavior.

{% hint style="info" %}
Indentation tabs are repaired automatically and reported, so look for wrong indentation depth, unclosed quotes or a misplaced colon.
{% endhint %}

## A key you deleted keeps coming back

**What it means:** the merge compares your file against the jar defaults on every start and re-inserts anything missing. This covers `config.yml`, language files and every menu file under `guis/` alike.

> Deletions stick only inside a section marked `# sn:extensible`; everywhere else the merge puts the key back.

**The fix:** to turn a feature off, set the value that disables it instead of deleting the line. To hide a menu button, remove its letter from the layout: see [Menus](menus.md). Marked sections, and marking one yourself, are explained in [Configuration Files](configuration-files.md).

Setting `update-configs: false` stops all merging; the plugin then only warns about what is missing:

```
[update-configs] update-configs is false: <n> keys missing in config.yml
```

## A marker warning about "the entries of a section"

```
'<path>' is marked sn:extensible but holds a value; the marker only protects the entries of a section
```

**What it means:** you put `# sn:extensible` above a plain `key: value` line. The marker protects a section's entries, so on a plain value it protects nothing.

**The fix:** move the marker above a section header, or remove it. See [Configuration Files](configuration-files.md).

## Chat shows `{prefix}` as literal text

```
<n> message key(s) in lang/messages_en.yml embed the literal {prefix} token; SnLib prepends the configured prefix automatically, so the token renders literally - remove it from those values
```

**What it means:** the prefix is automatic on single-line messages. A `{prefix}` you type is not a placeholder and prints as-is.

**The fix:** delete the token from the named keys. To send a line without the prefix, use `[noprefix]` instead: see [Language Files](language-files.md).

## A clickable message no longer reacts to clicks

```
<n> message key(s) in lang/messages_en.yml lost the <click>/<hover> tag their jar default carries (<keys>); the button still renders but clicking it does nothing - restore the tags in those values
```

**What it means:** while editing, you dropped the tags that made the message interactive. The text still shows; the click is gone.

**The fix:** copy the tags back from the default value of each named key. See [Language Files](language-files.md).

## A menu item renders in the wrong cells

```
Item '<id>': declares both 'slots' and 'key'; slots wins and key is ignored
```

**What it means:** the item declares `slots:` and `key:` at the same time, and declared slots always win.

**The fix:** keep exactly one placement form. See [Menu Items and Clicks](menu-items-and-clicks.md).

## A refresh or page button does nothing

There is no console output for this one. With debug raised you see:

```
Action [refresh-menu] skipped: pagination not enabled (opt-in per menu)
```

**What it means:** page actions and `[refresh-menu]` are no-ops unless the menu sets `pagination: true`. Pagination is opt-in per menu.

**The fix:** enable pagination in that menu file. See [Pagination, Regions and Templates](pagination-regions-templates.md).

## A head ignores its custom texture

```
skull-owner and base64 texture defined at the same time for PLAYER_HEAD; skull-owner wins and the base64 texture is ignored
```

**What it means:** the item declares both a `skull-owner` and a texture-based material, and the owner's skin wins.

**The fix:** remove one of the two. See [Item Appearance Reference](item-appearance.md).

## A sound name is rejected

```
[SnLib] Invalid sound '<id>': not resolved by enum nor by Registry.SOUNDS; ignored
```

**What it means:** the sound ID matches nothing on this server version. The sound is skipped; nothing else breaks. A bad volume or pitch logs `Invalid volume/pitch in '<value>'; using 1.0` instead.

**The fix:** use a real sound ID, or `none` to silence deliberately. See [Shared Value Formats](value-formats.md).

## A cron schedule is rejected

The error names the expression, for example:

```
Cron expression '<expression>': expected 5 fields (minute hour day month day-of-week), got 4
```

**What it means:** the schedule does not follow the 5-field cron grammar or one of its shortcuts.

**The fix:** write 5 fields, or `daily HH:mm`, or `hourly :mm`. See [Shared Value Formats](value-formats.md).

## Database trouble

An unknown type falls back to SQLite with a warning:

```
[<Plugin>] invalid database.type: '<type>', using sqlite
```

A failing critical operation disables the plugin:

```
Critical database operation failed; disabling <Plugin>: <error>
```

**Checks:** the credentials key is `username`, not `user`. A `socket-timeout-seconds` of `0` can hang a query forever on a dead link. Every key and default is in [Database Connection](database.md).

## Staff receives no update notices

**What it means:** update notices reach holders of `<plugin>.admin.update`. If the plugin never declares that permission, only players granted it explicitly are notified.

**The fix:** grant the node to your staff, or ask the developer to declare it. [Updates](updates.md) carries the full explanation.

## The self-updater refuses to run

```
plugins/ holds more than one SnLib jar (<first> and <second>); the self-updater will not touch any of them until only one is left.
```

**What it means:** two SnLib jars sit in `plugins/`, so the server picks one at random. The self-updater refuses to gamble on which.

**The fix:** stop the server, delete every SnLib jar except one, start again.

## A self-update disappears after a restart

The console said `SnLib <new> installed on disk; restart the server to activate it (running <old>).`, yet after restarting the old version is back.

**What it means:** early SnLib builds on Paper 1.20.5 and newer wrote the update into the remap cache, which the server rebuilds on every boot. The update was silently discarded.

**The fix, once:** a broken updater cannot repair itself. Download the current `SnLib.jar`, stop the server, replace the jar by hand and start again. Later self-updates then apply normally. Stray SnLib jars inside `plugins/.paper-remapped/` are inert leftovers you may delete.

## An integration hook was disabled

```
Hook '<plugin>' requires version >= <required> (installed: <installed>); hook disabled
```

```
Hook '<plugin>': required class <class> not found; hook disabled
```

**What it means:** the optional plugin it integrates with is present but too old or incompatible. The Sn plugin keeps running without that integration.

**The fix:** update the hooked plugin, then restart. `/snlib integrations` shows what is active: see [The /snlib Command](snlib-command.md).

## Player-typed text loses its colors

That is the style policy gating what players type, not your YAML. See [Text, Colors and Numbers](text-formatting.md).

## Need debug output for a bug report?

The `debug` subcommand raises log verbosity at runtime and persists across restarts. The full how-to lives in [The /snlib Command](snlib-command.md).

## Still stuck?

- Run `/snlib version` and include its output: it pins your SnLib version, API level and detected server version.
- `/snlib plugins` and `/snlib integrations` confirm which Sn plugins and integrations are hooked.
- The restart rule for jar swaps is in [Installation and Requirements](installation.md).

## Related pages

- [Configuration Files](configuration-files.md) - the merge, backups and markers behind most warnings here.
- [The /snlib Command](snlib-command.md) - the diagnostic subcommands and the debug how-to.
- [Installation and Requirements](installation.md) - the version handshake and the restart rule.
- [Updates](updates.md) - the update checker and the self-updater in full.
