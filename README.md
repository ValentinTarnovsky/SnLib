# SnLib

Common library for the Sn plugins, packaged as a STANDALONE PLUGIN. A single
`SnLib.jar` in `plugins/` provides yml, menus, items, commands, database,
PAPI, lang, debug and the rest of the modules to every consumer, with a single
development style and zero repeated dependencies.

## Standalone hard-depend model

- `SnLib.jar` is DUAL-PLATFORM: the exact same jar is a Paper plugin
  (`plugin.yml`) and a Velocity plugin (`velocity-plugin.json`, entry
  `SnLibVelocity`). Install it in `plugins/` of every Paper backend as usual,
  and ALSO in `plugins/` of the Velocity proxy for consumers that use
  SnBridge (see below); a Paper-only consumer never needs the proxy side.
- `SnLib.jar` is installed ONCE per server (with `load: STARTUP`).
- Each consumer declares `depend: [SnLib]` in its `plugin.yml` and compiles
  against `com.sn:snlib` with `provided` scope. Nothing from SnLib is shaded
  into the consumer.
- `SnLib.jar` is attached as an asset on every release of every consumer, so
  the user always installs the required API level.
- Hard rule: updating SnLib.jar requires a server restart. `/snlib reload`
  NEVER reloads classes; never hot-reload the lib (classloader shared with
  ~57 consumers).
- Distribution ONLY via local `.m2`: `mvn install -f <path>/SnLib/pom.xml`
  publishes `com.sn:snlib`. JitPack NOT supported (the repo is private).

## Entrypoint: mandatory SnPlugin + requiredApiLevel()

The ONLY initialization path is extending `com.sn.lib.SnPlugin` (the `SnLib`
init is package-private; there is no other way):

```java
public final class MyPlugin extends SnPlugin {
    @Override protected int requiredApiLevel() { return SnApi.LEVEL; }
    @Override protected SnSpec buildSpec() {
        return SnSpec.builder()
                .config("config.yml")   // managed yml + update-configs
                .lang()                 // lang/messages_<code>.yml
                .guis()                 // guis/ folder, one gui per file
                .items("items.yml")     // physical items via YML (optional)
                .db()                   // SQLite/MySQL via Hikari
                .debugCommand()         // "debug" sub on the plugin's own roots
                .build();
    }
    @Override protected void onInnerEnable() {
        Sn sn = sn();  // context: sn.yml(), sn.guis(), sn.items(), ...
    }
    @Override protected void onInnerDisable() {
        // optional; the lib's ordered teardown runs afterwards, on its own
    }
}
```

`requiredApiLevel()` returns `SnApi.LEVEL` inlined into the consumer's
bytecode at compile time: if the installed `SnLib.jar` is older than the
required level, the consumer disables itself cleanly with a message and a
download URL, without `NoSuchMethodError` or `NoClassDefFoundError`. An
accessor for a module not declared in the `SnSpec` throws
`UnsupportedOperationException` naming the missing builder.

## yml module (SnYml + YmlManager)

All YML reading goes through `SnYml`: tabs in indentation are fixed with ONE
warning (block scalars untouched), typed getters with a default and a WARN on
invalid values (never a stacktrace), local placeholders + PAPI resolved per
getter.

```java
SnYml cfg = sn.yml().config();                  // the spec's config
SnYml shop = sn.yml().managed("shop.yml");      // merge-always against the jar
SnYml seed = sn.yml().seedOnly("presets.yml");  // only copied if missing
SnYml data = sn.yml().data("state.yml");        // data: never merged
int max = cfg.getInt("max-uses", 10);           // non-numeric -> 10 + WARN
String s = cfg.getString("msg", "hi", viewer);  // overload with viewer (PAPI)
cfg.onReload(() -> recache());
cfg.set("last-run", now); cfg.save();           // async with coalescing
```

- Without a viewer, PAPI resolves with a null player (`%server_online%`
  works); with a viewer it resolves per-player; in async the PAPI tokens stay
  untouched and only local placeholders apply.
- `save()` is async with coalescing (at most one pending write per file);
  during teardown it switches to SYNCHRONOUS writes and `flush()` drains
  whatever is pending.

## Config auto-update (YamlUpdater, ALWAYS-MERGE)

No `config-version`: on every startup the jar resource is structurally
compared against the file on disk and the missing keys are inserted at their
anchored position, preserving user values, extra keys and comments.

- Pre-merge backup `old-<name>-<timestamp>.yml` keep-last-3, ONLY if there is
  something to insert.
- Corrupt YML -> moved to `<name>.backup-N`, regenerated from the jar plus a
  WARN; never a crash.
- Master boolean `update-configs: true` in the consumer's config; when false
  it counts the missing keys and WARNs without touching anything. The
  consumer's own config is EXEMPT from the gate (it is always merged so the
  key itself can arrive via merge).
- Opt-in prune via `sn.yml().managedPruning(path)`: deletes keys absent from
  the resource; the default merge never deletes.

## text module (SnText: MiniMessage + [small] + [rgb] + [center])

Pipeline with a FIXED ORDER: locals -> PAPI -> `[small]` -> `[rgb]` -> legacy
color -> `[center]` -> MiniMessage render to Component.

- `[small]` (v1.1) substitutes a-z and A-Z with small caps glyphs (1:1 mapping
  per char, accented vowels de-accented, the enye keeps its default glyph);
  digits, symbols, color codes and MiniMessage tags pass through untouched.
  Programmatic use without the tag: `SnText.smallCaps(String)`.
- `[rgb]` interpolates a per-character gradient over 7 fixed anchors
  (`#F300F3,#5555FF,#55FFFF,#55FF55,#FCFF21,#FF9B00,#FF5327`); it overrides
  pre-existing COLOR codes and PRESERVES formatting (`&l &o &n &m &k`).
- `[center]` centers to 154px by measuring the already-colored legacy string
  (small caps glyphs measure with their own widths); the three prefix tags are
  composable in ANY order.
- Legacy `&a` / `&#RRGGBB` and MiniMessage tags render together on the same
  line. `SnText.color(String)`, `mini`, `colorLegacy`, `colorList`.

## menus module (GuiManager, guis/ folder)

One GUI per file in `guis/`, golden spec `docs/menu-example.yml`: any field of
the spec ALREADY works without plugin code.

```java
Gui shop = sn.guis().get("shop");     // guis/shop.yml
shop.open(player);                    // one GuiSession + Inventory PER VIEWER
GuiSession s = shop.session(player);
s.bind(13, shop.template("offer"), Ph.of("price", 100));
s.bindPaged("entry", data, slots, (ph, item) -> ...);  // pagination: true
sn.guis().registerAction("my-tag", (ctx) -> ...);      // [custom] action
```

- `pagination: true` is OPT-IN per menu: real per-player page state (the same
  GUI serves N players on different pages). When `false`, page actions are
  no-ops with a debug note and `bindPaged` WARNs once. Without a live
  `bindPaged` the plugin can declare the total via `GuiSession.setTotalPages(n)`
  (v1.1) so the next nav gets disabled.
- ASCII layout (v1.1): `layout:` at menu level (1-6 rows of up to 9 chars over
  the chest grid), `key:` per item as an alternative to `slots:` and
  `paged-key:` as the target of `bindPaged(String templateId, ...)` without an
  `int[]`. `GuiMask` exposes the same geometry via API.
- Per-click matrix (v1.1): `right/left/shift-right/shift-left/middle` x
  `*-click-actions` / `*-click-requirements` / `*-click-deny-actions` with
  specific-over-generic resolution and fallback to `click-actions`;
  `strict-clicks: true` (opt-in per menu, default false) discards clicks
  outside the 4 basic mouse ones that have no specific list covering them.
- `close-actions:` and `close-sound:` (v1.1): run on natural close and on
  `[close]`, NEVER on page changes nor on teardown via sweep/disable.
- 7-vector NBT anti-theft + unconditionally cancelled `COLLECT_TO_CURSOR`
  (anti double-click stacking) + `ItemSpawnEvent` catch-all: a GUI item never
  circulates.
- On reload or disable the consumer's open GUIs are closed natively (no
  `ClassCastException`); those of other consumers remain untouched.

## items module (ItemDef + ItemRegistry)

Physical items via YML (golden spec `docs/item-example.yml`) or 100%
programmatic via `ItemDef.builder()` with no file at all.

```java
sn.items().register("wand", ItemDef.builder()
        .item(new SnItem(Material.BLAZE_ROD).name("[rgb]&lWand").glow())
        .locked().noDrop().obtainVia(ObtainMode.COMMAND_ONLY)
        .onRightClick((player, stack) -> ...)
        .build());
sn.items().give(player, "wand", 1);
```

- 12 interact-action variants (right/left x plain/shift/block/air plus the 4
  shift-positional ones from v1.1), with an optional Java callback per variant
  and a `shift-overrides-generic` flag (default true: on shift, the
  shift-positional variant with behavior replaces the plain positional one).
- `locked`: none of the 7 vectors extracts the item; the real displaced item
  is restored on quit and shutdown with a default-on write-through backup
  (survives a crash without `onDisable`). Mobs picking up registered items ->
  pickup cancelled.
- Custom durability (max, damage-per-use, break-actions, lore-format),
  held-effects (mainhand/offhand/armor), recipes (shaped/shapeless/cooking/
  stonecutting), keep-on-death, per-item cooldown. Programmatic damage
  `sn.items().damage(player, stack, amount)` (v1.1) with break-actions and
  removal on reaching 0; deny/break-actions run with the real ClickType and
  surface of the interact.
- `SnItem` (v1.1): `skull-owner:` / `skullOwner(String)` (player head via
  UUID or cached name, never a blocking lookup), `attributes:` /
  `attribute(...)` (modifiers with lenient resolution and a UUID fallback on
  1.20.4) and `damage:` / `damage(int)` (clamped vanilla durability).

## commands module (SnCommands)

Root/sub tree, EVERYTHING tab-completable with example/actual value and GATED
BY PERMISSION: a sub without permission is invisible in tab and in help.

```java
sn.commands().root("shop")
        .permission("shop.use")
        .sub("give")
            .permission("shop.admin.give")
            .arg("player", Args.onlinePlayer())
            .arg("amount", Args.intRange(1, 64))
            .executes(ctx -> ...)
        .and()
        .register();   // reload and help by default; snlib.* keys merged
```

- Reload-safe registration per Plugin owner: when the consumer is disabled its
  commands are unregistered and `player.updateCommands()` clears the ghosts.
- The default `reload` delegates to the ReloadManager; `debugCommand()` adds
  the `debug` sub.

## db module (SnDb: SQLite/MySQL via Hikari)

```java
sn.db().bootstrap(Schema.table("players", ...)).orDisablePlugin();
sn.db().query("SELECT ...", st -> st.setString(1, id), rs -> ...)
       .thenSync(result -> ...);          // hop to main with isEnabled guard
sn.db().update("INSERT ...", st -> ...);  // never on the main thread
PlayerDataCache<Stats> cache = sn.db().playerCache(loader, saver);
```

- SQLite: pool=1 + WAL + busy_timeout; MySQL: classic Hikari pool. Drivers
  shaded WITHOUT relocation (JNI/binaries), a SINGLE copy on the server.
- `join()` only allowed in bootstrap/teardown; shutdown joins the writes and
  calls `shutdownNow` after a timeout.

## papi module (SnPapi)

- `sn.papi().apply(viewer, text)`: with PlaceholderAPI absent it returns the
  string UNTOUCHED, without `NoClassDefFoundError` (hook in an isolated class).
- `applyOnMain` (v1.1, String and List variants): resolves PAPI with a hop to
  the main thread from async and returns an `SnFuture`; fail-open on disable
  (text untouched).
- Declarative expansions with `persist true`:
  `sn.papi().expansion("shop").resolver((player, params) -> ...).register()`.
- Reactive hook: if PlaceholderAPI is enabled/disabled live, the bridge
  activates/invalidates itself.

## lang module (SnLang)

- `lang/messages_<code>.yml` with merge-always and per-key fallback to `en` +
  WARN once; shared `snlib.*` keys (no-permission, player-only, usage...)
  merged into every language.
- `sn.lang().send(player, "shop.bought", Ph.of("item", name))`, `broadcast`,
  `actionbar`, `title` (format `title;subtitle;fadeIn;stay;fadeOut`),
  `get`/`getList` for Components; everything through the SnText pipeline.
- Persistent actionbar (v1.1): `actionbar(player, key, Duration, phs)` resends
  every 40 ticks during the hold, replaces the previous hold of the same
  (context, player), clears with `Component.empty()` on expiry and cleans up
  on quit.

## debug module (SnDebug)

- Runtime toggle without a restart: `/command debug` (with `debugCommand()`),
  persisted in the config if there is yml; categories and lazy `Supplier` to
  avoid building expensive strings with debug off:
  `sn.debug().log(() -> "state=" + expensive())`.
- Real levels (v1.1): OFF < INFO < DEBUG < TRACE ladder with `info(...)`,
  `trace(...)` and `tracing()`.

## Scheduler (SnScheduler, Folia-aware)

- `sync/async/syncLater/asyncLater/timer/timerAsync/supplyAsync/thenSync` with
  `TaskHandle.cancel()`; `thenSync` hops to the main thread with a
  `plugin.isEnabled()` guard.
- HONEST Folia claim: detection + no-crash (global/region schedulers); the
  GUI/items modules are validated only on Paper.

## Actions and Requirements (ActionEngine + RequirementEngine)

- `[tag] argument` lines in YML: `[player]`, `[player-as-op]`, `[console]`,
  `[message]`, `[sound]`, `[close]`, `[open]`, `[connect]`,
  `[broadcastmessage]`, `[actionbar]`, `[title]`, click-type filters,
  `[next-page]`/`[previous-page]`/`[set-page]`/`[refresh-page]`,
  `[refresh-menu]`, `[particle]`, `[potion]`, `[remove-item]` and custom tags
  via `sn.actions().register("tag", handler)`.
- Click guards (v1.1, full matrix documented in the specs): exact
  `[right-click-only]` / `[left-click-only]`, generic `[click=TYPE,...]`
  against the ClickType enum (invalid name = WARN-once and the guard FAILS),
  sugar `[middle-click]` / `[double-click]` / `[drop-click]` / `[number-key]`
  / `[swap-offhand]` and positional `[click-block]` / `[click-air]`
  (ClickSurface; in a GUI they always skip the line). The inclusive
  `[right-click]`/`[left-click]` keep their historical semantics ([left-click]
  passes with DOUBLE_CLICK and CREATIVE).
- `[particle]` accepts `key=value` options at the end (color=, size=, to=,
  block=, item=) and `[remove-item]` accepts selectors
  (`[remove-item] [n] [offhand|id:<item-id>|MATERIAL]`) (v1.1).
- Requirements: `%placeholder% > 0 && %placeholder% < 10`, `=`, `!=`, `>=`,
  `<=`, over PAPI or local placeholders; `view-requirements`,
  `click-requirements`, `interact-requirements` + `deny-actions`. Since v1.1
  the parser is a recursive descent with parentheses and quoting
  (`'...'`/`"..."`), fail-open policy untouched.

## Cooldowns, Economy and utils

- `sn.cooldowns().tryUse(uuid, "kit", Duration.ofMinutes(5))` without boxing;
  non-expired entries survive relogs BY DESIGN; session categories via
  `registerSessionCategory`.
- `sn.economy()`: Vault when present, otherwise a configurable command backend
  (`useCommandBackend(give, take, balancePlaceholder)`); `getBalance`, `give`,
  `tryTake` async-safe.
- Pure utils: `SlotParser` (mixed ranges), `TimeUtil` (`1d 2h 30m 15s`),
  `NumberFormatter` (K/M/B/T/Qa/Qi + inverse parse + `formatComma` v1.1),
  `LocationSerializer`, `WeightedRandomPool`, `Experience`, `MathUtil` (fair
  rounding, `convertToRoman`), `Page<T>`. Bukkit: `SoundUtil` (lenient ids),
  `HeadUtil` (base64/basehead/URL, bounded LRU cache; `fromPlayer` /
  `applyOwner` by OfflinePlayer v1.1), `TagIo` (PDC per owner), `InvUtil`.
- v1.1: `ArmourUtil` (`slotOf`/`isArmour`/`isWearingFullSet`), `LocationUtil`
  (world-aware `inCuboid` delegating to `Cuboid`, `distance2d`,
  `distance2dSquared`, `distanceToBoxSquared`) and `PlayerLookup.fetchUuid`
  (async lookup against the Mojang API with a bounded LRU cache that stores
  misses and dedupes in-flight requests).

## Custom events

- `SnArmourEquipEvent`: armor equip/unequip via any vector (8 methods),
  binding cancellation when the source allows it.
- `SnChunkMoveEvent` (v1.1): chunk crossing via movement
  (fromLocation/toLocation/fromChunk/toChunk); cancelling it cancels the
  source `PlayerMoveEvent`. Movement only: teleports and joins do not emit it.
- `SnSelectionCompleteEvent` (v1.1): completed cuboid selection (cancellable);
  see the region module.

## Bossbars, Holograms, Cron, Leaderboards, Discord

- `sn.bossbars().create("raid").text("[rgb]Raid").progress(1f).build()` +
  `show/hide/setText/setProgress/timer`; pure Adventure (zero packets),
  auto-hide on quit and on teardown.
- `sn.holograms().spawn("top", loc, lines)`: real TextDisplay entities
  (zero NMS), `setLines`, per-player visibility, optional PAPI refresh;
  orphans purged on chunk-load and at startup via the PDC mark
  `snlib_hologram`.
- `sn.cron().schedule("payout", "0 4 * * *", task)`: 5-field cron subset +
  shortcuts `daily 04:00` / `hourly :30`, DST-safe via ZonedDateTime,
  persistable `catchUp`.
- `sn.leaderboards().register("kills", Duration.ofMinutes(5), query)`:
  immutable snapshot with an atomic swap, lock-free `getTop/positionOf/valueOf`,
  opt-in `top_<id>_<n>_name/value` and `pos_<id>` placeholders.
- `sn.discord().message(url).content("...").embed(...).send()`: async POST
  with `java.net.http.HttpClient`, FIFO queue honoring `Retry-After`;
  best-effort `drain()` on teardown.

## UpdateChecker (v1.1, notify-only for consumers)

Update-check module FOR the consumer plugins (not for SnLib itself):
each consumer points it at ITS OWN GitHub repo and gets notified when there
is a release newer than the installed version.

```java
protected SnSpec buildSpec() {
    return SnSpec.builder()
            .config("config.yml")
            .updates("owner/repo")   // fully opt-in
            .build();
}
// or explicit, without a spec:
sn.updates().checkNow("owner/repo");
```

- Strict and permanent NOTIFY-ONLY guarantee: it NEVER downloads jars nor
  auto-swaps anything; the only outputs are an INFO in console and a join
  notice to players with the `<plugin>.admin.update` permission.
- Check on enable (+60s) and every 6 hours with the JDK `HttpClient` (5s/10s
  timeouts); comparison via `SemverComparator`; 403/404/network down =
  WARN-once per repo and then silence.
- PRIVATE GitHub repos: optional read-only token in the consumer config key
  `update-check.token` (read on every check, never logged).

## Region: cuboid selection (v1.1)

Module `com.sn.lib.region` (generalized port of the SnGens Admin Wand):
visual cuboid selections for any consumer, always available via
`sn.selections()` (100% programmatic, no spec gate).

```java
SelectionSpec spec = SelectionSpec.builder("arena")
        .permission("myplugin.wand")
        .onSelect(cuboid -> arenas.saveRegion(cuboid))
        .build();
sn.selections().giveWand(player, spec);   // or createWand(spec)
```

- `Cuboid`: immutable, thread-safe block cuboid (normalized corners,
  inclusive edges, `contains`/`intersects`/`expand`/`forEach`/`size` in long,
  round-trip serialization `world;x;y;z;x;y;z`).
- Physical wand tagged via PDC (`SnItem` template or BLAZE_ROD fallback): left
  click = pos1, right click = pos2; on completion it fires
  `SnSelectionCompleteEvent` (cancellable) and the `onSelect` callback.
- Edge rendering via particles with a budget (`particle-budget`,
  `render-distance`, `max-render-volume`), OWNER_ONLY or WORLD visibility,
  session timeout and configurable volume limits.
- Golden spec `docs/selection-example.yml` (optional): the YML section is
  loaded with `SelectionSpec.fromConfig(...)` and composed with `.toBuilder()
  .onSelect(...)`; the module works with zero YML.

## SnBridge: cross-server messaging (v1.2, experimental)

The SAME `SnLib.jar` is ALSO a Velocity plugin (`velocity-plugin.json`, entry
`SnLibVelocity`): drop it in the proxy's `plugins/` exactly as on every Paper
backend, and it hosts SnBridge, typed proxy<->backend messaging over plugin
messaging (HMAC-authenticated, chunked, with a HELLO handshake).

```java
// Paper backend consumer
SnBridgeChannel ch = sn.bridge().channel("myns", /*msgset*/ 1);
ch.register(MyMessage.TYPE);
ch.on(MyMessage.TYPE, (player, msg) -> ...);

// Velocity proxy consumer (velocity-plugin.json: "dependencies": [{"id":"snlib"}])
SnProxyChannel bridge = SnProxy.channel(this, "myns", /*msgset*/ 1);
bridge.to("gens").send(MyMessage.TYPE, new MyMessage(...))
    .thenAccept(d -> { if (!d.ok()) log.warn("gens: " + d); });

// Tier 2: generic verbs SnLib itself runs on the backend, no Paper jar needed
SnProxy.verbs().on("gens").console("crates key give " + name + " vote 1");
```

- **Experimental**: `com.sn.lib.bridge.*` and `com.sn.lib.velocity.*` are
  `@SnExperimental`, outside the japicmp gate and outside `SnApi.LEVEL` until a
  real migration stress-tests the API and it freezes. Not scheduled yet.
- Diagnostics: `/snlib bridge status` on the backend, `/snlibv status` on the
  proxy.
- Full reference: `docs/SNLIB-DOCS.md` section 19. Design: `docs/SNBRIDGE-SPEC.md`.
  Operator runbook: `docs/SNBRIDGE-RUNBOOK.md`. Golden config:
  `docs/bridge-example.yml`.

## Golden spec field matrix

Acceptance contract: if the user configures a field supported by the
spec, it ALREADY works without plugin code.

| Spec | Fields |
|------|--------|
| `docs/menu-example.yml` | title, rows, open-sound, close-sound (v1.1), close-actions (v1.1), update-interval, inventory-type, pagination, strict-clicks (v1.1), layout + paged-key (v1.1); per item: display-name, material (basehead), skull-owner (v1.1), custom-model-data, amount, slots, key (v1.1), glow, enchantments, flags (HIDE_ALL), color, trim-pattern, trim-material, potion-effects, update-interval, lore, view/click-requirements, click/deny-actions, per-click matrix right/left/shift-right/shift-left/middle x actions/requirements/deny-actions (v1.1), nav items with nav-disabled; templates without slots; [small]/[rgb]/[center]/MiniMessage in any string |
| `docs/item-example.yml` | display-name, material, skull-owner (v1.1), custom-model-data, amount, glow, lore, enchantments, flags, color, trim-pattern, trim-material, potion-effects, attributes (v1.1), damage (v1.1), unbreakable, max-stack-size, droppable, moveable, placeable, tradeable, despawnable, keep-on-death, cooldown, locked, no-drop, no-manual-equip, obtain-via, custom-durability (max/damage-per-use/break-actions/lore-format), 12 *-click-actions lists (8 + 4 shift-positional v1.1), shift-overrides-generic (v1.1), interact-requirements, deny-actions, pickup/drop-actions, held-effects (mainhand/offhand/armor), equipment-slot, recipe (7 types) |
| `docs/selection-example.yml` (v1.1) | item (full SnItem appearance schema), permission, particle (type/color/size), step, interval-ticks, render-distance, visibility (OWNER_ONLY/WORLD), particle-budget, max-render-volume, max-volume, timeout-ticks, silent |
| `docs/bridge-example.yml` (v1.2, experimental) | hmac-secret, default-ttl-seconds, queue-cap, max-message-bytes, max-pending-messages, console-allowlist (anchored patterns), console-rate-limit-per-second |

The headers of `GuiDef.java`, `GuiItemDef.java` and `ItemDef.java` carry the
field-by-field checklist with the exact parse point.

## Compatibility

- Runtime floor: 1.20.4. Target: 1.21.8. Unknown 1.22+ versions start with a
  forward WARN, never a hard-fail.
- Java 21 MANDATORY: the 1.20.4 floor requires a Java 21 JVM; the classfiles
  are release 21 and on a Java 17 JVM it fails with
  `UnsupportedClassVersionError` before any probe.
- Zero NMS/packets: 100% Paper and Adventure API. Nothing references
  `InventoryView`. Any API newer than 1.20.4 (setMaxStackSize, glint
  override) degrades with ONE WARN via `SnCompat.probe`.
- Open enums: Sound/Particle/ItemFlag resolve via individual `valueOf` with
  catch, never switch/EnumSet; lenient aliases
  `HIDE_POTION_EFFECTS` <-> `HIDE_ADDITIONAL_TOOLTIP` and `REDSTONE` <-> `DUST`.

## Threading

- PAPI ONLY on the main thread; in async the tokens stay untouched (debug
  note).
- `join()` allowed ONLY in onDisable/bootstrap (`SnFuture.join` verifies it
  against the teardown flag).
- Synchronous I/O ONLY in onEnable and in the reload command (declared
  exception); everything else is async with coalescing and a synchronous
  flush on teardown.
- `thenSync` with an `isEnabled()` guard: never an
  `IllegalPluginAccessException` during shutdown.

## Multi-tenant NON-interference contract

Every registration in the lib (contexts, GUIs, items, commands, hooks,
bossbars, holograms, cron, leaderboards) is keyed by Plugin owner in
TenantRegistry; the sweeper removes the whole KEY when a consumer is disabled
(PlugMan included). A consumer's reload/disable NEVER touches another
consumer's state nor the lib's. Namespace-less statics only for server-wide
data (SnVersion/SnCompat, WARN dedup, content-addressed caches of
HeadUtil/PlayerLookup). The 14 shared listeners (11 from v1.0.0 plus
ChunkMoveListener, the UpdateChecker join-listener and SelectionWandListener
from v1.1) live in ListenerHub and are registered ONCE in the SnLibPlugin
bootstrap.

## /snlib command

`/snlib version` (lib + API-level + MC), `/snlib plugins` (hooked consumers),
`/snlib integrations` (active SoftDependencies), `/snlib iteminfo` (PDC dump
of the item in hand), `/snlib bridge status` (SnBridge diagnostics: handshakes,
queues and drop counters, per namespace), `/snlib reload [plugin]` (without
args only the lib's own surface; with a plugin it delegates to that plugin's
ReloadManager). Permissions `snlib.admin.*` (default op). On the Velocity
side: `/snlibv status` (per-backend SnBridge table).

## Smoke QA v1.0.0 (gate executed)

Gate executed on the built jar, on local Paper with a Java 21 JVM:

- Paper 1.21.8 (build 60): startup WITHOUT errors or exceptions,
  `SnLib 1.0.0 enabled (API level 1)`; `/snlib version` answers
  `SnLib version: 1.0.0 / API level: 1 / Server: 1.21.8`; `/snlib plugins` and
  `/snlib integrations` answer; clean disable and stop.
- Paper 1.20.4 (build 499, floor): startup WITHOUT errors, `1.20.4` detected,
  `/snlib version` answers, zero `NoSuchMethodError`/`NoClassDefFoundError`,
  clean disable. The degradation WARN (setMaxStackSize/glint) only fires when
  a consumer builds an item that exercises those probes; the lib on its own
  has none to fire, this stays covered by the pilots.
- Finding fixed by the gate: with Vault absent, the Vault backend (isolated
  class) does not link; `EconomyBridge` instantiates it under a `Throwable`
  catch and starts without that backend.
- Reproducible procedure: copy `target/SnLib-1.0.0.jar` to `plugins/`, start
  Paper with Java 21 (`java -jar paper.jar nogui`), run
  `snlib version` in console and review the full log.

## Smoke QA v1.1.0 (gate executed)

Gate executed on the freshly built 1.1.0 jar, on the same local Paper installs
as the v1.0.0 gate, both with a Java 21 JVM (Temurin 21.0.8):

- Paper 1.21.8 (build 60): startup WITHOUT errors or exceptions,
  `SnLib 1.1.0 enabled (API level 2)`; `/snlib version` answers
  `SnLib version: 1.1.0 / API level: 2 / Server: 1.21.8-R0.1-SNAPSHOT
  (detected: 1.21.8)`; `/snlib plugins` and `/snlib integrations` answer;
  clean disable and stop.
- Paper 1.20.4 (build 499, floor): startup WITHOUT errors, `1.20.4` detected,
  `SnLib 1.1.0 enabled (API level 2)`; `/snlib version` answers
  `SnLib version: 1.1.0 / API level: 2 / Server: 1.20.4-R0.1-SNAPSHOT
  (detected: 1.20.4)`; zero `NoSuchMethodError`/`NoClassDefFoundError`,
  clean disable and stop. The degradation WARNs (setMaxStackSize/glint and
  the v1.1 AttributeModifier UUID fallback) only fire when a consumer builds
  items that exercise those probes; the lib on its own has none to fire, they
  stay covered by the pilots (same criterion as in v1.0.0). The selection
  wand's DUST particle resolves on 1.20.4 via the SelectionRenderer's
  bidirectional DUST/REDSTONE alias (FLAME fallback with a single WARN if
  the name does not resolve).
- bStats (service id 32541) initializes without exceptions on both versions;
  data showing up on the bstats.org panel is asynchronous and remains a
  NON-blocking post-deploy verification.
- Reproducible procedure: copy `target/SnLib-1.1.0.jar` to `plugins/`, start
  Paper with Java 21 (`java -jar paper.jar nogui`), run
  `snlib version` in console and review the full log.

## Smoke QA v1.2.0 (pending, live-server gate)

`mvn clean package` is green (323 unit/integration tests, shade, japicmp) but a
live-server smoke gate for 1.2.0 - the same physical startup check the
v1.0.0/v1.1.0 gates ran, PLUS a Velocity proxy startup and an end-to-end
SnBridge round trip (Paper backend <-> Velocity proxy) - has not been executed
yet. Two dedicated test plugins for exactly this (`SnLibTestPaper`,
`SnLibTestVelocity`) exist to drive it; run it before depending on SnBridge
against a live network.

## Adoption path

1. Release v1.0.0 + `mvn install` to the local `.m2`.
2. Post-release pilots: SnTags (simple) and SnCrates (complex); the documented
   manual smoke is the QA of the pilots.
3. Canary of `SnLib.jar` on ONE game mode 48h before the rest.
4. Migration of the remaining ~57 plugins outside this plan.

## Development

- Consumer templates in `docs/`: `consumer-pom-template.xml` (minimal pom,
  provided scope, `com.sn:snlib:1.2.0`) and `snlib-consumer-rules.pro`
  (ProGuard rules).
- Golden configuration specs in `docs/menu-example.yml` (GUIs),
  `docs/item-example.yml` (physical items), `docs/selection-example.yml`
  (selection wand, v1.1) and `docs/bridge-example.yml` (SnBridge config
  block: hmac-secret, queue caps, console-allowlist, v1.2 experimental).
- SnBridge design and operations: `docs/SNBRIDGE-SPEC.md` (design spec) and
  `docs/SNBRIDGE-RUNBOOK.md` (operator runbook: deploy order, HMAC rotation,
  troubleshooting checklist).
- Public API frozen under semver: additive-only japicmp ACTIVE with an
  explicit `com.sn:snlib:1.0.0` baseline (missing baseline = broken build);
  `*.internal` packages outside the contract; `SnApi.LEVEL` increments +1 on
  every release that adds public API (2 since the 1.1.0 release, unchanged in
  1.2.0). SnBridge (`com.sn.lib.bridge.*`, `com.sn.lib.velocity.*`) is
  `@SnExperimental` and stays OUTSIDE both the japicmp gate and `SnApi.LEVEL`
  until a real migration stress-tests it and it freezes - not scheduled yet.
