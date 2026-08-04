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

The plugin ships its default menus inside its jar and copies them into this folder for you the first time it runs, so the files are there to edit out of the box. They are [managed just like config files](configuration-files.md): when a plugin update adds a new field to a menu, the new key is merged into your file automatically and your edits are preserved, all under the same `update-configs` master switch that controls `config.yml`.

{% hint style="info" %}
If a plugin declares that it has menus but the `guis/` folder is empty and nothing loads, it logs a warning pointing at the folder. Normally you never see this, because the plugin's own menus are seeded automatically; it only shows up if the files were deleted or the plugin shipped without any bundled menu.
{% endhint %}

Being managed also means that deleting a button normally brings it back on the next restart. When a plugin intends for you to add and remove buttons freely, its `items:` block carries a `# sn:extensible` comment line - inside a marked block your deletions are permanent. The same marker appears in item files, usually as `# sn:extensible-root` in the header, when every entry in the file is yours to manage. If a menu you want to trim did not ship with the marker, you can add the line yourself and the plugin will honor it. See [Sections that are yours](configuration-files.md) for the full rules.

{% hint style="info" %}
To simply **hide** a button, you do not need the marker at all: remove its letter from `layout` and the button stops being drawn, wherever its definition still sits in `items:`. This is the intended way to remove a button and it is completely silent - no console warnings. Deleting the entry (and marking the block so it stays deleted) is only worth it when you want the file itself to be shorter.
{% endhint %}

Each file describes one menu top to bottom. What you can customize includes:

### The menu itself

- `title` - the text shown at the top of the inventory. Supports colors and formatting - see [Text Formatting and Colors](text-formatting.md).
- `rows` - how many rows the chest menu has (1 to 6), or an `inventory-type` for other container shapes.
- `layout` - an ASCII map of the menu, where each character represents a slot, letting you place items by drawing the menu instead of counting slot numbers.
- `open-sound` and `close-sound` - sounds played when the menu opens and closes.
- `open` actions and `close-actions` - things that happen when the menu opens and when it closes.
- `pagination` - turn on real per-player paging so one menu can show many pages of content, each player on their own page.
- `regions` - named groups of cells the plugin fills at runtime, one entry per cell (a permission matrix, a role selector, a list). Each region points at a letter of your `layout`, so moving, resizing or splitting it is just an edit to the drawing: `toggles: t` means "every `t` cell, left to right and top to bottom". Fewer cells simply shows fewer entries and more cells leaves the spare ones to whatever item you declared there - neither is an error. To reorder the cells by hand, replace the letter with a slot list (`toggles: {slots: ["19-25", "28-30"]}`); reordering changes only the picture, since every entry carries its own identity and can never end up doing something else. To turn a region off, take its letter out of the `layout` or leave its value blank (`toggles: ""`) - either way it goes quiet with no warning. But do NOT delete the `regions` lines themselves: they come back on the next restart and the plugin logs a warning until they do.

### Each item in the menu

- `display-name` - the item's shown name, with full color and formatting support (see [Text Formatting and Colors](text-formatting.md)).
- `material` - what the item is (including player heads).
- `lore` - the description lines under the name.
- `enchantments` and `glow` - real enchantments, or just the enchanted shimmer without any effect.
- `amount`, `custom-model-data`, `item-model` (1.21.2+ resource-pack model key like `nexo:2d_player_head`), `flags`, `color`, potion effects, and armor trim - the rest of the item's appearance.
- `slots` or `key` - where the item goes, either by slot number or by the letter you used in the `layout`. Removing the letter from the `layout` is how you hide a button you do not want. An item declared on a region's letter shows in whatever cells that region does not fill.

### What happens when an item is clicked

This is where menus become interactive. For each item you can define, per type of click (left, right, shift-left, shift-right, middle):

- `*-click-actions` - what happens on that click (send a message, run a command, play a sound, close the menu, open another one, and much more).
- `*-click-requirements` - conditions that must be true for the click to be allowed.
- `*-click-deny-actions` - what happens instead when a requirement is not met.

You can also set `view-requirements` to control whether an item is shown to a player at all. Hiding is total: an item a player cannot see cannot be clicked by them either, so the empty slot fires nothing - you never need to copy the same condition into `click-requirements`. Navigation items (next page, previous page) and their disabled states are configured here too when the menu uses pagination.

Several items may even share the same slot (or the same layout `key:`) with opposite view requirements: the first item in the file whose requirements pass is the one drawn and clicked, so one cell can show a different button per player state - for example an "info" button for members and a "create" button for everyone else. If every candidate is hidden, the cell stays empty. Order matters: put the preferred item first.

### Cells the plugin fills with a real item

Some cells show an actual item rather than one described in the file: a crate's reward, the contents of a kit, a shop's stock. There the plugin hands the real item over - with its own name, enchantments and texture - and the entry in the file only decides two things about how it looks:

- `display-name` - fill it in to rename the item, or leave it out (or blank) to show the item's real name.
- `lore` - whatever you write here is added UNDER the item's own description, so you can append a price or a chance line without erasing what the item already says.

`material`, `amount`, `glow` and the other appearance fields do nothing for those cells - the item already carries them. Everything about behavior still works normally: click actions, requirements, deny actions and view requirements are all yours to configure. You can tell which entries these are by their shape: they are the ones whose plugin documentation says the contents come from the game, and they usually ship with no `material` set.

The exact syntax for the action and requirement lists is a small mini-language shared across every Sn plugin. It is documented on its own page: see [Actions and Requirements](actions-and-requirements.md).

{% hint style="info" %}
SnLib ships a fully annotated example menu (`docs/menu-example.yml` in the SnLib repository) that shows every supported field in place. It is the best reference when you want to see exactly how a field is written.
{% endhint %}

## Items (the items YAML)

Some Sn plugins define custom physical items - special tools, kit items, currency items - through a YAML file as well. The item engine reads these directly, so you can reshape an item entirely in YAML. What you can customize includes:

### Appearance

The same appearance fields as menu items: `display-name`, `material`, `lore`, `enchantments`, `glow`, `custom-model-data`, `item-model` (1.21.2+), `flags`, `color`, armor trim, potion effects, player-head owner, and attribute modifiers.

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
