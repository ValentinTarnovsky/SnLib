# Server Admins

SnLib is a shared runtime library that every Sn plugin depends on. You install it once per server as `plugins/SnLib.jar`, and from that point on it is invisible infrastructure: it never appears in a menu, it has almost no commands of its own, and you rarely think about it directly. What it does is give every Sn plugin you install (SnGens, SnTags, SnCrates, and the rest) the same set of admin-facing behaviors, so that once you learn how one Sn plugin handles its config files, permissions, language files, menus, and update notices, you already know how all of them do.

This section documents those shared behaviors. It is written for the person who runs the server and installs the plugins, not for anyone writing Java. You do not need to read any code, and nothing here assumes you will. The point of collecting these behaviors in one place is that they are identical across every Sn plugin, so it makes no sense to repeat them in each plugin's own documentation. Learn them once here, recognize them everywhere.

{% hint style="info" %}
If you also write plugins on top of SnLib, the developer-facing API is documented separately in the [Developers section](../developers/README.md). This section is only about running a server.
{% endhint %}

## Pages in this section

- [Installation and Requirements](installation.md) - Java 21, supported Minecraft versions, where to download `SnLib.jar`, and why updating it always needs a full restart.
- [Configuration Files](configuration-files.md) - how every Sn plugin keeps your YAML config files up to date without ever overwriting your settings, and how it protects you from a corrupted file.
- [Permissions and Updates](permissions-and-updates.md) - the `<plugin>.admin.*` permission convention shared by every Sn plugin, and the notify-only update-check system.
- [Language Files](language-files.md) - where messages live, how to edit wording, and how missing translations fall back safely.
- [Text Formatting and Colors](text-formatting.md) - legacy color codes, hex colors, MiniMessage tags, and the `[small]`/`[rgb]`/`[center]` tags usable in any text field.
- [GUIs and Items in YAML](guis-and-items-yaml.md) - customizing menus and physical items entirely through YAML, with no code changes.
- [Actions and Requirements](actions-and-requirements.md) - the `[tag] argument` mini-language you can write inside menu and item YAML to make things happen and to gate them behind conditions.
- [The /snlib Command](snlib-command.md) - the full reference for SnLib's own diagnostic command.
- [Troubleshooting](troubleshooting.md) - the handful of log messages you might see, what each one means, and how to fix it.
