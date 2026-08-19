# Item Appearance Reference

Every item an Sn plugin draws reads one shared appearance schema: learn these keys once and you can restyle anything. Menu buttons, menu templates, physical items and the selection wand all accept the same block. This page is the full reference; the surface pages only link back here.

## Where the schema applies

| Surface | File | Page |
|---------|------|------|
| Menu items | `guis/<menu>.yml`, under `items:` | [Menu Items and Clicks](menu-items-and-clicks.md) |
| Menu templates | `guis/<menu>.yml`, under `templates:` | [Pagination, Regions and Templates](pagination-regions-templates.md) |
| Physical items | `items.yml`, one block per item id | [Physical Items](physical-items.md) |
| Selection wand | the plugin's `config.yml`, wand section | [Physical Items](physical-items.md) |

Inside menus the block re-renders per viewer; [Menu Items and Clicks](menu-items-and-clicks.md) owns those details.

{% hint style="success" %}
Appearance parsing is lenient. An unknown material, enchantment, flag or effect logs one WARN and is skipped. It never breaks the item or the menu.
{% endhint %}

## material

`material` names the item type. It defaults to `STONE`, and an unresolvable id falls back to `STONE` with one WARN.

```yaml
material: DIAMOND_SWORD            # Bukkit name, case-insensitive
material: minecraft:diamond_sword  # namespaced ids work too
```

### Custom heads

A texture value written in `material` builds a textured player head. You never write `PLAYER_HEAD` yourself: SnLib switches the material automatically.

| Value form | Example |
|------------|---------|
| `texture-` / `texture:` prefix | `texture-eyJ0ZXh0dXJlcyI6...` |
| `base64-` / `base64:` prefix | `base64-eyJ0ZXh0dXJlcyI6...` |
| `basehead-` prefix | `basehead-eyJ0ZXh0dXJlcyI6...` |
| Raw base64 payload | `eyJ0ZXh0dXJlcyI6...` |
| Skin URL | `https://textures.minecraft.net/texture/<id>` |

A value not recognized as one of these forms is read as a plain material name; unresolvable, it falls back to `STONE` with one WARN. A recognized form carrying a corrupt payload applies silently and shows the default head.

## skull-owner

`skull-owner` shows a real player's head by name or UUID. It requires `material: PLAYER_HEAD`. Placeholders resolve per viewer, so every player can see their own head.

```yaml
material: PLAYER_HEAD
skull-owner: '%player_name%'
```

A name the server has not cached shows the default head first, and the skin is fetched off-thread. A menu cell re-renders on its own when the skin lands; a physical stack already sitting in an inventory keeps the default look and shows the skin the next time the item is created. Only a truly unresolvable owner WARNs, once.

> When `skull-owner` and a texture material are both set, `skull-owner` wins and the texture is ignored, with one WARN.

## display-name and lore

Both render through the shared text pipeline: `&` codes, hex, MiniMessage and the `[small]`/`[rgb]`/`[center]` tags. See [Text, Colors and Numbers](text-formatting.md). Text renders non-italic unless you ask for italics.

```yaml
display-name: "[rgb]&lStarter Kit"
lore:
  - "&7A gift for new players."
  - ""                             # empty string = one blank line
  - "&eRight-click to open!"
```

An absent or empty `display-name` keeps the item's vanilla name. A literal `\n` inside one lore line splits it into several lore lines. A trailing `\n` adds no empty line.

## amount

`amount` sets the stack size and defaults to 1. Values below 1 are floored to 1; the schema imposes no upper cap.

## Present-only keys

Four keys only act when the key is physically present in the file. Writing the default value is not the same as omitting the key.

| Key | When present |
|-----|--------------|
| `custom-model-data` | Stamps the int used for resource pack model selection. |
| `unbreakable` | Sets the vanilla unbreakable flag, `true` or `false`. |
| `max-stack-size` | Overrides the stack cap. Needs Minecraft 1.20.5+; skipped with one WARN on 1.20.4. |
| `damage` | Starting vanilla durability already spent, clamped to the material's range. |

> `damage: 0` is not the same as no `damage` key. A present key always applies; an absent key changes nothing.

`damage` needs a material with vanilla durability; anything else WARNs once and skips the field. It is independent from the custom durability system of [Physical Items](physical-items.md).

### item-model (Minecraft 1.21.2+)

`item-model` sets the base ItemModel key, for example `nexo:2d_player_head`. A key without a namespace defaults to `minecraft:`. It is independent of `custom-model-data`: both can coexist on one item. Below Minecraft 1.21.2, or with an invalid key, the field is skipped with one WARN.

```yaml
item-model: "nexo:2d_player_head"
```

## glow and enchantments

`glow: true` adds the enchantment glint without any real enchantment. On Minecraft 1.20.4 it degrades to a hidden vanilla enchant plus `HIDE_ENCHANTS`, with one WARN.

`enchantments` accepts flat id and level pairs, or both tokens inside one quoted string:

```yaml
enchantments: [sharpness, 5, unbreaking, 3]
# same result:
enchantments:
  - "SHARPNESS 5"
  - "UNBREAKING 3"
```

A missing level defaults to 1. Ids resolve leniently: registry keys and legacy Bukkit names both work.

## flags

| Flag | Hides |
|------|-------|
| `HIDE_ENCHANTS` | enchantment lines |
| `HIDE_ATTRIBUTES` | attribute modifiers |
| `HIDE_UNBREAKABLE` | the unbreakable line |
| `HIDE_DESTROYS` | the can-destroy list |
| `HIDE_PLACED_ON` | the can-place-on list |
| `HIDE_POTION_EFFECTS` | potion and extra tooltip info |
| `HIDE_ALL` | everything: expands to every flag this server knows |
| `HIDE_TOOLTIP` | the whole tooltip box, display name included |

Any `ItemFlag` name your server knows is accepted; the table lists the common ones. `HIDE_POTION_EFFECTS` and `HIDE_ADDITIONAL_TOOLTIP` alias each other automatically, so whichever name your Minecraft version lacks still resolves.

`HIDE_ALL` and `HIDE_TOOLTIP` are SnLib names, not real `ItemFlag`s, and they are not the same thing. `HIDE_ALL` empties the tooltip of every flagged line but the item still shows its name on hover; `HIDE_TOOLTIP` removes the box entirely, so nothing is shown at all - the right choice for a decorative filler or a background item in a menu. Because it would silently erase the names of items that only asked for `HIDE_ALL`, `HIDE_TOOLTIP` is never implied by `HIDE_ALL`: list it yourself.

```yaml
flags:
  - HIDE_TOOLTIP
```

`HIDE_TOOLTIP` needs Minecraft 1.20.5 or newer (it is the `hide_tooltip` item component). On 1.20.4 it is skipped with one WARN and the item keeps its tooltip.

## color

`color` tints color-capable items: leather armor and potions. It accepts `R, G, B` or hex values.

```yaml
color: "235, 64, 52"
color: "#FF0000"
```

A malformed value, or a material that cannot be tinted, is ignored with one WARN.

## Armor trims

`trim-pattern` and `trim-material` are flat sibling keys, not a nested section. Values resolve against the server's trim registries, so any pattern and material id your server knows works. `NONE` or an empty value disables the trim. Trims apply to armor only; other materials WARN once.

```yaml
trim-pattern: sentry
trim-material: gold
```

> `trim-pattern` and `trim-material` only work as a pair. One without the other is ignored, with one WARN.

## potion-effects

`potion-effects` lists custom effects as flat triples: effect, level, duration in ticks.

```yaml
material: POTION
potion-effects: [SPEED, 2, 600]   # Speed II for 30 seconds
```

Level defaults to 1 and duration to 200 ticks. The values are level-based: `1` means level 1, exactly as players read it. The `held-effects` key of physical items counts amplifiers instead; see [Physical Items](physical-items.md) for the side-by-side. A material without potion meta WARNs once and skips the list.

## equipment-slot and attributes

`equipment-slot` declares where a physical item may be equipped: `MAINHAND`, `OFFHAND`, `HEAD`, `CHEST`, `LEGS` or `FEET`. A typo WARNs once. The stack itself is not altered; enforcement happens in the physical item layer of [Physical Items](physical-items.md).

`attributes` lists modifier lines with a fixed grammar:

```yaml
attributes:
  - "GENERIC_MOVEMENT_SPEED ADD_NUMBER 0.02 HAND"
  - "ARMOR ADD_NUMBER 4 CHEST"
```

| Part | Values |
|------|--------|
| Attribute | Any attribute id. `GENERIC_ARMOR` and `ARMOR` both resolve across the Minecraft 1.21.2 rename. |
| Operation | `ADD_NUMBER`, `ADD_SCALAR`, `MULTIPLY_SCALAR_1` |
| Amount | A number. |
| Slot group (optional) | `ANY`, `HAND`, `ARMOR`, `MAINHAND`, `OFFHAND`, `HEAD`, `CHEST`, `LEGS`, `FEET`, `BODY`. Default `ANY`. On Minecraft 1.20.4, `BODY` has no single-slot equivalent and applies to every slot. |

A line with fewer than three parts, or a non-numeric amount, is skipped with one WARN.

> Attribute lines are static definition values. Placeholders never resolve inside `attributes`.

## What does not exist

{% hint style="warning" %}
There are no key aliases, no PDC or NBT tags, no banner patterns and no book pages. If a key is not on this page, SnLib does not read it.
{% endhint %}

## Full example

One item carrying every appearance key. Keys that need a specific material say so in their comment; keep only the keys your material supports.

```yaml
starter-chestplate:
  material: LEATHER_CHESTPLATE       # Bukkit or minecraft: id; a texture value builds a head
  display-name: "[rgb]&lStarter Chestplate"
  lore:
    - "&7Part of the starter kit."
    - ""                             # empty string = one blank line
    - "&eYours forever: &funbreakable."
  amount: 1                          # floored at 1, no upper cap
  glow: true                         # glint without a real enchantment
  enchantments: [protection, 2]      # flat pairs; "PROTECTION 2" works too
  flags:
    - HIDE_ATTRIBUTES
    - HIDE_DYE
  color: "#8354F2"                   # leather armor and potions only
  trim-pattern: sentry               # sibling keys, only valid as a pair
  trim-material: gold
  custom-model-data: 4001            # present-only: applies because the key is here
  item-model: "minecraft:chainmail_chestplate"  # Minecraft 1.21.2+, coexists with custom-model-data
  unbreakable: true                  # present-only
  max-stack-size: 1                  # present-only; Minecraft 1.20.5+
  damage: 0                          # present-only: 0 still stamps pristine durability
  equipment-slot: CHEST              # declared slot; enforced by the physical item layer
  attributes:
    - "ARMOR ADD_NUMBER 4 CHEST"     # static values, never placeholders
  # These two need a different material; shown for completeness:
  # skull-owner: '%player_name%'     # requires material: PLAYER_HEAD; wins over textures
  # potion-effects: [SPEED, 2, 600]  # requires a potion material; level-based
```

## Related pages

- [Menu Items and Clicks](menu-items-and-clicks.md): where these keys sit inside a menu button, and how clicks resolve.
- [Pagination, Regions and Templates](pagination-regions-templates.md): templates reuse this schema, and plugin-supplied stacks override most of it.
- [Physical Items](physical-items.md): the behavior keys of `items.yml`, plus the held-effects versus potion-effects contrast.
- [Text, Colors and Numbers](text-formatting.md): the pipeline behind `display-name` and `lore` values.
- [Troubleshooting](troubleshooting.md): the appearance WARNs, including `skull-owner` overriding a texture.
