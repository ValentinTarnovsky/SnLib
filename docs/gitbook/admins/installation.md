# Installation and Requirements

SnLib ships as a single file, `SnLib.jar`, that you drop into your server's `plugins/` folder. It is a normal plugin from the server's point of view, but no player ever interacts with it directly. Its whole job is to be present and ready before any Sn plugin enables, so that those plugins can share it.

## Requirements

### Java 21 is mandatory

SnLib is compiled for Java 21 and will not run on anything older. If you start the server with Java 17 (or any version below 21), the server fails immediately with an error like this, before any plugin logic runs at all:

```
java.lang.UnsupportedClassVersionError: com/sn/lib/SnLibPlugin has been compiled by a more recent version of the Java Runtime
```

This is not a soft warning you can ignore. The class files themselves are Java 21, so the JVM refuses to load them. If you see `UnsupportedClassVersionError` anywhere involving an Sn class, the fix is always the same: install a Java 21 runtime and point your server at it.

{% hint style="warning" %}
The Minecraft version floor and the Java requirement are linked. Running Minecraft 1.20.4 or newer already requires a Java 21 JVM, so this is not an extra burden SnLib adds on top of a modern Paper server - it is the same Java your server already needs.
{% endhint %}

### Minecraft and Paper version

SnLib targets Paper. Its supported range is:

| | Version |
|---|---|
| Minimum (floor) | 1.20.4 |
| Target | 1.21.8 |

Anything from 1.20.4 up to 1.21.8 is fully supported and tested. If you run a newer version that SnLib has not seen yet (for example a future 1.22), it does not hard-fail. Instead it starts normally and logs a single forward-compatibility warning noting that it does not recognize the version. This is by design: SnLib is built to degrade gracefully on unknown newer versions rather than crash. See [Troubleshooting](troubleshooting.md) for what that warning looks like and why it is safe to ignore.

## Installing it

1. Download `SnLib.jar` (see below).
2. Place it in the `plugins/` folder of your server.
3. Start the server with a Java 21 runtime.

SnLib declares `load: STARTUP` in its plugin descriptor. That means the server loads it during the earliest startup phase, before the normal plugins enable. This ordering matters: every Sn plugin depends on SnLib, so SnLib has to be ready first. You do not have to configure this - it is built into the jar - but it is the reason SnLib must be present before any consumer plugin.

You install `SnLib.jar` once per server. A single copy in `plugins/` serves every Sn plugin on that server at the same time. You never install one SnLib per plugin.

{% hint style="info" %}
Most Sn plugins also attach a compatible `SnLib.jar` to their own releases, so if you download a plugin that needs a newer SnLib than you have, the matching library is usually right there next to it.
{% endhint %}

## Updating requires a full server restart

This is a hard rule with no exceptions:

{% hint style="danger" %}
Updating `SnLib.jar` always requires a full server restart. Never hot-swap it, and never expect `/snlib reload` to pick up a new jar.
{% endhint %}

`/snlib reload` re-reads configuration files. It never reloads Java classes. When you replace `SnLib.jar` on disk, the running server is still holding the old classes in memory, and every Sn plugin currently running is sharing those exact classes through the same classloader. Swapping the file underneath them while they run is not supported and will lead to broken state. The only correct way to update SnLib is:

1. Stop the server.
2. Replace `SnLib.jar` with the new one.
3. Start the server again.

Reload commands and plugin managers that claim to hot-reload jars do not change this. SnLib is shared by every consumer plugin at once, so a clean full restart is the only supported update path.

## The version handshake, in plain terms

Every Sn plugin is built against a specific minimum version of SnLib, which it records internally as an "API level". You never see this number day to day, but it is what keeps mismatches safe.

When an Sn plugin enables, it checks the SnLib that is actually installed:

- If the installed `SnLib.jar` is new enough, the plugin enables normally and you never notice anything.
- If the installed `SnLib.jar` is older than what that plugin needs, the plugin does not crash the server or throw a wall of stack traces. It logs one clear line stating that it requires a newer SnLib and disables itself cleanly, including a link to the latest release so you know exactly what to download.

The message looks roughly like this in your console:

```
[SomePlugin] Requires SnLib API level 3 (installed: 2). Update SnLib.jar: https://github.com/ValentinTarnovsky/SnLib/releases
```

The fix is always to download the newest `SnLib.jar`, replace the old one, and restart. See [Troubleshooting](troubleshooting.md) for a full walk-through of this exact message. Because the check happens cleanly and up front, an out-of-date library can only ever disable the plugin that needs the newer version - it can never take the whole server down with obscure `NoSuchMethodError` or `NoClassDefFoundError` failures.

## Where to get it

`SnLib.jar` is published on GitHub Releases. The repository is public:

```
https://github.com/ValentinTarnovsky/SnLib/releases
```

Always take `SnLib.jar` from the latest release unless a specific plugin tells you it needs an exact version. Newer SnLib versions stay backward compatible with plugins built against older ones, so updating the library is safe for every consumer already on your server.
