# The /snlib Command

SnLib has one command of its own, `/snlib`. It is purely diagnostic - it exists so you can inspect what SnLib and its consumer plugins are doing, and so you can reload configuration. It is the only command SnLib registers, and it does not affect gameplay.

```
/snlib <version|plugins|integrations|iteminfo|update|reload>
```

Every subcommand is gated behind its own `snlib.admin.*` permission, all of which default to `op`. See [Permissions and Updates](permissions-and-updates.md) for how the permission scheme works. A subcommand you do not have permission for does not appear in tab-completion or help.

## Subcommands

### `/snlib version`

Shows the installed SnLib version, its API level, and the Minecraft version SnLib detected on this server. This is the first thing to check when a plugin complains about needing a newer SnLib - it tells you exactly what you have installed.

```
SnLib version: 1.3.0 / API level: 2 / Server: 1.21.8-R0.1-SNAPSHOT (detected: 1.21.8)
```

Permission: `snlib.admin.version`.

### `/snlib plugins`

Lists every consumer plugin currently hooked into SnLib - that is, every Sn plugin that has enabled and connected to the shared library. If none are hooked, it says so. This confirms at a glance which of your Sn plugins are actually running on SnLib.

Permission: `snlib.admin.plugins`.

### `/snlib integrations`

Lists the active soft-dependency integrations SnLib has hooked into, such as PlaceholderAPI and Vault, showing which are active and which are inactive. Use this to confirm that, for example, PlaceholderAPI is actually being picked up.

Permission: `snlib.admin.integrations`.

### `/snlib iteminfo`

Dumps the hidden persistent data stored on the item you are currently holding in your main hand. This is a debugging tool for custom items: if an Sn plugin gave you a special item and you want to see the tags it carries (which plugin owns it, its item id, and so on), hold it and run this. Holding air is rejected with a message.

This subcommand is player-only, since it reads the item in your hand.

Permission: `snlib.admin.iteminfo`.

### `/snlib update`

Shows the state of SnLib's self-updater and forces an immediate check instead of waiting for the timer. It reports whether the self-updater is enabled, how often it checks, the installed version, the newest version it has seen, and whether a version is already sitting on disk waiting for a restart.

```
SnLib auto-update: enabled (every 12h)
Installed: 1.15.0
Checking ValentinTarnovsky/SnLib for a newer SnLib...
SnLib 1.16.0 installed on disk. Restart the server to activate it.
```

The check itself runs off the main thread, so the result arrives a moment after the command returns. This only ever concerns `SnLib.jar` - it never touches a consumer plugin. See [Permissions and Updates](permissions-and-updates.md#snlib-keeps-its-own-jar-up-to-date) for the full behavior and the `auto-update` config block.

Permission: `snlib.admin.update` (the same permission that receives update notices).

### `/snlib reload [plugin]`

Reloads configuration. It behaves differently depending on whether you name a plugin:

- `/snlib reload` (no argument) reloads only SnLib's own configuration surface. It does not reload any consumer plugin.
- `/snlib reload <plugin>` delegates to that specific plugin's own reload logic, re-reading its config and language files the same way that plugin's own reload command would.

The plugin name is validated against the set of SnLib plus the currently hooked consumers, so it tab-completes and rejects unknown names cleanly.

Permission: `snlib.admin.reload`.

{% hint style="warning" %}
Reloading re-reads files from disk. It never reloads Java classes. Updating `SnLib.jar` itself always requires a full server restart, never a reload - see [Installation and Requirements](installation.md).
{% endhint %}
