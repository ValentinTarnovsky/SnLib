# Customizing Commands

You decide what a command is called and how its help reads; the plugin only decides what the command does. Aliases live in the config, help text lives in the language file, and both apply on a reload.

## Command aliases

Sn plugins read the alias list of their root command from `config.yml`, under the conventional `command.aliases` key:

```yaml
command:
  aliases:
    - clan
    - c
```

With that config, a root command named `/clans` also answers to `/clan` and `/c`. The root name itself always stays registered; aliases only add alternatives.

| In your config.yml | Effect |
|---|---|
| Key absent | The plugin's built-in default aliases apply. |
| `aliases:` with entries | Exactly those aliases, replacing the defaults. |
| `aliases: []` | No aliases at all. |

> A set `command.aliases` key is the whole truth: an empty list removes every alias; only removing the key restores the defaults.

The list is re-read on every reload. An alias you remove is unregistered and disappears from the client command tree; no restart is needed.

## Every root has reload and help

Every SnLib command tree ships two subcommands the plugin did not have to write:

| Subcommand | Permission | What it does |
|---|---|---|
| `/<command> reload` | `<plugin>.admin.reload` | Reloads the plugin configuration, then confirms with `snlib.reload-done`. |
| `/<command> help [page]` | Inherited from the root | Prints the generated help, 10 entries per page. |

The help is permission-aware: a sender only sees the subcommands they are allowed to run. The footer appears only when the help spans several pages. A plugin may declare its own `reload` or `help` subcommand; the declared one then replaces the injected default.

Every rendered line echoes the alias the sender typed. Typing `/c help` on a root named `clans` lists `/c create <name>`, not `/clans create <name>`. The `{command}` placeholder of `snlib.help.footer` and the `{label}` token of plugin-declared usage lines both resolve to that typed alias.

The wording of the error and usage lines themselves, `snlib.usage`, `snlib.no-permission` and friends, lives in [Language Files](language-files.md).

### Translatable help (1.14.0)

Once a plugin registers its commands, it seeds a top-level `commands:` block into `lang/messages_en.yml`. The block holds the description of every command and the visible label of every argument:

```yaml
commands:
  clans:
    description: "Manage your clan"
    subcommands:
      create:
        description: "Creates a new clan"
        args:
          name: "name"
      invite:
        description: "Invites a player to your clan"
        args:
          player: "player"
```

The rules are the same as for every language key:

* Edit any quoted value freely; your edits are never overwritten.
* Delete an entry and its declared default returns on the next start.
* Edits apply on the next reload, without a restart.
* Translations pick the block up through the usual merge into `messages_<code>.yml`.

The `args` entries are the visible labels of each argument in the usage line and in tab completion. Setting `player: "jugador"` makes the usage read `/clans invite <jugador>`, and tab completion hints the same word. The root `description` also feeds the description Bukkit shows for the command.

> Translating an argument label changes the usage hint and the tab completion, never what the player has to type.

{% hint style="warning" %}
The YAML keys under `subcommands:` and `args:` are identifiers, not display text. Renaming a key orphans your edit and the merge restores the original entry on the next start. Change only the quoted values.
{% endhint %}

## Related pages

* [Language Files](language-files.md) - the file where the `commands:` block lives, plus the `snlib.*` error lines.
* [Permissions](permissions.md) - the `<plugin>.admin.reload` convention behind the injected reload.
* [The /snlib Command](snlib-command.md) - the library's own root command, built on the same system.
* [Configuration Files](configuration-files.md) - how config edits, reloads and merging behave.
