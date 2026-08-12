# Text, Colors and Numbers

Every Sn plugin renders text - messages, item names, lore, menu titles - through the same shared text engine. Color codes, special tags and number hints work identically in every text field of every Sn plugin.

{% hint style="info" %}
Everything on this page works anywhere you write text in an Sn plugin's YAML: `config.yml`, lang files, item files, and menu files.
{% endhint %}

## The rendering pipeline

Every line passes the same fixed stages, in this order:

| Step | Stage | What happens |
|------|-------|--------------|
| 1 | Local placeholders | The plugin's own `{tokens}` resolve. Number hints (below) apply here. |
| 2 | PlaceholderAPI | `%papi%` tokens resolve. Pre-colored `§` output is normalized automatically. |
| 3 | `[small]` | Letters become small-cap glyphs. |
| 4 | `[rgb]` | The gradient paints every visible character. |
| 5 | `[center]` | The line is measured and centered while its colors are still `&` codes. |
| 6 | Legacy codes | `&` codes and `&#RRGGBB` hex convert to the modern rendering format. |
| 7 | MiniMessage | MiniMessage tags render into the final text players see. |

The order explains most surprises. A placeholder's value gets styled like typed text because it resolves first. `[center]` measures the line after coloring, so centering stays accurate with gradients and small caps.

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

> A COLOR code cancels every format code before it. Write the color first, then the format.

`&l&cHello` is red and NOT bold: the `&c` color resets the earlier `&l`. Write `&c&lHello` for bold red. `&r` clears both color and formatting. This matches vanilla Minecraft and behaves the same in every text field.

## Hex colors

Beyond the 16 legacy colors, any exact RGB color is available with `&#RRGGBB`:

```yaml
display-name: "&#FF9B00Autumn Blade"
lore:
  - "&7Hex legacy: &#55FFFFcyan text"
```

## MiniMessage tags

Sn plugins also understand [MiniMessage](https://docs.advntr.dev/minimessage/format.html) tags. Legacy codes and MiniMessage tags mix freely on the same line:

```yaml
lore:
  - "<bold><#55FFFF>MiniMessage</#55FFFF></bold> &7mixed with &alegacy codes"
  - "&7Hex legacy: &#FF9B00orange &7plus <italic>MiniMessage italic</italic>"
```

{% hint style="info" %}
If you are not familiar with MiniMessage, you do not need it. The legacy `&` codes and hex colors already cover almost everything. MiniMessage adds richer formatting on top: gradients, hover text, click actions and more.
{% endhint %}

## The special tags

Sn plugins add four tags written at the very START of a line:

| Tag | Effect |
|-----|--------|
| `[small]` | Turns letters into small-capital glyphs. |
| `[rgb]` | Paints the line with a fixed seven-color gradient. |
| `[center]` | Centers the line in lore, titles and chat. |
| `[noprefix]` | Sends a lang message without the chat prefix. Full rules in [Language Files](language-files.md). |

> Tag order never matters: `[center][rgb]` and `[rgb][center]` render identically.

### `[small]` - small caps

Turns normal letters into small-capital glyphs. Accented vowels lose the accent. Digits, symbols, color codes and MiniMessage tags pass through untouched.

```yaml
display-name: "[small]Welcome to the shop"
lore:
  - "[small]&7Small caps lore line"
```

### `[rgb]` - gradient coloring

Paints the line with a smooth gradient over seven fixed anchor colors, left to right. The anchors are not configurable:

```
purple -> blue -> cyan -> green -> yellow -> orange -> red
```

Color codes already on the line are overridden. Format codes (`&l`, `&o`, `&n`, `&m`, `&k`) are preserved. Formatting behaves exactly like outside the gradient: a color code still cancels earlier formats, so a bold prefix never bleeds forward.

```yaml
display-name: "[rgb]&lEpic Gradient Title"
```

{% hint style="warning" %}
`[rgb]` colors the line character by character, so it is meant for titles and short lines, not long paragraphs.
{% endhint %}

### `[center]` - centered text

Centers the line at a fixed width of 154 pixels, so it looks balanced instead of hugging the left edge. It measures the line AFTER colors and small caps apply, so centering stays accurate with gradients and small-caps text.

```yaml
lore:
  - "[center]&eCentered legacy line"
  - "[center][rgb]Centered gradient line"
```

### Combining the tags

A single menu item can use everything at once:

```yaml
example-text-item:
  display-name: "[rgb]&lEpic Gradient Title"
  material: NETHER_STAR
  slots: [22]
  lore:
    - "[center][rgb]Centered gradient line"
    - "[rgb][center]Same result, tags in any order"
    - "[center]&eCentered legacy-only line"
    - "[small]Small caps lore line"
    - "[center][small][rgb]All three tags composed"
    - "<bold><#55FFFF>MiniMessage</#55FFFF></bold> &7mixed with &alegacy codes"
```

## Placeholders render along with everything else

Any placeholder resolves before colors and tags apply. The resolved value gets small-capped, gradient-colored and centered with the rest of the line. It renders exactly as if you had typed the resolved text yourself:

```yaml
display-name: "[rgb]&lWelcome, %player_name%!"
```

{% hint style="info" %}
Some PlaceholderAPI expansions return text already colored with section-sign (`§`) codes. That is fine: Sn plugins normalize `§` output back into the same coloring system before rendering. A pre-colored placeholder displays correctly instead of breaking the line. You do not have to do anything.
{% endhint %}

### Number hints (1.26.0)

Any placeholder that holds a number can be rendered in three ways, by adding a hint after a colon. This works in message values, item names, item lore and menu titles. It needs no change to the plugin.

| You write | `1500000` becomes | Use it for |
| --- | --- | --- |
| `{balance:short}` | `1.5M` | Chat, lore, scoreboards - anywhere space is tight |
| `{balance:grouped}` | `1,500,000` | Receipts and confirmations, where the full figure matters |
| `{balance:raw}` | `1500000` | Plain digits, no separators |

Writing the placeholder without a hint, `{balance}`, keeps whatever the plugin already sends. Nothing changes in a file you do not edit.

```yaml
# Before
balance-msg: "&aYou have &f{balance} &acoins"        # You have 1500000 coins

# After
balance-msg: "&aYou have &f{balance:short} &acoins"  # You have 1.5M coins
```

The suffix ladder is fixed: `K`, `M`, `B`, `T`, `Qa`, `Qi`. `short` and `grouped` round to at most two decimals. `raw` never rounds, so reach for it when a player needs the exact figure.

> Number hints work on local placeholders only, in either `{key:hint}` or `%key:hint%` form. A real `%papi%` token never takes a hint.

A hinted `%papi%` token is not recognized as local, so it passes through untouched, hint and all.

{% hint style="warning" %}
Only `raw`, `short` and `grouped` are hints. Any other word after a colon is left completely alone, so a Discord timestamp like `<t:1700000000:R>` keeps working untouched. A hint on something that is not a number does nothing rather than breaking the line: `{player:short}` still shows the player's name.
{% endhint %}

{% hint style="info" %}
A plugin may shorten a number before sending it. Then `:raw` returns a rounded figure, because the exact value was already lost. When you need the true number, use a placeholder the plugin sends unformatted.
{% endhint %}

Typing `2k` or `1.5m` INTO a plugin command is a separate input feature: see [Shared Value Formats](value-formats.md).

## Player-typed text is gated separately

{% hint style="info" %}
This page covers text YOU write in YAML. Text typed by players (chat input, renames, styled tags) passes a separate per-plugin gate called StylePolicy. If player-typed text loses colors or click tags, that gate filtered it; your files are not involved.
{% endhint %}

## Related pages

- [Language Files](language-files.md) - the chat prefix, `[noprefix]`, and the messages you edit most.
- [Item Appearance Reference](item-appearance.md) - the `display-name` and `lore` fields these codes style.
- [Actions and Requirements](actions-and-requirements.md) - placeholders inside action lines and conditions.
- [Shared Value Formats](value-formats.md) - typed number input like `2k`, plus sounds and durations.
- [Configuration Files](configuration-files.md) - how your edited text survives plugin updates.
