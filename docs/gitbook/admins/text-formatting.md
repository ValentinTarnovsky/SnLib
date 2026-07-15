# Text Formatting and Colors

Every Sn plugin renders text - messages, item names, lore, menu titles - through the same shared text engine. That means color codes and the special formatting tags below work identically in every text field of every Sn plugin: config messages, lang files, item `display-name`/`lore`, menu `title`, and everything else.

{% hint style="info" %}
You can use everything on this page anywhere you can write text in an Sn plugin's YAML: `config.yml`, `lang/messages_*.yml`, item files, and menu files.
{% endhint %}

## Legacy color and formatting codes

The classic Minecraft `&` codes work everywhere:

| Code | Result | Code | Result |
|------|--------|------|--------|
| `&0` | Black | `&8` | Dark gray |
| `&1` | Dark blue | `&9` | Blue |
| `&2` | Dark green | `&a` | Green |
| `&3` | Dark aqua | `&b` | Aqua |
| `&4` | Dark red | `&c` | Red |
| `&5` | Dark purple | `&d` | Light purple |
| `&6` | Gold | `&e` | Yellow |
| `&7` | Gray | `&f` | White |

Formatting codes stack with a color and with each other:

| Code | Effect |
|------|--------|
| `&l` | **Bold** |
| `&o` | *Italic* |
| `&n` | Underline |
| `&m` | ~~Strikethrough~~ |
| `&k` | Obfuscated (scrambled/magic text) |
| `&r` | Reset back to plain text |

```yaml
message: "&a&lWelcome &7to the &6&lserver&r&7!"
```

{% hint style="info" %}
Just like in vanilla Minecraft, a COLOR code clears any active formatting. `&l&cHello` is red and NOT bold, because the `&c` color resets the `&l` bold that came before it. To keep the formatting, write the color first: `&c&lHello` is bold red. `&r` clears both color and formatting. This behaves the same everywhere text is rendered - messages, item names, lore, and menu titles.
{% endhint %}

## Hex colors

Beyond the 16 legacy colors, any exact RGB color is available with `&#RRGGBB`:

```yaml
display-name: "&#FF9B00Autumn Blade"
lore:
  - "&7Hex legacy: &#55FFFFcyan text"
```

## MiniMessage tags

Sn plugins also understand [MiniMessage](https://docs.advntr.dev/minimessage/format.html) tags, and legacy codes and MiniMessage tags can be mixed freely on the same line:

```yaml
lore:
  - "<bold><#55FFFF>MiniMessage</#55FFFF></bold> &7mixed with &alegacy codes"
  - "&7Hex legacy: &#FF9B00orange &7plus <italic>MiniMessage italic</italic>"
```

{% hint style="info" %}
If you are not familiar with MiniMessage, you do not need it: the legacy `&` codes and hex colors above already cover almost everything a config editor needs. MiniMessage is there for anyone who wants richer formatting (gradients, hover text, and more) using its own tag syntax.
{% endhint %}

## The three special tags: `[small]`, `[rgb]`, `[center]`

On top of standard Minecraft coloring, Sn plugins support three extra tags written at the very START of a line. They are composable in any order - `[center][rgb]`, `[rgb][center]`, `[small][rgb][center]` all produce the same result.

### `[small]` - small caps

Turns normal letters into small-capital glyphs. Digits, symbols, color codes and other tags pass through untouched.

```yaml
display-name: "[small]Welcome to the shop"
lore:
  - "[small]&7Small caps lore line"
```

### `[rgb]` - gradient coloring

Paints the line with a smooth gradient that flows through these fixed colors, left to right:

```
purple -> blue -> cyan -> green -> yellow -> orange -> red
```

It overrides any color codes already on the line, but it keeps bold/italic/underline/strikethrough/obfuscated formatting intact:

```yaml
display-name: "[rgb]&lEpic Gradient Title"
```

{% hint style="warning" %}
`[rgb]` colors the line character by character, so it is meant for titles and short lines, not long paragraphs.
{% endhint %}

### `[center]` - centered text

Centers the line so it looks balanced in item lore or a menu title, instead of hugging the left edge. It measures the line AFTER colors and small caps are applied, so centering stays accurate even with a gradient or small-caps text.

```yaml
lore:
  - "[center]&eCentered legacy line"
  - "[center][rgb]Centered gradient line"
```

### Combining all three

```yaml
lore:
  - "[center][rgb]Centered gradient line"
  - "[rgb][center]Same result, tags in any order"
  - "[center]&eCentered legacy-only line"
  - "[small]Small caps lore line"
  - "[center][small][rgb]All three tags composed"
```

## Placeholders render along with everything else

Any `%placeholder%` in a text field resolves before colors and tags are applied, so a placeholder's resolved value gets small-capped, gradient-colored and centered together with the rest of the line, exactly as if you had typed the resolved text yourself:

```yaml
display-name: "[rgb]&lWelcome, %player_name%!"
```

See [Configuration Files](configuration-files.md) for where placeholders work and [Actions and Requirements](actions-and-requirements.md) for placeholders used inside conditions.
