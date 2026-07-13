# GUIs and Items in YAML

Many Sn plugins let you fully customize their menus and their physical items through YAML alone, with no code changes at all. This works because SnLib provides a shared menu engine and a shared item engine that read these fields directly. The plugin author does not have to write code for each field you configure - if the field is part of the spec, it already works. And because the same engine is behind every Sn plugin, the YAML structure looks and behaves the same everywhere.

{% hint style="info" %}
Not every plugin exposes every field, and a plugin can add its own fields on top. But wherever a field listed here appears in an Sn plugin, it behaves the way described here.
{% endhint %}

## Menus (the `guis/` folder)

Menus live in a `guis/` folder inside the plugin's data directory, one YAML file per menu:

```
plugins/SomePlugin/guis/shop.yml
plugins/SomePlugin/guis/settings.yml
```

Each file describes one menu top to bottom. What you can customize includes:

### The menu itself

- `title` - the text shown at the top of the inventory. Supports colors and formatting - see [Text Formatting and Colors](text-formatting.md).
- `rows` - how many rows the chest menu has (1 to 6), or an `inventory-type` for other container shapes.
- `layout` - an ASCII map of the menu, where each character represents a slot, letting you place items by drawing the menu instead of counting slot numbers.
- `open-sound` and `close-sound` - sounds played when the menu opens and closes.
- `open` actions and `close-actions` - things that happen when the menu opens and when it closes.
- `pagination` - turn on real per-player paging so one menu can show many pages of content, each player on their own page.

### Each item in the menu

- `display-name` - the item's shown name, with full color and formatting support (see [Text Formatting and Colors](text-formatting.md)).
- `material` - what the item is (including player heads).
- `lore` - the description lines under the name.
- `enchantments` and `glow` - real enchantments, or just the enchanted shimmer without any effect.
- `amount`, `custom-model-data`, `flags`, `color`, potion effects, and armor trim - the rest of the item's appearance.
- `slots` or `key` - where the item goes, either by slot number or by the letter you used in the `layout`.

### What happens when an item is clicked

This is where menus become interactive. For each item you can define, per type of click (left, right, shift-left, shift-right, middle):

- `*-click-actions` - what happens on that click (send a message, run a command, play a sound, close the menu, open another one, and much more).
- `*-click-requirements` - conditions that must be true for the click to be allowed.
- `*-click-deny-actions` - what happens instead when a requirement is not met.

You can also set `view-requirements` to control whether an item is shown to a player at all. Navigation items (next page, previous page) and their disabled states are configured here too when the menu uses pagination.

The exact syntax for the action and requirement lists is a small mini-language shared across every Sn plugin. It is documented on its own page: see [Actions and Requirements](actions-and-requirements.md).

{% hint style="info" %}
SnLib ships a fully annotated example menu (`docs/menu-example.yml` in the SnLib repository) that shows every supported field in place. It is the best reference when you want to see exactly how a field is written.
{% endhint %}

## Items (the items YAML)

Some Sn plugins define custom physical items - special tools, kit items, currency items - through a YAML file as well. The item engine reads these directly, so you can reshape an item entirely in YAML. What you can customize includes:

### Appearance

The same appearance fields as menu items: `display-name`, `material`, `lore`, `enchantments`, `glow`, `custom-model-data`, `flags`, `color`, armor trim, potion effects, player-head owner, and attribute modifiers.

### Behavior

- `custom-durability` - give an item its own durability that is separate from vanilla: a maximum, how much each use costs, what happens when it breaks, and how the remaining durability is shown in the lore.
- Obtain rules - control how the item can be gotten (for example, command-only) so it cannot be crafted or picked up in ways you did not intend.
- `recipe` - define how the item is crafted (shaped, shapeless, cooking, stonecutting, and more).
- `held-effects` - potion effects applied while the item is held in the main hand, off hand, or worn as armor.
- `cooldown` - a per-item cooldown between uses.
- `keep-on-death` - keep the item through death instead of dropping it.
- `locked` / `no-drop` - protect the item from being dropped, moved, or extracted.
- Interaction actions - what happens when a player right-clicks, left-clicks, and so on, using the same action lists as menus.

{% hint style="info" %}
SnLib ships an annotated example item file (`docs/item-example.yml` in the SnLib repository) showing every supported field.
{% endhint %}

## One structure, every plugin

Because a single shared engine reads all of this, a menu file in one Sn plugin is written the same way as a menu file in another, and the same is true for item files. Time spent learning the structure once carries over to every Sn plugin you install. For the action and requirement mini-language used inside these files, continue to [Actions and Requirements](actions-and-requirements.md).
