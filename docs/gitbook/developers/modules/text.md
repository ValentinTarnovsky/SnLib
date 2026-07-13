# Text rendering (SnText)

`SnText` is the single text pipeline shared by every SnLib module. Any string that reaches a display surface - a config getter, an item name, a GUI title, a lang message - goes through it. It turns a raw string carrying legacy color codes, MiniMessage tags, and SnLib's own `[small]` / `[rgb]` / `[center]` prefix tags into an Adventure `Component`.

You rarely call `SnText` directly, because the modules render for you. When you do render your own strings, the entry point is `SnText.color(String)`.

```java
Component title = SnText.color("[rgb]&lEpic Title");
player.sendMessage(SnText.color("&aHello &e%player_name%"));
```

## The fixed pipeline order

Rendering always runs the same stages in the same order:

```
local placeholders
  -> PAPI
    -> [small]
      -> [rgb]
        -> legacy color codes (&a, &#RRGGBB)
          -> [center]
            -> MiniMessage render to a Component
```

The order is not incidental, it is load-bearing:

- **Placeholders resolve first** so that `[small]`, `[rgb]` and `[center]` operate on the final visible text (a `%player_name%` becomes small-capped, gradient-colored and centered along with everything else). Locals and PAPI are resolved by the caller (the `SnYml` getters) before the tag stages run.
- **`[small]` before `[rgb]`** so the gradient colors the final small-caps glyphs, and so the small-caps pass works on the short string rather than the string already inflated by per-character hex codes.
- **`[center]` last in the legacy phase**, measured over the already-colored legacy string, right before the whole thing is handed to MiniMessage. "Last" means last in the legacy phase, never after the Component is built, because centering can only measure a legacy string.

Because the three prefix tags are consumed together at the start of the line, they compose in ANY order: `[center][rgb]`, `[rgb][center]`, `[small][rgb][center]` all render identically.

## `[small]` - small caps

`[small]` at the start of a line substitutes `a-z` and `A-Z` with small-capital glyphs, one glyph per character (a 1:1 mapping). Accented vowels are de-accented before mapping; the enye keeps its default glyph. Digits, symbols, color codes and MiniMessage tags pass through untouched.

```yaml
display-name: "[small]Welcome to the shop"
lore:
  - "[small]&7Small caps lore line"
```

For programmatic use without the tag - scoreboards, tab list, entity names - call `SnText.smallCaps(String)`:

```java
String label = SnText.smallCaps("Level " + level);   // digits pass through
```

The transform skips legacy color codes, section-sign sequences and MiniMessage tags verbatim, and returns the SAME instance when nothing changed.

## `[rgb]` - per-character gradient

`[rgb]` at the start of a line paints the text with a gradient interpolated per character across seven fixed anchor colors:

```
#F300F3  #5555FF  #55FFFF  #55FF55  #FCFF21  #FF9B00  #FF5327
```

Two rules matter when mixing `[rgb]` with other codes:

- It **overrides** any pre-existing COLOR codes on the line (the gradient wins).
- It **preserves** formatting flags: `&l` (bold), `&o` (italic), `&n` (underline), `&m` (strikethrough), `&k` (obfuscated) survive the gradient.

```yaml
display-name: "[rgb]&lEpic Gradient Title"   # bold gradient
```

{% hint style="info" %}
`[rgb]` emits one hex code per visible character, so it targets titles and short lines rather than long paragraphs. SnLang caches statically resolved lines, so that per-character cost is paid once.
{% endhint %}

## `[center]` - centered lines

`[center]` centers the line to a 154px reference width (the usable chat/lore width). It measures the already-colored legacy string, so the gradient's interpolated colors and the small-caps glyph widths are all accounted for - small-caps glyphs measure with their own widths, not the width of the base letters.

```yaml
lore:
  - "[center]&eCentered legacy line"
  - "[center][rgb]Centered gradient line"
  - "[rgb][center]Same result, tags in any order"
  - "[center][small][rgb]All three tags composed"
```

## Legacy and MiniMessage together

Legacy codes and MiniMessage tags coexist on the same line. Legacy `&X` and `&#RRGGBB` codes are converted to MiniMessage tags, then the whole string is deserialized by MiniMessage at the end, so both render together.

```yaml
lore:
  - "<bold><#55FFFF>MiniMessage</#55FFFF></bold> &7mixed with &alegacy"
  - "&7Hex legacy &#FF9B00orange &7plus <italic>MiniMessage italic</italic>"
```

A literal `<` that cannot start a valid tag is escaped automatically, so stray angle brackets in user text do not break rendering.

## Public entry points

| Method | What it does |
|--------|--------------|
| `SnText.color(String)` | Full render: prefix tags, then legacy conversion, then MiniMessage, to a `Component`. Null input renders as the empty component. |
| `SnText.mini(String)` | MiniMessage-only render. No prefix tags, no legacy conversion. |
| `SnText.colorLegacy(String)` | Same legacy phase as `color`, but the output stays a legacy string with section-sign codes (`&#RRGGBB` becomes the bungee hex sequence). For APIs that still require legacy strings. |
| `SnText.colorList(List<String>)` | Applies `color` to every line; a null list yields an empty list. |
| `SnText.smallCaps(String)` | Programmatic small-caps transform without the `[small]` tag. |

```java
Component full   = SnText.color("[rgb]&lTitle");
Component pure   = SnText.mini("<gradient:#f00:#00f>MiniMessage</gradient>");
String    legacy = SnText.colorLegacy("&aStill a legacy string");
List<Component> lore = SnText.colorList(config.getStringList("lore", List.of()));
```

{% hint style="info" %}
Because the pipeline is shared, the exact same string renders identically on the Paper side and on the Velocity base, where `snv.color()` delegates to this same `SnText`.
{% endhint %}

## Related pages

- [Configuration](yml.md) - config getters resolve placeholders before handing strings to this pipeline.
- [Menus](menus.md) and [Items](items.md) - every display string in a menu or item flows through `SnText`.
- Back to the [developer guide](../README.md) or the [quickstart](../quickstart.md).
