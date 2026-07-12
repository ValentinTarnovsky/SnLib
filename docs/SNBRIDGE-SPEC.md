# SnBridge - Design specification (SnLib v1.2)

> Status: Phases A-E IMPLEMENTED (wire core, Paper side, Velocity side, verbs, docs/gate);
> migrations (SnKeyAll, SnCredits) are deferred indefinitely by owner decision and the API
> freeze (Phase H) will not happen without a real migration to stress-test it first.
> This document is the design spec approved on 2026-07-11;
> it gets executed whenever the owner decides. It does not describe existing code (that is what
> SNLIB-DOCS.md is for).
> Origin: multi-agent analysis of SnCredits, SnKeyAll, SnStaffLink and the SnLib architecture
> (4 readers + option design + 3 adversarial critiques: versioning, operations, complexity).

## 0. Decision and scope

**Decision made**: SnLib becomes a universal jar (the same `SnLib-1.2.0.jar` is a Paper plugin
AND a Velocity plugin) that provides:

- **Tier 1 - Typed channels**: proxy<->backend messaging framework that replaces the duplicated
  SnCredits/SnKeyAll stack (~1840 lines of copy-paste codec/listeners/plumbing).
- **Tier 2 - Generic verbs**: SnLib on the backend executes by itself a bounded set of verbs
  (allowlisted commands, messages/sounds/titles, bossbar, actions), so that a simple
  Velocity-only plugin does NOT need its own Paper jar.

**Explicitly out of scope** (with a revisit trigger, see section 13):
- Remote menus verb (proxy-driven GUI).
- Redis / alternative transport / SnSyncMap / SnAckedQueue.
- PAPI round-trip query verb.

**Honest declared limits** (these are not bugs, they are transport physics):
- Plugin messaging ALWAYS travels over a player's connection. An empty backend is unreachable
  in both directions. Paid flows (rewards, purchases) still need DB persistence
  (SnCredits' `pending_commands` pattern, which is kept).
- "Resync on reconnect" means "resync on the first join post-restart". The WARMING state exists
  so that this is visible and manageable, not invisible.
- Verbs are at-most-once. Using them for paid deliveries without own persistence is forbidden.

## 1. Why (analysis findings)

Current stack of each proxy+paper pair (SnCredits ~1400 lines, SnKeyAll ~440):

1. **Wire format via enum `ordinal()` with no handshake**: adding a MessageType in the middle
   shifts every ordinal; mixed proxy/backend versions decode WRONG silently.
2. **`decode()` returns `Map<String,Object>`** with unchecked casts in every consumer.
3. **Zero correlation and no timeouts**: if the proxy does not answer, the GUI never opens and
   nobody notices.
4. **Size caps ignored**: SnCredits' JSON config travels proxy -> backend, which is the capped
   direction of the protocol (~32KB serverbound), and `writeUTF` adds an independent 64KB
   ceiling on top. There is no chunking today: growing the config is a time bomb.
5. `EXECUTE_COMMAND` runs console commands with no whitelist and no envelope authentication;
   security depends solely on the Velocity plugin calling `ForwardResult.handled()` (and if that
   plugin crashes or fails to load, Velocity forwards client bytes straight to the backend).
6. 100% silent drops; hand-parsed JSON (`JsonHelper` string-scanner) on the Paper side.

Immediate consumers: SnCredits, SnKeyAll, SnPlayerCount, ActionEngine `[connect]`.
Natural future ones: SnStaffLink v2 (MySQL polling today), SnGGWave, any new Velocity plugin.

## 2. Distribution: a single jar, two platforms

The same `com.sn:snlib` artifact contains:

- `plugin.yml` (Bukkit, entry `com.sn.lib.SnLibPlugin`, unchanged).
- `velocity-plugin.json` (entry `com.sn.lib.velocity.SnLibVelocity`, id `snlib`).

Deploy identical to the current mental model: `SnLib.jar` in the proxy's `plugins/` just like on
each backend. Velocity plugins declare `"dependencies": [{"id": "snlib"}]` the same way they
declare `depend: [SnLib]` on Paper today (Velocity classloaders delegate between plugins, same
model).

Loading rules:
- Velocity loads classes lazily: the Velocity entry NEVER touches Bukkit-bound packages and vice
  versa.
- Accepted dead weight: the jar ships sqlite/mysql drivers etc. that the proxy never uses.
  Harmless.
- Preconditions verified before implementing: the proxy runs Java 21 (the current velocity
  plugins are already Java 21); Velocity 3.3+.
- bstats: omitted on Velocity in v1.2 (the current service id is bukkit-only). Optional later.

Key advantage over the alternative design (shaded `snbridge-velocity` client): there is NO frozen
copy of the protocol inside each proxy plugin. A framing fix = updating SnLib.jar on proxy and
backends, with no rebuild of the plugins. And the classloader relocation trap disappears.

## 3. Package layout

```
com.sn.lib.bridge.wire       100% neutral core (ZERO Bukkit/Velocity imports):
                             SnBuf, SnWireType<T>, frame, HMAC, chunking, HELLO, codecs.
                             Literally shared by both sides.
com.sn.lib.bridge            public Paper-side API: sn.bridge() accessor, SnBridgeChannel,
                             SnDelivery, SnBridgeState. Bukkit-bound.
com.sn.lib.bridge.internal   Paper plugin-message transport, carrier queue, reassembly,
                             per-namespace demux via TenantRegistry, single listener in
                             ListenerHub, new numbered step in Sn.shutdown()
                             (steps 0-13 today; the bridge adds step 14).
com.sn.lib.velocity          Velocity bootstrap (SnLibVelocity, @Plugin id=snlib) + public
                             proxy API: SnProxy.channel(...)/SnProxyChannel, SnVerbs.
com.sn.lib.velocity.internal Velocity transport, channel registration, per-backend queue,
                             fleet state aggregation.
```

- Everything `*.internal` stays outside the semver contract (existing rule).
- `com.sn.lib.bridge.*` and `com.sn.lib.velocity.*` are born experimental: EXCLUDED from the
  japicmp gate and from `SnApi.LEVEL` until the SnCredits migration is done (so the API does not
  freeze at the moment of maximum ignorance). Mechanism: per-package `<exclude>` patterns in the
  pom, the style already used for `com.sn.lib.**.internal.**` (the exclusion is NOT done via
  annotation). `@SnExperimental` is a NEW purely documentational annotation marking the window.
  Only when freezing: remove the excludes, `SnApi.LEVEL = 3` (2 today) + japicmp baseline.
- New CI gate: test that scans the bytecode of `bridge.wire` and `velocity.*` and fails if a
  reference to `org.bukkit.*` appears (and in `bridge.wire`, `com.velocitypowered.*` neither).

## 4. Channels and namespaces

- One channel per consumer: `snlib:ext/<namespace>` (NEVER `snlib:ext:<ns>`: the second `:` is
  illegal in `NamespacedKey`/`MinecraftChannelIdentifier`).
- Generic verbs: dedicated channel `snlib:bridge`.
- First-claim-wins registration in the shared owner-keyed registry (TenantRegistry): claiming a
  namespace already taken by another tenant is a hard error in `channel()`, not silent fan-out.
- Tenant teardown sweeps its subscriptions (existing per-owner teardown pattern).

## 5. Frame format (versioned at 2 levels)

### Header
```
magic          u8      fixed constant (early garbage detection)
frameVersion   u8      framing version (chunking/correlation/HMAC layout)
flags          u8      bit0 = direction (FLAG_TO_PROXY: set = backend->proxy)
msgId          u32     correlation and chunk reassembly
chunkIndex     u16
chunkCount     u16
hmacTag        16B     HMAC-SHA256 truncated to 16B over (header[0..11) + sessionNonce 8B + body)
```

- **HMAC key**: Velocity's modern forwarding secret, ALREADY present on both sides: on the
  proxy it lives in the `forwarding.secret` file (pointed to by `forwarding-secret-file` in
  `velocity.toml`) and on each backend in `config/paper-global.yml` (`proxies.velocity.secret`).
  Zero new secrets to manage. Implementation note: no public API exposes it, so SnLib reads it
  from disk on both sides (resolving the toml indirection on the proxy).
  Configurable fallback: dedicated secret in `plugins/SnLib/config.yml` in case someday it needs
  to be decoupled from forwarding secret rotation.
- The direction travels as bit0 of the flags byte INSIDE the signed header (no extra byte in the
  HMAC input) and the receiver DEMANDS its expected direction in `FrameCodec.decode`: the HMAC
  makes the flag tamper-proof, and the receiver check is what rejects an authentic frame captured
  and reflected to the other leg (with its own counter, distinct from garbage).
- The 8 session nonce bytes are ALWAYS part of the HMAC input: `HANDSHAKE_NONCE = 0` for
  HELLO/HELLO_ACK and every pre-handshake frame; post-handshake `sessionNonce = nonceBackend XOR
  nonceProxy`, so a frame captured in another session does not verify (anti-replay).
- Frames with an invalid HMAC or received before a valid handshake are DISCARDED with a visible
  counter. This closes at once: spoofing via a direct connection to the backend, and the
  unclaimed-channel hole (if the proxy plugin did not load, Velocity forwards client bytes;
  with HMAC they are discarded garbage, not executed commands).
- Backend->client mirror: SnLib on the proxy SINKS (`ForwardResult.handled()`) every REGISTERED
  snlib channel (each claimed namespace + `snlib:bridge` pre-registered at init + the legacy
  ones from detectLegacy), in both directions, even with the consumer plugin crashed.
  Declared platform limit: Velocity does NOT fire PluginMessageEvent for channels nobody
  registered, so the traffic of an UNCLAIMED snlib channel is forwarded as-is;
  the floor that makes it inert on both ends is the HMAC (garbage without the key), and the
  channel names are announced to clients via minecraft:register (they are not secret).
- Response correlation: flags bit1 (`FLAG_RESPONSE`, signed) marks the frame that RESPONDS to
  the msgId it carries; without the flag, a push whose msgId collides with an in-flight request
  can never be swallowed as its response.

### Body
```
wireId         UTF     stable string per message type (e.g. "sncredits:open_confirm")
msgVersion     u16     message version
bodyLen        u32     LENGTH-PREFIX of the field block
fields         ...     written by the SnWireType encoder
```

- The decoder receives `(SnBuf, int version)`: it can branch by version, and the leftover bytes
  from a newer sender are skipped thanks to the length-prefix (REAL additive evolution).
- Codecs = explicit positional lambdas. NEVER reflection over records: the
  sn-obfuscate/ProGuard pipeline breaks reflection.
- Every `SnWireType` requires a round-trip `selfTest()` that runs in unit tests: field order
  drift between encoder and decoder fails in CI, not in production.
- wireIds: stable strings, NEVER derived from class names, with a "never reuse an ID" ledger in
  this document (section 12).

### Chunking (asymmetric, explicit)
- proxy -> backend: fragments at ~24KB (the real serverbound cap is ~32KB; SnCredits' config
  travels in THIS direction).
- backend -> proxy: up to ~1MB.
- Reassembly buffers: capped per connection and discarded on carrier disconnect/switch
  (correctness + defense against memory DoS from a spoofed client).

## 6. HELLO handshake

On the first available carrier per `(backend, namespace, connection session)`:

1. Companion (backend) sends `HELLO`: supported frameVersion range, the namespace's
   msgsetVersion, SnLib version, its random half of the session nonce (i64), and for
   `snlib:bridge` the verb catalog with each verb's vocabulary version (e.g. the version of
   ActionEngine's tag set).
2. Proxy answers `HELLO_ACK` with its own data, including its half of the nonce. The common
   minimum is negotiated and from then on both sides sign with `sessionNonce = nonceBackend XOR
   nonceProxy`.
3. The pending send queue is flushed STRICTLY after the ACK. No application message ever
   travels without a negotiated handshake.

- Per-namespace state: `WARMING -> READY` with an `onState` callback. The plugin decides what to
  do if interaction arrives before warmup (message to the player, retry, etc.) instead of the
  current silent failure.
- Lifecycle: HELLO is PER carrier CONNECTION; the namespace state is the aggregate of the live
  handshaked connections (READY while at least one remains). A chunked send whose carrier
  disconnects mid-transfer is ABORTED and resolves as `EXPIRED_TTL` on the sending side:
  never silent loss.
- All version constants (frameVersion, per-plugin msgsetVersion, verb vocabularies) are
  generated at build time from a single source with an equality check in CI (real precedent of
  manual drift: SnCredits `@Plugin 1.4.0` vs pom `1.11.1`).

## 7. Messaging semantics (Tier 1)

Three primitives, all at-most-once as the documented floor.

**A single result enum for both tiers** (`SnDeliveryResult`), always TERMINAL:

```
SENT                        delivered to a live connection (Tier 1: ends here; no app-level ack)
DELIVERED                   verbs only: the backend confirmed execution (ACK)
DENIED_BY_ALLOWLIST         verbs only: NACK from the backend
UNSUPPORTED_AT_DESTINATION  verbs only: the backend does not know the verb or its vocabulary
UNSUPPORTED_MSGSET          the HELLO negotiation says the destination does not speak this msgset
EXPIRED_TTL                 died in queue (no carrier, no handshake, or carrier dropped midway)
UNKNOWN_SERVER              the server name does not exist
```

ENQUEUED is not terminal and therefore is NOT an enum value: a send without a carrier or
pre-handshake stays queued (observable via `ch.pending()` and counters) and its future resolves
only when the message leaves (`SENT`) or dies (`EXPIRED_TTL`). Implementation rule: the future
ALWAYS resolves.

1. **Fire-and-forget**: `ch.send(...)`. ALWAYS returns `CompletableFuture<SnDelivery>` with a
   terminal result, never void, never a silent drop. Carrier queue with a TTL per message class
   (config: minutes; commands: seconds; configurable), size cap, `onUndeliverable` callback
   and counters. Turns "it got lost silently" into "it expired and got counted",
   and avoids the new incident of "it ran 20 minutes late when the first player joined".
2. **Request/response**: correlationId (msgId) + configurable timeout. On Paper it returns
   `SnFuture` (existing idiom: `thenSync`/`exceptionally`); on Velocity `CompletableFuture`.
   Replaces the implicit `REQUEST_CONFIG -> SYNC_CONFIG` pairs etc.
3. **Verbs** (Tier 2, section 8): predefined messages served by SnLib itself.

## 8. Generic verbs (Tier 2)

Served by the SnLib backend on `snlib:bridge`. v1.2 catalog (closed; adding a new verb is its
own proposal with a threat model):

| Verb | What it does on the backend | Immediate consumer |
|-------|------------------------|----------------------|
| `console` | `Bukkit.dispatchCommand(console, cmd)` filtered by allowlist | SnKeyAll, SnCredits resend, future ones |
| `message` / `title` / `actionbar` / `sound` | via SnLib's text pipeline and compat | SnKeyAll, SnCredits |
| `bossbar` | glue over the existing `BossBarUtil`: `create(id)` + `show/hide(player, id)` + `setText/setProgress(id, ...)` | SnKeyAll (kills its Paper jar) |
| `actions` | list of action-strings to the existing ActionEngine | generalizes almost everything |
| `heartbeat` | handshake/diagnostics internal | infra |

Hard verb rules:
- **Never void**: every call returns a future with a terminal result from the single
  `SnDeliveryResult` enum (section 7); verbs can additionally resolve `DELIVERED` or
  `DENIED_BY_ALLOWLIST` because they carry an application-level ACK/NACK.
- **Console verb allowlist**: backend-authoritative in `plugins/SnLib/config.yml`, patterns
  ANCHORED per argument (no prefix wildcards: `crates key give <player> vote <int:1..64>`
  yes, `crates key give *` no), rate limit per namespace. Rejections = visible NACK on the
  proxy (rate-limited), not silence.
- **Drift audit**: proxy command that requests and diffs the effective allowlists of all
  backends (sn-deploy's per-server yml merge makes drift the norm; it has to be visible, not a
  ghost incident).
- **At-most-once**: documented and forbidden for paid deliveries without own DB persistence.
- Vocabularies versioned in HELLO: if the proxy sends an action-tag that this backend's
  ActionEngine does not know, it is `UNSUPPORTED_AT_DESTINATION`, not a warn lost in a console.
- **`actions` verb is presentation-only (fail-closed)**: it runs an action list through the
  ActionEngine but ONLY when every line's effective tag (guards stripped) is in a safe set
  (message, broadcastmessage, actionbar, title, sound, close, particle, potion, remove-item,
  the page tags). Any command/op/network tag (`[console]`, `[player]`, `[player-as-op]`,
  `[connect]`) denies the whole verb with `DENIED_BY_ALLOWLIST`, and an unknown tag denies with
  `UNSUPPORTED_AT_DESTINATION`; nothing runs. Command execution goes ONLY through the console
  verb and its anchored allowlist, so `actions` can never bypass it. The verb shares the
  console rate limit.
- **Verbs never lie about the result**: a verb whose target player is offline, whose sound spec
  does not resolve, or whose bar id is unknown answers `FAILED_AT_DESTINATION`; an unknown
  action tag answers `UNSUPPORTED_AT_DESTINATION`; a denied console/actions answers
  `DENIED_BY_ALLOWLIST`. A backend NACK (older SnLib, throwing responder) surfaces on the proxy
  as the matching typed result, never as a timeout.
- **Bossbar verbs are per-player**: bars are keyed by `snbridge/<player>/<barId>`, so a shared
  barId never evicts another player's bar; `hide` unregisters the bar (ids do not accumulate)
  and works even if the player already left.

## 9. API sketch

### Shared records (common module of the plugin, zero platform imports)
```java
public record OpenConfirm(UUID player, String itemId, double price) {
  public static final SnWireType<OpenConfirm> TYPE = SnWireType.of(
      "sncredits:open_confirm",   // stable wireId, in the ledger, never reused
      2,                          // current msgVersion
      (buf, m) -> { buf.uuid(m.player()); buf.str(m.itemId()); buf.f64(m.price()); },
      (buf, version) -> {
        UUID p = buf.uuid(); String item = buf.str();
        double price = version >= 2 ? buf.f64() : 0.0;  // real additive via length-prefix
        return new OpenConfirm(p, item, price);
      });
}
// in tests: OpenConfirm.TYPE.selfTest(new OpenConfirm(uuid, "key_vote", 500.0));
```

### Paper side (thin companion = normal consumer SnPlugin)
```java
public final class SnCreditsBridge extends SnPlugin {
  // final post-freeze state; during the experimental window: return 2
  @Override protected int requiredApiLevel() { return 3; }
  @Override protected void onInnerEnable() {   // real SnPlugin hook (start(Sn) does not exist)
    Sn sn = sn();
    SnBridgeChannel ch = sn.bridge().channel("sncredits", /*msgset*/ 3);
    ch.register(OpenConfirm.TYPE, ShopClick.TYPE, SyncBalance.TYPE, SyncConfig.TYPE);

    ch.on(OpenConfirm.TYPE, (player, msg) ->      // handler already on main thread via SnScheduler
        sn.guis().open(player, "confirm", Map.of("item", msg.itemId())));
    ch.onState(state -> { if (state == SnBridgeState.READY) refreshOpenGuis(); });
    ch.detectLegacy("sncredits:main");            // migration window: logs "old proxy"

    ch.send(player, new ShopClick(player.getUniqueId(), cat, item));
    ch.request(RequestConfig.TYPE, RequestConfig.INSTANCE, SyncConfig.TYPE, Duration.ofSeconds(5))
      .thenSync(cfg -> configCache.accept(cfg))
      .exceptionally(t -> { getLogger().warning("config timeout"); return null; });
  }
}
```

### Velocity side (proxy plugin, SnLib as a plugin dependency, NO shading)

The consumer's `velocity-plugin.json` declares `"dependencies": [{ "id": "snlib" }]`.
These are the REAL entry points (`SnProxy.channel`/`SnProxyChannel`/`SnVerbs`/`SnDelivery`);
the earlier `SnProxy.init`/`SnProxyBridge`/`SnVerbResult` sketch was never implemented.

```java
@Plugin(id = "sncredits", dependencies = {@Dependency(id = "snlib")})
public final class SnCreditsVelocity {
  @Subscribe void onInit(ProxyInitializeEvent e) {
    SnProxyChannel bridge = SnProxy.channel(this, "sncredits", /*msgset*/ 3);
    bridge.register(OpenConfirm.TYPE, ShopClick.TYPE, SyncBalance.TYPE, SyncConfig.TYPE);

    bridge.on(ShopClick.TYPE, (src, msg) -> shop.handleClick(src.player(), msg));
    bridge.respond(RequestConfig.TYPE, SyncConfig.TYPE,
        (src, req) -> new SyncConfig(configBlob)); // reply chunked at ~24KB toward the backend

    bridge.to("gens").send(OpenConfirm.TYPE, new OpenConfirm(uuid, itemId, 500.0),
            SnSendOpts.ttl(Duration.ofSeconds(10)))
        .thenAccept(d -> { if (!d.ok()) log.warn("gens: " + d.result()); }); // SnDelivery

    // Verbs (Tier 2): no own Paper jar on the other side. Explicit wire type + record.
    SnVerbs verbs = SnProxy.verbs();
    verbs.on("gens").console("crates key give " + name + " vote 1")
        .thenAccept(d -> { if (!d.ok()) log.warn("gens console: " + d); }); // SnDelivery, not SnVerbResult
    verbs.on("work").bossbar(playerUuid, "keyall",
        bar -> bar.text("<red>KeyAll in 5m").progress(0.5f)); // bossbar takes a barId

    bridge.capabilities("work").ifPresentOrElse(         // Optional<SnBackendInfo>
        c -> { if (c.msgset() < 3) log.warn("work runs msgset " + c.msgset()); },
        () -> log.warn("work: no handshake (empty backend or old SnLib)"));
    log.info(SnProxy.statusReport());  // per-backend table for namespaces with a live session
  }
}
```

> Reverse request/response (Velocity requests, Paper answers) is available:
> `SnProxyChannel.Destination.request(...)` on the proxy and `SnBridgeChannel.respond(...)`
> on Paper. The Paper-requests-Velocity-answers direction uses `SnBridgeChannel.request(...)`
> and `SnProxyChannel.respond(...)`.

## 10. Diagnostics (a 1.0 deliverable, not a follow-up)

For an operator alone with 8 Pterodactyl consoles, "log loudly" is operationally silence.

- Backend: `/snlib bridge status` -> handshake state per namespace, negotiated versions, queue
  depth, drops/expired, NACKs, frames with invalid HMAC.
- Proxy: `/snlibv status` (or a per-plugin subcommand) -> aggregated table per backend
  (frame/msgset/state/queue/drops) via `SnProxy.statusReport()`.
- Proxy: `/snlibv allowlist-audit` -> diff of the effective allowlists across backends.
- Rate-limited NACKs visible on the proxy side.

## 11. New CI gates

japicmp protects not a single byte of the wire. The following are added:

1. Corpus of golden byte fixtures per message per version (current encode == fixture;
   decode of old fixtures == expected values).
2. Mandatory `selfTest()` per `SnWireType` (round-trip in unit tests).
3. Ledger of used wireIds (section 12) with a test that verifies no-reuse.
4. Version constants generated at build time from a single source + equality check.
5. Platform purity test: `bridge.wire` with no Bukkit NOR Velocity references;
   `com.sn.lib.velocity.*` with no Bukkit references.
6. Extended smoke: Paper 1.20.4/1.21.8 (existing) + Velocity startup with a dummy consumer.

## 12. Wire rules and ledger

Rules (checklist for every protocol change):
- A wireId is used ONCE in history; deprecating = stop emitting, never reassign.
- Fields are ADDITIVE only, at the end of the body; never reorder or change the type of an
  existing field.
- Incompatible change = new wireId (`sncredits:open_confirm_v2` is valid as a new id).
- Every new message lands with a golden fixture + selfTest in the same commit.

WireId ledger (filled in during implementation):
```
(infra reserved) snlib:hello, snlib:hello_ack, snlib:nack, snlib:heartbeat,
                 snlib:verb/console, snlib:verb/message, snlib:verb/title,
                 snlib:verb/actionbar, snlib:verb/sound, snlib:verb/bossbar,
                 snlib:verb/actions, snlib:verb/ack, snlib:verb/allowlist_req,
                 snlib:verb/allowlist
```

## 13. Deferred items with an explicit trigger

| Deferred | Trigger to revisit |
|----------|------------------------|
| Remote menus verb | probably never: click round-trips, double-purchase races, laggy UI. GUIs stay in thin Paper consumers |
| Redis / transport SPI | a second proxy, a cross-network feature, a web dashboard, or a measured incident rate from empty backends. The channel API no longer exposes plugin-messaging types in public signatures, so the seat is reserved for free |
| Acked queue (at-least-once) | only together with idempotency keys + persistent dedupe on the backend; without that, "lost purchase" becomes "duplicated purchase" |
| PAPI query verb | two concrete consumers asking for it |
| bstats on Velocity | when a velocity service id exists |

## 14. Migrations

No flag day: old and new channels coexist. `detectLegacy` makes the NEW side also listen on the
legacy channel and log "outdated counterpart" when it sees old traffic. The old side is old
code: it stays mute (it cannot warn); that is why detection lives on the new side of EACH half
of the migration, and requires the legacy side to actually emit traffic.

**SnKeyAll (first: small, validates the API with freedom to break it)**
- Delete `common/protocol` + the `messaging` packages on both sides (~440 lines).
- Define ~7 typed records; the proxy side uses a typed channel + verbs.
- SnKeyAll's Paper jar DISAPPEARS: bossbar + commands + messages are verbs.
- Deploy: new SnLib.jar on the backends where KeyAll runs (next manual restart cycle),
  then the new velocity jar. Every API finding is fixed freely (still experimental).

**SnCredits (second: the big one, BEFORE freezing the API)**
- Mapping: `REQUEST_*/SYNC_*` -> request/response; `OPEN_*/UPDATE_*` -> typed fire-and-forget;
  `buildConfigJson` + `JsonHelper` -> chunked `SyncConfig` record (kills the 64KB risk).
- The Paper jar remains a THIN consumer (records + GUI handlers); deletes ~1400 lines of stack.
- The PAPI caches remain plugin-owned ConcurrentHashMaps fed by typed handlers.
- `pending_commands` STAYS (the only path for paid flows with an empty backend), with a
  targeted fix: select+delete in a single transaction.
- Budget for timing differences (config warmup, first join post-restart) covered by the
  WARMING state.

**Closing**
- Freeze: `SnApi.LEVEL = 3`, new japicmp baseline, remove `@SnExperimental`.
- Internal adoption: ActionEngine `[connect]` and SnPlayerCount on top of the shared infra.
- SnStaffLink v2 (MySQL polling -> typed push channel) as a future post-freeze consumer.

## 15. Phased execution plan

Realistic solo-dev estimate: **7-9 weeks** calendar time (the repo's historical multiplier
included; v1.1 took 22 steps). During that span the other plugins receive no maintenance.

- **Phase 0 (done)**: spec + runbook written BEFORE implementing (this doc + SNBRIDGE-RUNBOOK.md).
  Forcing function: if the runbook is embarrassing, the design is wrong.
- **Phase A - wire core (done)**: complete `bridge.wire` (SnBuf, frame, HMAC, chunking,
  HELLO, codecs) + selfTest + golden fixtures + ledger + platform purity test.
- **Phase B - Paper side (done)**: `sn.bridge()`, tenancy/teardown, carrier queue with TTL/cap/
  counters, WARMING/READY, `detectLegacy`, `/snlib bridge status`, new step in `Sn.shutdown()`.
- **Phase C - Velocity bootstrap (done)**: `SnLibVelocity` + `velocity-plugin.json`,
  `SnProxy.channel(...)`, proxy-side typed channels, per-backend queue, `capabilities()`,
  `statusReport()`, Velocity smoke.
- **Phase D - verbs (done)**: console+anchored allowlist+rate limit+NACKs, message/title/
  actionbar/sound, bossbar, actions, fail-closed screening of dangerous action tags,
  the programmatic `SnVerbs.allowlist()` audit (a dedicated `/snlibv allowlist-audit`
  subcommand is deferred, see SNLIB-DOCS 19.8).
- **Phase E - docs and gate (done)**: SnBridge section 19 in SNLIB-DOCS, golden spec
  `docs/bridge-example.yml`, full suite + shade + japicmp, plus the defect fixes surfaced by
  an independent final-check (two exhaustive Paper/Velocity test plugins).
- **Phase F - SnKeyAll migration**: deferred indefinitely by owner decision (no plugin
  migration scheduled).
- **Phase G - SnCredits migration**: deferred indefinitely by owner decision.
- **Phase H - freeze**: API level 3, japicmp baseline, final release. NOT scheduled: the
  spec's own gate requires a real migration (F or G) to stress-test the API before it
  freezes forever under japicmp; skipping F/G means SnBridge stays `@SnExperimental`
  indefinitely, by design, until a real migration happens.

## 16. Resolved and remaining decisions

Resolved (2026-07-11):
- Distribution: a single universal jar (Paper + Velocity), no shaded client artifact. RESOLVED.
- v1.2 scope: Tier 1 + Tier 2 (basic verbs, no menus). RESOLVED.
- Experimental window outside japicmp until SnCredits migrates. ACCEPTED implicitly by the
  chosen scope; confirm when starting Phase A.
- HMAC key: Velocity's forwarding secret (zero new secrets) with a fallback to a dedicated
  secret in config. Recommended default; confirm when starting Phase A.

- Proxy command: `/snlibv` with `status` and `allowlist-audit` subcommands. RESOLVED (the
  runbook already operates with those names).

Remaining (decided during implementation, they do not block the spec):
- Default TTLs per message class.
- Exact format of `bridge-example.yml` (golden spec).
- Useful implementation note: `SnFuture` lives in `com.sn.lib.db` and already exposes
  `wrap(Sn, CompletableFuture)`, the natural path for the bridge to return SnFuture on Paper.
