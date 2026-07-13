# Custom events

SnLib fires three custom Bukkit events from its shared listeners. You consume them exactly like any Bukkit event: implement `Listener`, annotate a method with `@EventHandler`, and register with the plugin manager. All three carry a player and extend a cancellable base (`SnPlayerEvent`, which is a `PlayerEvent implements Cancellable`), so every one exposes `getPlayer()`, `isCancelled()` and `setCancelled(boolean)` - though what cancelling actually *does* differs per event, as noted below.

The library's shared listeners synthesize and fire these events once, in the SnLib plugin, so there is no per-consumer cost to listen for them.

## SnArmourEquipEvent

Fires when a player equips or unequips an armour piece, through any of the vectors the library tracks (equipping from the hotbar, shift-clicking, dispenser auto-equip, breaking, death drops, and so on). It is synthesized by the library's shared armour-equip listener.

### What it carries

| Accessor | Meaning |
|----------|---------|
| `getPlayer()` | The player whose armour changed |
| `getMethod()` | The input vector of the change (`EquipMethod`) |
| `getSlot()` | The armour slot affected (`EquipmentSlot`) |
| `getOldPiece()` | Piece leaving the slot, or null when the slot was empty |
| `getNewPiece()` | Piece entering the slot, or null when the slot empties |

### Cancellation is conditional

Cancellation is **binding only when the underlying source is cancellable** - in practice the dispenser vector (`EquipMethod.DISPENSER`), where the equip has not happened yet. Events that report an *already-applied* change (a primary in-hand swap, `EquipMethod.DEATH`) still let you call `setCancelled(true)`, but there it is a consumer-level signal for other listeners rather than an undo: the armour change already took effect. Check `getMethod()` if you need to know whether a cancel will actually prevent the equip.

```java
public final class ArmourListener implements Listener {

    @EventHandler
    public void onEquip(SnArmourEquipEvent event) {
        Player player = event.getPlayer();
        ItemStack incoming = event.getNewPiece();
        if (incoming != null && isBanned(incoming)) {
            event.setCancelled(true);   // binding for DISPENSER; a signal otherwise
            player.sendMessage("That armour is not allowed here");
        }
    }
}
```

{% hint style="info" %}
Pair this with `ArmourUtil` (`slotOf`, `isArmour`, `isWearingFullSet`) when you need to reason about the resulting set rather than the single piece. See [Cooldowns, Economy and utils](cooldowns-economy-utils.md#bukkit-aware-utilities).
{% endhint %}

## SnChunkMoveEvent

Fires when a player crosses from one chunk to another **through movement**. It is synthesized by the library's shared chunk-move listener from `PlayerMoveEvent`, and it fires only when the chunk actually changes - not on every move packet.

### What it carries

| Accessor | Meaning |
|----------|---------|
| `getPlayer()` | The player crossing the border |
| `fromLocation()` | Snapshot of the location the move started from |
| `toLocation()` | Snapshot of the location the move ends at |
| `fromChunk()` | The chunk being left (resolved from the origin) |
| `toChunk()` | The chunk being entered (resolved from the destination) |

Both locations are event-owned clones, so mutating them never affects the source `PlayerMoveEvent`.

### Movement only, and cancellation is binding

This event fires **only for movement**. Teleports, joins and respawns do NOT emit it - `PlayerTeleportEvent` has its own handler list and never passes through the move handler. If you need to react to those, listen for them separately.

Cancellation **is binding**: cancelling `SnChunkMoveEvent` cancels the source `PlayerMoveEvent`, so the player is held at the chunk border.

```java
public final class ZoneListener implements Listener {

    @EventHandler
    public void onChunkMove(SnChunkMoveEvent event) {
        if (isProtected(event.toChunk()) && !event.getPlayer().hasPermission("zone.enter")) {
            event.setCancelled(true);   // cancels the underlying PlayerMoveEvent
            event.getPlayer().sendMessage("You cannot enter this area");
        }
    }
}
```

{% hint style="warning" %}
This event is derived from player movement, so its handler runs on the movement hot path. Keep the body cheap: a chunk-crossing check is far cheaper than a per-move check, but it is still frequent. `LocationUtil` offers cheap `distance2dSquared` and cuboid checks that pair well here.
{% endhint %}

## SnSelectionCompleteEvent

Fires when a player completes a cuboid selection with the region/selection module (both positions set, in the same world, within the spec's volume cap). It is dispatched synchronously on the main thread.

### What it carries

| Accessor | Meaning |
|----------|---------|
| `getPlayer()` | The player who completed the selection |
| `owner()` | The consumer plugin owning the selection manager |
| `specId()` | The id of the `SelectionSpec` the session was opened with |
| `cuboid()` | The completed `Cuboid` (immutable, safe to share without copying) |

### Cancellation vetoes the completion

Cancellation **is binding**: when a listener cancels it, the spec's `onSelect` callback does not run and the session stays alive, so the player can re-click. This lets a protection or staff plugin veto selections owned by *other* consumers.

```java
public final class SelectionListener implements Listener {

    @EventHandler
    public void onSelect(SnSelectionCompleteEvent event) {
        if (event.cuboid().size() > MAX_BLOCKS) {
            event.setCancelled(true);   // onSelect will not run; the wand session lives on
            event.getPlayer().sendMessage("That region is too large");
        }
    }
}
```

{% hint style="info" %}
This is the only event the selection module fires by design: a per-click update event would fire server-wide on every wand click, so the spec's `onUpdate` callback covers that case with zero global cost instead. For the full selection-wand module - `SelectionSpec`, giving and creating wands, `Cuboid`, rendering and configuration - see [Region and selection](region-selection.md).
{% endhint %}

## Registering your listener

All three are ordinary Bukkit events, so registration is standard:

```java
@Override
protected void onInnerEnable() {
    getServer().getPluginManager().registerEvents(new ArmourListener(), this);
    getServer().getPluginManager().registerEvents(new ZoneListener(), this);
    getServer().getPluginManager().registerEvents(new SelectionListener(), this);
}
```

## See also

- [Region and selection](region-selection.md) - the full module behind `SnSelectionCompleteEvent`.
- [Cooldowns, Economy and utils](cooldowns-economy-utils.md) - `ArmourUtil` and `LocationUtil` pair with these events.
- [Developer overview](../README.md) and [the threading model](../threading-model.md).
