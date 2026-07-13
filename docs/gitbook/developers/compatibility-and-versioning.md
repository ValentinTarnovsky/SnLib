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
| Target | 1.21.8 |
| Compilation baseline | 1.21.1 |
| Unknown future versions | 1.22+ start with one forward-compatibility warning |

The floor is 1.20.4: SnLib runs on any Paper server from there up to the 1.21.8
target. The jar is compiled against the 1.21.1 Paper API (for methods such as
`setMaxStackSize`) but only requires the 1.20.4 runtime, bridging the gap with
reflective probing (below). When SnLib detects a server version it does not know
about - 1.22 and later - it emits a single forward-compatibility warning and
keeps running, rather than hard-failing. The philosophy is that a newer server
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

The current value is 2, and it has stayed at 2 through releases up to 1.3.0
because those releases did not grow the public Paper API surface (1.3.0 removed
the experimental SnBridge and added the separate Velocity base, neither of which
touches the Paper handshake).

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
