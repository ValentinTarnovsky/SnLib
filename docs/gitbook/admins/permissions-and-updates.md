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

1. It logs a single INFO line in the console, something like:
   ```
   Version 1.4.0 available, installed 1.3.0: https://github.com/owner/repo/releases
   ```
2. On join, it sends a one-time chat notice to any admin who holds the `<plugin>.admin.update` permission, telling them the new version and giving them the link:
   ```
   SomePlugin has a new version: 1.4.0 (installed 1.3.0) https://github.com/owner/repo/releases
   ```

The `<plugin>.admin.update` permission defaults to `op` when the plugin declares it, so operators get the join notice automatically. If a plugin does not declare that permission, only players who have been explicitly granted it receive the notice.

The notice is one-time per detection - you are not nagged every six hours. If the check cannot reach GitHub (network down, or a 403/404 response), the plugin logs a single warning for that repository and then stays quiet rather than spamming the log.

### It never updates anything by itself

This is the hard, permanent guarantee, and it is worth being completely clear about:

{% hint style="danger" %}
The update-check system is strictly NOTIFY-ONLY. It never downloads a jar, never modifies the running plugin, and never auto-updates anything. All it ever does is tell you that a newer version exists.
{% endhint %}

When you are notified, the update is entirely in your hands. You decide whether and when to update. To actually apply it you download the new jar yourself from the link, replace the old jar on disk, and restart the server. Nothing is ever swapped out from under a running server, and no code is fetched and executed automatically. The system's only job is to make sure you know an update is out there.

{% hint style="info" %}
For SnLib itself, remember that replacing `SnLib.jar` always requires a full server restart, never a reload. See [Installation and Requirements](installation.md).
{% endhint %}
