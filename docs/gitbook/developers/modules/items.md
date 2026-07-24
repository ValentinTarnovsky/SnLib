# Items

The items module defines PHYSICAL items - the ones given to players in the inventory, dropped, worn, or used in the world (not GUI icons; those live in the [menus](menus.md) module). An item can be defined in YAML (`items.yml`, golden spec `docs/item-example.yml`) or built 100% programmatically with `ItemDef.builder()` and `SnItem`, with no file at all.

You reach the module through `sn.items()`. Declaring `items("items.yml")` in the spec loads a file; programmatic registration works with no spec file at all.

```java
sn.items().register("wand", ItemDef.builder()
        .item(SnItem.builder(Material.BLAZE_ROD).name("[rgb]&lWand").glow())
        .locked().noDrop().obtainVia(ObtainMode.COMMAND_ONLY)
        .onRightClick((player, stack) -> castSpell(player))
        .build());

sn.items().give(player, "wand", 1);
```

- `register(id, def)` registers a definition (re-registering replaces it, adds any recipe, and starts held-effect timers lazily). `register(id, snYml)` parses a definition from a YAML section.
- `give(player, id, amount)` builds the stack tagged with an owner-namespaced id and hands it over, splitting into max-stack chunks and dropping the overflow at the player's feet.
- `take(player, id, amount)` / `removeAll(player, id)` are the symmetric removal: they scan every inventory slot (storage, armor, off hand) plus the open cursor for stacks tagged with the id and remove up to `amount` units (all of them for `removeAll`), returning how many were removed. Removal is programmatic, so `locked`/`no-drop` flags never block it and no cancellable event fires. Every command-given locked item MUST ship a removal path built on these (typically a give/remove toggle command plus cleanup on quit) - a give-only locked item is unremovable by design of the lock it opted into.

{% hint style="info" %}
Every created stack carries the PDC key `snlib_item_id` (namespaced per owner plugin). That tag is how the module resolves any stack back to its definition, which is what makes the protections below reliable.
{% endhint %}

## Building appearance with SnItem

`SnItem` is the fluent appearance builder. It covers the full appearance section of the golden spec, and every id (material, enchantment, effect, trim) resolves leniently: an unresolvable value logs one warning and is skipped, never thrown.

```java
SnItem look = SnItem.builder(Material.DIAMOND_SWORD)
        .name("[rgb]&lSoulblade")
        .lore("&7A blade that remembers.", "", "&eRight-click to strike")
        .glow()
        .enchant("sharpness", 5)
        .flags(List.of("HIDE_ALL"));
```

### Newer SnItem additions

- **`skullOwner(String)`** - a player head by UUID or by cached name, never a blocking lookup. A UUID resolves via the non-blocking `getOfflinePlayer(UUID)`; a name resolves through the profile cache. When the server has no cached textured profile the head shows the default now and an OFF-THREAD `PlayerProfile.update()` fetch upgrades it: inside a live GUI the affected slot re-renders when the texture lands, and a direct build shows it on its next build once the shared cache warms. Results are cached (a positive TTL, and a short negative TTL so a genuinely unresolvable owner is not hammered), and only that genuinely-unresolvable owner still warns once. Requires `PLAYER_HEAD`.
- **`attribute(id, operation, amount, slotGroup)`** - attribute modifiers with lenient id resolution (the bidirectional `GENERIC_ARMOR` / `ARMOR` alias of the 1.21.2+ rename is handled). On 1.21+ it uses the modern `NamespacedKey` + `EquipmentSlotGroup` constructor; on 1.20.4 it falls back to the deprecated UUID constructor with a deterministic name-derived UUID.
- **`damage(int)`** - initial VANILLA durability already spent, clamped to `[0, max durability]` at build. This is separate from the custom-durability system described below.

## The 12 interact-action variants

An item reacts to how the player interacts while holding it. There are twelve interact variants, each an optional YAML list AND an optional Java callback:

| Base | With shift |
|------|-----------|
| `right-click-actions` | `shift-right-click-actions` |
| `left-click-actions` | `shift-left-click-actions` |
| `right-click-block-actions` | `shift-right-click-block-actions` |
| `right-click-air-actions` | `shift-right-click-air-actions` |
| `left-click-block-actions` | `shift-left-click-block-actions` |
| `left-click-air-actions` | `shift-left-click-air-actions` |

The `block` / `air` variants fire only when the click hit a block or the air respectively. Each variant has a matching builder callback (`onRightClick`, `onShiftRightClickBlock`, and so on) that runs alongside the YAML list.

The `shift-overrides-generic` flag (default `true`) controls the shift-vs-base priority: when true, a declared shift variant runs INSTEAD of the plain positional one on a shift click; when false, BOTH run, the shift one first and then the base one, lists and callbacks in that order. The same rule applies to the shift positional variants over the plain positional ones.

```java
ItemDef.builder()
        .item(SnItem.builder(Material.STICK).name("&aTeleporter"))
        .rightClickActions(List.of("[player] spawn", "[sound] ENDERMAN_TELEPORT"))
        .onShiftRightClick((player, stack) -> openWarpMenu(player))
        .interactRequirements(List.of("%player_level% >= 5"))
        .denyActions(List.of("[message] &cReach level 5 first."))
        .build();
```

## Anti-extraction: locked and no-drop

`locked()` pins an item to its slot: none of the seven extraction vectors (click, drag, manual equip, hand swap, drop, death drops, hopper/inventory move) can pull it out. `noDrop()` is a hard alias of `droppable: false` that blocks Q-drop and drag-out attempts specifically.

The guarantee goes further than cancelling events. When a locked item displaces a real item (for example when applied into an equipment slot), the displaced item is backed up write-through and restored on quit and on shutdown. The backup is default-on and survives a crash without an `onDisable` call. Mobs that try to pick up a registered item have the pickup cancelled.

`obtainVia(ObtainMode.COMMAND_ONLY)` restricts circulation to command/API only; crafting, mob pickup and other acquisition paths are cancelled by the enforcement layer. The default `ObtainMode.UNRESTRICTED` allows every path.

```java
ItemDef.builder()
        .item(SnItem.builder(Material.NETHERITE_HELMET).name("&5Crown of Kings"))
        .locked().noManualEquip()
        .obtainVia(ObtainMode.COMMAND_ONLY)
        .keepOnDeath()
        .build();
```

{% hint style="info" %}
Locked-mode flags are stamped as PDC keys on the stack (`snlib_locked`, `snlib_no_drop`, `snlib_no_manual_equip`, `snlib_keep_on_death`, `snlib_obtain_via`), so the shared listeners recognize the stack no matter where it ends up.
{% endhint %}

## Custom durability

Separate from vanilla durability, an item can carry a custom durability counter - useful for items that normally have none (sticks, emeralds) or to override the vanilla value.

```java
ItemDef.builder()
        .item(SnItem.builder(Material.STICK).name("&6Fragile Wand"))
        .customDurability(50, 1,
                "&7Durability: &f%durability%/%max_durability%",
                List.of("[sound] ENTITY_ITEM_BREAK", "[message] &cYour wand shattered!"))
        .rightClickActions(List.of("[particle] FLAME 20"))
        .build();
```

- `max` (0 disables the system), `damage-per-use` (floored at 1), a `lore-format` rendering `%durability%` / `%max_durability%`, and `break-actions` that run when it reaches 0.
- Items come out of `give`/`create` seeded at full durability with the lore line rendered.
- Break-actions and deny-actions run with the REAL ClickType and surface (block/air) of the interaction, so click and surface guards inside those lists evaluate.

To spend durability programmatically:

```java
int remaining = sn.items().damage(player, stack, 5);
```

`damage(player, stack, amount)` subtracts durability (floored at 0), updates the tag, re-renders the lore, and - when this call is the one that breaks the stack - runs the break-actions and removes the stack from the player's inventory by identity. The two-arg `damage(stack, amount)` skips the break flow (no player to run it for). It returns the remaining durability, or `-1` when the stack was not created by this context or has no custom durability.

## Held effects, recipes and more

- **Held effects per slot.** `held-effects` applies potion effects continuously while the item is held or worn, keyed by `mainhand`, `offhand`, and `armor` (each a list of `"EFFECT amplifier"` lines, where amplifier is level minus one).
- **Recipes.** An item can register a `SHAPED`, `SHAPELESS`, `FURNACE`, `SMOKING`, `BLASTING`, `CAMPFIRE` or `STONECUTTING` recipe. In Java use the `ItemDef.Recipe` factories:

  ```java
  ItemDef.Recipe.shaped(
          List.of("DDD", "DSD", "DDD"),
          Map.of('D', "DIAMOND", 'S', "STICK"));
  ItemDef.Recipe.cooking("BLASTING", "COBBLESTONE", 0.5, 200);
  ItemDef.Recipe.stonecutting("STONE");
  ```

  Recipes are registered under `snlib_recipe_<id>` and are looked up before registering, so re-registration never throws.
- **Keep-on-death.** `keepOnDeath()` keeps the item through death and returns it on respawn.
- **Per-item cooldown.** `cooldownTicks(n)` sets a cooldown between interactions (0 disables it).
- **Equipment slot restriction.** `equipmentSlot("HEAD")` (MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET) restricts where the item can be equipped.
- **Behavior toggles.** `droppable`, `moveable`, `placeable`, `tradeable`, `despawnable` (all default true) gate the corresponding vectors and are enforced by the property listener; `pickup-actions` / `drop-actions` fire on those events.

## YAML vs programmatic

Both paths produce the same immutable `ItemDef`. In YAML, every field above is a key under an item id in `items.yml`:

```yaml
soulblade:
  material: DIAMOND_SWORD
  display-name: "[rgb]&lSoulblade"
  glow: true
  enchantments: [sharpness, 5]
  flags:
    - HIDE_ALL
  locked: true
  keep-on-death: true
  right-click-actions:
    - "[particle] SOUL 30"
    - "[message] &dThe blade hums."
  interact-requirements:
    - "%player_level% >= 10"
  deny-actions:
    - "[message] &cThe blade rejects you."
```

```java
sn.items().register("soulblade", sn.yml().managed("items.yml"));
sn.items().give(player, "soulblade", 1);
```

A Java callback per interact variant is only available through the builder, so when an item needs custom logic beyond the action grammar, define appearance/behavior in YAML and attach behavior in code, or build the whole definition programmatically.

The header of `ItemDef.java` carries a field-by-field checklist against the golden spec with the exact parse point for each field. When in doubt about a field's name or default, `docs/item-example.yml` is the source of truth.

## Full field reference example

Every field an item can carry, in one realistic definition. Nothing here is required - an item with just `material` is valid - but this is what the item YAML looks like when every section is actually used:

```yaml
items:
  legendary-blade:
    # --- Appearance ---
    display-name: "[rgb]&lLegendary Blade"
    material: DIAMOND_SWORD
    custom-model-data: 1001
    item-model: "nexo:legendary_blade"   # optional, 1.21.2+: base ItemModel key; works alongside custom-model-data
    amount: 1
    glow: true
    lore:
      - "&7A blade forged in starlight."
      - ""
      - "[small]&7Right-click to unleash its power"
    enchantments: [sharpness, 5, unbreaking, 3]
    flags:
      - HIDE_ENCHANTS
      - HIDE_ATTRIBUTES
      - HIDE_UNBREAKABLE
    color: "255, 85, 85"          # or hex: "#FF5555" - leather armor / potions only
    trim-pattern: SILVER          # armor only
    trim-material: DIAMOND        # armor only
    potion-effects: [SPEED, 1, 200]
    attributes:
      - "GENERIC_ATTACK_DAMAGE ADD_NUMBER 4 MAINHAND"
    damage: 0                     # vanilla durability already spent, independent of custom-durability

    # --- Properties ---
    unbreakable: true
    max-stack-size: 1
    droppable: true
    moveable: true
    placeable: false
    tradeable: true
    despawnable: true
    keep-on-death: true
    cooldown: 20                  # ticks between interactions, 0 = no cooldown

    # --- Locked mode and obtain control ---
    locked: true
    no-drop: false                # locked already blocks extraction; no-drop is the narrower droppable:false alias
    no-manual-equip: false
    obtain-via: COMMAND_ONLY      # "" = unrestricted (default)

    # --- Custom durability (separate from vanilla) ---
    custom-durability:
      max: 100
      damage-per-use: 1
      lore-format: "&7Durability: &f%durability%/%max_durability%"
      break-actions:
        - "[sound] ENTITY_ITEM_BREAK"
        - "[message] &cYour blade has shattered!"

    # --- Interact actions: all 12 variants ---
    right-click-actions:
      - "[player] spawn"
      - "[sound] ENTITY_EXPERIENCE_ORB_PICKUP"
      - "[particle] FLAME 50 0.5 0.5 0.5 0.1"
      - "[potion] SPEED 200 1"
      - "[remove-item] 1"
    left-click-actions:
      - "[message] &7You swing the blade."
    shift-right-click-actions:
      - "[message] &6You channel its power!"
    shift-left-click-actions: []
    right-click-block-actions:
      - "[message] &7You struck a block."
    right-click-air-actions:
      - "[message] &7You swung at the air."
    left-click-block-actions: []
    left-click-air-actions: []
    shift-right-click-block-actions: []
    shift-right-click-air-actions: []
    shift-left-click-block-actions: []
    shift-left-click-air-actions: []
    shift-overrides-generic: true

    # --- Interact requirements ---
    interact-requirements:
      - "%player_level% >= 10"
    deny-actions:
      - "[message] &cYou need to be level 10 to wield this."
      - "[sound] ENTITY_VILLAGER_NO"

    # --- Pickup / drop ---
    pickup-actions:
      - "[message] &aYou picked up the Legendary Blade!"
    drop-actions:
      - "[message] &cYou dropped the Legendary Blade."

    # --- Held effects, keyed by slot ---
    held-effects:
      mainhand:
        - "STRENGTH 0"
      offhand: []
      armor: []

    # --- Equipment slot restriction ---
    equipment-slot: ""            # MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET, or "" for any

    # --- Crafting recipe ---
    recipe:
      type: SHAPED                 # SHAPED, SHAPELESS, FURNACE, SMOKING, BLASTING, CAMPFIRE, STONECUTTING
      shape:
        - "DDD"
        - "DSD"
        - "DDD"
      ingredients:
        D: DIAMOND
        S: STICK
```

{% hint style="info" %}
A `PLAYER_HEAD` item adds `skull-owner` (a player name, UUID, or `%placeholder%` resolved per viewer). See the [full player-head example](menus.md#full-field-reference-example) in the menus page for that field in context.
{% endhint %}

## Redeemable items (1.12.0)

A registered item can be marked redeemable: any right-click holding it (air or block,
sneaking or not, from either hand) cancels the interaction, consumes items per the spec
and invokes your handler. SnLib owns the whole dispatch, so a placeable material (a
player head gem) is never placed instead of redeemed and clicks on air work exactly like
clicks on blocks - never hand-roll a `PlayerInteractEvent` listener for this.

```java
sn.items().redeemable("gem",
        RedeemSpec.allMatching(2304)                    // or single() / handStack() / allMatching()
                .blockedOn(Set.of(Material.CHEST)),     // let the chest open instead
        (player, amount, consumed) -> gems.add(player.getUniqueId(), amount));
```

- `RedeemSpec.single()` consumes one unit from the used hand; `handStack()` the whole
  held stack; `allMatching()` every matching stack of the inventory and cursor, with an
  optional unit cap (`allMatching(limit)`).
- The handler receives the consumed total plus an immutable snapshot of the consumed
  stacks, so per-stack data (a PDC value tag on a currency note) can be aggregated.
- A use denied by another plugin is respected, and a click on a `blockedOn` material
  steps aside so the block interaction wins. The item's cooldown and
  `interact-requirements` still gate the flow; a redemption replaces the interact
  variants and durability of that click.
- The registration is programmatic and survives item reloads; re-register (or
  `removeRedeemable(id)`) from your reload callback when the mode is config-driven.

## Multi-line lore placeholders (1.12.0)

A lore line containing `\n` splits into one lore line per segment (`SnItem.lore`), so a
LIST value can flow through a single placeholder in a menu template or item: bind
`Ph.of("body", String.join("\n", lines))` against a one-line `{body}` template line and
every entry becomes its own lore line. Each segment carries its own colour codes; a
trailing newline adds no empty line.

## Related pages

- [Menus](menus.md) - GUI icons and the shared action/requirement grammar used in `*-click-actions`.
- [Text rendering](text.md) - item names and lore flow through the text pipeline (`[small]`, `[rgb]`, `[center]`, legacy and MiniMessage).
- [Configuration](yml.md) - `items.yml` rides the same managed-config merge system.
- Back to the [developer guide](../README.md) or the [quickstart](../quickstart.md).
