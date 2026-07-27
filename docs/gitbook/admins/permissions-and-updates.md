# Permissions and Updates

Two things that work the same way in every Sn plugin: how admin permissions are named, and how a plugin tells you when a newer version of itself is available.

## The shared permission convention

Every Sn plugin exposes its admin controls behind a consistent permission naming scheme, so learning it once here applies to all of them. The pattern is:

- `<plugin>.admin.<subcommand>` - one child permission per admin subcommand.
- `<plugin>.admin` - a parent node that grants all of the children at once.

Every one of these permissions defaults to `op`, meaning server operators have them out of the box and everyone else does not, until you grant them through your permission plugin.

SnLib itself is the reference example. Its own permissions, straight from its plugin descriptor, look like this:

```yaml
permissions:
  snlib.admin:
    description: Grants every SnLib admin subcommand.
    default: op
    children:
      snlib.admin.version: true
      snlib.admin.plugins: true
      snlib.admin.integrations: true
      snlib.admin.iteminfo: true
      snlib.admin.reload: true
      snlib.admin.update: true
  snlib.admin.version:
    default: op
  # ...one node per subcommand, each default: op
```

So if you want to give a trusted staff member access to everything SnLib exposes, you grant `snlib.admin` and they get all six children. If you only want them to be able to run one subcommand, you grant just that child, for example `snlib.admin.reload`. Because this same structure appears in every Sn plugin (`sngens.admin`, `sntags.admin`, and so on), you set up staff permissions the same way for all of them.

{% hint style="info" %}
Subcommands are permission-gated in tab-completion and in help output too. A subcommand a player does not have permission for does not appear when they press tab and is not listed in the plugin's help, so players only ever see the commands they can actually run.
{% endhint %}

## Update notifications (notify-only)

Any Sn plugin can opt into checking its own GitHub releases for a newer version. Not every plugin turns this on, but when one does, here is exactly what happens.

The plugin checks its own GitHub repository shortly after it enables (about 60 seconds in) and then every 6 hours while the server runs. When it finds that a release newer than the installed version exists, it does two things and only two things:

1. It logs a single INFO line in the console (version only, no link), something like:
   ```
   Version 1.4.0 available, installed 1.3.0.
   ```
2. It sends a one-time chat notice to any admin who holds the `<plugin>.admin.update` permission - both admins already online at detection time and admins who join later - telling them the new version:
   ```
   SomePlugin has a new version: 1.4.0 (installed 1.3.0)
   ```

The `<plugin>.admin.update` permission defaults to `op` when the plugin declares it, so operators get the chat notice automatically. If a plugin does not declare that permission, only players who have been explicitly granted it receive the notice.

The notice is one-time per detection - you are not nagged every six hours. If the check cannot reach GitHub (network down, or a 403/404 response), the plugin logs a single warning for that repository and then stays quiet rather than spamming the log.

### It never updates a plugin by itself

This is the hard, permanent guarantee for **plugins**, and it is worth being completely clear about:

{% hint style="danger" %}
The update-check system is strictly NOTIFY-ONLY for every Sn plugin. It never downloads a plugin jar, never modifies a running plugin, and never auto-updates one. All it ever does is tell you that a newer version exists.
{% endhint %}

When you are notified, the update is entirely in your hands. You decide whether and when to update. To actually apply it you download the new jar yourself, replace the old jar on disk, and restart the server. No plugin is ever swapped out from under a running server, and no plugin code is fetched and executed automatically. The system's only job is to make sure you know an update is out there.

The one thing that does keep itself current is the shared library, `SnLib.jar`, and only itself - never a plugin. That is a separate mechanism with its own switch, described next.

## SnLib keeps its own jar up to date

SnLib can download and install **its own** newer release. This applies to `SnLib.jar` and nothing else: it can never download, move or delete a plugin jar. It is on by default and configured in `plugins/SnLib/config.yml`:

```yaml
auto-update:
  # Master toggle of the self-updater.
  enabled: true
  # Hours between checks; the first one runs 2 minutes after startup. Clamped to 1-168.
  interval-hours: 12
  # Only install releases within the installed major version (1.15.0 -> 1.16.0 yes,
  # 1.x -> 2.0.0 no). A major jump is only reported, never installed on its own.
  same-major-only: true
```

Set `enabled: false` if you would rather do it by hand. Changes to `enabled` and `same-major-only` take effect on the next check after a `/snlib reload`; a changed `interval-hours` re-arms the timer on reload too.

### What it actually does

Every `interval-hours` (12 by default), off the main thread, SnLib asks its own public GitHub repository whether a newer release exists. If there is one, and it is within the same major version, SnLib downloads it and **checks it before trusting it**: the file must match the SHA-256 checksum GitHub publishes for that asset, and the `plugin.yml` inside the downloaded jar must actually declare SnLib at the expected version. If either check fails, the download is deleted and your installed jar is left untouched.

Only after that does it swap the file: the new jar is written into `plugins/` and the old one is deleted. If the operating system refuses to replace the file because the server has it locked (this is the normal situation on Windows), SnLib instead hands the verified jar to the server's own update folder, which applies it at the next boot. Either way you end up with the new version in place.

The download itself never lands directly in `plugins/`. It goes into a `plugins/.snlib-update/` folder while it is still unverified, because your server scans `plugins/` for jars but never looks inside its subfolders - so a download that gets cut halfway can never be loaded by mistake. Once the check is over, whichever way it ended, the folder is removed again. If you do find it sitting there, it is either an update in progress or the remains of one that was interrupted; it is inert either way, and the next check cleans it up.

The file it replaces is always the SnLib jar sitting in your `plugins/` folder. That matters on Paper 1.20.5 and newer, which keeps a rewritten copy of every plugin in a `plugins/.paper-remapped/` cache and actually runs the copy - a cache the server rebuilds from `plugins/` on every boot, so a jar written there would be thrown away. If your `plugins/` folder somehow contains two SnLib jars, SnLib will not touch either of them and says so in the console; delete the one you do not want and the next check proceeds normally.

You then see one console line and a chat notice to admins holding `snlib.admin.update`:

```
SnLib 1.16.0 installed on disk; restart the server to activate it (running 1.15.0).
```

Admins who join later get the same notice while the restart is still pending.

{% hint style="danger" %}
Installing the file is not the same as running it. The new SnLib only becomes active on the next **full server restart**. Nothing is ever hot-swapped into a running server - see [Installation and Requirements](installation.md) for why that rule can never be relaxed. The purpose of the self-updater is that the new jar is already sitting in place before your next scheduled restart, so there is no manual step between a release and an updated server.
{% endhint %}

### Checking on it

`/snlib update` reports whether the self-updater is enabled, the interval, the installed version, the latest version it has seen, and whether a version is already on disk waiting for a restart. It also forces an immediate check, so you do not have to wait for the timer. It uses the `snlib.admin.update` permission - the same one that receives the notices.

A major version jump (for example 1.x to 2.0.0) is never installed automatically while `same-major-only` is on, because a new major of the library can change what plugins built against the old one expect. Those you install by hand.
