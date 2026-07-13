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
- Add or change color codes and formatting.
- Adjust punctuation, capitalization, and tone to match your server's style.

Because these files are [managed the same way as config files](configuration-files.md), your edits survive plugin updates. When a plugin adds a new message in an update, the new key is merged into your file automatically, in the right place, without disturbing the lines you have already customized.

## Automatic fallback to English

The English file (`messages_en.yml`) is the reference. Every message key is guaranteed to exist there. When you use a translated file for another language and a specific key happens to be missing from it, the plugin does not show a blank line or an error. It automatically falls back to the English value for that one key, and logs a single warning telling you which key was missing.

That warning is emitted once per missing key, not repeated every time the message would be shown, so a partially translated file produces a short, useful list of what still needs translating rather than flooding your console. To fix it, copy the missing key from `messages_en.yml` into your translated file and provide the translation.

{% hint style="info" %}
This means you can translate a plugin incrementally. Translate the keys you care about first; anything you have not gotten to yet keeps working in English until you fill it in.
{% endhint %}

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

Each of these lines is yours to restyle. Since they are merged in rather than hard-coded, editing them in your language file changes them everywhere that plugin uses them.
