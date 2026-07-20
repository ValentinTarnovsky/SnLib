# Language Files

Every Sn plugin that shows messages to players keeps those messages in language files, so you can rewrite any line - change the wording, the tone, the colors - without touching a single line of code. The system is the same across every Sn plugin.

## Where messages live

Messages are stored under a `lang/` folder inside the plugin's data directory, with one file per language:

```
plugins/SomePlugin/lang/messages_en.yml
plugins/SomePlugin/lang/messages_es.yml
plugins/SomePlugin/lang/messages_fr.yml
```

The file name encodes the language code (`en`, `es`, `fr`, and so on). Which language the plugin uses is chosen in its config. Each file is plain YAML: a key on the left, the message text on the right.

## Editing wording is safe

Open the language file for your chosen language, change any message text, save, and reload the plugin. Nothing else is affected. You can:

- Rewrite the phrasing of any message.
- Add or change color codes and formatting - see [Text Formatting and Colors](text-formatting.md) for the full list of codes and the `[small]`/`[rgb]`/`[center]` tags.
- Adjust punctuation, capitalization, and tone to match your server's style.

Because these files are [managed the same way as config files](configuration-files.md), your edits survive plugin updates. When a plugin adds a new message in an update, the new key is merged into your file automatically, in the right place, without disturbing the lines you have already customized.

## Automatic fallback to English

The English file (`messages_en.yml`) is the reference. Every message key is guaranteed to exist there. When you use a translated file for another language and a specific key happens to be missing from it, the plugin does not show a blank line or an error. It automatically falls back to the English value for that one key, and logs a single warning telling you which key was missing.

That warning is emitted once per missing key, not repeated every time the message would be shown, so a partially translated file produces a short, useful list of what still needs translating rather than flooding your console. To fix it, copy the missing key from `messages_en.yml` into your translated file and provide the translation.

{% hint style="info" %}
This means you can translate a plugin incrementally. Translate the keys you care about first; anything you have not gotten to yet keeps working in English until you fill it in.
{% endhint %}

## Do not repeat the prefix

Most plugins define a `prefix` at the top of the language file and add it in front of every one-line message automatically. Because it is added for you, you should never write the prefix placeholder inside a message value - the plugin's prefix token written in placeholder form. It is not replaced there, so it would just show up literally on top of the prefix the plugin already put in front. If any message value contains that token, the plugin logs a single warning at startup telling you how many messages need cleaning up. Remove the token from those lines and keep the prefix in the `prefix` key only.

## Skipping the prefix on one line

Sometimes a specific message looks better without the prefix - a full-width gradient line, a banner, a centered announcement. Start that message value with the `[noprefix]` tag and the plugin sends that one line bare; the tag itself never shows. It combines with the other tags in any order:

```yaml
reload-done: "[noprefix][rgb]Configuration reloaded."
motd: "[noprefix][center]&#8354f2Welcome to the server"
```

The tag only has meaning at the start of the line (together with the other leading tags). Written in the middle of a message it is treated as normal text. Requires the server to run SnLib 1.9.0 or newer; on older versions the tag shows literally.

## Keep clickable tags when editing

Some messages contain interactive tags like `<click:run_command:'...'>` and `<hover:show_text:'...'>` - they are what makes a chat button such as `[JOIN]` actually do something when clicked. Because your edits are always preserved, an edit or a translation that drops one of those tags is never repaired automatically: the button keeps its look but clicking it silently does nothing. Keep the tags around your rewritten text (only the visible text between them is yours to restyle). If a value loses a tag its default carries, the plugin logs a single warning at startup naming the affected keys so you can restore them.

Note for servers with Bedrock players (Geyser): Bedrock chat does not support click events at all, so well-made plugins also show the plain command to type next to the button. Keep that part of the message too when restyling.

## Shared core messages in every language

There is a set of common messages that are not specific to any one plugin - things like "you do not have permission to use this command", the correct-usage line shown when a command is typed wrong, and the messages for invalid or out-of-range arguments. These are the `snlib.*` keys.

Rather than making every plugin author write and translate these basics separately, SnLib merges its shared `snlib.*` keys into every plugin's language file automatically. On each startup, any `snlib.*` key missing from your file is inserted, with a comment explaining when the message is sent. Your existing values are never overwritten, so if you have already customized one of these lines it stays exactly as you set it.

The practical benefit is consistency: the "no permission" message, the usage format, and the other core messages read the same across every Sn plugin on your server, and you only have to style them the way you like once per plugin. The shared keys cover cases such as:

| Key | When it is sent |
|---|---|
| `snlib.no-permission` | The sender lacks the permission for a command or subcommand. |
| `snlib.usage` | A command was used with missing or malformed arguments. |
| `snlib.invalid-number` | An argument expected a number and got something else. |
| `snlib.invalid-value` | An argument value is not one of the accepted options. |
| `snlib.out-of-range` | A numeric argument fell outside its allowed range. |
| `snlib.player-not-found` | An argument expected an online player who was not found. |
| `snlib.unknown-subcommand` | The given subcommand does not exist. |
| `snlib.reload-done` | Shown after a successful reload. |
| `snlib.help.*` | The header, per-entry, and footer lines of generated help output. |
| `snlib.teleport.*` | The warmup and cancelled-on-move / cancelled-on-damage lines a plugin's warmup teleports show. |

Each of these lines is yours to restyle. Since they are merged in rather than hard-coded, editing them in your language file changes them everywhere that plugin uses them.
