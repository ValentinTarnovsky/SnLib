# Actions and Requirements

Inside any Sn plugin's YAML - menus, physical items, and often the plugin's own config - you write lists of actions and lists of requirements. The guiding principle: tags make things happen, guards decide when a line runs, requirements decide whether anything runs at all. Both use one shared mini-language, so what you learn here works in every Sn plugin.

## The action line

An action is a single line: optional guards first, then a tag in square brackets, then its argument:

```yaml
click-actions:
  - "[message] &aYou bought the item!"
  - "[sound] ENTITY_PLAYER_LEVELUP"
  - "[console] give %player_name% diamond 1"
  - "[right-click-only] [chance=25] [message] &6Lucky bonus!"
  - "[close]"
```

The tag decides what happens; the rest of the line is its argument. Guards chain: every guard on the line must pass before the tag runs.

> **A line with no tag is a `[message]`.** Write `- "&aWelcome!"` and the player receives it as chat. A typo inside the brackets is different: an unknown tag logs one warning and the line is ignored.

Placeholders resolve in every argument before the tag runs: the plugin's local `{tokens}` first, then PlaceholderAPI's `%...%` ones. Chat-like tags (`[message]`, `[title]`, `[actionbar]`, `[broadcastmessage]`) then render the full text pipeline: colors, `[rgb]`, `[center]`, MiniMessage. See [Text, Colors and Numbers](text-formatting.md) for that pipeline.

{% hint style="info" %}
One broken line never stops the list. A line that fails at runtime logs a warning naming the line, and the remaining actions still run.
{% endhint %}

## Action tags

### Commands and chat

| Tag | What it does |
|---|---|
| `[player]` | The player runs the command as themselves. |
| `[player-as-op]` | The player runs the command with temporary operator rights, restored right after. |
| `[console]` | Runs the command from the server console. |
| `[message]` | Sends a private chat message to the player. |
| `[broadcastmessage]` | Sends a message to everyone on the server. |
| `[actionbar]` | Shows text in the player's action bar (above the hotbar). |

A leading slash is optional in command arguments: `[player] spawn` and `[player] /spawn` are the same.

### `[title]`

`[title]` shows a title and subtitle with configurable timing. The argument is up to five parts separated by `;`:

```
[title] title;subtitle;fadeIn;stay;fadeOut
```

```yaml
click-actions:
  - "[title] &6Welcome;&eEnjoy your stay;20;40;20"
```

| Part | Meaning | Default |
|---|---|---|
| 1 | Title text | (required, may be empty) |
| 2 | Subtitle text | empty |
| 3 | Fade-in time, in ticks | 10 |
| 4 | Stay time, in ticks | 70 |
| 5 | Fade-out time, in ticks | 20 |

Leave a timing part empty (or off the end) to keep its default: `[title] Hi;;5` sets only the fade-in. All times are ticks; 20 ticks are one second. Language files reuse this exact grammar for titles, see [Language Files](language-files.md).

### `[sound]`

`[sound]` plays a sound to the player: `[sound] SOUND_ID [volume] [pitch]`, both numbers optional. A sound from your resource pack works too, as long as you write its namespace (`[sound] okimc:click-2`). The full sound value grammar is shared across SnLib and lives in [Shared Value Formats](value-formats.md).

### `[particle]`

`[particle]` spawns particles at the player's position:

```
[particle] TYPE [count] [offX offY offZ] [extra] [key=value ...]
```

```yaml
right-click-actions:
  - "[particle] FLAME 50 0.5 0.5 0.5 0.1"
  - "[particle] DUST 10 0.2 0.2 0.2 0.01 color=#FF9B00 size=1.5"
```

Positional numbers resolve in order: count, then the three offsets, then the extra value. Give the offsets as all three numbers or not at all. Any token containing `=` is an option instead:

| Option | Meaning | Applies to |
|---|---|---|
| `color=` | Particle color, `#RRGGBB` or `R,G,B` (0-255). | Colored particles like `DUST`. Invalid or absent falls back to red. |
| `size=` | Particle size as a decimal. | Sized particles like `DUST`. Default 1.0. |
| `to=` | Second color for transitions. | `DUST_COLOR_TRANSITION`. Defaults to the `color=` value. |
| `block=` | Block material for block particles. | `BLOCK`, `FALLING_DUST` and friends. Required there. |
| `item=` | Item material for item particles. | `ITEM`. Required there. |

An unknown option, or one the particle type cannot use, logs one warning and is ignored. A particle that requires `block=` or `item=` skips the line when the option is missing or invalid. Both `REDSTONE` and `DUST` are accepted on either side of the Minecraft 1.20.5 rename; the alias logs one notice.

### `[potion]`

`[potion]` applies a potion effect to the player:

```
[potion] EFFECT [seconds] [amplifier]
```

```yaml
click-actions:
  - "[potion] SPEED 30 1"   # Speed II for 30 seconds
```

The duration defaults to 10 seconds and the amplifier to 0. Amplifier 0 is level I, so `SPEED 30 1` is Speed II.

> **`[potion]` takes seconds, not ticks.** `[potion] SPEED 200` is over three minutes of Speed I, not ten seconds. This differs from `[title]`, whose times are ticks.

### `[remove-item]`

`[remove-item]` takes items out of the player's inventory:

```
[remove-item] [n] [offhand|id:<item-id>|MATERIAL]
```

```yaml
click-actions:
  - "[remove-item]"              # removes 1 from the main hand
  - "[remove-item] 5"            # removes 5 from the main hand
  - "[remove-item] 3 offhand"    # removes 3 from the off hand
  - "[remove-item] 1 id:my-item" # removes 1 registered custom item by id
  - "[remove-item] 2 DIAMOND"    # removes 2 by vanilla material
```

| Selector | What it removes |
|---|---|
| (none) | From the stack in the main hand. |
| `offhand` | From the stack in the off hand. |
| `id:<item-id>` | A custom item registered by this plugin, scanning storage slots then the off hand. |
| `MATERIAL` | By vanilla material, same scan order. |

{% hint style="warning" %}
The `MATERIAL` selector never consumes stacks tagged by an Sn plugin, so a vanilla-looking custom item is safe from it. Use `id:<item-id>` when you mean the custom item. Removing fewer items than requested is not an error; the action takes what it finds.
{% endhint %}

### Menu control

| Tag | What it does |
|---|---|
| `[close]` | Closes the currently open menu. |
| `[open]` | Opens another menu by its id: `[open] shop`. A missing menu logs one warning. |
| `[connect]` | Sends the player to another server on a proxy network: `[connect] lobby`. |

### Page navigation

These five tags only do something in a menu with `pagination: true`. With pagination off, each is a silent no-op. See [Pagination, Regions and Templates](pagination-regions-templates.md).

| Tag | What it does |
|---|---|
| `[next-page]` | Goes to the next page. |
| `[previous-page]` | Goes to the previous page. |
| `[set-page]` | Jumps to a specific page: `[set-page] 3`. |
| `[refresh-page]` | Rebuilds the current page in place. |
| `[refresh-menu]` | Rebuilds the whole menu. Also pagination-only. |

### Custom plugin tags

Every Sn plugin can register extra tags of its own, and may even replace a built-in. Check the plugin's own documentation for the tags it adds.

## Guards: click filters and chance

A guard is a bracketed prefix that gates the rest of its line. Several guards stack on one line; each must pass.

| Guard | Passes when |
|---|---|
| `[right-click]` | Any right click, including shift-right (inclusive). |
| `[left-click]` | Any left click, including shift-left, double-click and creative (inclusive). |
| `[right-click-only]` | Exactly a plain right click, nothing else. |
| `[left-click-only]` | Exactly a plain left click, nothing else. |
| `[shift-right-click]` | Exactly shift + right click. |
| `[shift-left-click]` | Exactly shift + left click. |
| `[middle-click]` | Exactly a middle click. |
| `[double-click]` | Exactly a double click. |
| `[drop-click]` | Exactly the drop key (Q). |
| `[number-key]` | Exactly a hotbar number key (1-9). |
| `[swap-offhand]` | Exactly the swap-to-offhand key (F). |
| `[click=TYPE,...]` | The click is in an exact list of click type names, for example `[click=middle,double_click]`. Case does not matter; `-` equals `_`. |
| `[click-block]` | The interaction hit a block (physical items only). |
| `[click-air]` | The interaction hit the air (physical items only). |
| `[chance=N]` | A random roll under N succeeds; N is 0-100 and accepts decimals. |

A click guard on a line that runs without any click (close actions, pickup actions, plugin-triggered runs) skips the line quietly.

{% hint style="warning" %}
`[click=TYPE,...]` is strict: a misspelled type or an empty list logs one warning and the line does NOT run. A typo must never fire actions on the wrong click. `[chance=N]` is the opposite: a malformed number logs one warning and the line runs anyway. `[click-block]` and `[click-air]` only exist for physical items; inside a menu there is no surface, so the line is skipped.
{% endhint %}

## Requirements

Requirements are conditions written as placeholder comparisons. They appear under `view-requirements`, `click-requirements` (and its per-click variants), and `interact-requirements`. Click and interact requirements pair with a `deny-actions` list that runs when the condition fails.

`view-requirements` both hide an item and block every click on it; the full rule is owned by [Menu Items and Clicks](menu-items-and-clicks.md). That page also owns shared slots, where several items compete for one cell in declaration order.

A requirement compares two values with an operator. Either side can be a PlaceholderAPI placeholder or a plugin-local one:

```yaml
click-requirements:
  - "%vault_eco_balance% >= 100"
  - "%player_level% > 0 && %player_level% < 10"
```

Multiple lines in one list are joined with an implicit AND: every line must pass.

> **There are no named requirement types and no `permission:` key.** Everything is a placeholder comparison. A permission check is just a placeholder token, for example `- "%player_has_permission_shop.vip% = yes"` (the exact output text depends on the expansion).

### Operators

| Operator | Meaning |
|---|---|
| `=` | Equal to. Text comparison ignores case. |
| `==` | Same as `=`. |
| `!=` | Not equal to. |
| `>` | Greater than. |
| `<` | Less than. |
| `>=` | Greater than or equal to. |
| `<=` | Less than or equal to. |

When both sides parse as numbers, the comparison is numeric. Otherwise `=` and `!=` compare the text ignoring case, and the four relational operators evaluate to false with one warning.

### Combining conditions

- Use `&&` for "and" and `||` for "or".
- `&&` binds tighter than `||`: `a || b && c` means `a || (b && c)`.
- Use parentheses `( )` to group conditions and make the order explicit.
- Use quotes `'...'` or `"..."` around text values. Inside quotes, `&&`, `||`, parentheses and operator symbols stay literal text.

```yaml
view-requirements:
  - "(%player_world% = 'spawn' || %player_world% = 'lobby') && %player_gamemode% != 'CREATIVE'"
```

### Fail-open policy

A malformed line - an incomplete comparison, unbalanced parentheses, a dangling `&&`, empty parentheses - never blocks anyone. The whole line is treated as always-true, and one warning names the exact line:

```
Malformed requirement: '%broken% >'; evaluates to true
```

{% hint style="info" %}
This is a deliberate fail-open choice: a broken requirement never locks players out of something they should reach. It errs toward allowing and telling you in the console. When you see that warning, fix the expression; it is usually a missing quote or an unbalanced parenthesis.
{% endhint %}

## Fail-open vs fail-closed at a glance

| Situation | What happens |
|---|---|
| Malformed requirement line | The line evaluates to true; one warning. Fail-open. |
| Relational operator on non-numeric text | That comparison is false; one warning. |
| Invalid `[click=...]` spec | The line is skipped; one warning. Fail-closed. |
| Invalid `[chance=N]` value | The line runs anyway; one warning. Fail-open. |
| Unknown action tag | The line is ignored; one warning per tag. |
| Action fails while running | The line is logged; the rest of the list still runs. |
| Click guard with no click in context | The line is skipped quietly. |
| `[click-block]` / `[click-air]` inside a menu | The line is skipped quietly. |

## Related pages

- [Menu Items and Clicks](menu-items-and-clicks.md) - where these lists live in menus, and the full view-requirements and shared-slot rules.
- [Pagination, Regions and Templates](pagination-regions-templates.md) - what the page actions need before they do anything.
- [Physical Items](physical-items.md) - the interact, pickup, drop and break lists that run these actions in the world.
- [Shared Value Formats](value-formats.md) - the full sound grammar used by `[sound]` and the sound keys.
- [Text, Colors and Numbers](text-formatting.md) - the color and tag pipeline that chat-like actions render.
