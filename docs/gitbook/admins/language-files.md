# Language Files

Every line a plugin says lives in a language file, and that file is yours: the file is the message. You can rewrite the wording, the colors and the tone of any line, and your edits survive every update. This page covers the files, the value forms, the prefix, and the shared `snlib.*` keys every Sn plugin carries.

## Where messages live

Messages sit under `lang/` in the plugin's data folder, one file per language:

```
plugins/SomePlugin/lang/
  messages_en.yml
  messages_es.yml
```

The plugin's `config.yml` picks the active language with the `lang:` key:

```yaml
lang: es   # uses lang/messages_es.yml
```

The choice is server-wide: one language for every player of that plugin, not per player. English is always seeded from the jar, so `messages_en.yml` always exists and holds every key. If the file for the configured language is missing, empty or corrupt, the plugin warns and uses English.

Language files are [managed like config files](configuration-files.md). Keys added by an update are merged into your file in place; your values are never overwritten. New keys merged into a translation are announced in the console, so you can translate them when convenient.

## Fallback to English

English is the reference. When your active language misses one key, that key falls back to its English value, with a single warning naming it. A key missing from both files renders in chat as `<missing:key>`, again with one warning.

{% hint style="info" %}
This makes incremental translation safe. Translate the keys you care about first; everything else keeps working in English until you fill it in.
{% endhint %}

## The three value forms

The form of a value decides how it is delivered:

| Value form | Example | What happens |
|---|---|---|
| Single string | `joined: "&aWelcome!"` | Sent as one line, with the prefix prepended. |
| List | `joined: ["&aWelcome!", "&7Enjoy your stay."]` | One send per entry, in order, never prefixed. |
| Empty: `""` or `[]` | `joined: ""` | Nothing is sent. The message is silenced. |

> One line gets the prefix, a list never does, and an empty value silences the message.

Silencing with `""` or `[]` is the supported way to mute a message. Deleting the key instead lets the updater restore the default on the next start.

## The prefix

Most plugins define their chat prefix once, at the top of the language file:

```yaml
prefix: "&8[&dClans&8] &7"
```

SnLib prepends it to every single-line message automatically, so you never write it inside a value. The insertion is tag-aware. On a line starting with `[center]`, `[rgb]` or `[small]`, the prefix lands after those tags and inherits their effect. Setting `prefix: ""` removes the prefix everywhere.

{% hint style="warning" %}
Never write the `{prefix}` token inside a message value. It is not a placeholder there: it renders literally, on top of the prefix already inserted. The plugin warns at startup when any value embeds it.
{% endhint %}

### Skipping the prefix on one line

Some lines look better bare: a gradient banner, a centered announcement. Start the value with `[noprefix]` and that line skips the prefix; the tag itself never shows:

```yaml
reload-done: "[noprefix][rgb]Configuration reloaded."
motd: "[noprefix][center]&#8354f2Welcome to the server"
```

The tag counts only inside the leading tag run, in any order with `[center]`, `[rgb]` and `[small]`. Written after the first visible character it does not opt out, and it shows literally.

## Keep clickable tags when editing

Some values carry interactive tags like `<click:run_command:'...'>` and `<hover:show_text:'...'>`. They are what makes a chat button such as `[JOIN]` actually work. Your edits are always preserved, so a rewrite that drops one of those tags is never repaired. The button keeps its look, but clicking it silently does nothing. Restyle only the visible text between the tags.

If a value loses a `<click>` or `<hover>` tag its jar default carries, the plugin warns at startup, naming the affected keys. Restore the tags in those values.

{% hint style="info" %}
Servers with Bedrock players (Geyser): Bedrock chat ignores click events entirely. Well-made messages also show the plain command to type next to the button; keep that part when restyling.
{% endhint %}

## Titles from the language file

When a plugin shows a message as a title, the value's first line reads as `title;subtitle;fadeIn;stay;fadeOut`. Times are in ticks, and omitted parts default to 10, 70 and 20. The grammar is the same one the `[title]` action uses; [Actions and Requirements](actions-and-requirements.md) documents it in full.

```yaml
level-up: "&6Level up!;&7You reached level {level};10;70;20"
```

## The shared snlib.* keys

Core messages such as "no permission" and the usage line are not written per plugin. SnLib merges its own `snlib.*` keys into every plugin's `messages_en.yml` on each start. Missing keys are inserted with a comment explaining when each line is sent; your existing values are never overwritten. This merge runs even with `update-configs: false`, because these keys are the library's own message contract.

Restyle them once per plugin and every core message reads consistently across your Sn plugins. The full set, with the placeholders each line can use:

| Key | Sent when | Placeholders |
|---|---|---|
| `snlib.no-permission` | The sender lacks the permission for a command or subcommand. | - |
| `snlib.usage` | A command is used with missing or malformed arguments. | `{usage}` |
| `snlib.invalid-number` | An argument expected a number and got something else. | `{value}` |
| `snlib.invalid-value` | An argument value is not one of the accepted options. | `{value}` |
| `snlib.out-of-range` | A numeric argument falls outside its allowed range. | `{min}`, `{max}`, `{value}` |
| `snlib.number-too-small` | An open-ended numeric argument is under its minimum. | `{min}`, `{value}` |
| `snlib.player-not-found` | An argument expected an online player who was not found. | `{value}` |
| `snlib.unknown-subcommand` | The typed subcommand does not exist. | `{value}` |
| `snlib.reload-done` | A reload finishes successfully. | - |
| `snlib.help.header` | Printed before the generated help entries. | `{plugin}` |
| `snlib.help.entry` | One line per subcommand visible to the sender. | `{usage}`, `{description}`, `{permission}` |
| `snlib.help.footer` | Printed after the entries, only when help spans several pages. | `{page}`, `{total}`, `{command}` |
| `snlib.teleport.warmup` | A warmup teleport starts. | `{time}` |
| `snlib.teleport.cancelled-move` | A pending teleport is cancelled because the player moved. | - |
| `snlib.teleport.cancelled-damage` | A pending teleport is cancelled because the player took damage. | - |
| `snlib.selection.pos1-set` | A selection wand sets position 1. | `{x}`, `{y}`, `{z}`, `{world}` |
| `snlib.selection.pos2-set` | A selection wand sets position 2. | `{x}`, `{y}`, `{z}`, `{world}` |
| `snlib.selection.different-worlds` | The two selected positions are in different worlds. | - |
| `snlib.selection.too-big` | The selected cuboid exceeds the allowed volume. | `{volume}`, `{max}` |
| `snlib.selection.no-permission` | The player lacks the wand permission. | - |
| `snlib.selection.timeout` | A selection session expires. | - |

`{command}` in the footer is the alias the sender actually typed, so aliased commands render consistently. The `snlib.teleport.*` lines appear only in plugins that use warmup teleports. The `snlib.selection.*` lines belong to the selection wand described in [Physical Items](physical-items.md).

## Other blocks in the file

A few sections of the language file are documented elsewhere, or belong to the plugin:

* The top-level `commands:` block renames command descriptions and argument labels in the generated help; see [Customizing Commands](customizing-commands.md).
* Numeric placeholders accept display hints such as `{balance:short}`; [Text, Colors and Numbers](text-formatting.md) owns the full table.

{% hint style="info" %}
Many Sn plugins keep reusable state words, such as Online/Offline or Enabled/Disabled, under a `status:` section of the language file. That section is a plugin convention: SnLib itself never reads it. When present, edit it like any other keys.
{% endhint %}

## Related pages

* [Text, Colors and Numbers](text-formatting.md) - every color code, tag and number hint you can use inside a value.
* [Customizing Commands](customizing-commands.md) - the `commands:` block and the config-driven command aliases.
* [Configuration Files](configuration-files.md) - how merging, backups and `update-configs` treat these files.
* [Actions and Requirements](actions-and-requirements.md) - the full title grammar and the action line format.
* [Physical Items](physical-items.md) - the selection wand behind the `snlib.selection.*` messages.
