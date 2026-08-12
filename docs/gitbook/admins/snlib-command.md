# The /snlib Command

`/snlib` is the only command SnLib registers, and it is purely diagnostic: it inspects, it reloads files, it never touches gameplay. You use it to see what the library and its consumer plugins are doing.

```
/snlib <version|plugins|integrations|iteminfo|update|reload|debug|help>
```

## Subcommands and permissions

| Subcommand | What it does | Permission |
|---|---|---|
| `version` | Shows the library version, API level and server version | `snlib.admin.version` |
| `plugins` | Lists the consumers hooked to SnLib | `snlib.admin.plugins` |
| `integrations` | Lists the registered soft-dependency hooks | `snlib.admin.integrations` |
| `iteminfo` | Dumps the PDC tags of the held item | `snlib.admin.iteminfo` |
| `update` | Shows the self-updater state and forces a check | `snlib.admin.update` |
| `reload [plugin]` | Reloads the library's own surface or one consumer | `snlib.admin.reload` |
| `debug` | Toggles runtime debug output | `snlib.admin.debug` |

Every node defaults to op. The parent node `snlib.admin` grants the first six in one line. See [Permissions](permissions.md) for the fleet-wide scheme.

> Granting `snlib.admin` does not grant `snlib.admin.debug`. The debug node is not declared as a child, so it stays ops-only until you grant it explicitly.

A subcommand you lack permission for stays out of tab completion and help. `/snlib help` lists the subcommands available to you and needs no permission.

### `/snlib version`

The first thing to check when a plugin complains about needing a newer SnLib.

```
SnLib version: <version>
API level: <level>
Server: <bukkit-version> (detected: <minecraft-version>)
```

On Folia the detected version carries a `Folia` suffix. The API level is the number every Sn plugin checks at enable time. [Installation and Requirements](installation.md) explains that handshake.

### `/snlib plugins`

Lists every Sn plugin currently hooked to the shared library, sorted by name.

```
Consumers hooked to SnLib (<count>):
- <PluginName> v<version>
```

With nothing hooked it prints `No consumers are hooked to SnLib.`

### `/snlib integrations`

Lists every soft-dependency hook your Sn plugins registered, such as PlaceholderAPI or Vault, with its live state.

```
Registered integrations (<count>):
- <PluginName> -> PlaceholderAPI: active
- <PluginName> -> Vault: inactive
```

The Vault rows also tell you how economy actions resolve. Plugins pick the first available backend: Vault, then a command backend, then a custom one.

### `/snlib iteminfo`

Dumps the hidden persistent data (PDC tags) of the item in your main hand. It is player-only, and holding air is rejected with a message.

```
PDC tags of <material> (<count>):
- <plugin>:snlib_item_id = <item-id>
```

Items managed by an Sn plugin carry tags like `<plugin>:snlib_item_id`, so this shows which plugin owns an item and under which id. See [Physical Items](physical-items.md) for those items. A tag that is not a string renders as `<non-string tag>`.

### `/snlib update`

Prints the self-updater state, then forces an immediate check instead of waiting for the timer.

```
SnLib auto-update: enabled (every <hours>h)
Installed: <version>
Latest seen: <version>
Pending restart: <version> is already on disk and activates on the next restart.
Checking ValentinTarnovsky/SnLib for a newer SnLib...
```

`Latest seen` appears once a check has seen a release. `Pending restart` appears only while a downloaded jar waits for a restart. The check runs off the main thread, so its result arrives a moment after the command returns.

| Result line | Meaning |
|---|---|
| `SnLib is up to date (<version>).` | No newer release exists |
| `SnLib <version> installed on disk. Restart the server to activate it.` | The new jar was just downloaded and placed |
| `SnLib <version> is already installed on disk; restart the server to activate it.` | An earlier check already staged it |
| `SnLib auto-update is disabled in config.yml.` | The forced check refuses to run while disabled |

This only ever concerns `SnLib.jar`, never a consumer plugin. Holders of `snlib.admin.update` also receive SnLib's update notices; the `auto-update` config block lives in [Updates](updates.md).

### `/snlib reload [plugin]`

Reloads configuration from disk. The scope depends on whether you name a plugin.

> `/snlib reload` without a name reloads SnLib's own surface only. It never touches a consumer plugin's files.

```
SnLib configuration reloaded (debug + bstats).
A reload never reloads classes: updating SnLib.jar requires a server restart.
```

The second line is the library restating its hard rule; [Installation and Requirements](installation.md) owns it. With a plugin name, the command delegates to that plugin's own reload logic instead:

```
Configuration of <PluginName> reloaded.
```

The name tab-completes against SnLib plus every hooked consumer. An unknown name answers `Plugin not found: <name>`. A plugin that exists but never hooked answers `Plugin <name> is not hooked to SnLib.`

### `/snlib debug`

Flips SnLib's runtime debug output and answers with the new state, `Debug: ON` or `Debug: OFF`. No restart is involved; output starts or stops immediately.

> The toggle persists. Whatever `/snlib debug` sets is written back to `config.yml` and survives restarts, until you flip it again.

The debug state lives in `plugins/SnLib/config.yml`:

```yaml
debug:
  enabled: false   # the switch /snlib debug flips
  level: DEBUG     # OFF, INFO, DEBUG or TRACE
  categories: []   # empty list = every category passes
```

#### Verbosity levels

The level is an escalating ladder. Each step up adds a channel to the console output.

| Level | What reaches the console |
|---|---|
| `OFF` | Nothing, even with the toggle on |
| `INFO` | `[SnLib][INFO]` lines |
| `DEBUG` | INFO plus `[SnLib][DEBUG]` lines (the default) |
| `TRACE` | Everything, including `[SnLib][TRACE]` lines |

An invalid `level` value logs one WARN and falls back to `DEBUG`.

#### Category filter

Categories are free-form labels the code attaches to its debug lines; they appear as `[category]` in the output. An empty `categories` list lets every category through. Listing categories narrows the output to only those, matched case-insensitively.

The command flips `enabled` and nothing else. To change `level` or `categories`, edit the file and run `/snlib reload`. Sn plugins can ship this same subcommand under their own command, gated by `<plugin>.admin.debug`.

## Related pages

- [Permissions](permissions.md): the node scheme behind every subcommand here.
- [Updates](updates.md): the auto-update block behind `/snlib update` and who receives notices.
- [Installation and Requirements](installation.md): the API level handshake `/snlib version` helps you diagnose.
- [Physical Items](physical-items.md): the plugin items whose tags `/snlib iteminfo` dumps.
- [Configuration Files](configuration-files.md): what a reload covers and what needs a restart.
