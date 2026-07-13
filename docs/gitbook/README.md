# SnLib

SnLib is a standalone Minecraft library plugin for Paper and Velocity. A single `SnLib.jar`, installed once per server, gives every plugin built on top of it a shared, battle-tested set of modules: managed config, GUIs, physical items, commands, database access, PlaceholderAPI integration, localization, scheduling, and more, all through one consistent API and one consistent admin experience.

SnLib is the engine behind the "Sn" family of Minecraft plugins. Every Sn plugin declares `depend: [SnLib]`, compiles against `com.sn:snlib` with `provided` scope, and extends `SnPlugin` as its only initialization path.

This documentation is split into two sections, depending on who you are:

{% hint style="info" %}
**Running an Sn plugin on your server?** Head to [Server Admins](admins/README.md). It covers everything common to every Sn plugin: installation, configuration files, permissions, update notifications, and the YAML-only customization surface for menus and items, with zero coding required.
{% endhint %}

{% hint style="success" %}
**Building a plugin on top of SnLib?** Head to [Developers](developers/README.md). It covers the full Java API: the mandatory `SnPlugin` entrypoint, every module (`yml`, `text`, `menus`, `items`, `commands`, `db`, `papi`, `lang`, `debug`, `scheduler`, and more), compatibility guarantees, threading rules, and the multi-tenant contract that lets dozens of consumer plugins share one library safely.
{% endhint %}

## Links

- Source and releases: [github.com/ValentinTarnovsky/SnLib](https://github.com/ValentinTarnovsky/SnLib)
- Latest release: [github.com/ValentinTarnovsky/SnLib/releases](https://github.com/ValentinTarnovsky/SnLib/releases)
