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

- **`skullOwner(String)`** - a player head by UUID or by cached name, never a blocking lookup. A UUID resolves via the non-blocking `getOfflinePlayer(UUID)`; a name resolves only through the profile cache. An uncached name leaves the default head with one warning. Requires `PLAYER_HEAD`.
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

## Related pages

- [Menus](menus.md) - GUI icons and the shared action/requirement grammar used in `*-click-actions`.
- [Text rendering](text.md) - item names and lore flow through the text pipeline (`[small]`, `[rgb]`, `[center]`, legacy and MiniMessage).
- [Configuration](yml.md) - `items.yml` rides the same managed-config merge system.
- Back to the [developer guide](../README.md) or the [quickstart](../quickstart.md).
