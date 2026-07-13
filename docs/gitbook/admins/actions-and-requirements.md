# Actions and Requirements

Inside any Sn plugin's YAML - menus, items, and often the plugin's own config - you can write lists of actions and lists of requirements. Actions make things happen (send a message, run a command, play a sound, change the page). Requirements are conditions that decide whether something is allowed. Both use a small, shared mini-language, so what you learn here works in every Sn plugin that exposes these lists.

## The `[tag] argument` format

An action is a single line that starts with a tag in square brackets, followed by its argument:

```yaml
click-actions:
  - "[message] &aYou bought the item!"
  - "[sound] ENTITY_PLAYER_LEVELUP"
  - "[console] give %player_name% diamond 1"
  - "[close]"
```

The tag decides what happens; the rest of the line is the argument for it. Placeholders (both SnLib's local ones and PlaceholderAPI's `%...%` ones) work inside the arguments.

## Action tags

### Running commands and messages

| Tag | What it does |
|---|---|
| `[player]` | Makes the player run the command as themselves. |
| `[player-as-op]` | Makes the player run the command with temporary operator rights, then removes them. |
| `[console]` | Runs the command from the server console. |
| `[message]` | Sends a private chat message to the player. |
| `[broadcastmessage]` | Sends a message to everyone on the server. |
| `[actionbar]` | Shows text in the player's action bar (above the hotbar). |
| `[title]` | Shows a title and subtitle on the player's screen. |
| `[sound]` | Plays a sound to the player. |
| `[particle]` | Spawns particles (see options below). |
| `[potion]` | Applies a potion effect to the player. |

### Menu control

| Tag | What it does |
|---|---|
| `[close]` | Closes the currently open menu. |
| `[open]` | Opens another menu. |
| `[connect]` | Sends the player to another server (on a proxy network). |

### Page navigation (paginated menus)

| Tag | What it does |
|---|---|
| `[next-page]` | Goes to the next page. |
| `[previous-page]` | Goes to the previous page. |
| `[set-page]` | Jumps to a specific page. |
| `[refresh-page]` | Rebuilds the current page in place. |
| `[refresh-menu]` | Rebuilds the whole menu. |

### `[particle]` options

`[particle]` accepts `key=value` options at the end of the line to fine-tune it:

- `color=` - the particle color.
- `size=` - the particle size.
- `to=` - a target for directional particles.
- `block=` - the block type for block particles.
- `item=` - the item type for item particles.

### `[remove-item]` and its selector

`[remove-item]` takes items out of the player's inventory. It accepts a selector so you can be specific about what and how many:

```
[remove-item] [n] [offhand|id:<item-id>|MATERIAL]
```

- `[n]` - how many to remove.
- `offhand` - remove from the off hand.
- `id:<item-id>` - remove a specific Sn custom item by its id.
- `MATERIAL` - remove by vanilla material.

## Click-type filters

Inside a list of actions, you can add a click filter as one of the lines. It makes the lines that follow only apply when the click matches. This lets a single list behave differently depending on how the player clicked.

| Filter | Matches |
|---|---|
| `[right-click-only]` | Only a right click. |
| `[left-click-only]` | Only a left click. |
| `[right-click]` | Right clicks (inclusive semantics). |
| `[left-click]` | Left clicks (inclusive; also passes double-click and creative). |
| `[middle-click]` | Middle click. |
| `[double-click]` | Double click. |
| `[drop-click]` | The drop key. |
| `[number-key]` | A hotbar number key. |
| `[swap-offhand]` | The swap-to-offhand key. |
| `[click-block]` | An interaction aimed at a block (world items only). |
| `[click-air]` | An interaction aimed at air (world items only). |
| `[click=TYPE,...]` | An exact list of click types by name (for example `[click=middle,double_click]`). |

{% hint style="warning" %}
`[click=TYPE,...]` is strict. If you misspell a click type or leave the list empty, the plugin logs a single warning and the line does NOT run. This is deliberate: a typo can never make actions fire on the wrong click. Note that `[click-block]` and `[click-air]` only make sense for physical world items; inside a menu they have no surface to match and the line is simply skipped.
{% endhint %}

## Requirements

Requirements are conditions written as comparisons. They appear under keys such as `view-requirements`, `click-requirements`, and `interact-requirements`, and each has a matching `deny-actions` list that runs when the condition is not met.

A requirement compares a placeholder against a value using an operator:

```yaml
click-requirements:
  - "%vault_eco_balance% >= 100"
  - "%player_level% > 0 && %player_level% < 10"
```

### Operators

| Operator | Meaning |
|---|---|
| `=` | Equal to |
| `!=` | Not equal to |
| `>` | Greater than |
| `<` | Less than |
| `>=` | Greater than or equal to |
| `<=` | Less than or equal to |

### Combining conditions

- Use `&&` for "and" and `||` for "or".
- Use parentheses `( )` to group conditions and control the order they are evaluated.
- Use quotes `'...'` or `"..."` around text values, especially when comparing strings or when a value could contain characters like `(`, `)`, `&&`, or `||` that would otherwise be read as part of the expression.

Placeholders on either side can be PlaceholderAPI placeholders (when PlaceholderAPI is installed) or SnLib's own local placeholders.

```yaml
view-requirements:
  - "(%player_world% = 'spawn' || %player_world% = 'lobby') && %player_gamemode% != 'CREATIVE'"
```

### Fail-open policy

If a requirement line is malformed - an invalid comparison, unbalanced parentheses, a leftover operator, empty parentheses - the plugin does not silently block the player and leave you guessing. Instead the whole line is treated as always-true, and a single warning is logged naming the exact line:

```
Malformed requirement: '%broken% >'; it evaluates as true
```

{% hint style="info" %}
This is a deliberate "fail-open" choice: a broken requirement never locks players out of something they should be able to use. It errs toward allowing the action and telling you in the console, rather than quietly denying and confusing everyone. When you see that warning, fix the expression in your YAML - most often it is a missing quote around a text value or an unbalanced parenthesis.
{% endhint %}

{% hint style="warning" %}
The fail-open behavior applies to requirements. The `[click=TYPE,...]` click filter is the one place that is strict instead (fail-closed), because there an invalid entry should stop the line rather than run it on unintended clicks.
{% endhint %}
