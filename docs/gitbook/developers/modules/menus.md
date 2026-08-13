# Menus (GUIs)

The menus module renders chest GUIs from YAML. Its guiding principle: **if the config user sets a supported field, it already works with zero plugin code.** The full set of supported fields is the golden spec at `docs/menu-example.yml`; a developer's job is usually just to open the menu and, when the content is dynamic, bind data into it.

You reach the module through `sn.guis()` once your `SnSpec` declares `guis()`.

```java
@Override protected SnSpec buildSpec() {
    return SnSpec.builder()
            .config("config.yml")   // required: guis need the yml module
            .guis()                 // seeds and loads the guis/ folder
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

### Naming the menu's subject in the title (1.22.0)

A `title:` is resolved per viewer through PAPI, which covers "your" menus. It does not cover a menu whose **subject is somebody else** - another player's inventory, the clan you are inspecting, the crate you are previewing. For those, pass local placeholders when you open:

```yaml
# guis/inventory.yml
title: "&#8354f2&l{player}'s Inventory"
```

```java
sn.guis().get("inventory").open(viewer, Ph.of("player", ownerName));
```

The token is yours to define; it is resolved before PAPI, so a placeholder may expand into a PAPI token. With none passed the title renders exactly as written, which is why every existing menu is unaffected.

To change the title of a menu that is **already open**, use `session.titlePlaceholders(...)`. Prefer the `open` overload where you can: a title cannot be painted into a live window, so changing one costs an inventory recreation, while `open` gets the first frame right for free.

## Bundling menus in your jar

Ship your default menus as `guis/*.yml` resources inside your jar. On load (onEnable and on every reload) SnLib seeds them into the data folder's `guis/` folder with the SAME managed semantics as configs: a missing file is written from the jar, an existing file is structurally merged (new keys added, user edits kept), and the whole seed is gated by the config's `update-configs` switch. Only top-level `guis/<name>.yml` resources are seeded; nested resources (`guis/sub/x.yml`) and non-`.yml` entries are ignored. The resources are read from the CONSUMER jar, so a menu you bundle is never confused with one bundled by SnLib itself.

The menu id is still the file name without the extension, so a bundled `guis/shop.yml` seeds to `plugins/YourPlugin/guis/shop.yml` and loads as `"shop"`.

{% hint style="warning" %}
If your spec declares `guis()` but the `guis/` folder ends up empty (nothing bundled in the jar and nothing dropped in by hand), no menu loads and SnLib logs a WARN naming the empty folder. Bundle your menus as `guis/*.yml` in the jar so they seed, or place the files in the folder.
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

`bind(slot, template, phs...)` renders immediately and survives page refreshes and inventory recreations until you overwrite it. It takes precedence over a declared item on the same slot. Templates are items declared under `templates:`; `slots:`/`key:` are optional on them - the developer decides where they go, or the file does (below).

### Config-driven placement (1.18.0)

A template may declare its own placement - `slots:` or a `key:` resolved against the menu `layout:`, exactly like an item - and be bound WITHOUT a slot:

```yaml
layout:
  - "ffabcdeff"
templates:
  banner:
    key: a
    display-name: "&#8354f2&l{clan}"
```

```java
s.bind("banner", Ph.of("clan", clan.name()));
```

The server owner then repositions the element by moving the key in the layout (or editing `slots:`) - no plugin update needed. A key covering N cells renders the same bind into every cell. The explicit `bind(slot, template, phs...)` always ignores the declared cells, so plugin-computed placements (one template bound N times with different data, e.g. one per list entry) keep working. Binding an unknown template id, or a slotless bind of a template that declares neither `slots:` nor a valid `key:`, warns once per menu and is ignored.

### Runtime regions (1.20.0)

`bind("id", ...)` renders the SAME thing into every cell of a key. When a group of cells needs one DISTINCT entry each - a permission matrix, a role selector, any non-paged list - declare a **region** and fill it with `bindEach`. This is what replaces the `private static final int[] SLOTS = {19,20,...}` that used to live in plugin code: the placement, the cell count and the order all come from the file.

```yaml
layout:
  - "fffffffff"
  - "fffrrrfff"
  - "ftttttttf"
  - "ftttftttf"
  - "ffffxffff"

regions:
  roles: r
  toggles: t

templates:
  # Keyless on purpose: the region owns the cell, these two only own the look.
  toggle-allowed:
    material: LIME_DYE
    display-name: "&a{action}"
  toggle-denied:
    material: GRAY_DYE
    display-name: "&7{action}"
```

```java
s.bindEach("toggles", perms.gatedActions(), (action, entry) -> entry
        .template(perms.isAllowed(clan, role, action) ? "toggle-allowed" : "toggle-denied")
        .add("action", labels.action(action))
        .add("action_id", action));
```

Entry `i` renders into cell `i` of the region: ascending row-major for a `key:`, the order you wrote for a `slots:` list. The filler runs once per entry on EVERY render, so values stay live under `update-interval:` instead of freezing at bind time, and it picks the template per entry - which is why several state variants of one button can share a region.

Ownership is per CELL, not per region: a cell with no entry, an entry whose filler picked no template and an entry hidden by its own `view-requirements` all fall through to the item declared underneath, on the screen and on the click alike. Declare an item on the region letter and it fills the spare cells. Precedence on a shared cell is `bind(slot, ...)` > `bindPaged` > region > declared item.

Cardinality belongs to the owner and never warns: fewer cells than entries shows the first ones and drops the tail, more cells than entries leaves the rest to the declared items. If your plugin must not truncate silently, read the count yourself and say so in your own lang file:

```java
int cells = gui.def().regionSlots("toggles").length;
```

Removing a region is a layout edit - take the letter out and the region binds nothing, silently; blanking its value (`toggles: ""`, or an empty `slots:` list) does the same. The `regions:` declaration itself must stay (the always-merge updater re-adds it, and `bindEach` warns once about a region the menu does not declare), so never mark `regions:` as `# sn:extensible`.

> **The rule**: distinct letters name distinct ELEMENTS, bound BY NAME through `key:`. ONE repeated letter is an ordered REGION, bound BY INDEX through `regions:` + `bindEach`. Never express an ordered sequence with distinct letters - `"123"` cannot be indexed, it is just three unrelated cells.

Unlike `bindPaged`, a region needs no `pagination: true`, never touches the page and shows the same entries on every page.

### Plugin-supplied stacks (1.21.0)

Every binding above builds the cell from the yml. That works while the plugin can DESCRIBE what it shows - but a crate reward, a kit's contents, shop stock or a lootbox preview is an `ItemStack` the plugin did not author, carrying enchantments, custom model data, a head texture and a custom name that no item definition can re-express. Hand the stack over instead, on any of the three bind surfaces:

```java
// manual bind
s.bind(22, gui.template("reward"), reward.icon(), Ph.of("chance", reward.chance()));

// paged bind: the mapper supplies the stack for its entry
s.bindPaged("kit-item", kit.contents(), (item, ph) -> ph
        .stack(item)
        .add("slot", kit.slotOf(item)));

// region: the filler supplies the stack for its cell
s.bindEach("stock", shop.offers(), (offer, entry) -> entry
        .template(offer.affordable(player) ? "offer" : "offer-locked")
        .stack(offer.item())
        .add("price", offer.price()));
```

The split is: **the stack supplies the appearance, the template supplies the behaviour.** The template's `view-requirements`, `click-requirements`, `deny-actions`, `click-actions` and the whole per-click matrix keep working exactly as they do for any other bind, and the rendered stack still carries the anti-theft marker, so an escaped copy is still deleted.

Two template fields are painted OVER the stack, because both are text a server owner must be able to write:

```yaml
templates:
  reward:
    # No material: needed - the stack brings its own. Blank the name to keep the
    # item's real one; leave out the lore to show the item exactly as it is.
    display-name: "&#8354f2{reward_name}"
    lore:
      - ""
      - "&7Chance: &f{chance}%"
    click-actions:
      - "[player] crate preview {crate}"
```

- `display-name`, when non-empty, **replaces** the stack's name;
- `lore` lines are **appended** after the lore the item already carries;
- a template that declares neither is a strict pass-through (below);
- everything else (`material`, `amount`, `glow`, `enchantments`, `custom-model-data`, ...) is ignored for that cell - the stack already has it.

Both resolve through the normal pipeline, so `%papi%` placeholders, your local `Ph` pairs, `&` colours, `[rgb]` and MiniMessage all work in them.

**If you already built the stack exactly how you want it, declare neither field and SnLib adds nothing.** No empty line appended, no lore cleared, no name written, no normalising pass over what you handed in - the item's meta is not even read. That holds for every way of leaving a field out: the key absent, `display-name: ""`, or `lore: []`. The one thing to know is that an empty LIST means "declared nothing", while a list holding an empty string means "declared one blank line" and is appended as a spacer:

```yaml
templates:
  raw-item:      # adds nothing at all to the stack
    click-actions:
      - "[player] kit claim {kit}"
  spaced-item:
    lore:
      - ""       # this DOES append a blank line
      - "&7Click to claim"
```

One caveat on "adds nothing": the stack that lands in the inventory still carries the `snlib_gui_item` anti-theft marker, one PDC key every rendered GUI stack has always carried - it is what lets SnLib delete an escaped copy. Your own NBT is untouched.

Your instance is never mutated and never ends up in the inventory: the stack is copied before anything is written to it. A supplied stack always needs its template - there is no way to bind a bare stack, because the template is what carries the click behaviour and a slot with no definition behind it would render but never respond. Passing no stack (or a null one) is exactly the behaviour of every version before 1.21.0, so nothing you already wrote changes.

**Cost.** One `ItemStack` clone per rendered cell, plus one small record per bind, plus a `Component` per name and lore line the template actually declares. A bare template costs the clone and nothing else. That is cheaper than the template render it stands in for (which reads ~20 YAML keys and builds a stack from scratch), but it is not free: this is a declarative per-viewer session built for opens, refreshes, page changes and `update-interval:` ticks. If you are driving a per-frame animation at 10 Hz and repainting a whole strip every frame, use a raw Bukkit inventory instead - that is not what a GUI session is for.

> A supplied stack narrows what the server owner can restyle to the name, the extra lore and the behaviour. Use it for contents you did not author; for a cell that is genuinely yours to describe, a plain template bind gives the owner the whole appearance.

## Receiving an item from the player (1.28.0)

Everything above puts items INTO a menu. This is the other direction: a cell that takes an item OUT of the player's hands - the shop that asks which item you are selling, the kit editor that asks what goes in slot 4, the deposit chest. Before 1.28.0 that could not be expressed at all: SnLib cancelled every click and drag, so a menu that needed a stack from the player had to be a hand-rolled Bukkit inventory with its own listener.

Two YAML keys and one callback:

```yaml
# guis/editor.yml
title: "&8Kit editor"
player-inventory: open      # the viewer may use their own inventory

layout:
  - "fffffffff"
  - "ffffiffff"
  - "fffffffff"

items:
  slot:
    key: i
    input: true             # THIS cell receives an item
    material: LIGHT_GRAY_STAINED_GLASS_PANE
    display-name: "&eDrop an item here"
    click-actions:          # fires only when the cursor is EMPTY
      - "[message] &7Hold the item you want to place."
```

```java
GuiSession s = gui.session(player);
s.onOffer(offer -> {
    kit.setIcon(offer.stack());                       // a copy, with its real amount
    s.bind(offer.slot(), gui.template("filled"), offer.stack());
});
```

That is the whole item-assignment flow. `input: true` marks the cell, `player-inventory: open` is what lets the viewer pick a stack up in the first place, and the handler decides what the offer means.

### The guarantee: the item is read, never consumed

Every event behind an offer is **cancelled before your handler runs**, and the offer carries a defensive clone. SnLib does not move, shrink, delete or store the offered stack, and never writes it into the menu. The stack is back on the player's cursor (or in their inventory) by the time you see it.

That is deliberate, and it is where the line sits: what accepting an offer means - how much you take, where it goes, what the cell then shows - is your plugin's decision, and a library that guessed it would own the money-shaped half of every deposit flow. If you accept the whole stack, you clear it yourself. If you accept part of it, you write the remainder back yourself.

### The three kinds

```java
s.onOffer(offer -> {
    switch (offer.kind()) {
        case CURSOR      -> ...   // clicked an input cell holding a stack
        case DRAG        -> ...   // dragged a stack onto one input cell
        case SHIFT_CLICK -> ...   // shift-clicked a stack in their own inventory
    }
});
```

| Kind | `slot()` | `playerSlot()` | `click()` |
| --- | --- | --- | --- |
| `CURSOR` | the input cell clicked | `-1` | `LEFT` or `RIGHT` |
| `DRAG` | the single input cell covered | `-1` | `RIGHT` for a single-item drag, `LEFT` for an even spread |
| `SHIFT_CLICK` | `-1` | the player-inventory slot the stack came from | `SHIFT_LEFT` or `SHIFT_RIGHT` |

`click()` is there so the vanilla convention is expressible: right click deposits one, left click deposits the stack. `stack()` always carries its real amount, which is what a deposit needs.

### A deposit, with the write-back

`SHIFT_CLICK` is the deposit gesture, and `playerSlot()` is what makes it expressible without SnLib ever touching an inventory: you compute how much you accepted and write the remainder back to the slot it came from.

```java
s.onOffer(offer -> {
    if (offer.kind() != ItemOffer.Kind.SHIFT_CLICK) {
        return;
    }
    ItemStack offered = offer.stack();
    int accepted = vault.deposit(player, offered);       // however much fits
    if (accepted <= 0) {
        sn.lang().send(player, "vault.full");
        return;
    }
    ItemStack remainder = offered.getAmount() > accepted
            ? offered.asQuantity(offered.getAmount() - accepted)
            : null;
    player.getInventory().setItem(offer.playerSlot(), remainder);
    player.updateInventory();                            // see below
    s.refreshMenu();
});
```

{% hint style="warning" %}
**Always follow a write-back with `updateInventory()`.** The click was cancelled, so the client is still drawing the stack it had before the event. When you then change that slot server-side, the client does not know: it keeps painting a stale stack the player can seem to click on. One resend right after the write fixes it. This is the single most common way to get this wrong.
{% endhint %}

### What each key actually controls

The two keys are orthogonal, and the split matters:

- `player-inventory: locked | open` is menu-level and governs the **bottom** inventory. `locked` is the default and is exactly what every menu did before 1.28.0. `open` leaves the viewer's own clicks, number keys, drops, offhand swaps and drags alone - stack splitting inside their own inventory works again - and turns a shift-click there into an offer.
- `input: true` is per item (and per template) and governs **individual menu cells**. It applies to the cells the item resolves to, through `slots:` or its layout `key:`.

A cell is an input slot when any definition that can occupy it declares `input: true`: the item declared on it, a template you bound to it, or the template a region entry painted there. So a plugin painting the *current contents* of an input cell over the declared one does not have to re-declare that the cell accepts an item:

```java
// The yml declares slot 13 as input; this bind only changes what it LOOKS like.
s.bind(13, gui.template("filled"), currentIcon);
```

In practice `input: true` needs `player-inventory: open` to be reachable at all - with the bottom inventory locked the viewer can never pick a stack up, so their cursor is always empty. SnLib WARNs on load if a menu declares one without the other.

An input cell clicked with an **empty cursor** is not an offer: it runs the cell's `click-actions` as usual. One cell can be a button and a drop target at once. Offers also never pass through `strict-clicks`, which filters actions - an offer is not one.

### What stays cancelled, always

`player-inventory: open` does not open the menu itself. Under both policies:

- every click over a cell of the **menu** is cancelled - as a plain click, as an offer, it makes no difference;
- `COLLECT_TO_CURSOR` (the double-click gather) is cancelled unconditionally, before anything else;
- a drag that touches two or more menu cells is cancelled rather than split;
- the anti-theft marker and its listener are untouched.

Those together are why no rendered GUI stack can reach the cursor, which is what makes leaving the player's own inventory alone safe. A menu that declares neither new key behaves byte-identically to 1.27.0.

{% hint style="info" %}
The handler runs on the main thread, inside the inventory event: keep it cheap and never block. Database work belongs behind a scheduler hop. Registering a handler is last-write-wins, `null` clears it, and it is dropped when the session closes. With no handler registered, offers are silently dropped with a debug note - the event was already cancelled, so nothing is lost.
{% endhint %}

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

If the menu declares no `paged-key`, this overload warns once and is ignored. For a group of cells that is NOT paginated, `regions:` + `bindEach` is the same idea without the page machinery - see [Runtime regions](#runtime-regions-1200).

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

Instead of explicit slot numbers, a menu can define a `layout:` of 1-6 strings of up to 9 characters each, read top to bottom over the 9-column chest grid (cell at row `i`, column `j` is slot `i*9+j`, space is always an empty cell). Items then reference a layout character with `key:` (one character) instead of `slots:`. The same geometry is exposed to Java through `GuiMask.slots(char, rows...)` when you want to compute slot arrays in code - that helper is for menus you BUILD in code, with no yml behind them. A file-backed menu never needs a computed `int[]`: use `key:` for one element, `paged-key:` for a paged block and `regions:` for an ordered group.

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

### View requirements gate the click too

`view-requirements` are a visibility gate AND an interaction gate: a click is dispatched against what the slot actually shows the clicking viewer, so an item hidden from them fires nothing at all - neither the click actions nor the deny actions - and the same holds for a paginated slot the current page left empty. Duplicating a view requirement into `click-requirements` is never needed to keep a hidden item safe (before v1.16.1 it was, because the dispatch only matched the slot).

The requirement is re-evaluated at click time, so a change that hides the item takes effect on the very next click even when the menu declares no `update-interval`; when a stale stack was still on screen, that click also clears the slot so the menu converges with the requirement instead of leaving a ghost item.

### Conditional variants on one slot

Since v1.17.0 several items may declare the same slot (or the same layout `key:`). Candidates resolve in **declaration order**: the first one whose `view-requirements` pass for the viewer owns the cell, and if every candidate is hidden the cell renders empty. There is no priority field - declare the preferred item first. Clicks resolve the same winner, so each variant fires its own `click-actions`.

This is how one cell shows a different button per state, with zero Java:

```yaml
items:
  info:
    material: BEACON
    key: a
    view-requirements:
      - "%snclans_has_clan% == true"
    click-actions:
      - "[custom] info"
  create:
    material: EMERALD
    key: a
    view-requirements:
      - "%snclans_has_clan% == false"
    click-actions:
      - "[player] clan create"
      - "[close]"
```

A member sees the info button; a clanless player sees the create button on the same cell. Before v1.17.0 a hidden later candidate wiped the slot of the visible earlier one, so this pattern needed one menu per state.

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
player-inventory: locked          # 1.28.0: "open" lets the viewer use their own inventory

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
    item-model: "nexo:profile_icon"   # optional, 1.21.2+: base ItemModel key; works alongside custom-model-data
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

# templates: identical fields to items; "slots"/"key" optional - with them the plugin
# binds slotless (bind("id", phs...)) and the file decides the cells; without them the
# plugin places each bind via Java (bind(slot, template, phs...))
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

{% hint style="info" %}
A `skull-owner` head whose textured profile the server has not cached yet (a rarely-seen offline player) renders as the default head immediately and then **pops in** its real skin: SnLib fetches the texture off the main thread and, when it lands, re-renders exactly that slot - a plain declared item, a manual `bind`, or a single paged entry alike. The GUI wires this automatically, so no code is needed. The re-render is guarded (it skips if the session closed, the page changed, or the slot was re-bound), and a genuinely unresolvable owner just keeps the default head. See the [items page](items.md#newer-snitem-additions) for the underlying `skullOwner` behavior.
{% endhint %}

## Related pages

- [Items](items.md) - physical items share the action and requirement engines and the same YAML appearance schema.
- [Text rendering](text.md) - every string in a menu (`title`, `display-name`, `lore`) flows through the text pipeline.
- [Configuration](yml.md) - menus are backed by managed YML files under `guis/`.
- Back to the [developer guide](../README.md) or the [quickstart](../quickstart.md).
