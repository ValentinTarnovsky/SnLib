# Compatibility and versioning

SnLib targets a deliberately wide range of server versions from a single jar,
and freezes its public API under semantic versioning so that a consumer compiled
today keeps working against future SnLib releases. This page covers the runtime
version range, the Java requirement, the "open enums" philosophy behind
cross-version resilience, and the two mechanisms that govern API stability: the
`japicmp` additive-only gate and the `SnApi.LEVEL` runtime handshake.

## Runtime version range

| | Version |
|---|---------|
| Runtime floor | 1.20.4 |
| Compilation baseline | 1.21.1 |
| Recognized schemes | the classic 1.20.4-1.21.x line and Mojang's year-based numbering (26.1+) |
| Latest verified live | 26.2 |
| Unparseable version string | starts with one forward-compatibility warning, never a hard-fail |

The floor is 1.20.4: SnLib runs on any Paper server from there onwards. The jar
is compiled against the 1.21.1 Paper API (for methods such as `setMaxStackSize`)
but only requires the 1.20.4 runtime, bridging the gap with reflective probing
(below).

Minecraft changed its numbering in 2026: after 1.21.11 the versions are
`YEAR.DROP[.PATCH]` (26.1, 26.2, 26.1.2), and Paper reports them as
`26.2.build.2632-stable`. SnLib reads both schemes from `Bukkit.getBukkitVersion()`
and treats every year-based version as newer than the whole 1.x line, so each
`supports(minor)` gate passes there. Nothing is pinned to a "highest known"
version: a future 26.3 or 27.1 starts silently, and the `Detected server:` line
on boot tells you what SnLib saw. Only a version string that carries no
`MAJOR.MINOR` pair at all triggers the single forward-compatibility warning and
keeps running with full support assumed. The philosophy is that a newer server
is far more likely to be compatible than not, so an unknown version degrades to
a warning, never a crash.

## Java 21 is mandatory

SnLib's classfiles are compiled at release 21, and the 1.20.4 runtime floor
already requires a Java 21 JVM. On an older JVM such as Java 17, the server fails
fast with `UnsupportedClassVersionError` while loading the jar, before any
version probe or module runs. There is no partial or degraded mode on Java 17;
Java 21 is a hard requirement.

## Open enums: resilience to Bukkit enum churn

Bukkit adds and renames enum constants across versions. `Sound`, `Particle` and
`ItemFlag` in particular gain new members almost every release. A `switch` or an
`EnumSet` over such an enum breaks the moment it meets a constant that did not
exist when the code was compiled, or that was renamed out from under it.

SnLib never does that. It resolves these enums by individual `valueOf` calls
wrapped in a catch, so an unknown or renamed constant degrades to a warning and a
fallback instead of throwing. It never uses `switch` or `EnumSet` over them. On
top of that it keeps lenient aliases for constants that Bukkit renamed between
supported versions, resolving either spelling to whichever one the running server
actually has:

- `HIDE_POTION_EFFECTS` maps to and from `HIDE_ADDITIONAL_TOOLTIP`.
- `REDSTONE` maps to and from `DUST`.

This is why, for example, the selection wand's dust particle resolves on both a
1.20.4 server (where it is `REDSTONE`) and a newer server (where it is `DUST`),
with a single warning and a `FLAME` fallback only if neither name resolves.

## `SnCompat.probe`: reflective probing of newer API

Any API added after the 1.20.4 floor - `setMaxStackSize`, the glint override and
similar - is reached through `SnCompat.probe` rather than a direct call. The
probe reflectively checks whether the method exists on the running server. If it
does, SnLib uses it; if it does not (an older server), the feature degrades with
exactly one warning instead of crashing with a `NoSuchMethodError`. SnLib itself
references zero NMS and no packets: it is 100% Paper and Adventure API, and does
not touch `InventoryView`.

{% hint style="info" %}
On its own, `SnLib.jar` fires none of these degradation warnings at startup. They
only appear when a consumer actually exercises a probed API - for example when a
consumer builds an item that sets a custom max stack size on an old server. A
clean SnLib startup log on 1.20.4 is expected.
{% endhint %}

## The semver contract

SnLib's public API is frozen under semantic versioning. Two independent
mechanisms enforce and communicate this: a build-time gate that prevents
accidental breaking changes, and a runtime handshake that lets consumers detect a
too-old installed jar.

### `japicmp`: the additive-only build gate

The build runs the `japicmp` Maven plugin in the `verify` phase, comparing the
current public API against an explicit baseline of `com.sn:snlib:1.0.0` installed
in the local `.m2`. The gate is configured to break the build on any
binary-incompatible modification: you may add new public methods and classes, but
you may not remove or change the signature of existing ones. A missing baseline
also breaks the build, so the comparison can never be silently skipped.

Excluded from the gate are the packages that are explicitly outside the public
API contract:

- `com.sn.lib.**.internal.**` - the `*.internal` packages, which are outside the
  contract and can change freely between releases. Consumers must not depend on
  anything under an `internal` package.
- `com.sn.lib.libs.**` - the shaded and relocated third-party libraries.
- `com.sn.lib.velocity.**` - the Velocity base, kept outside the additive gate
  while it stabilizes (see below).
- The unrelocated shaded drivers (`com.mysql.**`, `org.sqlite.**`) and
  `org.slf4j.**`.

{% hint style="warning" %}
The `internal` package convention is the boundary of the stability promise. Only
the non-`internal` public surface is frozen. If you find yourself importing a
class from a `*.internal` package into your consumer, treat that as a bug: it can
disappear or change in any release.
{% endhint %}

### `SnApi.LEVEL`: the runtime handshake constant

`com.sn.lib.SnApi.LEVEL` is a `public static final int` compile-time constant. It
is incremented by exactly 1 on every release that adds new public Paper API
surface (new public methods or classes). Its history:

- LEVEL 1 = release 1.0.0
- LEVEL 2 = release 1.1.0
- LEVEL 3 = release 1.4.0 - shared multi-plugin releases repo support in the UpdateChecker
- LEVEL 4 = release 1.8.0 - `ItemRegistry.take`/`removeAll`, the SnYml comment write surface
- LEVEL 5 = release 1.10.0 - `SubCommandBuilder.helpVisible`
- LEVEL 6 = release 1.11.0 - `SnItem.itemModel` (1.21.2+ `item_model` component)
- LEVEL 7 = release 1.12.0 - redeemable items, `Args.intMin`/`doubleMin`, k/m/b suffixes
- LEVEL 8 = release 1.13.0 - alias-aware command rendering (`CommandContext.label()`)
- LEVEL 9 = release 1.14.0 - translatable command help (the `commands` lang block)
- LEVEL 10 = release 1.15.0 - owner-owned yml sections (`# sn:extensible`)
- LEVEL 11 = release 1.18.0 - config-driven template placement (`GuiSession.bind(String, Ph...)`)
- LEVEL 12 = release 1.20.0 - runtime layout regions (`regions:` + `GuiSession.bindEach`)
- LEVEL 13 = release 1.21.0 - plugin-supplied stacks in menus (`GuiSession.bind(int, GuiTemplate, ItemStack, Ph...)`, `PhCollector.stack`, `GuiEntry.stack`)
- LEVEL 14 = release 1.22.0 - per-session menu titles (`GuiSession.titlePlaceholders`, the `Gui.open(Player, Ph...)` overloads)
- LEVEL 15 = release 1.23.0 - placeholders on an event thread (`SnPapi.applyHere`, the viewer-aware `SnLang.get(String, Player, Ph...)`)
- LEVEL 16 = release 1.24.0 - a bounded teardown wait (`SnFuture.joinWithin`, `DbConfig.connectTimeoutSeconds`/`socketTimeoutSeconds`)
- LEVEL 17 = release 1.25.0 - placeholders that need no player (`ExpansionBuilder.global`/`globalPrefixed`, `LeaderboardCache.exposePlaceholders`)
- LEVEL 18 = release 1.27.0 - a continuation a caller can chain onto (`SnFuture.chainSync`)
- LEVEL 19 = release 1.28.0 - menus can receive an item (`input:` cells, the `player-inventory:` policy, `ItemOffer` + `GuiSession.onOffer`/`handleOffer`/`isInputSlot`)
- LEVEL 20 = release 1.32.0 - bind-time locals before PAPI (the `SnYml.getString`/`getStringList` overloads taking `Ph...`, read by every appearance field of `SnItem.fromConfig`, so a bound placeholder may sit inside a PAPI token)
- LEVEL 21 = release 1.34.0 - a broadcast about a player (`SnLang.broadcast(String, Player, Ph...)`, which resolves PAPI against the subject once so a per-player token renders for everyone)
- LEVEL 22 = release 1.35.0 - an enum key survives the YAML boolean trap (`SnYml.getEnum(String, Class, Enum)`, which maps the boolean an unquoted `OFF`/`ON`/`YES`/`NO`/`TRUE`/`FALSE` resolves to back onto the constant the owner typed)

The current value is 22. Releases that change only behaviour do not bump it:
1.3.0 removed the experimental SnBridge and added the separate Velocity base,
1.19.0/1.19.1 changed marker and warning behaviour, 1.26.0 changed no public
surface, 1.29.0 added the `HIDE_TOOLTIP` flag name - a value the existing
`flags:` key and `SnItem.flags(List)` already accept, not a new method -
1.30.0 made namespaced custom sound ids play instead of warning, inside the
`SoundUtil` methods that already existed, and 1.31.0 added the `drop-click-*`
keys to the menu click matrix, which are yml the existing `GuiItemDef` readers
pick up with no new public method - none of which grows the Paper
handshake surface. The source of truth is
the history javadoc on `SnApi` itself.

Because `SnApi.LEVEL` is a compile-time constant, javac inlines its literal value
into each consumer's bytecode. A consumer's `requiredApiLevel()` therefore records
the level it was built against. At enable time, `SnPlugin` compares that recorded
level against the level baked into the actually-installed `SnLib.jar`, and if the
installed jar is older, the consumer disables itself cleanly with an update
message rather than failing later with a `NoSuchMethodError`. This is the runtime
counterpart to the build-time `japicmp` gate: `japicmp` stops SnLib from breaking
old consumers, and the handshake stops a new consumer from silently running
against an old jar. See [Quickstart](quickstart.md) for how a consumer wires up
`requiredApiLevel()`.

### The Velocity base is a separate surface

The Velocity base (`com.sn.lib.velocity.*`) is a Velocity-only API kept
deliberately outside both the Paper `SnApi.LEVEL` handshake and the `japicmp`
additive gate while it settles. It has its own, less strict stability guarantees
for now. See [Velocity base](modules/velocity-base.md) for its API.
