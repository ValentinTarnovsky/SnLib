# Physical Items

Physical items are real stacks: given into inventories, dropped, worn, crafted and used in the world. Your plugin defines them in `items.yml`, and the guiding principle is: one id, one item, every key optional. An item with only a `material` is already valid; every other key layers behavior on top. GUI icons are not physical items; those live in [Menus](menus.md).

## One flat map of ids

`items.yml` has no wrapper section. Every top-level key is an item id, and everything about that item sits under it.

```yaml
teleport-wand:
  material: BLAZE_ROD
  display-name: "&dTeleport Wand"

ruby:
  material: RED_DYE
  display-name: "&cRuby"
```

Because ids live at the top level, the file can carry the `# sn:extensible-root` marker. Ids you add yourself then survive every update; [Configuration Files](configuration-files.md) explains the marker system. Some plugins define their items purely in code, so a plugin can use physical items and still ship no file.

Not sure which plugin owns a stack? Hold it and run `/snlib iteminfo` ([The /snlib Command](snlib-command.md)).

## Appearance

Physical items use the shared appearance schema: `material`, `display-name`, `lore`, `enchantments`, `flags`, `color`, trims, `equipment-slot` and more. Every key is listed in the [Item Appearance Reference](item-appearance.md). Appearance is re-read on every give, so placeholders in names and lore resolve for the receiving player.

## Behavior properties

These booleans and numbers control what the player can do with the stack.

| Key | Default | What it controls |
|---|---|---|
| `droppable` | `true` | Whether the player can drop the item. |
| `moveable` | `true` | Whether the item can be moved in inventories. |
| `placeable` | `true` | Whether a block item can be placed. |
| `tradeable` | `true` | Whether the item can be placed into a villager trade window. Player-to-player transfers follow `droppable` and `moveable`. |
| `despawnable` | `true` | Whether the dropped stack despawns on the ground. |
| `keep-on-death` | `false` | The item survives death and returns on respawn. |
| `cooldown` | `0` | Ticks between uses; `0` disables the cooldown. |

## Protection and obtain control

Four keys turn an item into something players cannot lose, steal or duplicate.

| Key | Default | Effect |
|---|---|---|
| `locked` | `false` | Pins the item to its slot; all seven theft vectors are blocked. |
| `no-drop` | `false` | Hard alias of `droppable: false`; blocks Q-drop and drag-out attempts. |
| `no-manual-equip` | `false` | Blocks manual equipping into armor and offhand slots. |
| `obtain-via` | `""` | `""` is unrestricted; `COMMAND_ONLY` lets only the owning plugin equip the piece, cancelling manual equips. |

The seven theft vectors of `locked` are: inventory clicks, drag, manual equip via interact, hand swap, drop, death drops, and hopper transfer. Death drops being one of them, a locked item survives death even without `keep-on-death`. When a locked item displaces a real one, for example in an armor slot, the displaced item is backed up write-through. It is restored on quit and on shutdown, and the backup survives a server crash.

`COMMAND_ONLY` does not touch crafting; give the item no `recipe:` and it cannot be crafted. Mobs can never pick up a registered Sn item, with or without `obtain-via`.

> Locked blocks players, not the plugin. The owning plugin removes a locked item through its own commands, never through the inventory.

```yaml
vip-badge:
  material: NAME_TAG
  display-name: "&6VIP Badge"
  locked: true
  no-manual-equip: true
  obtain-via: COMMAND_ONLY
```

## Custom durability

An item can carry its own durability counter, separate from vanilla durability. Use it for items that normally have none, like sticks or emeralds, or to override the vanilla value.

| Key | Default | Meaning |
|---|---|---|
| `max` | `0` | Maximum durability; `0` disables the whole system. |
| `damage-per-use` | `1` | Durability lost per use. |
| `lore-format` | `""` | Lore line rendering `%durability%` and `%max_durability%`; empty shows no line. |
| `break-actions` | `[]` | Action lines run when durability reaches 0. |

```yaml
fragile-pick:
  material: IRON_PICKAXE
  custom-durability:
    max: 50
    damage-per-use: 1
    lore-format: "&7Durability: &f%durability%/%max_durability%"
    break-actions:
      - "[sound] ENTITY_ITEM_BREAK"
      - "[message] &cYour pickaxe shattered!"
```

Break actions run with the real click type and surface of the breaking interaction. Click and surface guards inside the list therefore evaluate normally.

## Interact actions

An item reacts to clicks in the world while it is held. There are twelve optional action lists, one per click variant.

| Base list | Shift variant |
|---|---|
| `right-click-actions` | `shift-right-click-actions` |
| `left-click-actions` | `shift-left-click-actions` |
| `right-click-block-actions` | `shift-right-click-block-actions` |
| `right-click-air-actions` | `shift-right-click-air-actions` |
| `left-click-block-actions` | `shift-left-click-block-actions` |
| `left-click-air-actions` | `shift-left-click-air-actions` |

The `-block-` lists fire only when the click hits a block; the `-air-` lists only when it hits the air. Every line uses the shared action grammar of [Actions and Requirements](actions-and-requirements.md). Clicks inside a menu never reach these lists; menus have their own click matrix ([Menu Items and Clicks](menu-items-and-clicks.md)).

> With shift held, a declared shift list runs instead of its base list. Set `shift-overrides-generic: false` to run both, shift first.

An empty shift list does not count as declared: the base list still runs. The same priority rule applies to the shift positional lists over the plain positional ones.

### Requirements and deny actions

`interact-requirements` gate all twelve lists. Each line is a placeholder comparison, and multiple lines mean AND; the full grammar lives in [Actions and Requirements](actions-and-requirements.md). When the check fails, `deny-actions` run instead. Deny actions also see the real click type and surface, so guards inside them evaluate.

```yaml
interact-requirements:
  - "%player_level% >= 5"
deny-actions:
  - "[message] &cYou need level 5 to use this."
  - "[sound] ENTITY_VILLAGER_NO"
```

### Pickup and drop

`pickup-actions` fire when a player picks the stack up; `drop-actions` fire when a player drops it. Both are plain action lists with no extra keys.

## Held effects

`held-effects` applies potion effects continuously while the stack sits in a specific place. It has three optional lists: `mainhand`, `offhand` and `armor`.

```yaml
held-effects:
  mainhand:
    - "SPEED 0"           # Speed I while in the main hand
  offhand:
    - "NIGHT_VISION 0"
  armor:
    - "RESISTANCE 1"      # Resistance II while worn
```

Each line is `"EFFECT amplifier"`. The amplifier is the level minus one, which is the opposite convention of the appearance key `potion-effects`. Keep the two apart:

| | `held-effects` | `potion-effects` |
|---|---|---|
| Lives in | `held-effects.mainhand` / `offhand` / `armor` | The appearance section ([Item Appearance Reference](item-appearance.md)) |
| Line format | `"EFFECT amplifier"` | Flat triples: effect, level, duration in ticks |
| Number convention | Amplifier based: `0` means level 1 | Level based: `1` means level 1 |
| Active | While held or worn in the matching slot | Stored on the stack itself |

> `held-effects` counts from zero and `potion-effects` counts from one: `"SPEED 0"` here and a `potion-effects` level of `1` are both Speed I.

## Crafting recipes

The `recipe:` block registers a real crafting or cooking recipe for the item. The `type` picks the grammar; an absent type registers nothing.

| `type` | Required keys | Notes |
|---|---|---|
| `SHAPED` | `shape` + `ingredients` map | Up to three shape rows; each symbol maps to a material. |
| `SHAPELESS` | `ingredients` list | A flat material list, order does not matter. |
| `FURNACE` | `input` | Optional `experience` (default 0.0) and `cooking-time` in ticks (default 200). |
| `SMOKING` | `input` | Same optional keys as `FURNACE`. |
| `BLASTING` | `input` | Same optional keys as `FURNACE`. |
| `CAMPFIRE` | `input` | Same optional keys as `FURNACE`. |
| `STONECUTTING` | `input` | A single input material, nothing else. |

```yaml
recipe:
  type: SHAPED
  shape:
    - "DDD"
    - "DSD"
    - "DDD"
  ingredients:
    D: DIAMOND
    S: STICK
```

```yaml
recipe:
  type: FURNACE
  input: RAW_GOLD
  experience: 0.5
  cooking-time: 200   # ticks
```

A `SHAPELESS` recipe writes its ingredients inline: `ingredients: [DIAMOND, DIAMOND, STICK]`. A malformed recipe, such as `SHAPED` without a shape or a cooking type without `input`, warns and is ignored. The item itself still loads; only the recipe is dropped. An unknown `type` warns and is ignored the same way.

{% hint style="info" %}
Some plugins ship redeemable items: right-clicking consumes the stack and credits you something. Redeemables are configured entirely in code and have no `items.yml` keys. If a redeemable misbehaves, the plugin owns that behavior, not this file.
{% endhint %}

## The selection wand

Plugins that let you select cuboid regions hand out an SnLib selection wand. Its configuration is a section in the plugin's own `config.yml`; the examples use `selection-wand:` as the section name.

> The wand is configured in the plugin's `config.yml`, never in `items.yml`.

```yaml
selection-wand:
  item:
    material: GOLDEN_AXE
    display-name: "&6&lRegion Wand"
    lore:
      - "&7Left click: &fpos1"
      - "&7Right click: &fpos2"
    glow: true
  permission: "myplugin.wand"
  particle:
    type: DUST
    color: "255, 140, 0"
    size: 1.2
  step: 0.5
  interval-ticks: 5
  render-distance: 48
  visibility: OWNER_ONLY
  particle-budget: 2000
  max-render-volume: 250000
  max-volume: 0
  timeout-ticks: 0
  silent: false
```

Every field is optional: absent fields fall back to the defaults below, and invalid values warn once and keep the default.

| Key | Default | Meaning |
|---|---|---|
| `item` | Internal fallback | Wand appearance; the full shared appearance schema applies. The fallback is a BLAZE_ROD named `&6&lRegion Wand`. |
| `permission` | `""` | Permission required to use the wand; empty means anyone holding it. |
| `particle.type` | `DUST` | Any particle name; `REDSTONE` is an alias of `DUST`. Invalid names fall back to `FLAME` with one warning. |
| `particle.color` | `"255, 140, 0"` | `DUST` only; `"R, G, B"` or `"#RRGGBB"`. |
| `particle.size` | `1.2` | `DUST` only; clamped between 0.1 and 4.0. |
| `step` | `0.5` | Blocks between edge points; minimum 0.1. |
| `interval-ticks` | `5` | Render refresh period in ticks; minimum 1. |
| `render-distance` | `64` | Max distance from a viewer to the box; measured to the closest point of the box. |
| `visibility` | `OWNER_ONLY` | `OWNER_ONLY` shows edges only to the selecting player; `WORLD` shows them to that world's players in range. |
| `particle-budget` | `2000` | Max particle points per refresh per viewer; bigger boxes render sparser, never heavier. |
| `max-render-volume` | `250000` | Above this block volume only the 8 corners are marked. |
| `max-volume` | `0` | Max selectable volume in blocks; `0` is unlimited. An oversized selection warns and stays open for correction. |
| `timeout-ticks` | `0` | Session timeout; `0` never expires. A timeout cancels the selection and sends a message. |
| `silent` | `false` | Suppresses every selection message. |

Wand messages come from SnLib's own language file, under the `snlib.selection.*` keys. You edit them in [Language Files](language-files.md), and `silent: true` turns them all off.

## Full example: items.yml

Three complete items showing the sections above working together. Copy any of them as a starting point.

```yaml
# =========================================================
# 1) A command-only cosmetic crown, impossible to lose
# =========================================================
royal-crown:
  material: GOLDEN_HELMET
  display-name: "[rgb]&lRoyal Crown"
  lore:
    - "&7Worn by the server royalty."
  glow: true
  trim-pattern: SENTRY
  trim-material: GOLD
  equipment-slot: HEAD        # appearance schema key: HEAD, CHEST, LEGS, FEET, MAINHAND, OFFHAND
  keep-on-death: true
  locked: true
  obtain-via: COMMAND_ONLY
  held-effects:
    armor:
      - "RESISTANCE 0"        # amplifier 0 = Resistance I while worn

# =========================================================
# 2) A gated teleport wand with charges
# =========================================================
teleport-wand:
  material: BLAZE_ROD
  display-name: "&dTeleport Wand"
  lore:
    - "&7Right-click to travel to spawn."
  cooldown: 100               # ticks: 5 seconds between uses
  droppable: false
  tradeable: false
  custom-durability:
    max: 10
    damage-per-use: 1
    lore-format: "&7Charges: &f%durability%/%max_durability%"
    break-actions:
      - "[sound] ENTITY_ITEM_BREAK"
      - "[message] &cYour wand is spent."
  interact-requirements:
    - "%player_level% >= 5"
  deny-actions:
    - "[message] &cYou need level 5 to use this."
  right-click-actions:
    - "[player] spawn"
    - "[sound] ENTITY_ENDERMAN_TELEPORT"

# =========================================================
# 3) A craftable gem that never despawns
# =========================================================
ruby:
  material: RED_DYE
  display-name: "&cRuby"
  lore:
    - "&7A rare crafting gem."
  despawnable: false
  pickup-actions:
    - "[actionbar] &cRuby &7added to your inventory"
  recipe:
    type: SHAPED
    shape:
      - " R "
      - "RDR"
      - " R "
    ingredients:
      R: REDSTONE
      D: DIAMOND
```

## Related pages

- [Item Appearance Reference](item-appearance.md) - every appearance key an item can carry.
- [Actions and Requirements](actions-and-requirements.md) - the grammar behind every action line and requirement in this file.
- [Configuration Files](configuration-files.md) - how `items.yml` merges on update and how the extensible-root marker protects your ids.
- [Language Files](language-files.md) - the `snlib.selection.*` keys behind the wand messages.
- [The /snlib Command](snlib-command.md) - `/snlib iteminfo` identifies any SnLib stack in your hand.
