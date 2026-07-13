# Region Selection

The `com.sn.lib.region` module gives every consumer a visual cuboid selection tool: a wand a player left/right-clicks to mark two corners, with the selected box rendered as particle edges, and a callback that fires with the completed `Cuboid`. It is a generalized port of the SnGens admin wand, available to any plugin.

{% hint style="info" %}
Unlike most modules, the selection module is **not** gated behind an `SnSpec` declaration. It is always available via `sn.selections()`, 100% programmatic, no spec entry required (the same as `sn.actions()` or `sn.cooldowns()`). Its idle cost is an empty map plus one quit-cleanup registration, so declaring nothing costs nothing.
{% endhint %}

## Quick start

Build a `SelectionSpec`, then hand a wand to a player:

```java
Sn sn = sn();

SelectionSpec spec = SelectionSpec.builder("arena")
        .permission("myplugin.wand")
        .onSelect(cuboid -> arenas.saveRegion(cuboid))
        .build();

sn.selections().giveWand(player, spec);   // adds the wand to the inventory
```

`giveWand` builds the wand and adds it to the player's inventory (overflow drops at their feet). If you would rather place or gift the `ItemStack` yourself, use `createWand(spec)` and it returns the tagged item.

## The `Cuboid` value type

`Cuboid` is an immutable, thread-safe, axis-aligned block cuboid: a world name plus two corners, normalized on construction so `min <= max` per axis (callers never worry about corner order). Every edge is **inclusive**: a cuboid built from two clicked blocks contains both blocks. Its core (containment, iteration, size, serialization) is pure and never touches Bukkit statics; only the bridge methods (`of(Location, Location)`, `contains(Location)`, `world()`, `blocks()`, `center()`) do.

```java
Cuboid box = Cuboid.of("world", 10, 64, 10, 20, 70, 20);

box.size();                       // long: 11 * 7 * 11 = 847 (computed in long, never overflows)
box.contains(15, 66, 15);         // true - pure, world-agnostic
box.contains(location);           // false for null / unloaded / different-world, never throws
box.intersects(otherBox);         // inclusive edges: merely touching counts; different worlds never intersect
Cuboid grown = box.expand(2, 0, 2);  // new cuboid, grown both directions per axis

box.forEach((x, y, z) -> {...});  // pure per-block visitor, x then y then z
for (Location loc : box.blocks()) {...}  // lazy Bukkit bridge, never materializes a list
```

`size()` is computed in `long`, so even a region spanning the whole world height and a large footprint never overflows an `int`. `expand(dx, dy, dz)` grows (or shrinks, with negatives) in both directions of each axis and never throws: shrinking an axis past itself collapses it to a single block at the original midpoint. `blocks()` is a lazy iterable, so iterating a huge region never spikes the heap; when the world is not loaded it yields an empty iterable rather than throwing.

### Serialization round-trip

A cuboid serializes to `world;minX;minY;minZ;maxX;maxY;maxZ` and back:

```java
String raw = box.serialize();              // "world;10;64;10;20;70;20"
Cuboid restored = Cuboid.deserialize(raw); // null on malformed input, never throws
```

`serialize()` (and `toString()`) always emit the normalized min-to-max order. `deserialize` is lenient (the data path): null/blank input, a wrong part count, a bad number, or a blank world name all yield `null`; each part is trimmed individually, and the world is not required to be loaded, so cuboids deserialize safely in `onEnable` before worlds load. By contrast `Cuboid.of(...)` is the programmer path and **throws** on invalid input (null corner, unloaded world, corners in different worlds). This format is a sibling of `LocationSerializer` but is **not** interchangeable with it (that format has 4 or 6 parts).

## The wand

`createWand`/`giveWand` produce a physical item PDC-tagged (under `snlib_selection_wand`, namespaced per owner) with the spec id as the tag value. Because the tag is the spec id and not a random UUID, identical wands stack, and an old wand still in an inventory resolves its spec again after a relog as soon as the spec is registered.

The wand appearance comes from, in order of precedence:

1. the spec's `SnItem` (`wandItem(SnItem)`), rendered;
2. a template `ItemStack` (`wandItem(ItemStack)`), cloned;
3. otherwise a fallback `BLAZE_ROD` named `&6&lRegion Wand`.

### Click mechanics

- **Left-click** sets position 1.
- **Right-click** sets position 2.
- Setting a position refreshes the particle renderer and (unless silent) messages the owner with the coordinates.
- When both positions are set in the same world, the completion pipeline runs: an optional `max-volume` check, then the cancellable `SnSelectionCompleteEvent`, then your `onSelect` callback.

If the two positions end up in different worlds, the owner gets a `different-worlds` message and the selection does not complete. If the volume exceeds `maxVolume` (when set), the owner gets a `too-big` message and the positions stay set for correction.

### The completion event

`SnSelectionCompleteEvent` fires with the player, your plugin, the spec id and the completed `Cuboid` **before** `onSelect` runs, and it is cancellable. Cancel it to veto a selection (for example, one overlapping an existing claim):

```java
@EventHandler
public void onComplete(SnSelectionCompleteEvent event) {
    if (claims.overlapsProtected(event.cuboid())) {
        event.setCancelled(true);
    }
}
```

If the event is cancelled, `onSelect` never runs.

## Building a spec

`SelectionSpec.builder(id)` is a fluent builder; every field has a default and the documented clamps are applied once at `build()`, never at runtime. A null or blank id falls back to `"default"`.

```java
SelectionSpec spec = SelectionSpec.builder("arena")
        .permission("myplugin.wand")          // null/blank = no gate
        .wandItem(new SnItem(Material.GOLDEN_AXE).name("&6&lRegion Wand").glow())
        .particle("DUST")                     // by name; REDSTONE alias accepted
        .dustColor(255, 140, 0)               // or dustColor("#FF8C00") / dustColor("255, 140, 0")
        .dustSize(1.2f)                       // clamped 0.1..4.0
        .step(0.5)                            // block distance between edge points, min 0.1
        .refreshIntervalTicks(5)              // render period, min 1
        .renderDistance(64.0)                 // viewer cull distance
        .visibility(SelectionSpec.Visibility.OWNER_ONLY)  // or WORLD
        .particleBudget(2000)                 // points per refresh per viewer
        .maxRenderVolume(250_000L)            // above this, only the 8 corners are marked
        .maxVolume(0L)                        // 0 = unlimited selectable volume
        .timeoutTicks(0L)                     // 0 = never expires
        .silent(false)                        // true = suppress all selection messages
        .onSelect(cuboid -> arenas.save(cuboid))
        .onUpdate(session -> preview(session))  // fires on every position set
        .onCancel(uuid -> cleanup(uuid))        // fires on cancel/quit/timeout
        .build();
```

### Edge rendering and the particle budget

Edges are drawn with a particle budget so a big selection never gets heavier, only sparser:

- `particleBudget` caps the number of particle points **per refresh per viewer** (default 2000). Bigger boxes render with a coarser step so the whole box stays visible without flooding the client.
- `renderDistance` culls viewers too far from the box (default 64).
- `maxRenderVolume` (default 250000) is the threshold above which edges are not drawn at all; only the 8 corners are marked, each with a small cross.
- `visibility` is `OWNER_ONLY` (default, only the selecting player sees the edges) or `WORLD` (every player of the box's world within render distance).
- `timeoutTicks` (default 0 = never) expires an idle session; on expiry it cancels with `onCancel` and sends the `timeout` message unless the spec is silent.

The `DUST` particle resolves leniently: the `REDSTONE` alias is accepted, and an invalid name falls back to `FLAME` with one WARN.

## Sessions

A `SelectionSession` is the mutable, per-player, main-thread-only state behind a wand. You rarely construct one directly (the wand does), but you can drive selections programmatically:

```java
SelectionSession session = sn.selections().begin(player, spec);  // opens a session, hands no wand
session.setPos1(cornerA);   // same consequences as a left-click
session.setPos2(cornerB);   // same consequences as a right-click

session.hasBothPositions(); // both set, same world
Cuboid cuboid = session.cuboid();  // the box, or null
session.clearPositions();   // reset both, keep the session

sn.selections().cancel(player);  // cut renderer, run onCancel
```

`begin` registers the spec and opens a session without giving a wand, so you can compose it with `giveWand`. Sessions are **never persisted** (transient player state); to persist a result, serialize the `Cuboid`. A player who quits or is kicked has their session cancelled automatically, and a config reload deliberately never touches live selections (a reload mid-selection must not steal an admin's in-progress box).

## Optional YML integration

The module works fully with zero YAML. If you prefer to expose the wand's appearance and visuals to server admins, there is a golden spec at `docs/selection-example.yml` you can bundle and load:

```java
SelectionSpec spec = SelectionSpec
        .fromConfig(sn(), sn().yml().config(), "selection-wand", "arena")
        .toBuilder()
        .onSelect(cuboid -> arenas.saveRegion(cuboid))
        .build();

sn.selections().giveWand(player, spec);
```

`fromConfig` reads the YML section (wand `item` following the full `SnItem` appearance schema, `permission`, `particle.type/color/size`, `step`, `interval-ticks`, `render-distance`, `visibility`, `particle-budget`, `max-render-volume`, `max-volume`, `timeout-ticks`, `silent`). Absent fields fall back to the builder defaults and invalid values WARN once and keep the default. Callbacks are code-only, so you compose them over the loaded spec with `.toBuilder().onSelect(...)`. The YML integration is 100% opt-in: no new `SnSpec` module is involved, and the same spec is fully expressible with `SelectionSpec.builder("id")` alone.

The bundled example:

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

## See also

- [Bossbars, Holograms, Cron, Leaderboards, Discord](bossbars-holograms-cron-leaderboards-discord.md) - more small modules.
- [Text rendering](text.md) - the `SnText` pipeline the wand messages run through.
- Back to the [developer guide](../README.md).
