# Menu Items and Clicks

Every button is one entry under `items:` in the menu file, and the file is the button: its position, its look and its click behavior all live in yml, not in the plugin. Each entry answers three questions: where the button sits, what it shows, and what each click does. This page covers placement, clicks, the per-click matrix, strict clicks, view requirements and shared slots.

```yaml
items:
  buy-diamond:                # any id, as long as it is unique
    material: DIAMOND         # appearance
    display-name: "&bDiamond"
    lore:
      - "&7Costs &e100 coins"
    slots: [22]               # placement
    click-requirements:       # behavior
      - "%vault_eco_balance% >= 100"
    click-actions:
      - "[console] shop buy %player_name% diamond 1"
      - "[sound] ENTITY_EXPERIENCE_ORB_PICKUP"
    deny-actions:
      - "[message] &cYou cannot afford this."
```

## Anatomy of an item

| Group | Keys | Documented in |
| --- | --- | --- |
| Placement | `slots:` or `key:` | This page |
| Appearance | `material`, `display-name`, `lore`, `glow`, `skull-owner` and the rest of the shared schema | [Item Appearance Reference](item-appearance.md) |
| Behaviour | `view-requirements`, `click-requirements`, `click-actions`, `deny-actions`, the per-click matrix, `update-interval` | This page |

{% hint style="info" %}
Appearance is re-read on every render, and placeholders resolve per viewer. Two players looking at the same menu can see different names, lore and heads. A per-item `update-interval:` (in ticks) re-renders just that item on a timer; 0 disables it.
{% endhint %}

## Placement

An item declares its cells with `slots:` or with `key:`. Slot numbers count from 0, left to right, top to bottom: slot = row * 9 + column. The layout grammar itself lives in [Menus](menus.md).

```yaml
items:
  filler:
    material: GRAY_STAINED_GLASS_PANE
    display-name: "&7 "
    key: f                    # every layout cell holding 'f'
  buy-button:
    material: EMERALD
    display-name: "&aBuy"
    slots: [10, 12, 14-16]    # single slots and ranges mix freely
```

`slots:` accepts single numbers, ranges, or a mix: `[0, 1, 2]`, `[0-2]`, `[0, 2, 4-6]`. `key:` is exactly one character of the menu `layout:`, and the item renders in every cell holding that character.

| You declare | Result |
| --- | --- |
| `slots:` only | The item renders in every listed slot. |
| `key:` only | The item renders in every layout cell holding that character. |
| Both | `slots:` wins with a WARN; `key:` is ignored. |
| Neither | The item is skipped with a WARN: items require a placement. |
| `key:` whose letter is not in the layout | The item is hidden, silently. This is the supported way to remove a button. |
| `key:` in a menu with no `layout:`, or longer than one character | WARN; the key is ignored. |

> When an item declares both `slots:` and `key:`, slots wins and the key is ignored, with a WARN. There is no merging of the two.

To hide a button, remove its letter from the layout and leave the item declared; nothing is logged. [Menus](menus.md) owns that workflow.

## Clicks and actions

Three generic lists drive a click. `click-requirements` gates the click, `click-actions` runs when it passes, `deny-actions` runs when it fails. Action lines and requirement grammar are documented in [Actions and Requirements](actions-and-requirements.md).

| List | When it runs |
| --- | --- |
| `click-actions` | The click passed `click-requirements` (or none are declared). |
| `deny-actions` | The click failed `click-requirements`. |

> Every click inside a menu is cancelled before anything runs. Viewers can never take a button out, drag across the grid, or double-click items into their cursor. Only your action lines decide what happens.

You have two ways to branch on the click type. Click guards like `[right-click]` filter a single line inside one list. The per-click matrix below swaps entire lists per click type. Use guards for one different line, the matrix for a fully different behavior.

### The per-click matrix

Besides the three generic lists, five click keys each accept three optional lists: 15 keys in total.

| Click | Actions | Requirements | Deny actions |
| --- | --- | --- | --- |
| Right | `right-click-actions` | `right-click-requirements` | `right-click-deny-actions` |
| Left | `left-click-actions` | `left-click-requirements` | `left-click-deny-actions` |
| Shift-right | `shift-right-click-actions` | `shift-right-click-requirements` | `shift-right-click-deny-actions` |
| Shift-left | `shift-left-click-actions` | `shift-left-click-requirements` | `shift-left-click-deny-actions` |
| Middle | `middle-click-actions` | `middle-click-requirements` | `middle-click-deny-actions` |

The side lists group related click types. The shift lists match only their exact click.

| List | Fires for |
| --- | --- |
| `shift-right-click-*` | SHIFT_RIGHT only |
| `shift-left-click-*` | SHIFT_LEFT only |
| `right-click-*` | RIGHT and SHIFT_RIGHT |
| `left-click-*` | LEFT, SHIFT_LEFT, DOUBLE_CLICK and CREATIVE |
| `middle-click-*` | MIDDLE only |

Each click resolves in three steps:

1. The exact shift list of the click, when declared.
2. The side list of the click, when declared.
3. The generic list.

> Resolution is specific over generic and field by field: actions, requirements and deny actions each fall back independently. A list counts as declared only when it is non-empty. An item may declare `right-click-actions` alone, and its requirement still resolves from the generic `click-requirements`.

A worked example. Left click buys one, shift-left buys sixteen, right click sells:

```yaml
items:
  buy-diamond:
    material: DIAMOND
    display-name: "&bDiamond"
    slots: [22]
    click-requirements:
      - "%vault_eco_balance% >= 100"
    click-actions:
      - "[console] shop buy %player_name% diamond 1"
    deny-actions:
      - "[message] &cYou cannot afford this."
    shift-left-click-actions:
      - "[console] shop buy %player_name% diamond 16"
    right-click-actions:
      - "[console] shop sell %player_name% diamond 1"
    right-click-requirements:
      - "%shop_diamonds_owned% > 0"
    right-click-deny-actions:
      - "[message] &cYou have no diamonds to sell."
```

How each click resolves:

| The viewer clicks | Actions | Requirement | Deny actions |
| --- | --- | --- | --- |
| LEFT | generic `click-actions` (no left list declared) | generic | generic |
| SHIFT_LEFT | `shift-left-click-actions` | generic (no shift-left requirement declared) | generic |
| RIGHT | `right-click-actions` | `right-click-requirements` | `right-click-deny-actions` |
| SHIFT_RIGHT | `right-click-actions` (the right side covers it) | `right-click-requirements` | `right-click-deny-actions` |

Every matrix key is optional, so existing menus keep working unchanged. Templates support the same 15 keys with the same resolution.

### strict-clicks

`strict-clicks:` is a menu-level key, declared next to `title:` and `rows:`. Off by default: any click type fires the resolved actions list, keyboard keys included.

```yaml
title: "&8Shop"
rows: 3
strict-clicks: true
```

With `strict-clicks: true`, the generic lists only answer the four basic mouse clicks. Everything else is discarded unless a specific actions list covers that exact type.

| Click type | With strict-clicks: true |
| --- | --- |
| LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT | Always allowed. |
| MIDDLE | Only with a non-empty `middle-click-actions`. |
| DOUBLE_CLICK, CREATIVE | Only with a non-empty `left-click-actions` (the left side groups them). |
| NUMBER_KEY, DROP, CONTROL_DROP, SWAP_OFFHAND, UNKNOWN | Always discarded: no specific list can cover them. |

> A discarded click runs nothing at all: no click actions and no deny actions. The discard happens before the requirement test, and the click is still cancelled.

A vanilla double click is two left clicks, so a declared `left-click-actions` deliberately enables DOUBLE_CLICK even in strict mode.

## View requirements

`view-requirements` decide whether the item exists for a viewer at all. They use the same expression grammar as `click-requirements` (see [Actions and Requirements](actions-and-requirements.md)).

```yaml
items:
  staff-button:
    material: COMMAND_BLOCK
    display-name: "&cStaff settings"
    slots: [8]
    view-requirements:
      - "%player_level% >= 10"
```

A failing view requirement does two things at once. The item is not rendered, and the same slot cannot fire it either. The check re-runs on the click itself, so a viewer whose state just changed cannot click a stale button.

> View requirements hide the item AND block the click. An item a viewer cannot see fires nothing at all, not even `deny-actions`. Never repeat the expression in `click-requirements`.

The two requirement lists answer different questions:

| List | A failure means |
| --- | --- |
| `view-requirements` | The button does not exist for this viewer. Nothing renders, nothing fires. |
| `click-requirements` | The button shows, but this click runs `deny-actions` instead of `click-actions`. |

{% hint style="warning" %}
A malformed requirement expression fails open: the item stays visible and clickable, with a WARN. See [Actions and Requirements](actions-and-requirements.md) for the fail-open and fail-closed rules.
{% endhint %}

### Shared slots (1.17.0)

Several items may declare the same slot, or the same layout `key:`. Candidates are tried in declaration order, and the first one whose view requirements pass for the viewer owns the cell. Clicks resolve the same winner, so each variant fires its own actions. When every candidate is hidden, the cell renders empty.

```yaml
items:
  clan-info:                  # declared first: preferred when both pass
    material: BEACON
    display-name: "&aYour clan"
    slots: [22]
    view-requirements:
      - "%snclans_has_clan% == true"
    click-actions:
      - "[player] clan info"
  clan-create:
    material: EMERALD
    display-name: "&eCreate a clan"
    slots: [22]
    view-requirements:
      - "%snclans_has_clan% == false"
    click-actions:
      - "[player] clan create"
```

Here a clan member sees the info button and a clanless player sees the create button. One cell, one file, two states.

> There is no `priority:` field. Candidates are tried in declaration order, so declare the preferred item first. The screen and the click always agree on the winner.

Binds, paged slots and regions can also paint a cell that items declare. That precedence (manual, then paged, then region, then declared item) belongs to [Pagination, Regions and Templates](pagination-regions-templates.md).

## Related pages

* [Menus](menus.md): the menu-level keys, the layout grammar and hiding a button by removing its letter.
* [Pagination, Regions and Templates](pagination-regions-templates.md): navigation items, regions, templates and cell precedence over declared items.
* [Item Appearance Reference](item-appearance.md): every appearance key an item or template accepts.
* [Actions and Requirements](actions-and-requirements.md): the full action tag list, click guards and requirement grammar.
* [Troubleshooting](troubleshooting.md): symptoms like a button rendering in the wrong cell after declaring both slots and key.
