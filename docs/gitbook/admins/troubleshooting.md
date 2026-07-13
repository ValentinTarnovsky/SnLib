# Troubleshooting

A short guide to the log messages an admin is most likely to run into with SnLib and Sn plugins, what each one actually means, and what to do about it. None of these are common, and most are informational rather than errors.

## A plugin disables itself asking to update SnLib

You start the server and, right after an Sn plugin enables, it immediately disables itself with a line like:

```
[SomePlugin] Requires SnLib API level 3 (installed: 2). Update SnLib.jar
https://github.com/ValentinTarnovsky/SnLib/releases
```

**What it means:** the `SnLib.jar` you have installed is older than the version that plugin was built against. The plugin needs a newer SnLib and, rather than crash the server with cryptic errors, it disabled itself cleanly and told you exactly what it needs. This is the [version handshake](installation.md) working as intended.

**The fix:**

1. Download the latest `SnLib.jar` from GitHub Releases: `https://github.com/ValentinTarnovsky/SnLib/releases`.
2. Replace the old `SnLib.jar` in your `plugins/` folder with it.
3. Restart the server (a full restart - not a reload).

To confirm what you currently have before and after, run `/snlib version`. Newer SnLib versions stay compatible with your other plugins, so updating the library is safe for everything already on the server.

## A warning about an unrecognized Minecraft version

You see a single warning at startup saying SnLib does not recognize your Minecraft version, typically on a very new release.

**What it means:** you are running a Minecraft version newer than the one SnLib was last tested against (its target is 1.21.8). SnLib is deliberately built to degrade gracefully on unknown newer versions rather than hard-fail, so it starts normally and just notes that it has not seen this version before.

**What to do:** nothing, in most cases - it is safe to ignore, and it is logged once, not repeatedly. It is simply letting you know you are ahead of what has been verified. If you do run into an actual problem on a brand-new version, that warning is useful context to include when you report it.

## A config file got renamed to `.backup-N`

You find that a config or language file was renamed to something like `config.backup-1`, a fresh default file appeared in its place, and the console has a warning like:

```
[update-configs] config.yml does not parse as YAML: backed up at config.backup-1 and regenerated from the jar
```

**What it means:** your file was no longer valid YAML (usually a stray tab, a bad indentation, or a misplaced colon), so instead of crashing, the plugin moved your broken file aside and regenerated a clean working one from its defaults. See [Configuration Files](configuration-files.md) for the full behavior.

**The fix:**

1. Open the `<name>.backup-N` file - that is your original, broken file, preserved exactly.
2. Compare it against the freshly regenerated default to spot the formatting mistake (an indentation or a tab is the usual culprit).
3. Reapply your custom settings into the working file, correctly this time, and reload the plugin.

{% hint style="info" %}
YAML is whitespace-sensitive and does not allow tab characters for indentation. If you edited a file in an editor that inserted tabs, that is very often the cause.
{% endhint %}

## Getting debug output to report a bug

If a plugin developer asks you for detailed logs to diagnose a problem, the source of that detail is the `debug` subcommand. Not every plugin has it - it is present only on plugins that opt into a debug command - but where it exists it lets you raise the log verbosity at runtime without restarting.

The verbosity levels, from quietest to loudest, are:

| Level | Meaning |
|---|---|
| `OFF` | No debug output (the normal state). |
| `INFO` | High-level notes. |
| `DEBUG` | Detailed internal steps. |
| `TRACE` | The most verbose, fine-grained tracing. |

Turn debug up on the relevant plugin, reproduce the problem so the extra detail is written to the console log, then copy that portion of the log for the developer. When you are done, turn it back to `OFF` so the log returns to normal. The setting is remembered in the plugin's config, so check that it is off again if you do not want verbose logs persisting across restarts.

## Still stuck?

- Run `/snlib version` and include its output when reporting an issue - it pins down your SnLib version, API level, and detected Minecraft version.
- Run `/snlib plugins` and `/snlib integrations` to confirm which Sn plugins are actually hooked and which integrations (PlaceholderAPI, Vault) are active.
- Remember that any change to `SnLib.jar` needs a full restart, not a reload. See [Installation and Requirements](installation.md).
