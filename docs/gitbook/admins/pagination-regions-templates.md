# Pagination, Regions and Templates

This page covers the menu features that show data the plugin computes at runtime. One principle drives all of them: the plugin supplies the data, your file supplies the placement. Pagination pages a long list per viewer. A region places a fixed set of entries. Templates define how each entry looks and what it does when clicked.

Everything here builds on the layout and menu-level keys from [Menus](menus.md), and on the item anatomy from [Menu Items and Clicks](menu-items-and-clicks.md).

## Pagination

Pagination is opt-in per menu and defaults to off:

```yaml
pagination: true
```

With pagination on, every viewer gets their own page state. Ten players can browse the same menu on ten different pages at the same time.

Five action lines control paging. You use them inside `click-actions`, exactly like any other action:

| Action line | What it does |
| --- | --- |
| `[previous-page]` | Goes back one page. Does nothing on page 1. |
| `[next-page]` | Advances one page. Stops on the last known page. |
| `[set-page] <n>` | Jumps to page n, clamped to the known total. |
| `[refresh-page]` | Re-renders the current page. |
| `[refresh-menu]` | Re-renders the whole menu, title included. |

With `pagination: false` all five lines are silent no-ops, and that includes `[refresh-menu]`. Nothing errors: the click simply does nothing. The full action grammar lives in [Actions and Requirements](actions-and-requirements.md).

> **`{page}` and `{total}` are not built-in placeholders.** SnLib defines no page counter token for menus. They render as literal text unless the plugin passes them as its own placeholders.

Page changes never count as closing the menu. The close sound and `close-actions` stay silent between pages; see [Menus](menus.md).

### The paged area: paged-key

`paged-key` marks which layout cells hold the paged entries. It is a menu-level key naming exactly one character of the layout:

```yaml
layout:
  - "fffffffff"
  - "fdddddddf"
  - "fdddddddf"
  - "fffffffff"
paged-key: d
```

The cells of that letter become the paged area, fourteen slots in this example. The plugin fills them by template id, so the letter also decides the page size. Move or resize the `d` block and the page size follows, with no plugin update.

A `paged-key` that is not one character, is absent from the layout, or is declared without a `layout:` warns and is ignored. Declaring it while `pagination` is false also warns, but the value is kept for when you enable pagination.

On the last page, leftover paged cells simply render empty.

### Navigation items

Navigation items are regular items whose `click-actions` use the page actions. SnLib detects them by those actions: any list containing `[previous-page]` or `[next-page]` marks the item as navigation.

Each one supports an optional `nav-disabled` section. It renders in the same slots, instead of the item, when there is no page to go to. Previous is disabled on the first page; next is disabled on the last known page.

```yaml
next-page:
  display-name: "&eNext page"
  material: ARROW
  slots: [53]
  click-actions:
    - "[next-page]"
    - "[sound] UI_BUTTON_CLICK"
  nav-disabled: # shown on the last page instead of this item
    display-name: "&7No next page"
    material: GRAY_STAINED_GLASS_PANE
    lore:
      - "&8You are on the last page"
```

`nav-disabled` accepts the same appearance fields as a regular item: display-name, material, lore, glow, flags and the rest. It accepts no `slots:`, no `key:` and no actions.

> **`nav-disabled` is appearance only.** A disabled arrow renders the override and fires nothing at all. It never changes what the enabled item does.

One case keeps the next arrow permanently enabled: the menu has no live paged data and the plugin never declares a total. The total pages is then unknown, so there is no "last page" to detect. This is plugin behavior, not a file mistake.

## Regions (1.20.0)

A region is a named group of cells that the plugin fills with one entry per cell. The plugin picks each entry's template and placeholders; your file picks how many cells, where, and in what order. Use it for a matrix, a selector or any fixed list that does not page. A region needs no `pagination: true`: it is the non-paged sibling of `paged-key`.

The short form names one layout character:

```yaml
layout:
  - "fffffffff"
  - "f ttttt f"
  - "fffffffff"
regions:
  toggles: t
```

The long form takes `slots:` or `key:`, exactly like an item:

```yaml
regions:
  toggles:
    slots: ["19-25", "28-30", "32-34"]
```

Entry i renders into cell i of the region. With `key:` the cells run in ascending row-major order, left to right and top to bottom. With `slots:` the cells keep the order you wrote, so `slots:` is how you reorder a region by hand. Reordering changes the picture only. Every entry carries its own id in its placeholders, so a moved switch can never toggle something else.

> **Region sizing is yours, and it never warns.** Fewer cells than entries shows the first ones and drops the tail. More cells than entries leaves the spares to the items declared on them. Both are configuration, not mistakes.

Ownership is per cell, not per region. A cell with no entry falls through to the item declared underneath. So does a cell whose filler picks no template, and one hidden by its view requirements. That holds on the screen and on the click alike. Declare a filler item on the region letter and it fills every gap.

### Turning a region off

Two ways, both silent and supported:

- Take the region's letter out of `layout:`.
- Blank its value: `toggles: ""`, or an empty `slots:` list.

{% hint style="warning" %}
Never delete the `regions:` declaration itself. The managed merge re-adds it on the next restart, and the plugin warns that the region is missing. For the same reason, never mark `regions:` with the extensible marker; see [Configuration Files](configuration-files.md).
{% endhint %}

### Malformed region declarations

Every malformed declaration warns and leaves the region declared with no cells. Declared-but-empty is what separates "you turned it off" from "the plugin named a region that does not exist".

| You wrote | What happens |
| --- | --- |
| Both `slots:` and `key:` | Warns; `slots:` wins and `key:` is ignored. |
| A section with other keys, but neither `slots:` nor `key:` | Warns; the region has no cells. |
| A `key:` longer than one character | Warns; the region has no cells. |
| A `key:` in a menu with no `layout:` | Warns; the region has no cells. |
| Cells covering the `paged-key` | Warns once, naming the first shared slot. A live paged bind wins there. |
| Cells covering an earlier region | Warns once; the later region wins. |
| A letter the layout simply lacks | Silent. That is the removal path, not a mistake. |

### Letters and regions are two different tools

> **Distinct letters name distinct elements, bound by name through `key:`. One repeated letter is an ordered region, bound by index.** Never write an ordered sequence as distinct letters: "123" cannot be indexed and is just three unrelated cells.

## Templates

Templates live under `templates:` and are identical to items, with `slots:`/`key:` optional instead of required. They support every appearance key, listed in [Item Appearance Reference](item-appearance.md). They support the same clicks, requirements and per-click matrix as items, covered in [Menu Items and Clicks](menu-items-and-clicks.md). The plugin supplies the runtime data, like `%warp_name%`, at bind time. You customize appearance and actions freely.

Placement works three ways:

| Placement | The template declares | Who moves the element |
| --- | --- | --- |
| Config-driven | `slots:` or `key:`, resolved against the layout like an item | You: edit the layout or the slots, no plugin update needed. |
| Region-driven | Nothing; the cell comes from a `regions:` entry | You: edit `regions:` or the layout. |
| Plugin-driven | Nothing; the plugin picks the slot in code | The plugin only. A slot decided in Java is a slot you cannot move. |

A template can also declare `input: true` (1.28.0), exactly like an item: the cell the plugin binds it into then RECEIVES an item instead of only firing click actions. The rules, and the `player-inventory: open` it needs, live in [Menu Items and Clicks](menu-items-and-clicks.md#cells-that-receive-an-item-1280).

Region-driven placement is the one for state variants of the same button, such as an on and an off look. Several templates then share one region, so none of them owns the placement. Keep those templates keyless: a region-painted cell ignores the template's own `slots:`/`key:` anyway.

When several sources target the same cell, one order decides:

> **Precedence over a shared cell, resolved per cell: manual bind, then paged bind, then region, then the declared item.** A paged slot the current page leaves empty stays empty. A region cell with no entry falls through to the declared item.

### Plugin-supplied contents (1.21.0)

A plugin can hand a ready-made stack to any cell: a crate reward, kit contents, shop stock. Those stacks carry enchantments, textures and names no yml definition can re-express. When that happens, the stack decides the appearance and your file still decides the behavior. On such a template, only these keys keep any effect:

| Template key | Effect when the plugin supplies a stack |
| --- | --- |
| `display-name` | Non-empty replaces the stack's own name. Absent or `""` keeps the real name. |
| `lore` | Your lines are appended after the lore the stack already has. Absent appends nothing. |
| `view-requirements`, `click-requirements`, `deny-actions`, `click-actions`, the per-click matrix | Unchanged: behavior stays yours. |
| `material`, `amount`, `glow`, `enchantments`, `custom-model-data`, every other appearance key | Ignored: the stack already carries them. |

> **`lore: []` and a list holding `""` are not the same.** An empty list declares nothing, and the stack keeps its own lore untouched. A list with one empty string declares one blank line, appended as a spacer.

Nothing changes for a bind without a stack: the template renders the cell as it always has.

{% hint style="info" %}
A template's own `update-interval:` is parsed but never scheduled. Only the menu-level `update-interval` refreshes template-painted cells.
{% endhint %}

## Full example

One menu combining all three features: a paged warp list, a region of category toggles, and templates for both. The `%warp_name%` and `%category%` tokens are locals the plugin supplies at bind time.

```yaml
# guis/warps.yml
title: "&8Warps"
pagination: true
open-sound: "BLOCK_CHEST_OPEN"

# f = frame, d = paged warp entries, s = category region, p/n = navigation
layout:
  - "fffffffff"
  - "fdddddddf"
  - "fdddddddf"
  - "f sssss f"
  - "fp     nf"

# The 14 'd' cells hold the current page: 14 warps per page.
paged-key: d

# The 5 's' cells hold one category toggle each, in row-major order.
regions:
  categories: s

items:
  filler:
    display-name: " "
    material: GRAY_STAINED_GLASS_PANE
    key: f

  previous-page:
    display-name: "&ePrevious page"
    material: ARROW
    key: p
    click-actions:
      - "[previous-page]"
      - "[sound] UI_BUTTON_CLICK"
    nav-disabled: # shown on the first page
      display-name: "&7No previous page"
      material: GRAY_STAINED_GLASS_PANE

  next-page:
    display-name: "&eNext page"
    material: ARROW
    key: n
    click-actions:
      - "[next-page]"
      - "[sound] UI_BUTTON_CLICK"
    nav-disabled: # shown on the last page
      display-name: "&7No next page"
      material: GRAY_STAINED_GLASS_PANE

templates:
  # Painted into the paged 'd' cells; one warp per cell.
  warp-entry:
    display-name: "&b%warp_name%"
    material: ENDER_PEARL
    lore:
      - "&7Click to teleport"
    click-actions:
      - "[player] warp %warp_name%"
      - "[close]"

  # State variants served by the 'categories' region: keyless on purpose.
  # The plugin picks category-on or category-off per cell; the region
  # decides where each one renders.
  category-on:
    display-name: "&a%category%"
    material: LIME_DYE
    lore:
      - "&7Click to hide this category"
    click-actions:
      - "[player] warps toggle %category%"

  category-off:
    display-name: "&7%category%"
    material: GRAY_DYE
    lore:
      - "&7Click to show this category"
    click-actions:
      - "[player] warps toggle %category%"
```

Everything below retunes without touching the plugin:

- Warps per page: resize the `d` block.
- Category count and order: edit the `s` cells, or switch `categories:` to a `slots:` list.
- Every look, every click line, and the disabled arrow art.

## Related pages

- [Menus](menus.md): the layout, menu-level keys and close behavior this page builds on.
- [Menu Items and Clicks](menu-items-and-clicks.md): item anatomy, view requirements, shared slots and the per-click matrix.
- [Item Appearance Reference](item-appearance.md): every appearance key items and templates accept.
- [Actions and Requirements](actions-and-requirements.md): the full grammar of the action lines used here.
- [Troubleshooting](troubleshooting.md): what to check when a page action or a bind stays silent.
