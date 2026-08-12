# Server Admins

SnLib is the shared runtime every Sn plugin depends on: learn a behavior once and you know it in every Sn plugin. You install a single `plugins/SnLib.jar` per server. From then on it is invisible infrastructure: it gives every Sn plugin the same config handling, language files, menus, permissions and update behavior.

This section is written for the person who runs the server, not for anyone writing Java. Because the behaviors are identical across the whole family, they are documented once here instead of in each plugin's docs.

> If you learn a behavior in one Sn plugin, you have learned it for all of them.

{% hint style="info" %}
If you also write plugins on top of SnLib, the developer-facing API lives in the [Developers section](../developers/README.md). This section is only about running a server.
{% endhint %}

## I want to...

| Task | Page |
|---|---|
| Change the wording of any message | [Language Files](language-files.md) |
| Recolor text with hex codes or gradients | [Text, Colors and Numbers](text-formatting.md) |
| Show big numbers as `12.3K` | [Text, Colors and Numbers](text-formatting.md) |
| Move or hide a menu button | [Menus](menus.md) |
| Change what a click does, or restrict who sees a button | [Menu Items and Clicks](menu-items-and-clicks.md) |
| Add pages or data-driven rows to a menu | [Pagination, Regions and Templates](pagination-regions-templates.md) |
| Change an item's material, name, lore or glow | [Item Appearance Reference](item-appearance.md) |
| Give an item a recipe, a cooldown or protection | [Physical Items](physical-items.md) |
| Configure the selection wand | [Physical Items](physical-items.md) |
| Run commands, sounds or titles from YAML | [Actions and Requirements](actions-and-requirements.md) |
| Rename a command or translate its help | [Customizing Commands](customizing-commands.md) |
| Move a plugin from SQLite to MySQL | [Database Connection](database.md) |
| Give staff access to admin commands | [Permissions](permissions.md) |
| Understand why a deleted key comes back | [Configuration Files](configuration-files.md) |
| Get update notices to my staff | [Updates](updates.md) |
| Update SnLib itself | [Updates](updates.md) |
| Capture debug output for a bug report | [The /snlib Command](snlib-command.md) |

## Pages in this section

- [Installation and Requirements](installation.md) - Java, supported servers, the version handshake and the restart rule.
- [Configuration Files](configuration-files.md) - the merge that updates your YAML without overwriting your edits.
- [Text, Colors and Numbers](text-formatting.md) - color codes, MiniMessage, the `[small]`/`[rgb]`/`[center]` tags and number hints.
- [Language Files](language-files.md) - every message, the prefix rules and safe fallbacks.
- [Customizing Commands](customizing-commands.md) - command aliases and translatable help.
- [Menus](menus.md) - the menu file: layout, sounds and close actions.
- [Menu Items and Clicks](menu-items-and-clicks.md) - buttons, the per-click matrix and view requirements.
- [Pagination, Regions and Templates](pagination-regions-templates.md) - pages, data-driven regions and reusable templates.
- [Item Appearance Reference](item-appearance.md) - every appearance key, shared by menus and physical items.
- [Physical Items](physical-items.md) - `items.yml`: properties, protection, recipes and the wand.
- [Actions and Requirements](actions-and-requirements.md) - the `[tag] argument` mini-language and its conditions.
- [Database Connection](database.md) - the `database:` block, SQLite and MySQL.
- [Permissions](permissions.md) - the `<plugin>.admin` convention and SnLib's own nodes.
- [Updates](updates.md) - the notify-only checker and the SnLib self-updater.
- [The /snlib Command](snlib-command.md) - the diagnostic command, subcommand by subcommand.
- [Shared Value Formats](value-formats.md) - sounds, durations, cron and typed numbers.
- [Troubleshooting](troubleshooting.md) - console lines you may see and their fixes.

## Related pages

- [Installation and Requirements](installation.md) - start here on a fresh server.
- [The /snlib Command](snlib-command.md) - your first diagnostic step when something looks off.
- [Troubleshooting](troubleshooting.md) - when a console line needs decoding.
