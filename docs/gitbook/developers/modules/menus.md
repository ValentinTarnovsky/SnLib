# Menus (GUIs)

The menus module renders chest GUIs from YAML. Its guiding principle: **if the config user sets a supported field, it already works with zero plugin code.** The full set of supported fields is the golden spec at `docs/menu-example.yml`; a developer's job is usually just to open the menu and, when the content is dynamic, bind data into it.

You reach the module through `sn.guis()` once your `SnSpec` declares `guis()`.

```java
@Override protected SnSpec buildSpec() {
    return SnSpec.builder()
            .config("config.yml")   // required: guis need the yml module
            .guis()                 // loads the guis/ folder
            .build();
}
```

## One file per menu

Each `.yml` file in the plugin's `guis/` folder is one menu. The menu id is the file name without the extension, so `guis/shop.yml` loads as `"shop"`.

```java
Gui shop = sn.guis().get("shop");   // null if no guis/shop.yml
shop.open(player);
```

`open(player)` gives the viewer their own live `GuiSession`: their own inventory, their own holder, and their own page state, all sharing the immutable parsed definition. Two players in the "same" menu are two independent sessions and can sit on different pages. Opening again for a viewer who already has a live session re-shows that session instead of stacking a second one.

{% hint style="info" %}
The whole GUI module is main-thread only, like all inventory work in Paper. Open menus and bind content from the main thread.
{% endhint %}

## A realistic example

A trimmed `guis/shop.yml`:

```yaml
title: "[rgb]&lShop"
rows: 3
open-sound: BLOCK_CHEST_OPEN
close-sound: BLOCK_CHEST_CLOSE

items:
  buy-diamond:
    material: DIAMOND
    slots: [13]
    display-name: "&bDiamond"
    lore:
      - "&7Price: &a$100"
    click-requirements:
      - "%vault_eco_balance% >= 100"
    click-actions:
      - "[console] eco take %player% 100"
      - "[console] give %player% diamond 1"
      - "[message] &aPurchased!"
    deny-actions:
      - "[message] &cYou can't afford this."
      - "[sound] ENTITY_VILLAGER_NO"

  filler:
    material: GRAY_STAINED_GLASS_PANE
    display-name: " "
    slots: [0-8, 9, 17, 18-26]
```

Opening it needs no per-item Java at all:

```java
sn.guis().get("shop").open(player);
```

Everything above (requirements, actions, deny actions, sounds, the text pipeline in every string) is handled by the library.

## Binding dynamic content

When content depends on runtime data, bind it into the viewer's session. Get the session and bind a template to a slot with local placeholders:

```java
GuiSession s = shop.session(player);
s.bind(13, shop.template("offer"), Ph.of("price", 100), Ph.of("item", "Diamond"));
```

`bind(slot, template, phs...)` renders immediately and survives page refreshes and inventory recreations until you overwrite it. It takes precedence over a declared item on the same slot. Templates are items declared under `templates:` with no `slots:` - the developer decides where they go.

## Paginated content

Pagination is OPT-IN per menu with `pagination: true`. With it on, each viewer has real per-player page state, and you fill the paged slots with `bindPaged`:

```yaml
title: "Warps"
rows: 6
pagination: true
```

```java
List<Warp> warps = warpService.all();
int[] slots = { 10,11,12,13,14,15,16,  19,20,21,22,23,24,25 };
s.bindPaged("warp-entry", warps, slots, (warp, ph) -> {
    ph.add("name", warp.name());
    ph.add("world", warp.world());
});
```

`bindPaged` snapshots the data immutably, pages it by `slots.length` entries, and renders the viewer's current page one entry per slot in order. Leftover slots on a short page stay empty. The bind survives page changes and inventory recreations until rebound, and the page is clamped to the snapshot's total pages - which also drives the `nav-disabled` state of the navigation items in the YAML.

Navigation is pure config. Items whose `click-actions` use `[next-page]` / `[previous-page]` / `[set-page]` / `[refresh-page]` move the viewer, and each can declare a `nav-disabled` override rendered in the same slots when there is no page to go to.

{% hint style="warning" %}
With `pagination: false` (the default), page actions are silent no-ops with a debug note, and `bindPaged` warns once per menu and is ignored. If you need pagination, set `pagination: true` on the menu.
{% endhint %}

### Manual page counts

If you page content yourself (through `[refresh-page]` or custom actions) without a live `bindPaged`, the total page count is unknown and the "next" arrow never disables. Declare the total explicitly:

```java
s.setTotalPages(5);   // enables the next-page cap and nav-disabled state
```

A value `<= 0` resets the total to "unknown". A live `bindPaged` always takes precedence over this value. Like the page operations, `setTotalPages` requires `pagination: true`.

### Layout-driven slots

You can also target paged slots without an `int[]`. A menu can declare an ASCII `layout:` and a `paged-key:` (exactly one layout character); the cells holding that character become the target of the no-slots overload:

```yaml
pagination: true
layout:
  - "fffffffff"
  - "f ddddd f"
  - "f ddddd f"
paged-key: d
```

```java
s.bindPaged("warp-entry", warps, (warp, ph) -> ph.add("name", warp.name()));
```

If the menu declares no `paged-key`, this overload warns once and is ignored.

## Custom action tags

The action grammar (`[player]`, `[console]`, `[message]`, `[sound]`, `[open]`, `[close]`, `[next-page]`, and the rest) is already available in config. To add a tag of your own, register a handler:

```java
sn.guis().registerAction("give-token", ctx -> {
    tokenService.grant(ctx.player(), 1);
});
```

Then config can use it like any built-in tag:

```yaml
click-actions:
  - "[give-token]"
  - "[message] &aYou received a token!"
```

`registerAction` is sugar over `sn.actions().register`, so the tag is available anywhere actions run for your context, not just in this menu.

## Config-only surfaces (no Java needed)

A large part of the menu system is config-only. Knowing it exists tells you what NOT to write Java for.

### ASCII layout mode

Instead of explicit slot numbers, a menu can define a `layout:` of 1-6 strings of up to 9 characters each, read top to bottom over the 9-column chest grid (cell at row `i`, column `j` is slot `i*9+j`, space is always an empty cell). Items then reference a layout character with `key:` (one character) instead of `slots:`. The same geometry is exposed to Java through `GuiMask.slots(char, rows...)` when you want to compute slot arrays in code.

```java
int[] border = GuiMask.slots('f', "fffffffff", "f       f", "fffffffff");
```

### Per-click action matrix

Besides the generic `click-actions` / `click-requirements` / `deny-actions`, five click keys each accept three optional lists (15 keys total): `right`, `left`, `shift-right`, `shift-left` and `middle`, each with `-click-actions`, `-click-requirements` and `-click-deny-actions`.

Resolution is **specific-over-generic** and **field by field** (actions, requirement and deny list resolve independently):

1. the exact shift list of the click, when declared (`SHIFT_RIGHT` / `SHIFT_LEFT`);
2. the side list, when declared (right covers RIGHT and SHIFT_RIGHT; left covers LEFT, SHIFT_LEFT, DOUBLE_CLICK and CREATIVE; middle covers MIDDLE);
3. fallback to the generic `click-*` list.

A list counts as "declared" only when it is non-empty, so you can declare `right-click-actions` and still inherit the generic `click-requirements`.

`strict-clicks: true` (opt-in per menu, default false) discards any click outside the four basic mouse clicks (LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT) unless a specific list covers it: `middle-click-actions` enables MIDDLE, and a declared `left-click-actions` enables DOUBLE_CLICK and CREATIVE. NUMBER_KEY, DROP, CONTROL_DROP and SWAP_OFFHAND have no possible specific list and stay discarded in strict mode. With strict off (the default), any ClickType fires the resolved list, exactly as it always has.

### Close actions and sound

`close-actions:` (same grammar as `click-actions`) and `close-sound:` run once per close, on the natural client close (ESC) and on the `[close]` action. They deliberately do NOT run on page changes, on inventory recreations, or when the library closes the session programmatically (reload, owner disable, quit cleanup). Click guards inside close-actions are skipped with a debug note, since there is no click.

{% hint style="info" %}
Keep close-actions idempotent. On a disconnect the server fires the close event before the quit event, and the double online-check covers the normal case, but idempotent close logic is the safe habit.
{% endhint %}

## Anti-theft and safe lifecycle

GUI items are protected without any config on your part. Every rendered stack is stamped with an owner-namespaced PDC key (`snlib_gui_item` carrying `"<guiId>:<slot>"`), `COLLECT_TO_CURSOR` is unconditionally cancelled (blocking double-click stacking), and an `ItemSpawnEvent` catch-all backs it up - so a menu item never circulates into the world or a real inventory.

On reload or disable, the consumer's open GUIs are closed natively with no `ClassCastException`, and sessions of every other consumer stay untouched. Because sessions are per viewer, nobody is left holding a stale inventory.

## The golden spec

`docs/menu-example.yml` is the golden spec: it documents every supported field with its default and its behavior, and it is the acceptance contract for the module. When you want to know whether something is configurable (it almost always is), that file is the source of truth. The header of `GuiDef.java` and `GuiItemDef.java` carries the same checklist with the exact parse point for each field.

## Full field reference example

Every menu-level field, plus an item using every appearance/behavior field and the full per-click matrix, plus a filler, paginated navigation with `nav-disabled`, a player head, and a template - all in one file:

```yaml
title: "[rgb]&lThe Shop"
rows: 6
inventory-type: CHEST
open-sound: BLOCK_CHEST_OPEN
close-sound: BLOCK_CHEST_CLOSE
update-interval: 100
pagination: true
strict-clicks: false

layout:
  - "fffffffff"
  - "f ddddd f"
  - "f ddddd f"
  - "f ddddd f"
  - "f ddddd f"
  - "f       f"
paged-key: d

items:
  legendary-blade-icon:
    # appearance
    display-name: "[rgb]&lLegendary Blade"
    material: DIAMOND_SWORD
    attributes:
      - "GENERIC_ATTACK_DAMAGE ADD_NUMBER 4 MAINHAND"
    damage: 0
    custom-model-data: 1001
    amount: 1
    slots: [4]
    glow: true
    enchantments: [sharpness, 5]
    flags:
      - HIDE_ALL
    color: "#FF5555"
    trim-pattern: SILVER
    trim-material: DIAMOND
    potion-effects: [SPEED, 1, 200]
    update-interval: 0
    lore:
      - "&7Price: &a$5000"

    # gates
    view-requirements:
      - "%player_level% >= 10"
    click-requirements:
      - "%vault_eco_balance% >= 5000"
    deny-actions:
      - "[message] &cYou can't afford this yet."
      - "[sound] ENTITY_VILLAGER_NO"

    # generic click grammar
    click-actions:
      - "[console] eco take %player% 5000"
      - "[message] &aPurchased the Legendary Blade!"
      - "[sound] ENTITY_PLAYER_LEVELUP"
      - "[close]"

    # per-click matrix: specific-over-generic, field by field (15 optional keys)
    right-click-actions: []
    right-click-requirements: []
    right-click-deny-actions: []
    left-click-actions: []
    left-click-requirements: []
    left-click-deny-actions: []
    shift-right-click-actions:
      - "[message] &7Preview: a blade forged in starlight."
    shift-right-click-requirements: []
    shift-right-click-deny-actions: []
    shift-left-click-actions: []
    shift-left-click-requirements: []
    shift-left-click-deny-actions: []
    middle-click-actions: []
    middle-click-requirements: []
    middle-click-deny-actions: []

  filler:
    display-name: " "
    material: GRAY_STAINED_GLASS_PANE
    key: f                          # renders in every layout cell holding "f"

  your-head:
    display-name: "&eYour head, %player_name%"
    material: PLAYER_HEAD
    skull-owner: "%player_name%"    # resolves PER VIEWER - each player sees their own head
    slots: [49]

  previous-page:
    display-name: "[rgb]&lPrevious Page"
    material: ARROW
    slots: [45]
    click-actions:
      - "[previous-page]"
      - "[sound] UI_BUTTON_CLICK"
    nav-disabled:                   # shown instead, on the first page
      display-name: "&7No previous page"
      material: GRAY_STAINED_GLASS_PANE
      lore:
        - "&8You are on the first page"

  next-page:
    display-name: "[center][rgb]Next Page"
    material: ARROW
    slots: [53]
    click-actions:
      - "[next-page]"
      - "[sound] UI_BUTTON_CLICK"
    nav-disabled:                   # shown instead, on the last page
      display-name: "&7No next page"
      material: GRAY_STAINED_GLASS_PANE
      lore:
        - "&8You are on the last page"

close-actions:
  - "[message] &7See you soon!"
  - "[sound] UI_BUTTON_CLICK"

# templates: identical fields to items, minus "slots" - the plugin places them via Java
templates:
  offer-template:
    display-name: "&f%item%"
    material: STONE
    amount: 1
    lore:
      - "&7Price: &a$%price%"
```

{% hint style="info" %}
`%player_level%`, `%vault_eco_balance%` and `%player_name%` above are ordinary PlaceholderAPI tokens - requirements, actions and appearance strings all resolve placeholders the same way. See [Configuration](yml.md) for exactly which mechanism resolves which kind of field.
{% endhint %}

## Related pages

- [Items](items.md) - physical items share the action and requirement engines and the same YAML appearance schema.
- [Text rendering](text.md) - every string in a menu (`title`, `display-name`, `lore`) flows through the text pipeline.
- [Configuration](yml.md) - menus are backed by managed YML files under `guis/`.
- Back to the [developer guide](../README.md) or the [quickstart](../quickstart.md).
