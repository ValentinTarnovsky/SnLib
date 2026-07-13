# Contributing

SnLib is now public on GitHub at
[`ValentinTarnovsky/SnLib`](https://github.com/ValentinTarnovsky/SnLib). It is a
solo-maintained project and is open to pull requests, but it does not yet have a
formal contribution process - no issue templates, no CI pipeline and no CLA. This
page documents what actually exists today so you can build and test a change
locally before proposing it.

## Building and testing locally

You need Java 21 (see [Compatibility and versioning](compatibility-and-versioning.md)).
The full local build and test cycle is:

```bash
mvn clean verify
```

`verify` compiles the code, runs the unit test suite, produces the shaded jar and
then runs the `japicmp` API gate. A green `verify` is the bar a change should
clear before it goes up as a pull request.

## The japicmp additive-only gate

The `verify` phase runs `japicmp`, which compares the current public API against
an explicit baseline of `com.sn:snlib:1.0.0`. The gate is additive-only: adding
public methods and classes is fine, but removing or changing the signature of an
existing public member breaks the build. This is how backward compatibility for
consumers is enforced mechanically rather than by review alone.

Two failure modes are worth knowing:

- A **baseline mismatch** - the build reports a binary-incompatible modification -
  means your change removed or altered part of the existing public API. Either the
  change is genuinely additive and you need to restructure it that way, or the API
  break is intentional and belongs in a major version, which is a maintainer
  decision, not something to force through by editing the gate.
- A **missing baseline** breaks the build on purpose, so the comparison is never
  silently skipped. The baseline `com.sn:snlib:1.0.0` must be resolvable from your
  local `.m2`.

If your change intentionally adds public API, remember to increment `SnApi.LEVEL`
by 1, per the policy described in
[Compatibility and versioning](compatibility-and-versioning.md).

## Semver and the `internal` convention

SnLib follows semantic versioning. The public, non-`internal` API is frozen and
guarded by the japicmp gate above. The `*.internal` packages are explicitly
outside that contract and can change freely between releases; do not build a
change that leans on another module's `internal` classes as if they were stable.
The same boundary applies to the shaded `com.sn.lib.libs.**` packages and the
Velocity base (`com.sn.lib.velocity.**`), which is kept outside the additive gate
while it stabilizes.

## Proposing a change

There is no formal workflow yet, so keep it simple and honest: open a pull request
against `ValentinTarnovsky/SnLib` with a clear description of what the change does
and why, and make sure `mvn clean verify` passes locally first. Because the
project is solo-maintained, expect review to be direct and occasional rather than
governed by a process.
