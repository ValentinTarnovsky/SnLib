# Installation and Requirements

SnLib ships as a single file, `SnLib.jar`, that you drop into your server's `plugins/` folder. The guiding principle: you install one SnLib per server, and every Sn plugin shares it. No player ever interacts with it directly; its whole job is to be ready before any Sn plugin enables.

## Requirements

### Java 21 is mandatory

SnLib is compiled for Java 21 and refuses to run on anything older. On Java 17 or below the server fails immediately, before any plugin logic runs:

```
java.lang.UnsupportedClassVersionError: com/sn/lib/SnLibPlugin has been compiled by a more recent version of the Java Runtime
```

This is not a warning you can ignore. The class files themselves are Java 21, so the JVM refuses to load them. Seeing `UnsupportedClassVersionError` on an Sn class always has the same fix: point the server at a Java 21 runtime.

{% hint style="info" %}
Minecraft 1.20.5 and newer already require Java 21 on their own. Only a 1.20.4 server may still run an older Java; SnLib raises the bar to 21 there.
{% endhint %}

### Minecraft and Paper version

SnLib targets Paper. The supported range is:

| | Version |
|---|---|
| Minimum (floor) | 1.20.4 |
| Target | 1.21.8 |

A newer version SnLib does not recognize yet never hard-fails. The server starts normally and logs one forward-compatibility warning. See [Troubleshooting](troubleshooting.md) for that warning and why it is safe to ignore.

The same `SnLib.jar` also loads on a Velocity proxy, with a reduced surface for proxy-side Sn plugins. Everything in this section describes the Paper side unless a page says otherwise.

### Optional integrations

Two separate plugins unlock extra behavior across every Sn plugin. Neither is required; without them the related features quietly do nothing.

| Plugin | What it unlocks |
| --- | --- |
| [PlaceholderAPI](https://www.spigotmc.org/resources/placeholderapi.6245/) | `%...%` placeholders in messages, menus and requirements. An unresolved token is left as written, never an error. |
| Vault | Economy-based requirements and prices, like `%vault_eco_balance%` (through PlaceholderAPI's Vault expansion). |

Confirm what SnLib actually hooked with `/snlib integrations`: see [The /snlib Command](snlib-command.md).

## Installing it

1. Download `SnLib.jar` (see below).
2. Place it in the server's `plugins/` folder.
3. Start the server with a Java 21 runtime.

SnLib declares `load: STARTUP`, so the server loads it in the earliest startup phase, before normal plugins enable. Every Sn plugin depends on SnLib, so the library must be ready first. This ordering is built into the jar; you configure nothing.

> One `SnLib.jar` serves every Sn plugin on the server. Never install a copy per plugin, and never leave two SnLib jars in `plugins/`.

{% hint style="info" %}
Most Sn plugins attach a compatible `SnLib.jar` to their own releases. If a plugin needs a newer SnLib than you have, the matching library is usually right next to its download.
{% endhint %}

## What SnLib creates

On first start, SnLib creates exactly one file of its own:

```
plugins/
  SnLib.jar
  SnLib/
    config.yml
```

That `config.yml` holds the config merge gate, the debug output, bStats and the self-updater. [Configuration Files](configuration-files.md) walks through the annotated file. Every other folder under `plugins/` belongs to a consumer plugin, never to SnLib itself.

## Updating requires a full server restart

This is a hard rule with no exceptions:

> Updating `SnLib.jar` always requires a full server restart. Never hot-swap it, and never expect `/snlib reload` to load a new jar.

A reload re-reads configuration files; it never reloads Java classes. Every running Sn plugin shares the loaded SnLib classes through the same classloader. Swapping the file underneath them is unsupported and leads to broken state. The only correct update path is:

1. Stop the server.
2. Replace `SnLib.jar` with the new one.
3. Start the server again.

Plugin managers that claim to hot-reload jars do not change this rule.

{% hint style="info" %}
SnLib performs step 2 for you by default: it downloads and verifies its own newer releases ahead of time. The restart is still yours, and still mandatory. See [Updates](updates.md) for the switch and the checks it runs before replacing anything.
{% endhint %}

## The version handshake, in plain terms

Every Sn plugin is built against a minimum SnLib version, recorded internally as an API level. You never track this number yourself; it exists to keep version mismatches safe.

When an Sn plugin enables, it checks the SnLib actually installed:

- New enough: the plugin enables normally and you notice nothing.
- Too old: the plugin logs one clear line and disables itself cleanly, with no stack traces.

The message looks like this, with the two levels filled in:

```
[SomePlugin] Requires SnLib API level <built-against> (installed: <installed>). Update SnLib.jar (restart required): https://github.com/ValentinTarnovsky/SnLib/releases
```

The fix is always the same: download the newest `SnLib.jar`, replace the old one, restart. An outdated library can only disable the plugin that needs more. It never takes the server down with obscure `NoSuchMethodError` or `NoClassDefFoundError` failures. You can check the installed version and API level any time with `/snlib version`; see [The /snlib Command](snlib-command.md).

## Where to get it

`SnLib.jar` is published on GitHub Releases, in a public repository:

```
https://github.com/ValentinTarnovsky/SnLib/releases
```

Take the latest release unless a specific plugin asks for an exact version. Newer SnLib versions stay backward compatible with plugins built against older ones, so updating is safe for every consumer.

## Related pages

- [Configuration Files](configuration-files.md): the config.yml this install just created.
- [Updates](updates.md): the self-updater that stages new jars for you.
- [The /snlib Command](snlib-command.md): check the installed version and API level.
- [Troubleshooting](troubleshooting.md): the forward-compatibility warning and the handshake message, walked through.
