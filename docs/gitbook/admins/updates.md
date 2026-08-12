# Updates

Two separate systems live on this page, and they never mix. Plugins only ever tell you a newer version exists; SnLib alone keeps its own jar current.

## Plugin update notices (notify-only)

Any Sn plugin can opt into watching its own GitHub releases. Not every plugin turns this on. A plugin that does checks its repository about 60 seconds after startup, then every 6 hours, off the main thread.

When it finds a newer release, it does two things and only two things. It logs one console INFO line:

```
Version <latest> available, installed <current>.
```

And it sends one chat notice to every player holding `<plugin>.admin.update`:

```
<Plugin> has a new version: <latest> (installed <current>)
```

Neither line carries a download link; you only get the versions. The notice fires once per detection, so you are not nagged every six hours. Admins joining later still receive it while the finding stands. A failed check (network down, 403, 404) warns once per repository and then stays quiet.

{% hint style="danger" %}
The checker is strictly NOTIFY-ONLY for every Sn plugin. It never downloads a plugin jar, never modifies a running plugin, and never auto-updates one. Its only job is telling you that a newer version exists.
{% endhint %}

Applying a plugin update is entirely in your hands. You download the new jar, replace the old one on disk, and restart the server.

{% hint style="info" %}
The notice text is fixed English built into the library. It has no language file keys, so you cannot translate or restyle it.
{% endhint %}

### Who receives notices

The node is always the plugin's name in lowercase plus `.admin.update`. For a plugin named `SnClans` that is `snclans.admin.update`.

SnLib delivers the notice, but the permission belongs to the consumer plugin. A plugin that declares the node in its own `plugin.yml` with `default: op` notifies operators out of the box. A plugin that does not declare it notifies only players granted the node explicitly.

> `<plugin>.admin.update` defaults to op only when the plugin declares it in its own plugin.yml. Without the declaration, only players granted the node explicitly are notified.

If notices never reach your staff, grant the node explicitly through your permissions plugin. [Permissions](permissions.md) covers the surrounding `<plugin>.admin` convention.

### Private repositories

A watched repository can be private. Put a read-only GitHub token in the consumer plugin's main config:

```yaml
update-check:
  # Read-only token; sent as a Bearer header, never logged.
  token: "github_pat_your_token_here"
```

The key is read fresh on every check, so a changed token applies without a restart. Leave it out for public repositories.

## SnLib keeps its own jar current

SnLib can download and install its own newer release. This applies to `SnLib.jar` and nothing else: it can never download, move or delete a plugin jar. It is on by default and configured in `plugins/SnLib/config.yml`:

```yaml
auto-update:
  # Master toggle of the self-updater.
  enabled: true
  # Hours between checks; the first one runs 2 minutes after startup. Clamped to 1-168.
  interval-hours: 12
  # Only install releases within the installed major version. A release that
  # crosses a major is only reported, never installed on its own.
  same-major-only: true
```

Set `enabled: false` to update by hand instead. Changes to `enabled` and `same-major-only` apply on the first check after a `/snlib reload`. A changed `interval-hours` re-arms the timer on reload too.

### What a pass verifies

Each pass polls SnLib's own public GitHub repository, off the main thread. A newer release inside the installed major is downloaded and checked before it is trusted:

| Check | The download is rejected when |
| --- | --- |
| Source | The release asset is not served from SnLib's own repository. |
| SHA-256 | The file does not match the digest GitHub publishes for the asset. |
| plugin.yml | The jar's descriptor does not declare SnLib at the exact expected version. |

Any failed check deletes the download and leaves your installed jar untouched. A release that publishes no digest is verified by its plugin.yml alone, and the console says so.

### Staging in `plugins/.snlib-update/`

The download never lands in `plugins/` directly. It streams into a `plugins/.snlib-update/` folder while it is still unverified. Your server scans `plugins/` for jars but never looks inside its subfolders, so a half-finished download can never be loaded. Once the pass ends, the folder is removed when nothing is left in it. Anything you do find there is inert, and the next check cleans it up.

### The swap

The verified jar is moved into `plugins/` first, and only then is the old jar deleted. That order is deliberate: a crash in between leaves two jars and a booting server, never a server without SnLib.

Windows normally keeps the running jar locked, so replacing it in place is refused there. SnLib then hands the verified file to the server's own update folder (`plugins/update/` by default). The server applies it over the installed jar at the next boot.

The file it replaces is always the SnLib jar in `plugins/`. Paper 1.20.5 and newer actually runs a rewritten copy out of a `plugins/.paper-remapped/` cache. Writing there would update nothing: the cache is rebuilt from `plugins/` on every boot.

{% hint style="warning" %}
Two SnLib jars in `plugins/` stop the self-updater completely. It refuses to touch either and says so in the console. Delete the one you do not want; the next check proceeds normally.
{% endhint %}

With `same-major-only: true`, a release that crosses a major version is reported in the console and never installed. Install those by hand: a new library major can change what plugins built against the old one expect.

### After an install

You see one console line, and holders of `snlib.admin.update` get a chat notice. Admins joining later are told too, as long as the restart is still pending.

```
SnLib <latest> installed on disk; restart the server to activate it (running <current>).
```

Installing the file is not the same as running it. The new SnLib only becomes active on the next full server restart. Nothing is ever hot-swapped into a running server; [Installation and Requirements](installation.md) explains why that rule never bends. The point of the self-updater is that the jar already sits in place before your next scheduled restart.

## Checking with /snlib update

`/snlib update` reports the updater's state: enabled or not, the latest version seen, and any version waiting for a restart. It then forces an immediate pass, so you never wait for the timer. The command sits behind `snlib.admin.update`, the same node that receives the notices. [The /snlib Command](snlib-command.md) covers the full output.

## Related pages

* [Permissions](permissions.md): the `<plugin>.admin` convention around the update node.
* [The /snlib Command](snlib-command.md): forcing a check and reading the updater status.
* [Installation and Requirements](installation.md): the restart rule, and installing jars by hand.
* [Configuration Files](configuration-files.md): how `plugins/SnLib/config.yml` is merged and preserved.
* [Troubleshooting](troubleshooting.md): notices that never arrive, and the two-jars warning.
