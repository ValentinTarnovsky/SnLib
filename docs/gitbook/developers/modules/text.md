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

## Legacy color codes reset decorations

A legacy COLOR code (`&0`-`&f` or `&#RRGGBB`) resets the decorations opened by earlier legacy format codes on the same line, exactly like vanilla Minecraft and Adventure's `LegacyComponentSerializer.legacyAmpersand()`. So `&l&cText` renders red and NOT bold: the `&c` color clears the `&l` bold that came before it. To get bold red, write the color first (`&c&lText`). `&r` clears every active decoration and color.

Internally the legacy-to-MiniMessage conversion emits the negation right after the color, so `&l&c` becomes `<bold><red><!bold>`. Only decorations opened by legacy format codes (`&k`-`&o`) are tracked, so an author-written MiniMessage tag keeps pure MiniMessage semantics, where a color tag alone never closes a decoration (`<bold><red>` stays bold). Centering measures the same way: a legacy color inside a `[center]` line drops the bold before the width is counted, so the alignment stays accurate.

{% hint style="info" %}
This is a 1.5.0 behavior fix. The legacy-string path (`colorLegacy`) always reset on the client through section codes; before 1.5.0 the `Component` path (`color`, and therefore every message, item name, lore and menu title) did not, so bold could bleed across a later legacy color. All render paths now share the same vanilla reset.
{% endhint %}

## Section-sign safety

`color(String)` normalizes section-sign (`§`) codes back to the `&` form as its very FIRST step, before any other stage runs. A simple `§X` code becomes `&X` and the `§x§R§R§G§G§B§B` bungee-hex form becomes `&#RRGGBB`, so a `§`-carrying string renders exactly like its `&` equivalent. A `§`-free string (the common case) is passed through untouched, by identity, with no allocation.

This matters because MiniMessage 4.25 **hard-rejects a raw section sign** in its input: a single stray `§` would throw and crash the whole render. Most `§` content arrives from PlaceholderAPI - many expansions return already-colored, section-sign text - so a value like `%some_papi_placeholder%` that resolves to `§aOnline` would otherwise blow up the message, item name or menu title it landed in. The normalization makes that impossible: PAPI output can no longer crash a render. The same conversion is exposed directly as `SnText.normalizePapiOutput(String)` for code that resolves PAPI itself before handing the string on.

## Player-supplied styled text: `cosmetic` and `StylePolicy`

Never render untrusted player input through `color`/`mini`: full MiniMessage includes interactive and metadata tags - `click`, `hover`, `insertion`, `font`, `keybind`, `translatable`, `selector` - and letting a player fire those from a chat rename, a sign, an anvil or a book is a vector for abuse (fake run-command links, spoofed hovers). SnLib gives you two pieces to render player text safely.

### `SnText.cosmetic(String)` - the safe render

`cosmetic` runs the exact same pipeline as `color` (section safety, prefix tags, legacy conversion) but honors only the **cosmetic MiniMessage subset**: colors, decorations, `gradient`, `rainbow` and `reset`. Every other tag is left unresolved and renders as inert literal text, so it can never fire. Use it as the render step for any styled text that originated from a player.

### `StylePolicy` - the gate

`StylePolicy` (in `com.sn.lib.text`) decides *which* styling forms a piece of input is even allowed to carry, and what to do when it carries a disallowed one. Capabilities are fine-grained: `LEGACY_COLOR` (`&0`-`&f`), `HEX` (`&#RRGGBB`), `BOLD`/`ITALIC`/`UNDERLINE`/`STRIKETHROUGH`/`OBFUSCATED`, `MINIMESSAGE` (cosmetic tags as a whole) and `GRADIENT` (the `[rgb]` prefix tag and the `<gradient>`/`<rainbow>` tags).

```java
StylePolicy policy = StylePolicy.builder()
        .allow(StylePolicy.Capability.LEGACY_COLOR, StylePolicy.Capability.BOLD)
        .onDisallowed(StylePolicy.OnDisallowed.STRIP)
        .build();

String vetted = policy.apply(rawNickname);        // enforce the policy
player.sendMessage(SnText.cosmetic(vetted));      // then render with the cosmetic subset
```

- `accepts(input)` / `violations(input)` inspect what an input uses; `violations` returns the disallowed capabilities in declaration order, empty when the input is acceptable.
- `apply(input)` enforces the policy per `OnDisallowed`: `REJECT` (the default) drops all styling to plain visible text, `STRIP` removes only the disallowed styling and keeps the rest. An acceptable input is returned unchanged; null in, null out.
- Build one with `StylePolicy.builder()` (starts enabled, allowing nothing, rejecting), or read one from config with `StylePolicy.fromConfig(section, path)` (keys `enabled`, `allow-legacy-colors`, `allow-hex`, `allow-bold`, ... `allow-minimessage`, `allow-gradient`, `on-disallowed`). A disabled or allow-nothing policy is plain-text-only: every form of styling is a violation.

{% hint style="danger" %}
The safety rule is absolute: a **non-cosmetic** MiniMessage tag (click, hover, and the rest) is a `MINIMESSAGE` violation ALWAYS, even when the `MINIMESSAGE` capability is allowed. `allow-minimessage` only ever unlocks the cosmetic subset. Pair `StylePolicy` with `SnText.cosmetic(...)`, never `SnText.color(...)` / `SnText.mini(...)`, for player input - the policy vets and the cosmetic render is the second line of defense that resolves only that same subset.
{% endhint %}

## Public entry points

| Method | What it does |
|--------|--------------|
| `SnText.color(String)` | Full render: section safety, prefix tags, then legacy conversion, then MiniMessage, to a `Component`. Null input renders as the empty component. |
| `SnText.cosmetic(String)` | Same pipeline as `color`, but only the cosmetic MiniMessage subset (colors, decorations, gradient, rainbow, reset) resolves; every other tag renders as inert literal text. The safe render for player-supplied styled text vetted through `StylePolicy`. |
| `SnText.mini(String)` | MiniMessage-only render. No prefix tags, no legacy conversion. |
| `SnText.colorLegacy(String)` | Same legacy phase as `color`, but the output stays a legacy string with section-sign codes (`&#RRGGBB` becomes the bungee hex sequence). For APIs that still require legacy strings. |
| `SnText.section(String)` | FULL render (legacy, hex, MiniMessage and gradients all resolved to a `Component` first, so a gradient becomes one hex code per glyph) serialized back to section-sign codes. For PAPI and legacy string sinks that cannot take a `Component`. Null in, null out. |
| `SnText.plain(String)` | Visible text only: every form of styling is removed and the fully rendered glyphs are returned. Null in, null out. |
| `SnText.visibleLength(String)` | Codepoint count of `plain(s)`; null and empty count as zero. |
| `SnText.normalizePapiOutput(String)` | Converts section-sign output (a PAPI value) back to the `&` form the pipeline understands; the same conversion `color` runs as its first step. Returns the input by identity when it holds no `§`. |
| `SnText.colorList(List<String>)` | Applies `color` to every line; a null list yields an empty list. |
| `SnText.smallCaps(String)` | Programmatic small-caps transform without the `[small]` tag. |

```java
Component full   = SnText.color("[rgb]&lTitle");
Component safe   = SnText.cosmetic(vettedPlayerInput);
Component pure   = SnText.mini("<gradient:#f00:#00f>MiniMessage</gradient>");
String    legacy = SnText.colorLegacy("&aStill a legacy string");
String    codes  = SnText.section("<gradient:#f00:#00f>for a PAPI sink</gradient>");
String    plain  = SnText.plain("&aHello &lthere");   // "Hello there"
int       width  = SnText.visibleLength("[rgb]&lTitle");
List<Component> lore = SnText.colorList(config.getStringList("lore", List.of()));
```

{% hint style="info" %}
Because the pipeline is shared, the exact same string renders identically on the Paper side and on the Velocity base, where `snv.color()` delegates to this same `SnText`.
{% endhint %}

## Related pages

- [Configuration](yml.md) - config getters resolve placeholders before handing strings to this pipeline.
- [Menus](menus.md) and [Items](items.md) - every display string in a menu or item flows through `SnText`.
- Back to the [developer guide](../README.md) or the [quickstart](../quickstart.md).
