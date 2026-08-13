# Menus

Every menu an Sn plugin opens is one YAML file in that plugin's `guis/` folder. The guiding principle: **whatever the file declares, you can change, and the change already works without plugin code**. This page covers the file as a whole: where it lives, its top-level keys, the layout drawing, sounds and close behavior. The buttons inside it have their own page: [Menu Items and Clicks](menu-items-and-clicks.md).

> **The file is the menu.** Title, size, drawing, sounds, buttons and clicks all live in one YAML file that you own.

## Where menu files live

One file per menu, inside the plugin's data folder:

```
plugins/SomePlugin/guis/shop.yml
plugins/SomePlugin/guis/confirm.yml
```

The menu id is the file name without `.yml`, so `shop.yml` is the menu `shop`. The plugin seeds its default menus from its jar on first run. After that the files are managed like any config file: updates merge new keys in, and your edits are kept. See [Configuration Files](configuration-files.md) for the merge rules. Your edits load on the next restart, or on a reload with [The /snlib Command](snlib-command.md).

{% hint style="info" %}
Retitle menus freely. SnLib identifies a menu by its inventory holder, never by its title text, so nothing breaks when you rewrite a title.
{% endhint %}

## A complete shop.yml

Start from a whole file, not from a field list. This menu draws a bordered shop, pages its stock, sells one fixed item and navigates with two arrows.

```yaml
# guis/shop.yml
title: "[rgb]&lShop"
open-sound: BLOCK_CHEST_OPEN
close-sound: "BLOCK_CHEST_CLOSE 1.0 0.8"   # optional volume and pitch
pagination: true      # the d cells below hold paged stock

# The drawing IS the placement: f border, d paged stock,
# b buy button, p and n navigation arrows.
layout:
  - "fffffffff"
  - "f ddddd f"
  - "f ddddd f"
  - "f   b   f"
  - "p fffff n"
paged-key: d          # paged cells; the plugin fills them through the stock template below

items:
  filler:
    display-name: " "
    material: GRAY_STAINED_GLASS_PANE
    key: f            # renders in every layout cell holding an f

  buy-diamond:
    display-name: "&bDiamond"
    material: DIAMOND
    key: b
    lore:
      - "&7Price: &a$100"
      - "&8Shift-right-click for details"
    click-requirements:
      - "%vault_eco_balance% >= 100"
    click-actions:
      - "[console] eco take %player_name% 100"
      - "[console] give %player_name% diamond 1"
      - "[message] &aPurchased!"
      - "[sound] ENTITY_EXPERIENCE_ORB_PICKUP"
    deny-actions:
      - "[message] &cYou cannot afford this."
      - "[sound] ENTITY_VILLAGER_NO"
    shift-right-click-actions:   # per-click matrix: this click only
      - "[message] &7A flawless gem, straight from the mines."

  previous-page:
    display-name: "&ePrevious Page"
    material: ARROW
    key: p
    click-actions:
      - "[previous-page]"
      - "[sound] UI_BUTTON_CLICK"
    nav-disabled:     # shown in the same cell on the first page
      display-name: "&7No previous page"
      material: GRAY_STAINED_GLASS_PANE

  next-page:
    display-name: "&eNext Page"
    material: ARROW
    key: n
    click-actions:
      - "[next-page]"
      - "[sound] UI_BUTTON_CLICK"
    nav-disabled:     # shown in the same cell on the last page
      display-name: "&7No next page"
      material: GRAY_STAINED_GLASS_PANE

templates:
  stock:              # the look of each paged stock entry; the plugin binds the data
    display-name: "&b{name}"
    lore:
      - "&7Price: &a${price}"

close-actions:
  - "[message] &7See you soon!"
```

Every string supports colors, gradients and placeholders: see [Text, Colors and Numbers](text-formatting.md). The action and requirement lines are a shared mini-language: see [Actions and Requirements](actions-and-requirements.md). The buttons themselves, from appearance to the per-click matrix, live in [Menu Items and Clicks](menu-items-and-clicks.md). Paging, regions and templates live in [Pagination, Regions and Templates](pagination-regions-templates.md).

## Every menu-level key

| Key | What it does |
| --- | --- |
| `title` | The text above the inventory, resolved per viewer, full formatting support. Default: `Menu`. |
| `rows` | Chest height, `1` to `6`. Default: `3`. A value outside the range WARNs and uses 3. |
| `layout` | The ASCII drawing of the grid. Covered below. |
| `inventory-type` | A non-chest shape: `HOPPER`, `DISPENSER`, `ANVIL`, `BARREL` and more. Default: `CHEST`. |
| `open-sound` | Played to the viewer when the menu opens. Sound format: [Shared Value Formats](value-formats.md). |
| `close-sound` | Played when the viewer closes the menu. Same format. |
| `close-actions` | Action lines run once per close. Covered below. |
| `update-interval` | Ticks between automatic re-renders. `0`, the default, means no timer. Covered below. |
| `pagination` | Opt-in per-viewer paging. Default: `false`. Owned by [Pagination, Regions and Templates](pagination-regions-templates.md). |
| `paged-key` | The one layout letter whose cells receive paged content. Same page. |
| `regions` | Named cell groups the plugin fills, one entry per cell. Same page. |
| `strict-clicks` | Opt-in filter that discards exotic clicks. Default: `false`. Owned by [Menu Items and Clicks](menu-items-and-clicks.md). |
| `player-inventory` | Whether the viewer may use their own inventory while the menu is open: `locked` or `open`. Default: `locked`. Covered below. |
| `items` | The buttons of the menu. Owned by [Menu Items and Clicks](menu-items-and-clicks.md). |
| `templates` | Button definitions the plugin fills with runtime data. Owned by [Pagination, Regions and Templates](pagination-regions-templates.md). |

An unknown `inventory-type` WARNs and falls back to a chest. In fact every malformed field WARNs and falls back: a menu file never fails to load. An empty or unreadable file WARNs and loads as a default menu without items.

## The layout grid

`layout:` is a list of 1 to 6 strings, up to 9 characters each. You draw the menu instead of counting slot numbers:

```yaml
layout:
  - "fffffffff"
  - "f ddddd f"
  - "f ddddd f"
  - "fffffffff"
```

| Rule | Behavior |
| --- | --- |
| Grid | Rows read top to bottom over the 9-column chest grid. The cell at row `i`, column `j` is slot `i*9+j`. |
| Spaces | A space is always an empty cell. |
| Size | With a layout present, `rows` derives from the number of drawn rows. |
| Contradiction | A `rows:` value that disagrees with the drawing WARNs, and the layout wins. |
| Overflow | Rows beyond 6, and characters beyond 9 in a row, are truncated with a WARN. |
| Letters | An item claims its cells by letter through its `key:` field. See [Menu Items and Clicks](menu-items-and-clicks.md). |

{% hint style="warning" %}
The layout assumes the 9-column chest grid. Combining `layout:` with a non-chest `inventory-type` WARNs, and out-of-range cells are not rendered.
{% endhint %}

One repeated letter can also name an ordered region the plugin fills entry by entry. That rule lives in [Pagination, Regions and Templates](pagination-regions-templates.md).

### Hiding a button

To hide a button, delete its letter from the layout. The item stays declared under `items:`, nothing renders, and nothing is logged. The silence is deliberate: hiding a button is configuration, not a mistake.

> Removing a letter from the layout is the supported way to remove a button. It is completely silent, and the definition stays in the file for the day you want it back.

Deleting the item's whole definition instead usually brings it back on the next restart. That changes once the block is marked as yours: see [Configuration Files](configuration-files.md).

## Sounds and close actions

`open-sound` plays to the viewer when the menu opens; `close-sound` when they close it. The value is a sound id with optional volume and pitch: see [Shared Value Formats](value-formats.md). An empty value plays nothing.

`close-actions:` uses the same grammar as click actions and runs once per close:

```yaml
close-actions:
  - "[message] &7See you soon!"
  - "[sound] UI_BUTTON_CLICK"
```

| Trigger | Runs? |
| --- | --- |
| The viewer presses ESC | Yes |
| A `[close]` action closes the menu | Yes |
| A page change or inventory recreation | No |
| The plugin closes the session itself (reload, disable, quit cleanup) | No |

Click guards inside these lines, like `[right-click]`, are skipped with a debug note: there is no click on a close.

> There are `close-actions`, but there are no `open-actions`. Nothing in a menu file runs actions on open; only `open-sound` greets the viewer.

## The viewer's own inventory (1.28.0)

While a menu is open, the bottom half of the screen is the viewer's own inventory. `player-inventory:` decides whether they may touch it:

```yaml
player-inventory: locked   # default
```

| Value | What the viewer can do with their own items |
| --- | --- |
| `locked` | Nothing. Every click and drag over their inventory is cancelled. This is the default and how every menu behaved before 1.28.0. |
| `open` | Everything they normally can: move stacks, split them with a drag, use number keys, drop with Q, swap with F. |

Whatever the value, clicks over the **menu's own cells** stay cancelled, and so does the double-click gather. A menu item can never end up in a player's inventory.

There is one change to how the viewer's inventory behaves under `open`: **shift-clicking one of their stacks does not move it**. A shift-click aims into the menu, and only the plugin can decide what the menu does with an item, so the stack is offered to the plugin instead. If the plugin has no use for it, nothing happens and the stack stays exactly where it was.

An unknown value WARNs and falls back to `locked`. Some menus need `open` to work at all - see the `input:` field in [Menu Items and Clicks](menu-items-and-clicks.md) - and those menus ship with it already set; changing it back to `locked` breaks them.

## Automatic refresh

`update-interval:` re-renders the whole menu every that many ticks: items, title and rows alike. 20 ticks are one second. `0`, the default, disables the timer. Each pass re-reads every string, so placeholder values like balances stay current without any plugin code. A single item can also refresh alone through its own `update-interval`: see [Menu Items and Clicks](menu-items-and-clicks.md).

## Related pages

- [Menu Items and Clicks](menu-items-and-clicks.md): every field of a button, placement, requirements and the per-click matrix.
- [Pagination, Regions and Templates](pagination-regions-templates.md): page state, ordered regions and the cells a plugin fills.
- [Actions and Requirements](actions-and-requirements.md): the mini-language inside every actions and requirements list.
- [Text, Colors and Numbers](text-formatting.md): the formatting pipeline behind titles, names and lore.
- [Configuration Files](configuration-files.md): the merge, backup and marker rules that also govern `guis/` files.
