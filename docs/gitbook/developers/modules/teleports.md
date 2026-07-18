# Warmup teleports

The teleports module solves the warmup teleport that every `/home`, `/warp`, `/rally` and
`/tpa` re-implements: one pending teleport per player, a "teleporting in N..." message, cancel
on movement and on damage, an optional cooldown, and a completion that works the same on Paper
and Folia. You describe the teleport; the module runs the state machine.

## Opt in

The module is **opt-in**. Declare it in your `SnSpec`, then reach it through the context.

```java
@Override protected SnSpec buildSpec() {
    return SnSpec.builder()
            .lang()          // optional: for localized messages (defaults render without it)
            .teleports()     // enables sn.teleports()
            .build();
}
```

```java
Teleports teleports = sn.teleports();
```

Calling `sn.teleports()` without declaring `teleports()` throws `UnsupportedOperationException`
naming the missing builder call, like every other gated module. Requests are **main-thread
only**, like all teleport work in Paper.

## Requesting a teleport

`request(player, target, options)` runs the whole flow and returns a `TeleportResult` you react
to. A two-argument overload uses `TeleportOptions.instant()` (no warmup, no cooldown) for a
plain immediate teleport that still flows through the module's bookkeeping.

```java
TeleportResult result = sn.teleports().request(player, home.location(),
        TeleportOptions.builder()
                .warmupSeconds(3)
                .cooldown("home", 30)                 // shared with sn.cooldowns()
                .onComplete(p -> p.playSound(p.getLocation(), Sound.ENTITY_ENDERMAN_TELEPORT, 1f, 1f))
                .build());

switch (result) {
    case WARMUP_STARTED -> { /* the warmup message was already sent */ }
    case TELEPORTED     -> { /* instant teleport dispatched */ }
    case ALREADY_PENDING -> player.sendMessage(SnText.color("&cYou are already teleporting."));
    case ON_COOLDOWN    -> { long left = sn.cooldowns().remainingMillis(player.getUniqueId(), "home"); /* ... */ }
    case FAILED         -> player.sendMessage(SnText.color("&cCannot teleport there right now."));
}
```

The target `Location` is cloned on entry, so mutating it afterwards never affects a pending
teleport.

## The result state machine

Every request resolves to exactly one `TeleportResult`; the module never throws for a rejected
request. The decision is a pure priority ladder:

| Result | When | `accepted()` |
|--------|------|:---:|
| `ALREADY_PENDING` | the player already has a pending teleport of this module (dedup wins first, never double-scheduled) | no |
| `ON_COOLDOWN` | the options declared a cooldown category that is still running | no |
| `TELEPORTED` | `warmupSeconds == 0`: dispatched immediately, no warmup message | yes |
| `WARMUP_STARTED` | otherwise: the warmup message is sent and the teleport is scheduled | yes |
| `FAILED` | null player, null target, or the target world is not loaded | no |

`accepted()` is true for `WARMUP_STARTED` and `TELEPORTED`; `rejected()` is its complement. Dedup
outranks cooldown, and a zero warmup is an instant teleport.

## Options

`TeleportOptions` is an immutable per-request snapshot. Build one with `TeleportOptions.builder()`,
or use the two shortcuts `TeleportOptions.instant()` (the shared no-warmup default) and
`TeleportOptions.warmup(seconds)`.

| Builder call | Effect |
|--------------|--------|
| `warmupSeconds(int)` | Warmup length; `0` means instant. Negatives clamp to zero. |
| `cooldown(String category, int seconds)` | Arms `category` for `seconds` on completion, and rejects a new request while it is still running (`ON_COOLDOWN`). The category is **shared with `sn.cooldowns()`**, so the same key can gate other actions. |
| `silent(boolean)` | Suppresses every message of this request (warmup and both cancel messages). |
| `onComplete(Consumer<Player>)` | Callback run on the **main thread after a successful teleport only** (see below). |
| `warmupKey` / `cancelledMoveKey` / `cancelledDamageKey` | Override the lang key of each message with a consumer-owned one. |

## Cancellation and completion

While a teleport is warming up it is cancelled by any of:

- **Movement.** A shared `PlayerMoveEvent` listener with a block-delta quick exit: head rotation
  and sub-block movement never cancel, only an actual block-position change does. Sends the
  `cancelled-move` message.
- **Damage.** A shared `EntityDamageEvent` listener at `MONITOR`, `ignoreCancelled = true`, so a
  damage another plugin cancelled does not count. Sends the `cancelled-damage` message.
- **Quit / kick**, and the context teardown or reload - silently, no message.

Both listeners are registered once by SnLib and only ever act for owners that declared the
module, so a plugin without `teleports()` pays nothing.

Completion teleports through `Player#teleportAsync`, the region-safe call that behaves the same
on Paper and Folia. The `onComplete` callback runs on the main thread **only after a genuinely
successful teleport** - never one another plugin vetoed, never one that completed
exceptionally.

You can also drive the pending teleport directly: `isPending(player)` queries it, and
`cancel(player)` cancels it **without sending a message** and returns whether one was pending
(idempotent - a no-op when nothing is pending). `request` itself is the dedup point: a second
request while one is pending returns `ALREADY_PENDING` and is never double-scheduled.

## Messages

The three messages resolve against your [lang module](lang.md) when it is declared, falling back
to embedded English defaults otherwise (the module never requires lang). The keys:

| Key | Placeholder | Sent when |
|-----|-------------|-----------|
| `snlib.teleport.warmup` | `{time}` (seconds) | a warmup teleport starts |
| `snlib.teleport.cancelled-move` | (none) | a pending teleport is cancelled because the player moved |
| `snlib.teleport.cancelled-damage` | (none) | a pending teleport is cancelled because the player took damage |

Like every `snlib.*` key these merge into your language files automatically and are yours to
restyle. Override the key used by a single request with `warmupKey(...)` /
`cancelledMoveKey(...)` / `cancelledDamageKey(...)` on the options to point at a message you own.

## Related pages

- [Cooldowns, economy and utils](cooldowns-economy-utils.md) - the cooldown store the
  `cooldown(...)` option shares.
- [Localization](lang.md) - the `snlib.teleport.*` keys and how to override them.
- [Threading model](../threading-model.md) - why `onComplete` hops back to the main thread.
- Back to the [developer guide](../README.md) or the [quickstart](../quickstart.md).
