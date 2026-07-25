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

## Something I deleted from a config keeps coming back

That is the auto-updater doing its job: it compares your file against the defaults inside the jar on every start and re-inserts anything missing, so the plugin never runs on a config that lacks a key it needs.

Whether a deletion should stick depends on what you deleted:

- **A plugin setting** (a number, a toggle, a message). It is part of the plugin's structure and will always come back. If you want it inactive, set it to the value that disables it rather than deleting the line.
- **An entry in a section you are meant to fill yourself** - a point type, a world, a reward. Those sections are marked with a `# sn:extensible` comment line above them, and inside a marked section your deletions ARE permanent. If your deletion keeps reverting and the section has no marker, the plugin does not consider those entries yours; report it to the plugin author rather than fighting the file.

See [Sections that are yours](configuration-files.md) for the full rules, including how to end up with zero entries in a marked section.

If you want to stop ALL merging in every file, set `update-configs: false` in the plugin's `config.yml`. The plugin will then only warn about missing keys instead of adding them.

## SnLib said an update was installed, but after restarting I still have the old version

**Affects SnLib 1.16.0 and 1.16.1 only, on Paper 1.20.5+ (and forks of it).** Those two versions
looked up their own jar the wrong way: on a server that remaps plugins, they wrote the new jar into
the `plugins/.paper-remapped/` cache instead of `plugins/`. The console still printed

```
SnLib 1.16.1 installed on disk; restart the server to activate it (running 1.16.0).
```

but the server rebuilds that cache from `plugins/` on every boot, so the update was discarded and
the old version came back with no error anywhere.

**The fix, once per server:** a broken updater cannot repair itself, so this one update has to be
done by hand.

1. Download `SnLib-1.16.2.jar` (or newer) from the [releases page](https://github.com/ValentinTarnovsky/SnLib/releases).
2. Stop the server.
3. Delete the old `SnLib-*.jar` from `plugins/` and put the new one in its place. Make sure exactly
   one SnLib jar is left there.
4. Optionally delete any stray `SnLib-*.jar` and the `.snlib-update` folder inside
   `plugins/.paper-remapped/` - leftovers from the bug. They are inert, just wasted disk.
5. Start the server. `/snlib update` should now report the installed version as 1.16.2.

From 1.16.2 onward the self-updater replaces the jar in `plugins/` and updates apply on restart as
documented.

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
