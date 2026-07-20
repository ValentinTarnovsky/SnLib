# SnLib v1.3.0 - Technical documentation of the current state

> Generated on 2026-07-10 against the real repo code (HEAD commit of main); updated on 2026-07-11
> for the 1.1.0 release, on 2026-07-12 for the 1.2.x releases, and on 2026-07-13 for the 1.3.0
> release (removed the experimental SnBridge; added a small Velocity base - config, text,
> scheduler, commands - in section 19), and on 2026-07-15 for the 1.5.0 behavior changes:
> legacy COLOR codes reset decorations in the Component render path too (section 03),
> bundled `guis/*.yml` are seeded into the data folder (section 12), config-driven command
> aliases plus arg-name tab hints and sender-aware / suggest-only args (section 13), and a
> one-time WARN when a lang value embeds the literal prefix placeholder token (section 05).
> Updated on 2026-07-20 for the 1.8.0 additions (API level 4): `ItemRegistry.take`/`removeAll`
> symmetric removal (section 11), `SnYml.setComments`/`setInlineComments` write surface
> (section 04), and a one-time WARN when a lang value lost the `<click:>`/`<hover:>` tag its
> jar default carries (section 05).
> Updated on 2026-07-20 for the 1.9.0 changes: color codes inside the `[rgb]` gradient now
> clear the accumulated legacy format, so a bold prefix no longer bleeds into the gradient
> body (section 03), and the new `[noprefix]` leading tag opts a single-line lang value out
> of the configured prefix (sections 03 and 05).
> Coverage: every class under `src/main/java/com/sn/lib`, resources, build and tests (211
> tests, all green; 1.5.0 adds text, gui, command and lang tests on top).

**Project summary:** SnLib is the standalone base plugin of the ~57 Sn plugins, shipped as a
DUAL-PLATFORM jar: the SAME `SnLib-1.3.0.jar` is a Paper plugin (`plugin.yml`, `depend: [SnLib]`,
provided scope) AND a Velocity plugin (`velocity-plugin.json`, entry `SnLibVelocity`). On Paper it
provides the full module set; on Velocity it is a small homogeneity base
(`Snv`/`SnvConfig`/`SnvScheduler` + the shared `SnText` pipeline), NOT a messaging framework. Java
21, floor 1.20.4, target 1.21.8, forward 1.22+ with WARN. 211 green JUnit tests; smoke gate green on
Paper 1.21.8 and 1.20.4 (executed for v1.0.0, re-executed for v1.1.0 with the release jar). The
frozen Paper API stays additive over the 1.0.0 baseline (API level 2, japicmp gate active); the
Velocity base (`com.sn.lib.velocity.*`) is a separate Velocity-only surface, outside that gate while
it settles. v1.3.0 removed the experimental SnBridge (`com.sn.lib.bridge.*` + its proxy runtime): it
had grown into a full cross-server messaging framework, well beyond the library's "consistent base"
goal.

## Index

- [01. Architecture and lifecycle](#01-architecture-and-lifecycle)
- [02. Multi-version compat](#02-multi-version-compat)
- [03. Text pipeline](#03-text-pipeline)
- [04. YML: reading, preprocessing and auto-update](#04-yml-reading-preprocessing-and-auto-update)
- [05. Lang and Debug](#05-lang-and-debug)
- [06. Scheduler, SoftDependency and Cron](#06-scheduler-softdependency-and-cron)
- [07. Utils](#07-utils)
- [08. Actions, Requirements and PAPI](#08-actions-requirements-and-papi)
- [09. Multi-tenant, cleanup and reload](#09-multi-tenant-cleanup-and-reload)
- [10. Custom events](#10-custom-events)
- [11. Items](#11-items)
- [12. GUI](#12-gui)
- [13. Commands](#13-commands)
- [14. Database and Economy](#14-database-and-economy)
- [15. BossBars, Holograms, Leaderboards and Discord](#15-bossbars-holograms-leaderboards-and-discord)
- [16. Build, tests, golden specs and TODOs](#16-build-tests-golden-specs-and-todos)
- [17. UpdateChecker (v1.1)](#17-updatechecker-v11)
- [18. Region: cuboid selection (v1.1)](#18-region-cuboid-selection-v11)
- [19. Velocity base: config, text, scheduler, commands (v1.3)](#19-velocity-base-config-text-scheduler-commands-v13)
- [20. Warmup teleports (v1.6)](#20-warmup-teleports-v16)

---
## 01. Architecture and lifecycle

SnLib is a standalone plugin loaded exactly once (load: STARTUP) and shared by all consumers through a shared classloader; every registry that stores plugin, player or session data MUST be keyed by the owning `Plugin` (statics without an owner namespace are only allowed for server-wide data). The only public initialization path is extending `SnPlugin`, which runs the API level handshake against the installed jar and calls the package-private `SnLib.init`; the resulting context (`Sn`) mounts every module declared in the consumer's `SnSpec` in a single call and is torn down with `Sn.shutdown()` in a strict 13-step order. The `*.internal` packages sit outside the semver contract. Runtime floor 1.20.4, target 1.21.8, Java 21, 100% Paper/Adventure API (NMS, packets and InventoryView are forbidden); unknown versions (1.22+) start with a WARN and never hard-fail.

### SnLib
`src/main/java/com/sn/lib/SnLib.java`

Entry point and context registry of the library. `final` class with a private constructor; it keeps the server-wide static `CONTEXTS` (a manually synchronized `LinkedHashMap<Plugin, Sn>`), justified by being the single context registry: keyed by owning plugin and insertion-ordered so the sweeper cascade shuts down in reverse registration order. A `static {}` block calls `TenantSweeper.bindContexts(new ContextAccessImpl())` when the class loads.

- `static Sn init(JavaPlugin plugin, SnSpec spec)` - (package-private ON PURPOSE) creates and registers a consumer's context, mounting everything its spec declares in one call: managed config (with the `update-configs` gate seeded), lang, the `guis/` folder with its load, the items file, the database module and the runtime debug command. Idempotent per owner: a double init returns the existing live context with the WARN "double SnLib.init: returning the existing context without re-mounting modules" instead of mounting a second one. On a race in the final `putIfAbsent` it returns the context that won the race.
- `static void detach(Plugin owner, Sn ctx)` - (package-private) removes an owner's context key only if it still maps to `ctx` (`CONTEXTS.remove(owner, ctx)`); it is the final step of `Sn.shutdown()` and idempotent with the tenant sweeper's own detach.
- `public static @Nullable Sn context(JavaPlugin plugin)` - context of a consumer plugin, or `null` if that plugin never initialized against SnLib.

#### Internal logic

- `ContextAccessImpl` (private static class, implements `TenantSweeper.ContextAccess`): access the tenant sweeper uses to detach context keys without widening the public API.
  - `boolean detach(Plugin owner, Sn expected)` - conditional remove (owner -> expected) under the `CONTEXTS` lock; returns whether it removed.
  - `List<Sn> detachAllReversed()` - copies all live contexts, clears the map and returns the reversed list (reverse registration order) for the cascade when SnLib gets disabled.

#### Notes and gotchas

- `init` is package-private on purpose: there is no alternative public initialization path; extending `com.sn.lib.SnPlugin` is the only way, and that class runs the API level handshake BEFORE calling here.
- The tenant sweeper acts as a double safety net for owners that never shut down, and cascades all live contexts, in reverse registration order, when SnLib itself gets disabled.
- Architectural contract documented in the class Javadoc: (a) shared classloader with owner-keyed registries, (b) `*.internal` packages outside semver, (c) 1.20.4 to 1.21.8 compatibility with `Sound`/`Particle`/`ItemFlag` treated as open sets (never switch/EnumSet over them), lenient aliases with WARN (`HIDE_POTION_EFFECTS` -> `HIDE_ADDITIONAL_TOOLTIP`, `REDSTONE` -> `DUST`) and every post-1.20.4 API only behind `SnCompat.probe`, (d) frozen entrypoint: `SnPlugin` + `requiredApiLevel()` + `SnSpec` + `SnApi.LEVEL` never change within a major version.

### SnLibPlugin
`src/main/java/com/sn/lib/SnLibPlugin.java`

Bootstrap plugin of the SnLib runtime. Loaded at STARTUP before every consumer; the single point that registers the shared listeners and the runtime side of the consumers' API level handshake. It owns its own context over `plugins/SnLib/config.yml` (library debug plus the bStats opt-out), created by the same package-private `SnLib.init` every consumer goes through.

Constants: `private static final int BSTATS_SERVICE_ID = 32541` (SnLib's real service id on bstats.org). State: `private static volatile @Nullable SnLibPlugin instance`, plus the instance fields `selfCtx` and `metrics` (both nullable).

- `public static SnLibPlugin get()` - the running SnLib bootstrap. Consumers never call it directly; `SnPlugin` uses it for the handshake, guaranteed present by `depend: [SnLib]` plus `load: STARTUP`. If `instance` is null it throws `IllegalStateException("SnLib is not enabled: the consumer needs depend: [SnLib]")`.
- `public int apiLevel()` - API level of the installed SnLib.jar: returns `SnApi.LEVEL` as it was inlined into THIS jar at compile time, compared against the consumer's `requiredApiLevel()`.
- `public @Nullable Sn selfContext()` - the library's own context, or `null` while it is disabled.
- `public void onEnable()` - bootstrap: see "Internal logic".
- `public void onDisable()` - `TenantSweeper.cascadeAll()` (shuts down every live context in reverse registration order), `metrics.shutdown()` if bStats was active (and nulls it), `HeadUtil.clearCache()`, `SkinResolver.clearCache()` (1.6, the shared async skin cache), `PlayerLookup.clearCache()`, nulls `selfCtx` and `instance`.

#### Internal logic

Exact bootstrap order (`onEnable`):
1. `instance = this` (enables `get()` for the consumer handshake).
2. `logDetectedVersion()` - forces initialization of the `SnVersion` class (which WARNs exactly once on unknown forward versions and never hard-fails) and logs "Detected server: X.Y[.Z]" with the suffix " (Folia)" when applicable.
3. `ListenerHub.registerAll(this)` - registers the library's shared listeners.
4. `Sn ctx = SnLib.init(this, buildSelfSpec())` - creates the self context (`selfCtx`) with spec `SnSpec.builder().config("config.yml").debugCommand().updates("ValentinTarnovsky/SnLib").build()` (config + debug command + self update-check only; no lang/guis/items/db). Since the repo is public, `UpdateChecker` reaches `releases/latest` with no token and watches SnLib against its own GitHub releases exactly like any consumer would watch itself.
5. `SnLibCommand.register(this, ctx)` - registers the administrative `/snlib` command.
6. If `ctx.yml().config().getBoolean("bstats", true)` is on, creates `new Metrics(this, 32541)`.
7. `ctx.scheduler().sync(this::purgeOrphanHolograms)` - startup scan for orphan holograms, deferred to the first tick: SnLib enables at STARTUP before any world loads and before any consumer registers its holograms; by the first tick both things have already happened and every marked TextDisplay without a live registration is a leftover of a previous run (`HologramChunkListener.purgeLoadedWorlds()`; if it purged > 0 it logs "Purged N orphan holograms from previous startups").
8. Final log: `"SnLib <version> enabled (API level " + SnApi.LEVEL + ")"`.

### SnPlugin
`src/main/java/com/sn/lib/SnPlugin.java`

Mandatory base class of every SnLib consumer and the ONLY initialization path of the library: since `SnLib.init` is package-private, extending this class is the only public way to obtain a context. Part of the frozen entrypoint (this surface never changes within a major version). Consumers must declare `depend: [SnLib]` in their plugin.yml. `abstract` class extending `JavaPlugin`; `onEnable()` and `onDisable()` are `final`.

- `protected abstract int requiredApiLevel()` - implement it EXACTLY as `return SnApi.LEVEL;` - it inlines the consumer's compile-time API level (see handshake below).
- `public final void onEnable()` - runs the API level handshake; if it passes, does `this.sn = SnLib.init(this, buildSpec())` and then calls `onInnerEnable()` inside a `Throwable` try/catch: on any failure it logs SEVERE "onInnerEnable failed" with the stacktrace and disables the plugin via `PluginManager.disablePlugin(this)`.
- `public final void onDisable()` - calls `onInnerDisable()` and, in a `finally` (that is, even if the consumer's disable throws), runs `sn.shutdown()` if the context exists.
- `protected SnSpec buildSpec()` - modules declared by this consumer; overridden to opt in (`SnSpec.builder().config("config.yml").lang()...build()`). The default (`SnSpec.builder().build()`) declares no optional module.
- `protected abstract void onInnerEnable()` - the consumer's enable logic; runs after the handshake and the context initialization.
- `protected void onInnerDisable()` - the consumer's disable logic; runs before the context teardown. Optional (empty default implementation).
- `public final Sn sn()` - this plugin's SnLib context; available from `onInnerEnable()` onward.

#### API level handshake (bytecode-side)

The complete mechanism, as implemented:

1. `SnApi.LEVEL` is a `public static final int` (compile-time constant). When the consumer compiles, javac inlines the literal into the CONSUMER's class file: the `return SnApi.LEVEL;` of the consumer's `requiredApiLevel()` is frozen with the level it compiled against, not with the level of the jar installed at runtime.
2. In `onEnable()`, `SnPlugin` obtains `int installed = SnLibPlugin.get().apiLevel()` - which returns the `SnApi.LEVEL` inlined in the INSTALLED SnLib.jar - and `int required = requiredApiLevel()`.
3. If `installed < required`, it logs SEVERE: `"Requires SnLib API level " + required + " (installed: " + installed + "). Update SnLib.jar (requires restart): https://github.com/ValentinTarnovsky/SnLib/releases"` and disables itself with `disablePlugin(this)`, with an immediate `return` (it never reaches `SnLib.init`).
4. Result: a consumer compiled against a newer API level than the installed jar shuts down cleanly with an update message instead of failing later with `NoSuchMethodError` or `NoClassDefFoundError`.

The presence of `SnLibPlugin.get()` in step 2 is guaranteed by the consumer's `depend: [SnLib]` plus SnLib's `load: STARTUP`; if it is missing, `get()` throws `IllegalStateException` with an explanatory message.

### Sn
`src/main/java/com/sn/lib/Sn.java`

Per-plugin SnLib context: the handle through which a consumer reaches every module it declared in its `SnSpec`. `final` class; package-private constructor `Sn(JavaPlugin plugin, SnSpec spec)` that wires ALL modules at construction (declared ones end up mounted; undeclared ones stay `null` only for the 5 gated modules). Part of the frozen entrypoint surface: its accessor set only grows within a major version. Field `volatile boolean shuttingDown` (package-private): the teardown sets it before anything else and it flips `SnYml.save()` to synchronous writes.

Accessors ALWAYS available (they never throw, whether modules were declared or not):

- `public JavaPlugin plugin()` - consumer plugin that owns this context.
- `public SnScheduler scheduler()` - Folia-aware scheduler bound to the owning plugin.
- `public SnDebug debug()` - runtime debug service; toggles persist to the main config when the yml module is declared, in memory otherwise.
- `public SnPapi papi()` - PlaceholderAPI bridge; every apply returns the text untouched when PlaceholderAPI is absent.
- `public ActionEngine actions()` - action engine; runs YML lists of the form `[tag] argument` and accepts custom tags via `ActionEngine.register`.
- `public Cooldowns cooldowns()` - cooldown store; non-expired entries survive relogs by design; only categories registered as session-scoped reset on quit/kick.
- `public EconomyBridge economy()` - economy bridge; resolves Vault if present, then the command backend configured via `EconomyBridge.useCommandBackend`; with no backend available it WARNs once and reports failure.
- `public BossBarUtil bossbars()` - Adventure boss bars (zero packets) with titles through the SnText pipeline; departing viewers drop themselves and the teardown hides all bars of this owner.
- `public HologramUtil holograms()` - holograms as real TextDisplay entities marked in the PDC; the teardown deletes this owner's entities and the library purges orphan markers on chunk load and at startup.
- `public SnCron cron()` - main-thread jobs at the calendar instants of a 5-field cron subset or the daily/hourly shortcuts, re-armed after every run; jobs with `catchUp(true)` persist their last run to a data yml and fire one missed run on the next schedule.
- `public LeaderboardCache leaderboards()` - each board runs its async query at a fixed interval and swaps an immutable snapshot behind a volatile reference: getTop/positionOf/valueOf are lock-free cache reads safe for PlaceholderAPI resolvers.
- `public DiscordWebhook discord()` - messages enqueue FIFO and post async through the JDK HttpClient, honoring Retry-After on 429; the teardown drains whatever is still queued.
- `public UpdateChecker updates()` - notify-only update checker of the owning plugin (v1.1); without `SnSpec.builder().updates(...)` or explicit `watch()`/`checkNow()` it stays inert (zero traffic). See section 17.
- `public ItemRegistry items()` - item registry; works with zero files: definitions from `ItemDef.builder()`, YML sections or the file declared via `SnSpec.builder().items(...)`.
- `public SelectionManager selections()` - cuboid selection module (v1.1); 100% programmatic: registers `SelectionSpec`s, hands out PDC-tagged wands and delivers the complete `Cuboid` through a callback or the cancelable event. See section 18.
- `public SnCommands commands()` - command module; roots built here inject default reload and help subcommands, permission-gated tab-complete, and unregister on shutdown.
- `public ReloadManager reload()` - reload orchestrator; rebuilds the declared modules in strict order and re-dispatches the reloadables registered via `ReloadManager.register`; the default `reload` subcommand and `/snlib reload <plugin>` delegate here.
- `public boolean isShuttingDown()` - true from the moment this context's teardown started; module I/O must switch to synchronous.

Accessors WITH a spec GATE (they throw `UnsupportedOperationException` naming the missing builder call when the module was not declared):

- `public YmlManager yml()` - yml manager of the owning plugin (managed/seedOnly/data files plus the mounted main config). Gate: message "yml module not declared: missing SnSpec.builder().config(\"config.yml\")".
- `public SnLang lang()` - language module: `lang/messages_<code>.yml` with the shared `snlib.*` keys always merged and per-key English fallback. Gate: "lang module not declared: missing SnSpec.builder().lang()".
- `public GuiManager guis()` - GUI module: `guis/` folder with one GUI per file, one session and inventory per viewer, and opt-in pagination per menu. Gate: "guis module not declared: missing SnSpec.builder().guis()".
- `public SnDb db()` - database module: dual SQLite/MySQL over a Hikari pool with every query and update off the main thread. Gate: "db module not declared: missing SnSpec.builder().db()".
- `public Teleports teleports()` (1.6) - warmup teleport module: one pending teleport per player (dedup), a warmup message, cancel on move and damage, an optional cooldown category shared with `cooldowns()` and a Folia-safe completion. Gate: "teleports module not declared: missing SnSpec.builder().teleports()". See section 20.

Other methods:

- `SnSpec spec()` - (package-private) module declaration this context was initialized with.
- `public void shutdown()` - shuts down every module of this context and releases its registrations, in a strict order that never loses a pending write. Idempotent: only the first call runs the teardown. See the exact order below.
- `public void reloadAll()` - reloads every module of this context; delegates to `reload.reloadPlugin()`.

#### Internal logic: construction order

The constructor wires in this order: scheduler, papi, yml (null if `spec.config() == null`), debug (receives the config or null), actions, lang (only if `spec.lang()`), cooldowns, economy, bossbars, holograms, cron, leaderboards, discord, updates (with an immediate `updates.watch(repo)` if the spec declared `updates("owner/repo")`), items. If the spec declares items with a file and there IS yml, it does `items.loadAll(yml.managed(itemsFile))`; if it declares items WITHOUT config, it WARNs: `items("...") declared without config(): the file is not mounted and sn.items() stays programmatic only`. Then selections (always available; after items and before teleports/guis, it depends on no other module), teleports (1.6, only if `spec.teleports()`: `new Teleports(this)`, else null), guis (only if `spec.guis()`, with an immediate `guis.load()`), db (only if `spec.db()`, with `DbConfig.load(plugin, yml.config().getSection("database"))` or a null section without yml), commands (`new SnCommands(this, lang, spec.debugCommand())`) and reload.

#### Internal logic: the 13 steps of shutdown()

If `shuttingDown` was already true, it returns without doing anything (idempotency). Otherwise it runs exactly:

- Step 0 (pre-step): `shuttingDown = true` BEFORE everything else: `SnYml.save()` switches to inline writes and `SnFuture.join` accepts the teardown thread. This way every persistence done inside the teardown itself (equipment backups, debug toggles, data files) writes SYNC on the calling thread instead of going through a scheduler about to be cancelled.
1. `guis.closeAll()` (if guis declared) - closes this owner's open GUIs; each per-viewer session cancels its timers, untracks its holder and forces the close on its viewer.
2. `commands.unregisterAll()` - unregisters this owner's command roots and refreshes the clients' command trees.
3. `yml.flushAll()` (if yml declared) - drains the coalesced async yml writes BEFORE cancelling the scheduler that would run them.
4. `items.cancelTasks()`, `selections.shutdown()`, `teleports.shutdown()` (1.6, if teleports declared) and `scheduler.cancelAll()` - only now cuts the selection renderers (clearing sessions and specs, without onCancel), cancels every pending warmup teleport and clears the teleport map, and cancels every remaining task of the owning plugin.
5. `EquipmentBackup.restoreAll(plugin)` - locked items: returns the displaced real equipment; the write-through store persists synchronously thanks to the `shuttingDown` flag.
6. `db.flushPlayerCaches()` (if db declared) - player caches: saves every dirty entry and joins the writes...
7. `db.shutdown()` (if db declared) - ...and then closes the pool (joins pending work, `shutdownNow` after a timeout).
8. `RecipeLoader.unregisterAll(plugin)` - removes this owner's recipe keys from the server.
9. `cooldowns.clearAll()` - this owner's cooldown store.
10. `SoftDependency.forEachRegistered(...)` with `hook.forceDisable()` only for hooks whose `owner() == plugin`, plus `papi.unregisterAll()` - own integrations: forces the disable of this owner's soft-dependency hooks and unregisters its PlaceholderAPI expansions.
11. `actions.shutdown()` - releases the outgoing BungeeCord channel if `[connect]` registered it.
12. `bossbars.hideAll()`, `holograms.deleteAll()`, `discord.drain()`, `updates.shutdown()` - teardown hooks of the extra modules, before the generic removeOwner: hides this owner's bossbars, deletes its TextDisplay holograms so they do not linger as orphans until the next startup purge, drains the queued Discord webhooks best-effort under a short deadline, and cancels the update checker's watch timers releasing its HttpClient.
13. `TenantRegistry.sweepOwner(plugin)` and `SnLib.detach(plugin, this)` - removes this owner's key from ALL tenant registries and unmounts the context from the global registry.

#### Notes and gotchas

- Non-interference: `shutdown()` and `reloadAll()` operate ONLY on state owned by this plugin; other consumers' contexts are never touched.
- The 3-before-4 order is deliberate: draining yml writes before cancelling the scheduler avoids losing pending coalesced writes.
- Accessor policy: 4 modules gated by spec (`yml`, `lang`, `guis`, `db`) and the rest always available; every declared module is wired at construction, so no accessor of a declared module ever throws.

### SnSpec
`src/main/java/com/sn/lib/SnSpec.java`

Immutable declaration of the SnLib modules a consumer plugin uses. Part of the frozen entrypoint: the builder surface never changes within a major version. Everything declared mounts with ONE init call when the consumer enables (managed config seeded and merged with the `update-configs` gate, lang and `guis/` load, the items file registers its definitions, the db comes up and the debug subcommand is injected); the corresponding teardown runs automatically when the consumer disables.

- `public static Builder builder()` - creates a new spec builder.
- `public @Nullable String config()` - name of the managed main config file, or `null` if the config module was not declared.
- `public boolean lang()` - whether the lang module (messages_en.yml) was declared.
- `public boolean guis()` - whether the guis module (`guis/` folder) was declared.
- `public @Nullable String items()` - name of the items file, or `null` if the items module was not declared with a YML source.
- `public boolean db()` - whether the database module was declared.
- `public boolean teleports()` - whether the warmup teleport module was declared (v1.6).
- `public boolean debugCommand()` - whether the runtime debug command was declared.
- `public @Nullable String updates()` - GitHub repo `owner/repo` of the update check (v1.1), or `null` if not declared.

#### SnSpec.Builder (public nested class)

`SnSpec` builder; every method is opt-in, omitted modules stay disabled. Private constructor (only via `SnSpec.builder()`).

- `public Builder config(String fileName)` - declares the managed main config file (for example `"config.yml"`).
- `public Builder lang()` - declares the lang module.
- `public Builder guis()` - declares the guis module.
- `public Builder items(String fileName)` - declares the items module backed by a YML file (for example `"items.yml"`).
- `public Builder db()` - declares the database module.
- `public Builder teleports()` - declares the warmup teleport module (v1.6; `sn.teleports()`, section 20).
- `public Builder debugCommand()` - declares the runtime debug command.
- `public Builder updates(String ownerRepo)` - declares the notify-only update check against a GitHub repo, `owner/repo` format (v1.1; see section 17).
- `public SnSpec build()` - builds the immutable spec.

### SnApi
`src/main/java/com/sn/lib/SnApi.java`

Public API level of this SnLib build. `final` class with a private constructor; a single public constant:

- `public static final int LEVEL = 2` - API level of this build. Policy: it goes up by exactly 1 on EVERY release that adds public methods or classes to the FROZEN Paper API; the surface is frozen under an additive-only japicmp gate. The Velocity base (`com.sn.lib.velocity.*`, see section 19) is a separate Velocity-only surface excluded from the gate and from this level while it settles, so it can evolve without bumping LEVEL. It is the number every consumer handshakes against via `SnPlugin#requiredApiLevel()`: the required level is inlined into the consumer's bytecode at compile time, so a consumer compiled against a newer level than the installed SnLib.jar disables itself cleanly instead of failing with `NoSuchMethodError`.

### Ph
`src/main/java/com/sn/lib/Ph.java`

Public record `Ph(String key, String value)`: local placeholder pair resolved by the text pipeline BEFORE PlaceholderAPI. `key` is the placeholder name without delimiters (it matches both `%key%` and `{key}`); `value` is the replacement text.

- `public static Ph of(String key, Object value)` - creates a pair from any value via `String.valueOf(Object)`.

### plugin.yml
`src/main/resources/plugin.yml`

Bukkit descriptor of the bootstrap plugin. Exact values:

- `name: SnLib`, `main: com.sn.lib.SnLibPlugin`, `version: ${project.version}` (filtered by Maven), `author: ValentinTarnovsky`, `api-version: '1.20'`.
- `load: STARTUP` - lifecycle key: SnLib enables before all consumers (which declare `depend: [SnLib]`), guaranteeing `SnLibPlugin.get()` works during every consumer's handshake.
- `description: Common library core for Sn plugins (standalone hard-depend).`
- `softdepend: [PlaceholderAPI, Vault]` - optional integrations; both degrade gracefully when absent.
- `snlib` command: "SnLib administration command.", usage `/snlib <version|plugins|integrations|iteminfo|reload>`.
- Permissions (all `default: op`): `snlib.admin` (parent; grants all admin subcommands plus the self update-check join notice, with children `snlib.admin.version`, `snlib.admin.plugins`, `snlib.admin.integrations`, `snlib.admin.iteminfo`, `snlib.admin.reload`, `snlib.admin.update`, all true) plus the six individual child permissions, one per subcommand (`snlib.admin.update` is not a subcommand: it gates the `UpdateChecker` join notice, declared here so it defaults to op like every consumer is told to do for itself).

### config.yml
`src/main/resources/config.yml`

Managed config of SnLib itself (mounted at `plugins/SnLib/config.yml` by the self-context). It is a "managed" config: keys added by future versions merge in on every startup, preserving the user's values and extra keys. Keys:

- `update-configs: true` - master gate of the always-merge updater: when `false` it skips every yml merge except this file.
- `debug.enabled: false` - master toggle of the library's debug output (also toggleable live, no restart).
- `debug.level: DEBUG` - verbosity threshold: `OFF`, `INFO`, `DEBUG` or `TRACE`.
- `debug.categories: []` - category filter; an empty list lets everything through.
- `bstats: true` - anonymous metrics via bStats (https://bstats.org); `false` to opt out (read in `SnLibPlugin.onEnable` before creating `Metrics`).

### TODOs and limitations

None. There are no TODO/FIXME/XXX markers in any file of this scope. Design limitations documented in the code (decisions, not pending work): `items(...)` declared without `config()` leaves `sn.items()` programmatic-only with a WARN; the API level handshake only protects against an OLD installed jar (`installed < required`), a jar newer than the consumer always passes (guaranteed by the additive-only `SnApi.LEVEL` policy); a double `SnLib.init` does not re-mount modules (it returns the existing context with a WARN).
---

## 02. Multi-version compat

SnLib's multi-version compatibility module (package `com.sn.lib.compat`). It consists of two static final utility classes: `SnVersion`, which parses the server version exactly once at class initialization and exposes `supports(...)` checks plus Folia detection, and `SnCompat`, which reflectively probes API added after the 1.20.4 runtime floor so old servers degrade with a single WARN instead of throwing. The module's philosophy is "forward tolerance": an unknown or 1.22+ version never hard-fails, full target support (1.21.8) is assumed with a single WARN. Both classes use server-wide static state, allowed by the SnLib contract because they describe the server and not a consumer plugin.

### SnVersion
`src/main/java/com/sn/lib/compat/SnVersion.java`

Server version detection, parsed once in the class `static` block. Parses `Bukkit.getBukkitVersion()` (e.g. `1.21.1-R0.1-SNAPSHOT`) with the regex `(\d+)\.(\d+)(?:\.(\d+))?`; it never uses `getVersion()`, whose free-form text varies per fork. `final` class with a private constructor (non-instantiable).

Public constants:
- `public static final int MAJOR` - parsed major, or `1` when the string could not be parsed.
- `public static final int MINOR` - parsed minor, or the target minor (`21`) when the string could not be parsed.
- `public static final int PATCH` - parsed patch; `0` when absent from the string, target patch (`8`) when the string could not be parsed.

Public methods:
- `public static boolean supports(int minor)` - true when the server runs 1.`minor` or newer. Always true on unknown versions (internal `ASSUME_TARGET` flag).
- `public static boolean supports(int minor, int patch)` - true when the server runs 1.`minor`.`patch` or newer: `ASSUME_TARGET || MINOR > minor || (MINOR == minor && PATCH >= patch)`. Always true on unknown versions.
- `public static boolean isFolia()` - true when the server is Folia. Detected once and cached in the static `FOLIA` field.

Internal logic:
- `KNOWN_MAX_MINOR = 21` (private): the highest minor line recognized by this build, with target 1.21.8.
- `static` block: applies the regex to `Bukkit.getBukkitVersion()`. On a match it takes major/minor/patch (patch `0` if the group is null). "Assume target" mode activates (`ASSUME_TARGET = true`) in two cases: (a) the string does not match the regex, or (b) `major != 1 || minor > KNOWN_MAX_MINOR`, that is any 1.22+ or a major other than 1. In that case it logs exactly one WARN: `[SnLib] '<raw>': unrecognized version, assuming target compat`. With `ASSUME_TARGET` active, both `supports(...)` always return true: forward tolerance instead of hard-fail.
- `private static boolean detectFolia()` - tries `Class.forName("io.papermc.paper.threadedregions.RegionizedServer")`; true if the class exists, false on `ClassNotFoundException`. Runs exactly once at class initialization.

Notes and gotchas:
- When parsing fails, the exposed values are NOT the real version but the target: `MAJOR=1`, `MINOR=21`, `PATCH=8`. Any code reading the constants directly instead of using `supports(...)` must account for this.
- Detection runs at class initialization (first use), so the unknown-version WARN appears once per JVM lifetime, not per plugin.
- The Javadoc justifies the server-wide statics: the server version is not per-consumer data, so it does not violate SnLib's per-plugin ownership contract.

### SnCompat
`src/main/java/com/sn/lib/compat/SnCompat.java`

Feature probing for API added after the 1.20.4 runtime floor. Every use of Paper/Adventure API newer than 1.20.4 must go through `probe` or `since`, so an older server degrades with a single WARN instead of throwing. `final` class with a private constructor.

Real version-sensitive points documented in the class Javadoc:
- `ItemMeta#setMaxStackSize` (1.20.5+).
- `ItemMeta#setEnchantmentGlintOverride` (1.20.5+).
- `ItemFlag.HIDE_ADDITIONAL_TOOLTIP` (alias of the legacy `HIDE_POTION_EFFECTS`).

Public methods:
- `public static @Nullable Method probe(Class<?> owner, String name, Class<?>... params)` - reflective lookup of a public method via `owner.getMethod(name, params)`, done once, caching both the hit and the miss. The cache key includes the parameter types, so two overloads of the same name never collide. Returns the `Method` or null when missing on this server (one WARN, cached miss). `owner` must be a server API or JDK class.
- `public static <T> T since(int minor, Supplier<T> modern, Supplier<T> fallback)` - version gate: returns `modern.get()` when `SnVersion.supports(minor)`, otherwise `fallback.get()` with one WARN per call site. The WARN reports: `1.<minor>+ API not available on <MAJOR>.<MINOR>.<PATCH>; using fallback`.

Internal logic:
- `CACHE` (`ConcurrentHashMap<String, Method>`): cached hits, key `owner.getName() + "#" + name + "(" + parameter types joined by "," + ")"` (e.g. `org.bukkit.inventory.meta.ItemMeta#setMaxStackSize(java.lang.Integer)`); the same key is used in `MISSING` and as the `warnOnce` tag of the miss.
- `MISSING` (`ConcurrentHashMap.newKeySet()`): keys probed and not found on this server. It exists as a separate set because a `ConcurrentHashMap` cannot hold null values, so the miss sentinel lives here.
- `WARNED` (`ConcurrentHashMap.newKeySet()`): tags already warned; `warnOnce(tag, message)` only logs `[SnLib] <message>` via `Bukkit.getLogger().warning(...)` if `WARNED.add(tag)` is true (first time).
- `private static boolean isForeignPluginClass(Class<?> owner)` - classloader guard of the probe. Name-based check: returns false if `owner`'s loader is null (JDK/bootstrap classes) or is the same classloader as `SnCompat` (SnLib classes). Otherwise it walks the loader's class hierarchy (`getClass()` and superclasses) looking for a name ending in `PluginClassLoader`; true if found. Covers both `org.bukkit.plugin.java.PluginClassLoader` and Paper's `PaperPluginClassLoader` without referencing internal server API.
- `private static String callSiteTag()` - uses `StackWalker` to identify the first frame outside `SnCompat` and builds the tag `Class#method:line` (or `"unknown"`). Used as the dedup key of the `since` WARN, achieving "one WARN per call site".
- `private static void warnOnce(String tag, String message)` - logs the WARN exactly once per tag.

Classloader guard of the probe (detail):
- If `owner` was loaded by a `PluginClassLoader` other than SnLib's, `probe` warns once (tag `"loader:" + owner.getName()`) with the message `probe of <class> rejected: class loaded by a foreign PluginClassLoader; only server API/JDK classes` and returns null WITHOUT caching.
- Reason (from the Javadoc): a `Method` retains its declaring `Class` and therefore its `PluginClassLoader`; caching it in SnLib's static `CACHE` would leak the consumer plugin's classloader across reloads.

Notes and gotchas:
- The `probe` cache key includes the parameter types (`owner#name(types)`), so two probes of the same method name with different signatures on the same class cache separately and never collide.
- The foreign-classloader rejection is NOT cached on purpose (only the WARN is deduplicated via `WARNED`): every call re-evaluates the guard and returns null, avoiding retaining references to the foreign loader.
- Threading: all state is `ConcurrentHashMap` / `newKeySet()`, so `probe`, `since` and `warnOnce` are safe from any thread. `Bukkit.getLogger()` is also thread-safe. There is no main-thread requirement in this module.
- `since` evaluates `fallback.get()` (or `modern.get()`) lazily via `Supplier`, so the untaken branch is never constructed.
- `probe` only finds public methods (`getMethod`), never private or protected ones.
- Server-wide statics allowed by the SnLib contract: probe results describe the server, not a consumer.

TODOs and limitations:
- None (no TODO/FIXME/placeholder in the files of this scope). Design limitation already noted above: `probe` limited to public methods of server API/JDK classes.

---

## 03. Text pipeline

The `com.sn.lib.text` package implements the text rendering pipeline shared by all SnLib modules. The order is FIXED and not configurable: locals -> PAPI -> [small] -> [rgb] -> legacy color conversion -> [center]. The locals and PAPI steps are resolved by the caller (SnYml getters) before invoking these methods; the four classes in this module cover the rest. `SnText` is the orchestrator (section-sign safety, prefix tags, legacy-to-MiniMessage conversion, `<` escaping, final render to `Component`); `SmallCapsUtil` substitutes letters with small caps glyphs behind the `[small]` tag; `RgbGradientUtil` interpolates the 7-anchor gradient behind the `[rgb]` tag; `CenterUtil` centers the line by measuring pixels with the vanilla font table. All four classes are pure string transformations (no state, no Bukkit, no per-plugin ownership): they are thread-safe and usable from main or async interchangeably. `StylePolicy` (1.6) is the reusable gate for player-supplied styled text, rendered through the cosmetic-subset `SnText.cosmetic`.

### SnText
`src/main/java/com/sn/lib/text/SnText.java`

Final utility class (private constructor) that orchestrates the pipeline. It keeps a static instance of `MiniMessage.miniMessage()` and the `MINI_TAGS` map that translates each legacy `&X` code to its named MiniMessage tag (`0`->`black`, `1`->`dark_blue`, `2`->`dark_green`, `3`->`dark_aqua`, `4`->`dark_red`, `5`->`dark_purple`, `6`->`gold`, `7`->`gray`, `8`->`dark_gray`, `9`->`blue`, `a`->`green`, `b`->`aqua`, `c`->`red`, `d`->`light_purple`, `e`->`yellow`, `f`->`white`, `k`->`obfuscated`, `l`->`bold`, `m`->`strikethrough`, `n`->`underlined`, `o`->`italic`, `r`->`reset`). Private constants: `CENTER_TAG = "[center]"`, `RGB_TAG = "[rgb]"`, `SMALL_TAG = "[small]"`, `NOPREFIX_TAG = "[noprefix]"` (1.9.0), `SECTION = (char) 0xA7`. It exposes no public constants or enums.

- `public static Component color(String s)` - Full render: normalizes section-sign codes to `&` (FIRST step, see below), consumes the FOUR prefix tags `[small]`/`[rgb]`/`[center]`/`[noprefix]` (the last one stripped without effect, see `applyPrefixTags`), converts legacy codes to MiniMessage tags and deserializes with MiniMessage. Null input yields `Component.empty()`. Section safety (1.6): MiniMessage 4.25 hard-rejects a raw `§` in its input, so a pre-rendered or PAPI-expanded value carrying `§` (a simple `§X` or the `§x§R§R§G§G§B§B` bungee-hex form) would otherwise crash the whole render; the leading `normalizeSectionSigns` pass rewrites it to the `&` form so it renders like its `&` equivalent, and a `§`-free input (common case) is passed through untouched by identity.
- `public static Component cosmetic(String s)` (1.6) - Same pipeline as `color(String)` but honors only the COSMETIC MiniMessage subset (`StandardTags.color`, `decorations`, `gradient`, `rainbow`, `reset`; kept in the static `COSMETIC` MiniMessage instance). Interactive/metadata tags (click, hover, insertion, font, keybind, translatable, selector, ...) are left unresolved and render as inert literal text, so they never fire from player-supplied text. The safe render for player input vetted through `StylePolicy`. Null yields `Component.empty()`.
- `public static Component mini(String s)` - MiniMessage-only render: no prefix tags and no legacy conversion. Null yields `Component.empty()`.
- `public static String colorLegacy(String s)` - Same legacy phase as `color(String)` but the output stays a legacy string with section-sign codes (`&#RRGGBB` becomes the bungee hex sequence `§x§R§R§G§G§B§B`). For APIs that still require legacy strings; MiniMessage tags stay untouched. Null returns null.
- `public static String section(String s)` (1.6) - FULL render serialized to section-sign codes: legacy, hex, MiniMessage and gradients are all resolved to a `Component` first (via `color(s)`, so a `<gradient>` or `[rgb]` becomes one hex code per glyph) and then serialized through the static `LEGACY_SECTION` `LegacyComponentSerializer` with hex in the `§x§R§R...` bungee form. For PAPI and legacy string sinks that cannot take a `Component`. Distinct from `colorLegacy`, which never resolves MiniMessage. Null returns null.
- `public static String plain(String s)` (1.6) - Visible text only: every form of styling (legacy `&`/`§`, hex, MiniMessage tags, gradients) is removed by rendering with `color(s)` and serializing through the static `PLAIN` `PlainTextComponentSerializer`. Null in, null out.
- `public static int visibleLength(String s)` (1.6) - Codepoint count of `plain(s)` (`codePointCount`, not `length()`); null and empty count as zero.
- `public static String normalizePapiOutput(String s)` - Converts PlaceholderAPI output back to the `&` form the pipeline understands: bungee hex sequences (`§x§R§R...`) become `&#RRGGBB` and `§X` codes become `&X`, so PAPI-colored values survive the conversion to MiniMessage. Delegates to the shared `normalizeSectionSigns` normalizer (the same first step `color` runs). If the string is null or contains no section sign, it is returned as-is without allocating.
- `public static String applyLocals(String s, Ph... phs)` - Resolves local placeholders from `Ph` (key/value) pairs; builds a `HashMap` and delegates to the resolver overload. Returns the input untouched if the string is null/empty or there are no pairs.
- `public static String applyLocals(String s, Function<String, String> resolver)` - Single-pass scanner over `%key%` and `{key}` tokens. If the resolver returns null the token stays untouched (so unresolved PAPI tokens survive intact); replacement values are NOT re-scanned (no recursive expansion).
- `public static List<Component> colorList(List<String> lines)` - Applies `color(String)` to each line; a null or empty list returns a new empty `ArrayList`.
- `public static String smallCaps(String s)` - Programmatic small caps transformation (scoreboards, tab, names) without going through the `[small]` tag: delegates to `SmallCapsUtil.applySmallTag`. 1:1 char-to-char mapping; legacy color codes, section-sign sequences and MiniMessage tags are skipped intact. Null/empty pass-through and it returns the SAME instance when nothing changed.
- `public static String applyPrefixTags(String line)` - Consumes the `[center]`, `[rgb]`, `[small]` and `[noprefix]` prefix tags at the start of the line, in any order and case-insensitive (via `regionMatches(true, ...)`), in a loop until none remain (one branch per tag). `[noprefix]` (1.9.0) is stripped WITHOUT effect here: it is SnLang's marker for "do not prepend the configured prefix", consumed by every render so the literal tag never reaches the player. FIXED internal application order: `[small]` runs first (delegates to `SmallCapsUtil.applySmallTag` on the remaining content), then `[rgb]` (delegates to `RgbGradientUtil.applyRgbTag`); `[center]` is re-emitted as a single normalized leading `[center]` mark that the final legacy phase consumes. Small BEFORE rgb so the gradient colors the final glyphs and the small pass operates on the short string (not on the string inflated 9x by the gradient hex); since the mapping is 1:1 and does not touch spaces, the gradient's visible-count does not change and the 6 tag permutations render identically. Null returns null.

#### Internal logic (private methods)

- `consumeCenterMark(String line)` - If the line starts with `[center]` (already normalized by `applyPrefixTags`), strips the tag and delegates to `CenterUtil.center`.
- `legacyToMini(String s)` - Legacy-to-MiniMessage conversion: `&#RRGGBB` becomes `<#RRGGBB>`, `&X` becomes its named tag per `MINI_TAGS`, and a literal `<` that cannot start a tag is escaped as `\<`. A legacy COLOR code (`&0`-`&f`, `&#RRGGBB`) resets the decorations opened by earlier legacy format codes: it tracks the decorations opened by `&k`-`&o` in a `LinkedHashSet` and, right after emitting a color tag, negates every still-active one (`negateLegacyDecorations` emits `<!name>` per tracked decoration and clears the set), so `&l&c` becomes `<bold><red><!bold>`. `&r` emits `<reset>` and clears the set. Only legacy-opened decorations enter the set, so author-written MiniMessage tags keep pure MiniMessage semantics (a MiniMessage color tag never triggers the reset). This matches vanilla and Adventure's `LegacyComponentSerializer.legacyAmpersand()`; before 1.5.0 the Component path did not reset, so bold bled across later legacy colors.
- `normalizeSectionSigns(String s)` (1.6) - Section-safety normalizer shared by `color`/`cosmetic`/`normalizePapiOutput`: rewrites `§x§R§R§G§G§B§B` bungee-hex sequences to `&#RRGGBB` (validated by `isBungeeHex`, advancing 14 chars) and simple `§X` codes (`isCodeChar`) to `&X`. A `§`-free string returns the SAME instance (no allocation), which is why a normal `&`-only string is unaffected.
- `toSectionCodes(String s)` - Counterpart for `colorLegacy`: `&#RRGGBB` becomes `§x` followed by `§` + each hex digit lowercased, and `&X` (valid code) becomes lowercase `§x`. Everything else passes unchanged.
- `canStartTag(String s, int next)` - MiniMessage tag-start heuristic: the character after `<` must be `/` (closing tag), `#` (hex color), `!` (negated decoration), `_` or an ASCII letter (a-z, A-Z). Otherwise the `<` gets escaped.
- `isCodeChar(char c)` - Validates a legacy code character: `0-9`, `a-f`, `k-o`, `r` or `x`.
- `isBungeeHex(String s, int from)` - Validates that from `from` there are 6 `§` + hex digit pairs (the 12-char bungee format).
- `isHex(String s, int from)` / `isHexDigit(char c)` - Validate 6 consecutive hex digits / one hex digit (upper or lower case).

#### Notes and gotchas

- The pipeline order is FIXED per class Javadoc: locals -> PAPI -> `[small]` -> `[rgb]` -> legacy color conversion -> `[center]`. "`[center]` last" means at the end of the LEGACY PHASE, never after the render to `Component`: `CenterUtil` only knows how to measure legacy strings, which is why centering applies to the legacy-colored string (with the gradient's `&#RRGGBB` already interpolated) right before `legacyToMini` + deserialization. `[small]` runs BEFORE both: the gradient colors the final glyphs and centering measures the final glyphs.
- Legacy and MiniMessage mix within the same string: `&X` / `&#RRGGBB` codes are translated to MiniMessage tags and the WHOLE string goes through MiniMessage at the end, so both formats render together. In `colorLegacy` it is the other way around: MiniMessage tags stay untouched inside the legacy string.
- 1.5.0 behavior fix: a legacy COLOR code resets the decorations opened by earlier legacy format codes in the Component path as well, matching vanilla. The `colorLegacy` (section-code) path already reset naturally on the client; the `color` Component path now does the same via `negateLegacyDecorations`, so `&l&c` is red non-bold and `&c&l` is bold red. Only legacy-opened decorations are tracked, so a MiniMessage color tag written by the author does not close an active MiniMessage decoration.
- `<` escaping: it is only escaped (`\<`) when the next character CANNOT start a tag per `canStartTag`. A `<` followed by a letter, `/`, `#`, `!` or `_` is let through and MiniMessage will try to parse it as a tag.
- `applyLocals` cuts the search for the closing delimiter at the first `%`/`}` it finds; `%%` or `{}` (empty token, `end == i + 1`) are not treated as a token and stay literal.
- The `[rgb]` tag targets titles and short lines: it emits one hex code per visible character (very verbose). SnLang caches statically resolved lines to pay that cost only once.

### SmallCapsUtil
`src/main/java/com/sn/lib/text/SmallCapsUtil.java`

Final utility class (private constructor). Substitution of letters with Unicode small caps glyphs behind the `[small]` prefix tag. Pure string transformation, no Bukkit. The private dictionary `SMALL` (written with `\uXXXX` escapes, index = letter - 'a') maps the 26 letters; all codepoints are BMP (one Java char each), so the mapping is ALWAYS 1:1 char to char.

Full dictionary (letter -> glyph -> codepoint):

| Letter | Glyph | Codepoint |
|---|---|---|
| a | ᴀ | U+1D00 |
| b | ʙ | U+0299 |
| c | ᴄ | U+1D04 |
| d | ᴅ | U+1D05 |
| e | ᴇ | U+1D07 |
| f | ꜰ | U+A730 |
| g | ɢ | U+0262 |
| h | ʜ | U+029C |
| i | ɪ | U+026A |
| j | ᴊ | U+1D0A |
| k | ᴋ | U+1D0B |
| l | ʟ | U+029F |
| m | ᴍ | U+1D0D |
| n | ɴ | U+0274 |
| o | ᴏ | U+1D0F |
| p | ᴘ | U+1D18 |
| q | ǫ | U+01EB |
| r | ʀ | U+0280 |
| s | ꜱ | U+A731 |
| t | ᴛ | U+1D1B |
| u | ᴜ | U+1D1C |
| v | ᴠ | U+1D20 |
| w | ᴡ | U+1D21 |
| x | x | U+0078 (itself, ASCII) |
| y | ʏ | U+028F |
| z | ᴢ | U+1D22 |

Mapping semantics (`mapChar`, private): uppercase A-Z transforms the SAME as lowercase (case does not exist in small caps); accented vowels of BOTH cases are de-accented to the small glyphs (a/e/i/o/u with acute and the u with diaeresis); the lowercase enye stays intact and the uppercase enye lowers to the default lowercase enye U+00F1 (design decision: the small glyph for the enye looks bad in MC). Digits, symbols, spaces, already-small glyphs and any other codepoint pass through intact.

- `public static String applySmallTag(String line)` - Applies the small caps mapping to a line already stripped of its `[small]` prefix. SINGLE-pass scanner without regex. Verbatim skip rules: legacy codes `&X` / `&#RRGGBB` (helper `codeLength` duplicated from RgbGradientUtil, precedent of self-contained utils), section-sign codes `§X` and the full 14-char bungee sequence (helper `sectionCodeLength`; programmatic callers of `SnText.smallCaps` may pass already-sectioned strings), and MiniMessage tags via the `canStartTag` heuristic with a search for the closing `>`: if there is NO `>` in the rest of the line the `<` is treated as literal and the text keeps transforming (so `<bold>` stays intact but "i<3" transforms). String arguments inside tags (`hover:show_text:'...'`) are NOT transformed because they live between `<` and `>`. 1:1 length invariant: the output ALWAYS measures the same as the input (which preserves the `[rgb]` gradient's visible count). Returns the SAME instance when no char mapped (zero garbage on unchanged lines); null and empty pass-through.
- `static boolean isSmallGlyph(char c)` - Package-private, consumed by `CenterUtil.baseWidth`: O(1) switch that returns true for the 25 NON-ASCII codepoints of the dictionary (all but the 'x').

#### Notes and gotchas

- The `[small]` tag runs BEFORE `[rgb]` and `[center]` in the pipeline (see `SnText.applyPrefixTags`): the gradient colors the final glyphs and centering measures the final glyphs.
- Being positioned after locals/PAPI, placeholder VALUES also come out in small caps: intended behavior.

### RgbGradientUtil
`src/main/java/com/sn/lib/text/RgbGradientUtil.java`

Final utility class (private constructor). Character-by-character RGB gradient behind the `[rgb]` prefix tag. Pure string transformation, no Bukkit. Private constant `ANCHORS` with the 7 color anchors and `SEGMENTS = ANCHORS.length - 1` (6 segments).

The 7 anchors (index 0 colors the first visible character, index 6 the last):

| Index | Hex | Approximate color |
|---|---|---|
| 0 | `0xF300F3` | magenta |
| 1 | `0x5555FF` | blue |
| 2 | `0x55FFFF` | aqua |
| 3 | `0x55FF55` | green |
| 4 | `0xFCFF21` | yellow |
| 5 | `0xFF9B00` | orange |
| 6 | `0xFF5327` | orange red |

- `public static String applyRgbTag(String line)` - Applies the gradient to a line already stripped of its `[rgb]` prefix. With `n` visible non-space characters, character `i` receives the color at `t = i / (n - 1)` over the anchor chain (`t = 0` if `n <= 1`), so the first character is exactly `ANCHORS[0]` and the last exactly `ANCHORS[6]`. Returns the line with one interpolated `&#RRGGBB` per visible character. Null or empty is returned as-is.

#### Internal logic (interpolation algorithm)

- `hexAt(int index, int total)` - Maps the position onto the anchor chain: `t = index / (total - 1)` (0.0 if `total <= 1`), `segment = t * 6`, `idx = min((int) segment, 5)` selects the segment and `fraction = segment - idx` is the progress within it. It interpolates R, G and B separately between `ANCHORS[idx]` and `ANCHORS[idx + 1]` and formats with `String.format("&#%02X%02X%02X", r, g, b)` (uppercase hex).
- `lerp(int from, int to, double fraction)` - Per-channel linear interpolation with `Math.round`.
- `countVisibleNonSpace(String line)` - Counts visible non-space characters skipping legacy codes (uses `codeLength`).
- `codeLength(String s, int i)` - Length of the legacy code starting at `i`: 8 for `&#RRGGBB`, 2 for a valid `&X` (`0-9`, `a-f`, `k-o`, `r`, `x`), 0 if there is no code.
- `isFormatChar(char c)` - `true` for `k`-`o` (formats: obfuscated, bold, strikethrough, underlined, italic).
- `isHex(String s, int from)` - Validates 6 consecutive hex digits.

#### Notes and gotchas

- Pre-existing COLOR codes (`&0`-`&f`, `&#RRGGBB`) are DISCARDED because the gradient overrides them, but since 1.9.0 they also EMPTY the accumulated format, matching vanilla legacy semantics (a color code resets earlier formats) and the reset `legacyToMini` applies outside the gradient. Before 1.9.0 only `&r` emptied it, so with a bold prefix inserted after `[rgb]` (`&#8354f2&lBrand &8| &7`) the `&l` survived its own trailing `&8`/`&7` and the whole gradient body rendered bold. FORMAT codes (`&l &o &n &m &k`) accumulate in a buffer (no duplicates) and are re-emitted after EACH interpolated hex, because every emitted hex resets formatting; `&r` also empties the accumulated format.
- Spaces copy through as-is and do NOT consume a gradient position: they receive no hex of their own and do not distort the anchor distribution.
- The output uses the `&#RRGGBB` format, which the rest of the pipeline later converts (to `<#RRGGBB>` in `color` or to a bungee sequence in `colorLegacy`).

### CenterUtil
`src/main/java/com/sn/lib/text/CenterUtil.java`

Final utility class (private constructor). Chat centering against the 154px half-width of the default chat window, using the vanilla font width table (DefaultFontInfo). Pure string transformation: it measures the visible pixels of a legacy-colored string and prepends the needed spaces. Private constants: `CENTER_PX = 154` (half of the default chat window width, in font pixels) and `SECTION = '§'`.

- `public static String center(String legacyColored)` - Prepends the spaces required to center the line at 154px. The parameter must be the FINAL legacy-colored string (with the gradient hex already interpolated). Returns the centered line, or the input unchanged if it is null, empty, or already wider than the target (`toCompensate <= 0`). Computes `toCompensate = 154 - px / 2` and adds spaces of `width(' ', false)` = 4px each until covered.

#### Internal logic

- `measure(String s)` - Sums the pixel width of the visible characters. Skips `&#RRGGBB` (advances 8 chars) and `&X` / `§X` codes; tracks bold state: `&l`/`§l` opens it and it is cleared by any COLOR code (`&0`-`&f`, `&#RRGGBB`) or `&r` (`clearsBold`), matching the vanilla reset the Component render now applies. Before 1.5.0 only `&r` cleared bold during measurement, which could over-measure a line where a later color had already dropped the bold.
- `width(char c, boolean bold)` - Pixel advance: table width plus 1px glyph gap; bold adds 1px extra except for spaces.
- `baseWidth(char c)` - Vanilla DefaultFontInfo width table (see table below). In the `default` branch, BEFORE the printable ASCII range check, small caps glyphs (detected via `SmallCapsUtil.isSmallGlyph`, package-private access within `com.sn.lib.text`) return base 5 like uppercase letters, except U+026A (the small i, narrow) which returns base 3 like the uppercase 'I'; without this branch they would fall into the fallback of 4 and a `[center][small]` line would come out shifted right.
- `isCodeChar(char c)` - Validates a legacy code character (`0-9`, `a-f`, `k-o`, `r`, `x`).
- `isHex(String s, int from)` - Validates 6 consecutive hex digits.

Pixel measurement table (base width per glyph, BEFORE adding the 1px gap and the bold +1px):

| Base width | Characters |
|---|---|
| 1 | `i` `!` `:` `;` `'` `.` `,` `\|` |
| 2 | `l` `` ` `` |
| 3 | `I` `t` `[` `]` `"` space |
| 4 | `f` `k` `(` `)` `{` `}` `<` `>` |
| 5 | rest of the printable ASCII range (`!` to `~`) |
| 6 | `@` |
| 5 | small caps glyphs of the `SmallCapsUtil` dictionary (except U+026A) |
| 3 | U+026A (the small caps i, measures like uppercase `I`) |
| 4 | fallback for unknown glyphs (outside `!`-`~`) |

The small caps glyph widths (5 and 3) are reasonable approximations adjustable in `baseWidth` only: the exact advances depend on the client's accented font bitmaps.

#### Notes and gotchas

- It can only measure LEGACY strings, never `Component`s: that is why the pipeline applies it as the last step of the legacy phase, before `legacyToMini` and MiniMessage deserialization. An unconverted MiniMessage tag inside the line would be measured as visible text.
- `measure` accepts codes with both `&` and `§`, but the hex form is only skipped as `&#RRGGBB` (the bungee sequence `§x§R...` is still skipped because each `§X` pair is a valid code: `x` is in `isCodeChar`).
- The compensation uses normal 4px spaces (3 from the table + 1 gap): centering has 4px granularity, it is an approximation, not pixel-perfect.

### StylePolicy (v1.6)
`src/main/java/com/sn/lib/text/StylePolicy.java`

Reusable gate for player-supplied styled text: which styling forms an input may carry, and what to do when it carries a disallowed one. Immutable (built via `builder()` or `fromConfig(...)`), no Bukkit at evaluation time; the detection is a pure single-pass scanner covered by `StylePolicyTest`. Rendered text should go through `SnText.cosmetic`, never `SnText.color`/`mini`.

- `public enum Capability` - the styling forms a policy can allow: `LEGACY_COLOR` (`&0`-`&f`), `HEX` (`&#RRGGBB`), `BOLD`/`ITALIC`/`UNDERLINE`/`STRIKETHROUGH`/`OBFUSCATED` (`&l &o &n &m &k`), `MINIMESSAGE` (cosmetic tags as a whole - a `<red>` or `<bold>` is MiniMessage usage, not legacy usage) and `GRADIENT` (the `[rgb]` prefix tag plus the `<gradient>`/`<rainbow>` tags).
- `public enum OnDisallowed` - `REJECT` (drop all styling, keep plain visible text) or `STRIP` (remove only the disallowed styling, keep the allowed styling and the text).
- `public static StylePolicy fromConfig(@Nullable ConfigurationSection section, String path)` - reads the `path` subsection. Keys: `enabled` (default true), `allow-legacy-colors`, `allow-hex`, `allow-bold`, `allow-italic`, `allow-underline`, `allow-strikethrough`, `allow-obfuscated`, `allow-minimessage`, `allow-gradient` (each default false, deny by default) and `on-disallowed` (`reject`/`strip`, default `reject`). A null section, a missing subsection or `enabled: false` yields a `disabled()` plain-text-only policy.
- `public static StylePolicy disabled()` - plain-text-only policy: any styling is a violation.
- `public static Builder builder()` - programmatic builder; starts enabled, allowing nothing, rejecting. Methods: `enabled(boolean)`, `allow(Capability...)`, `allowAll()`, `disallow(Capability...)`, `onDisallowed(OnDisallowed)`, `build()`.
- `public boolean isEnabled()` / `public boolean isPlainOnly()` (no capability allowed) / `public boolean isAllowed(Capability)` / `public Set<Capability> allowed()` (immutable view) / `public OnDisallowed onDisallowed()`.
- `public List<Capability> violations(String input)` - the disallowed capabilities the input actually uses, in `Capability` declaration order; empty when acceptable. SAFETY rule: a non-cosmetic MiniMessage tag (click, hover, font, ...) always contributes `MINIMESSAGE`, EVEN when `MINIMESSAGE` is allowed - `allow-minimessage` only ever unlocks the cosmetic subset.
- `public boolean accepts(String input)` - whether `violations(input)` is empty.
- `public String apply(String input)` - enforces the policy: an acceptable input is returned unchanged; otherwise `STRIP` -> `strip(input)`, `REJECT` -> `SnText.plain(input)`. Null in, null out.
- `public String strip(String input)` - removes the disallowed styling while keeping the allowed styling and all visible text. Section codes are normalized to `&` first (`SnText.normalizePapiOutput`); non-cosmetic MiniMessage tags are always removed; `&r` and `<reset>` are always kept. The leading `[center]`/`[rgb]`/`[small]` prefix run is preserved, dropping `[rgb]` only when `GRADIENT` is disallowed.

#### Internal logic (detection)
- `analyze(String)` returns a `Usage` struct (per-form booleans, splitting `cosmeticMini` from `nonCosmeticMini` for the subset rule) by scanning the normalized input once, skipping `\<` escapes and consuming the leading prefix-tag run (`[rgb]` sets gradient usage).
- `classifyTag(String inner)` classifies a MiniMessage tag by the name inside `<...>` (stripping a leading `/` and `!`, cutting at `:`): `reset` -> `RESET`; `gradient`/`rainbow` -> `GRADIENT`; a `#hex`, a `COLOR_NAMES` entry or a `DECORATION_NAMES` entry -> `COSMETIC`; anything else -> `NON_COSMETIC`.
- `codeCapability(char)` maps a legacy code to its capability (`0-9`/`a-f` -> `LEGACY_COLOR`; `l/o/n/m/k` -> the decoration; `r` and unknowns -> null).

### TODOs and limitations

There are no TODO/FIXME markers in the files of this scope. Limitations documented in the code:

- `[rgb]` is intended for titles and short lines: it emits one hex code per visible character, which multiplies the string length (the `StringBuilder` reserves `line.length() * 9`). SnLang caches statically resolved lines to pay the cost only once.
- `CenterUtil` only measures legacy strings (never `Component`s) and assumes the default chat window (154px half-width) with the vanilla font table: resource packs with custom fonts or modified chat widths are not accounted for.
- `applyLocals` does not re-scan replacement values: a placeholder whose value contains another `%key%`/`{key}` token does not expand recursively (deliberate decision to avoid loops).
- The locals and PAPI steps are the caller's responsibility (SnYml getters): `color`/`colorLegacy` do not run them.
---

## 04. YML: reading, preprocessing and auto-update

Module `com.sn.lib.yml`: the entire life of a consumer plugin's YAML file goes through here. `YamlPreprocessor` repairs tab-indented YAML text before parsing (pure text, no Bukkit types). `SnYml` is the live view of ONE file: typed getters with placeholders and fallback + WARN, and coalesced async saving that switches to sync during shutdown. `YamlUpdater` is the line-based always-merge updater (no version key): it inserts whatever is missing relative to the jar resource while preserving the user's values, comments and extra keys, with pre-merge backups and recovery of corrupt files. `YmlManager` is the context's `sn.yml()` module: it registers each `SnYml` by path relative to the data folder and decides each file's mode (managed / managedPruning / seedOnly / data / load) at first mount.

### YamlPreprocessor
`src/main/java/com/sn/lib/yml/YamlPreprocessor.java`

Pure text preprocessor (`final` class with private constructor, statics only) that repairs tab-indented YAML before SnakeYAML rejects it. It rewrites each tab in a line's leading whitespace run to TWO spaces, leaving the rest of the line byte-for-byte intact (tabs inside quoted or plain values are preserved). Normalizes CRLF and lone CR to LF. It references no Bukkit types: testable in plain unit tests. `preprocess` never throws.

- `public record Result(String cleanText, List<Integer> fixedLines)` - result of `preprocess`: repaired text with LF line endings plus the list (copied immutable in the compact constructor) of 1-based line numbers whose indentation tabs were replaced; empty if nothing was repaired.
- `public static Result preprocess(String rawText)` - repairs indentation tabs; `null` is treated as empty; never throws and never returns null. It returns the corrected lines so the caller can emit ONE single warning.
- `public static String read(Path file) throws IOException` - reads the file as UTF-8; malformed byte sequences decode to the replacement character instead of failing, and a leading BOM (U+FEFF) is trimmed.

#### Internal logic: block scalar state machine

`preprocess` walks line by line with two state variables: `boolean enBlockScalar` and `int scalarIndent`.

1. Normal state: each line goes through `fixIndentTabs(line, n, fixedLines)` (replaces each tab of the whitespace prefix with `"  "` and records the 1-based line if it touched anything; the rest of the line copies verbatim). Then, if the CLEAN line ends in a block scalar indicator (`startsBlockScalar`), the block scalar state is entered and `scalarIndent = indentColumns(cleanLine)` (the HEADER's indent, not the content's).
2. Block scalar state: a blank line (`isBlank`: only spaces/tabs) or a line with `indentColumns(line) > scalarIndent` is scalar CONTENT and copies untouched (tabs there are literal content). The first non-blank line with indent `<= scalarIndent` closes the state and is processed as a normal line (which may in turn open another block scalar).
3. `indentColumns` measures the width of the leading whitespace in columns: space = 1, tab = 2 (same width as the replacement, so the measurement is consistent before and after repairing).
4. Indicator detection (`startsBlockScalar` + `isBlockScalarIndicator`): the comment is trimmed with `stripComment` (cuts at the first `#` outside quotes that is at the start of the line or preceded by a space/tab; handles single quotes, double quotes and `\` escapes inside doubles), the line is rstripped, and the last token is taken (from the last space/tab). The token is an indicator if it starts with `|` or `>` and its modifiers are empty or match `[0-9][+-]?|[+-][0-9]?` (chomping `+`/`-` and an indentation digit in any order, one digit max).

Private methods: `fixIndentTabs(String, int, List<Integer>)`, `startsBlockScalar(String)`, `isBlockScalarIndicator(String)`, `stripComment(String)`, `indentColumns(String)`, `isBlank(String)`, `rstrip(String)`.

#### Notes and gotchas
- The indicator's indentation digit (`|2`, `>4`) is accepted syntactically but NOT used to compute the content indent: scalar membership is decided only relative to the header's indent. It suffices for the goal (not touching literal content); it is not a full YAML parser.
- Since a tab is replaced by 2 columns and `indentColumns` counts a tab as 2, block scalar content detection is identical before or after the repair.

### SnYml
`src/main/java/com/sn/lib/yml/SnYml.java`

A YAML file owned by a consumer context (`Sn ctx`): tab-tolerant loading, placeholder-aware typed getters with fallback + WARN, and coalesced async saving. Instances are created by the context's `YmlManager`, one per file (package-private constructor `SnYml(Sn ctx, File file)` that runs `loadFromDisk()`). The `yaml` field is `volatile YamlConfiguration`: getters read the current snapshot from any thread.

- `public File file()` - the backing file on disk.
- `public String getString(String key, String def)` - resolved string; a missing key returns `def` silently. Delegates to the null-viewer variant.
- `public String getString(String key, String def, Player viewer)` - resolved string; PAPI tokens resolve per-viewer when one is passed.
- `public int getInt(String key, int def)` - integer; `Number`s are read directly (`intValue()`), strings are resolved (null viewer) and parsed with `Integer.parseInt(trim())`.
- `public double getDouble(String key, double def)` - same with `doubleValue()` / `Double.parseDouble`.
- `public long getLong(String key, long def)` - same with `longValue()` / `Long.parseLong`.
- `public boolean getBoolean(String key, boolean def)` - boolean; `Boolean` directly; from a string only the literals `true`/`false` parse (case-insensitive, after resolving and trimming).
- `public List<String> getStringList(String key, List<String> def)` - string list with each element resolved; a missing key returns `def` silently.
- `public List<String> getStringList(String key, List<String> def, Player viewer)` - same, resolving per-viewer; a null element is converted to `""` before resolving.
- `public ConfigurationSection getSection(String key)` - raw section or null if it does not exist; values read through it do NOT go through `resolve` (no placeholders).
- `public boolean isSet(String key)` - true when the key exists in the file, even with a 0/false/empty value; keeps "explicit 0" distinguishable from "missing key".
- `public void set(String key, Object value)` - sets the value in memory; `save()` must be called to persist.
- `public void setComments(String key, List<String> lines)` (1.8.0) - sets the in-memory block comments rendered above the key, one list entry per line without the leading `#`; null clears them. Persisted by `save()` (the yaml load path parses comments, so they round-trip). The write-surface companion of `set` so keys a plugin writes at runtime (setup wizards writing into seedOnly files) ship the same per-key documentation as shipped resources.
- `public void setInlineComments(String key, List<String> lines)` (1.8.0) - sets the in-memory inline comments after the key's value; null clears them.
- `public void save()` - persists the current state (see "coalesced save()" below).
- `public void flush()` - drains any pending save; invoked by the context teardown (see below).
- `public SnYml placeholder(String key, Supplier<String> value)` - registers a local placeholder resolved BEFORE any PAPI lookup; fluent (returns `this`).
- `public SnYml placeholders(Map<String, Supplier<String>> values)` - batch registration of local placeholders.
- `public void onReload(Runnable hook)` - registers a hook fired after each `reload()`.
- `public void reload()` - re-reads the file from disk (preprocessing tabs) and fires the reload hooks; a hook throwing `Throwable` only logs WARN "Reload hook failed for <file>: <t>" and does not stop the others.

#### Per-type resolution table of the getters

All getters share the same skeleton over `Object raw = yaml.get(key)`:

| Case | getString | getInt / getLong / getDouble | getBoolean | getStringList |
|---|---|---|---|---|
| `raw == null` and `!isSet(key)` (missing key) | `def` silently | `def` silently | `def` silently | `def` silently |
| `raw == null` but `isSet(key)` (key with explicit null value) | WARN + `def` | WARN + `def` | WARN + `def` | WARN + `def` |
| Expected native type | `String` -> `resolve(s, viewer)` | `Number` -> `intValue()/longValue()/doubleValue()` (no resolve) | `Boolean` -> direct | `List<?>` -> each element `String.valueOf` + `resolve` (null -> `""`) |
| `String` (for non-string types) | n/a | `resolve(s, null).trim()` + parse; `NumberFormatException` -> WARN + `def` | `resolve(s, null).trim()`; only `"true"`/`"false"` ignore-case; anything else -> WARN + `def` | n/a (a string is NOT a list: WARN + `def`) |
| Other type | WARN + returns `resolve(String.valueOf(raw), viewer)` (NOT `def`) | WARN + `def` | WARN + `def` | WARN + `def` |

The WARN is always `warnInvalid`: `"Invalid value in <file> -> '<key>': received '<value>', using default '<def>'"` (in the special getString wrong-type case the message says "using default" but the method returns the stringified, resolved value, not `def`).

Placeholder resolution (`resolve(String s, Player viewer)`, private): locals first via `SnText.applyLocals(s, this::localValue)`; then, only if any `%` remains in the text, PAPI: on the primary thread it delegates to `ctx.papi().apply(viewer, out)` (identity if the papi service is still null during context construction); OUTSIDE the main thread PAPI tokens stay intact and the skip is recorded through `ctx.debug()` (if non-null): "PAPI skipped off the main thread in <file>; tokens untouched: <text>". Viewerless getters resolve PAPI against the server (null player). Numeric and boolean getters always resolve with a null viewer.

#### Coalesced async save() + sync switch + anti stale-write guard

State: `saveLock` protects `pendingSnapshot`, `pendingSeq`, `pendingWrite`, `writeScheduled` and the `saveSeq` counter; `ioLock` protects the physical write and `lastAttemptedSeq`.

1. `save()` takes the serialized snapshot (`yaml.saveToString()`) ON the calling thread: what persists is the state at save time, not at write time.
2. If `ctx.isShuttingDown()`: under `saveLock` the `pendingSnapshot` is discarded (covered by this newer snapshot), `seq = ++saveSeq` is taken, and it writes SYNCHRONOUSLY on the calling thread via `writeToDisk` - never through the scheduler (which may already be rejecting tasks).
3. Normal runtime: under `saveLock` it replaces `pendingSnapshot`/`pendingSeq` (coalescing: at most ONE pending write per file; a newer save overwrites the pending snapshot) and, if no drain is scheduled (`!writeScheduled`), schedules `ctx.scheduler().supplyAsync(this::drainPendingWrites)` and stores the future in `pendingWrite`. A `whenComplete` resets `writeScheduled = false` ONLY if the future ended with an error (if the async never ran, a later save can reschedule); on the happy path the drain itself resets it.
4. `drainPendingWrites()` (runs on the async pool): a loop that under `saveLock` steals the pending snapshot and clears it; if there is nothing, it turns off `writeScheduled` and finishes; if there is, it writes outside the lock via `writeToDisk` and iterates again (thus consuming saves that arrived while it was writing).
5. `writeToDisk(String content, long seq)`: under `ioLock`, sequence guard - if `seq <= lastAttemptedSeq` it returns without writing. The code comment states it literally: a snapshot older than one already attempted NEVER overwrites the newer state (async drain vs synchronous teardown save race). It then creates the parent directories if needed and writes UTF-8; an `IOException` only logs WARN "Could not save <file>: <msg>".

`flush()`: copies `pendingWrite` under `saveLock` and if present does `get(10, TimeUnit.SECONDS)` (an `InterruptedException` re-asserts the interrupt; any other exception or timeout is ignored because the remainder is handled below). Then, under `saveLock`, it steals the leftover `pendingSnapshot` (case: the scheduler rejected or cancelled the async and it never ran) and writes it synchronously with its `pendingSeq`. Invoked by the context teardown (via `YmlManager.flushAll()`) so no coalesced write is ever lost.

#### Notes and gotchas
- `loadFromDisk()` with a nonexistent file leaves an empty `YamlConfiguration`; on `IOException`/`InvalidConfigurationException` it does NOT overwrite the state: WARN "Could not read <file>: <msg>; keeping the previous content" and the previous yaml stays current.
- If the preprocessor repaired lines, ONE warning is emitted: "Indentation tabs corrected in <file> (lines [...])".
- `locales` is a `ConcurrentHashMap` and `reloadHooks` is a `CopyOnWriteArrayList`: safe registration from any thread.

### YamlUpdater
`src/main/java/com/sn/lib/yml/YamlUpdater.java`

LINE-based always-merge YAML updater (`final` class, private constructor, all static). It inserts keys/sections present in the jar resource but absent from the file on disk, preserving user values, comments, list content and extra keys the user added. There is NO version key (`config-version`): the resource is compared structurally against disk ON EVERY startup. By default it never deletes anything or reformats existing lines; deleting keys absent from the resource only happens with explicit prune. Synchronous I/O by design: `update` runs only inside onEnable and the reload command, never during gameplay (documented exception to the lib's async-I/O rule).

Constants (private): `BACKUP_STAMP = DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")`, `BACKUPS_KEPT = 3`.

- `public static List<String> merge(List<String> resourceLines, List<String> diskLines)` - pure entry point of the merge: returns a copy of `diskLines` with each missing block (keys plus their attached comments) inserted at its anchored position. No I/O, testable in plain unit tests.
- `public static boolean isParseable(String yamlText)` - true when the text parses as YAML (`null` treated as empty); used to detect corrupt disk files before a merge. Pure.
- `public static void update(JavaPlugin plugin, String resourcePath, File diskFile, boolean prune)` - merges the jar resource into the disk file, seeding it if missing and backing it up if corrupt. The `update-configs` gate is read from the data folder's `config.yml`; when `diskFile` IS that config, it is exempt from the gate and always merges.
- `static void update(JavaPlugin plugin, String resourcePath, File diskFile, boolean prune, @Nullable File gateFile, boolean gateExempt)` - gate-aware variant (package-private) used by `YmlManager`, which knows the REAL config file declared in the consumer's spec (it may not be named `config.yml`).
- `public static boolean updateFromLines(JavaPlugin plugin, List<String> referenceLines, File diskFile, @Nullable File gateFile)` - variant whose reference lives in memory instead of the jar (e.g. a translation merged against the on-DISK `messages_en.yml`). Same semantics: seed if missing, backup-N + reseed if corrupt, keep-last-3 pre-merge backup and gate read from `gateFile` (`null` skips the gate). Returns true when the disk file changed (seeded, regenerated or merged). It does not support prune.
- `static void seedIfMissing(JavaPlugin plugin, String resourcePath, File diskFile)` - (package-private) seeds the file from the jar resource ONLY when it does not exist; never merges.
- `static boolean readUpdateConfigsGate(@Nullable File gateFile)` - (package-private) reads the master gate by parsing the config straight from DISK before any merge; a null gateFile, missing file, missing key or unreadable content all count as `true`.
- `public static List<String> prune(List<String> resourceLines, List<String> lines)` - pure entry point of the prune: returns a copy of `lines` with every block whose key-path does not exist in the resource removed, comments included. Opt-in only (via `managedPruning`). No I/O.

#### Internal logic: Node / insertions algorithm

A custom parser (`parse(List<String>)`) builds a tree of `Node` (private inner class: `key`, `indent`, `keyLine`, `blockStart`, `blockEnd`, `children`, and `findChild(String)` that compares normalized keys via `unquoteKey`: a key wrapped in balanced quotes `'...'` or `"..."` counts the same as the unquoted one). It walks line by line with a stack:

- Empty or comment lines accumulate `pendingCommentStart`: comments preceding a key belong to its block (`blockStart`), and they also mark the boundary when closing earlier blocks, so a comment "hangs" from the key that follows it, not the previous one.
- List items (`- ` or lone `-`) are skipped: they are part of the current node's VALUE; comments above them stay attached to whatever they originally headed (typically the parent key).
- A line without an "unquoted" colon (`findUnquotedColon`: colon outside quotes followed by whitespace or end of line; a `#` outside quotes aborts) is treated as a multiline scalar continuation and skipped.
- On finding a key: all nodes with `indent >= indent` of the new one are popped from the stack (assigning them `blockEnd = boundary - 1`), the child node of the top is created and pushed.

Planning (`planInsertions`): parses resource and disk, and `collectInsertions` walks the resource's children recursively; a child absent from disk produces an `Insertion` (private inner class: `position`, `sequence`, `lines`) whose block is the RESOURCE's `blockStart..blockEnd` lines (leading comments included) and whose position comes from `computeInsertPosition`; a present child recurses to insert only what is missing inside.

Anchoring (`computeInsertPosition`): 1) the closest PRECEDING sibling that exists in both resource and disk -> insert right after its `blockEnd`; 2) if none, the closest shared FOLLOWING sibling -> insert right before its `blockStart` (preserving its leading comments); 3) no siblings on disk -> end of the parent (root `indent < 0` -> `diskLines.size()`; parent with children -> `blockEnd + 1` of the last child; otherwise -> `blockEnd + 1` of the parent).

Application: insertions sort by `position` descending and, on ties, by `sequence` (the `blockStart` in the resource) descending; `applyInsertions` does `addAll(pos, lines)` with `pos` clamped to `lines.size()`. Inserting bottom-up avoids recomputing offsets, and the sequence tiebreak makes multiple blocks at the same position land in resource order.

Prune (`collectRemovals`): mirror of the merge - each DISK child absent from the resource contributes a `[blockStart, blockEnd]` range (comments included); ranges sort by start descending and are removed line by line from back to front, with `end` clamped to the list size.

#### `update` flow (gate, backups, corruption)

1. `readResource`: if the resource is not in the jar -> WARN "[update-configs] Resource <path> missing from the jar; <file> cannot be updated" and return.
2. Nonexistent disk file -> `seed` (creates parent directories and writes the resource lines UTF-8) and return.
3. Corruption: the disk is read with `YamlPreprocessor.read` + `preprocess` and validated with `isParseable`; if it does NOT parse -> `backupCorrupt` MOVES the file to `<name>.backup-N` (N = first free integer from 1, never overwrites a previous backup), it reseeds from the jar and logs WARN "[update-configs] <file> does not parse as YAML: backed up at <backup> and regenerated from the jar". It never crashes the caller.
4. In-memory merge: `planInsertions` + `applyInsertions` over the disk lines; with `prune=true` `prune` is applied as well. If the result is identical to disk, it returns without touching anything (no backup, no gate, no log).
5. Gate: only if there were changes and `!gateExempt`, `readUpdateConfigsGate(gateFile)` is read (fresh parse from DISK, so a previous merge of the config itself already counts); when `false` it logs a WARN and does NOT write: "N keys missing in <file>" when there are insertions, or "prune pending in <file>" when the change was prune-only.
6. Write: `backupBeforeMerge` COPIES the current file to `old-<base>-<yyyyMMdd-HHmmss>.yml` next to it (exact timestamp with `BACKUP_STAMP`), and `pruneOldBackups` deletes the oldest keeping only the last `BACKUPS_KEPT = 3`; the match is EXACT by regex `old-<base>-\d{8}-\d{6}\.yml` (the code comment explains why: a loose prefix would mix in the backups of another file whose name extends the base, e.g. `config` vs `config-extra`). An old backup that cannot be deleted never blocks the merge. Finally the resulting lines are written UTF-8.
7. Errors: `IOException` -> SEVERE "[update-configs] Failed updating <file>: <msg>"; `RuntimeException` (parsing) -> SEVERE "[update-configs] Parse error merging <resource> into <file>: <msg>". Never propagates.

`updateFromLines` follows the same flow with three differences: the reference is in-memory lines (no step 1), it returns early with `false` if there are no insertions (no prune), and it returns a boolean indicating whether the disk changed. Two overloads: `updateFromLines(JavaPlugin, ...)` and `updateFromLines(Logger, ...)`. The `Logger` variant (1.5.0) exists for callers that hold a reference in memory but no `JavaPlugin` handle - the GUI seeder reads each `guis/*.yml` reference straight from the consumer jar and merges it through this same path; the `JavaPlugin` overload just delegates to it with `plugin.getLogger()`.

#### Notes and gotchas
- No `config-version`: the comparison is structural against the resource on every startup; adding a key to the jar resource is enough for it to reach every server.
- The gate is checked AFTER computing the result and only if it differs: a startup without changes logs nothing even with the gate off.
- The own-config exemption exists so the `update-configs` key itself can arrive by merge on the first post-upgrade startup.
- `findChild` compares keys normalizing quoting (`unquoteKey`): `foo`, `'foo'` and `"foo"` count as the same key both in the insertion plan and in the prune. Normalization is ONLY for comparison: when inserting, the resource's textual form is copied verbatim and existing disk lines are never reformatted.
- Limitation documented in the Javadoc: indentation is assumed to be spaces and consistent between resource and disk (both come from the same plugin baseline).

### YmlManager
`src/main/java/com/sn/lib/yml/YmlManager.java`

Yml module of a consumer context, reached via `sn.yml()`. It owns all the plugin's `SnYml`s, keyed by path relative to the data folder in a `LinkedHashMap` (mount order preserved), and mounts the managed main config AT CONSTRUCTION. All map access is synchronized on `entries`; iterations (`reloadAll`, `flushAll`) work over a `snapshot()` copied under the lock. Synchronous I/O by design: mount and `reloadAll()` run only in onEnable and in the reload command.

Constants (private): `GATE_KEY = "update-configs"`, `GATE_COMMENT = "# Master gate of the always-merge updater: false skips every yml merge except this file."`. Private internal enum `Mode { MANAGED, SEED_ONLY, PLAIN }` and private internal record `Entry(SnYml yml, String resourcePath, Mode mode, boolean prune, boolean isConfig)`.

- `public YmlManager(Sn ctx, String configName)` - creates the manager and mounts the managed main config; `configName` is the config file declared in the spec (e.g. `config.yml`). Instantiated by the context.
- `public SnYml config()` - managed main config; the master `update-configs` key is seeded if missing.
- `public SnYml managed(String path)` - managed file: seeded if missing, always-merged from the jar resource, never pruned.
- `public SnYml managedPruning(String path)` - managed with opt-in prune: keys removed from the jar resource are deleted from disk. It is the ONLY mode that deletes.
- `public SnYml seedOnly(String path)` - seeded from the jar if missing; existing content is never merged or touched.
- `public SnYml data(String path)` - runtime data file fully owned by the plugin: never seeded, never merged.
- `public SnYml load(String path)` - arbitrary yml under the data folder, read as-is: never seeded, never merged. (Same implementation as `data`: both mount in `Mode.PLAIN`; the distinction is semantic.)
- `public void reloadAll()` - re-runs the merge of every managed file (the config FIRST, by insertion order, so the gate is fresh for the rest) and the seeding of missing seedOnly files, then reloads every mounted file from disk firing its reload hooks.
- `public void flushAll()` - drains the pending coalesced write of every mounted file; used by the context teardown.

#### Internal logic
- `mountConfig()` (private): calls `YamlUpdater.update(plugin, configPath, disk, false, null, true)` - null gate and `gateExempt=true`: the config itself ALWAYS merges. Then `ensureGateKey(disk)` and registers the entry with `isConfig=true`.
- `mount(String rawPath, Mode mode, boolean prune)` (private): normalizes the path and, under the lock, if an entry for that path already exists it returns its `SnYml` WITHOUT re-running anything: the mode is decided by the FIRST mount of each path. A later mount with a different mode (or different prune) returns the existing instance and logs a single per-path WARN (`yml '<path>' already mounted in mode <MODE>; ignoring mode <MODE>`, with names `MANAGED`/`MANAGED_PRUNING`/`SEED_ONLY`/`PLAIN` via the `describe` helper, deduped in the `modeConflictWarned` set accessed under the `entries` lock). For MANAGED it runs `YamlUpdater.update(..., prune, gateFile(), false)` (subject to the gate), for SEED_ONLY `seedIfMissing`, for PLAIN nothing.
- `ensureGateKey(File disk)` (private): guarantees `update-configs` exists in the DISK config. If the file does not exist, it creates it with just the comment and `update-configs: true`. If it exists, it parses it (preprocessed); if the key is already there, nothing is touched; if it is missing, it APPENDS at the end a blank line (only if the last line is not empty), the `GATE_COMMENT` and `update-configs: true`. I/O or parse errors -> WARN "Could not seed the update-configs key in <file>: <msg>".
- `gateFile()` / `fileFor(String)` (private): resolve files against `ctx.plugin().getDataFolder()`; the gate is the config declared in the spec, not a hardcoded `config.yml`.
- `normalize(String)` (private static): backslashes to `/` and trims leading slashes, so `"gui\\menu.yml"` and `"/gui/menu.yml"` key the same.
- `snapshot()` (private): a copy of the entries under the lock to iterate without holding the monitor.

#### Notes and gotchas
- A file's mode is nailed at the first mount: asking for `managed("x.yml")` and later `data("x.yml")` returns the same managed `SnYml`, with a single per-path WARN that makes the conflict visible (behavior does not change: the first mount rules).
- In `reloadAll` the config's merge runs before the other files because `entries` is a `LinkedHashMap` and the config is inserted in the constructor: the fresh value of `update-configs` (even freshly merged) governs the rest of the cycle.
- `ensureGateKey` writes the file directly (line append), not via `SnYml.save()`: it happens before the config's `SnYml` is created.

### TODOs and limitations

There are no TODO/FIXME/XXX markers in the files of this scope. Limitations documented in code/Javadoc:

- `YamlUpdater`: assumes space indentation consistent between resource and disk (both come from the same plugin baseline); it does not handle tabs in the merge algorithm (the preprocessor covers that at READ time, not in the line merge).
- `YamlUpdater.parse`: list items are treated as the current node's value, so comments above a list item stay attached to the parent key; keys are compared normalizing balanced quoting (quoted vs unquoted count the same).
- `YamlPreprocessor`: a block scalar's indentation digit (`|2`) is accepted but not used to compute the content indent; detection is relative to the header line's indent.
- `YamlUpdater.update` / `YmlManager.mount` / `reloadAll`: synchronous I/O by design, valid only in onEnable and the reload command (documented exception to the async-I/O rule).
- `YmlManager.mount`: the mode is fixed by the first mount of the path; later mounts with a different mode do not re-run merges (they warn with a single per-path WARN).
---

## 05. Lang and Debug

This module groups two per-consumer-context services: `SnLang` (localizable messaging, reached via `sn.lang()`) and `SnDebug` (runtime-toggleable debug logging, reached via `sn.debug()`). SnLang manages the consumer plugin's `lang/messages_<code>.yml` files: it seeds the English from the jar, merges the shared `snlib.*` keys that travel inside SnLib.jar, merges translations against the on-disk English, resolves per-key fallback with a single WARN and caches static messages pre-rendered. SnDebug offers verbosity levels, filtered categories, lazy `Supplier`s so strings are not built in vain, and persistence of every toggle to the main config. All load I/O is synchronous by design: it runs only in onEnable and the reload command, never during gameplay.

### SnLang

`src/main/java/com/sn/lib/lang/SnLang.java`

Language module of a consumer context (`public final` class). Instantiated by the context when the spec declares `lang()`; consumers reach it via `sn.lang()`. Files live under `lang/` in the consumer's data folder. On every load: it seeds `lang/messages_en.yml` from the consumer's jar, merges the `snlib-messages.yml` resource of SnLib.jar on top, and if the config asks for another language it loads and merges the translation. Lang files carry NO version marker: the merge is structural and always-on.

Public methods:

- `public SnLang(Sn ctx, @Nullable SnYml config)` - Constructor: stores the context and the mounted main config (which provides the `lang` and `update-configs` keys, or null if the config module was not declared), registers in `QuitCleanupListener` the callback that cancels the departing player's persistent actionbar (idempotent against kick+quit, BossBarUtil pattern) and runs `load()` immediately (seed + merges + caches).
- `public String language()` - Active language code; returns `en` when the configured language fell back.
- `public void send(Player target, String key, Ph... phs)` - Sends the message to a player; single-line values get the prefix prepended. Delegates to the `CommandSender` overload.
- `public void send(CommandSender target, String key, Ph... phs)` - Sends the message to any sender; PAPI resolves per-viewer when the sender is a `Player`. Null-safe: a null target or key is a no-op.
- `public void broadcast(String key, Ph... phs)` - Broadcast to the whole server via `Bukkit.getServer()` as an Audience; PAPI resolves against the server (null viewer).
- `public Component get(String key, Ph... phs)` - First rendered line of the message, WITHOUT prefix. With no placeholders it uses the pre-rendered cache if present. A missing key yields `<missing:key>`.
- `public String getLegacy(String key, Ph... phs)` - First line as a legacy string with section codes (via `SnText.colorLegacy`), for APIs that still require legacy text; same resolution and fallback as `get`. A missing key returns the string `<missing:key>` (and fires the single WARN).
- `public List<Component> getList(String key, Ph... phs)` - All lines of the message rendered in order; missing keys produce a list with the marker line. With no placeholders it returns a copy of the pre-rendered cache.
- `public void actionbar(Player target, String key, Ph... phs)` - Shows the first line in the player's action bar; an empty line or empty key is a no-op, a missing key sends the marker to the action bar.
- `public void actionbar(Player target, String key, Duration hold, Ph... phs)` - Persistent overload (v1.1). Lifecycle: the line is rendered exactly ONCE at call time (PAPI/locals frozen; for dynamic content the consumer re-calls, which replaces), sent immediately and re-sent every 40 ticks (`ACTIONBAR_REFRESH_TICKS`; the vanilla actionbar lasts ~2-3s) until `hold` runs out (deadline via `System.nanoTime()`); on expiry it sends `Component.empty()` to clear it. A null, zero or negative `hold` delegates to the 3-arg overload; a missing key sends the marker ONCE without a timer. A new actionbar with hold for the same player replaces and cancels the previous one (one `TaskHandle` per player in the `persistentBars` map); the timer is cancelled on quit via the `QuitCleanupListener` callback and the `scheduler.cancelAll()` of `Sn.shutdown()` sweeps the remaining handles. A plain actionbar sent during a hold will be overwritten on the next 40-tick refresh.
- `public void title(Player target, String key, Ph... phs)` - Shows the message as a title. The first line parses as `title;subtitle;fadeIn;stay;fadeOut` (times in ticks, defaults 10;70;20, converted to millis by multiplying by 50); omitted or non-numeric parts fall back to their default.
- `public void reload()` - Re-runs the seed, the merges and the re-read of both language files from disk, rebuilding all caches. Invoked only from onEnable and the reload command.

Constants: it exposes no public constants or enums. Internally it uses the private constants `LANG_DIR = "lang"`, `FALLBACK_CODE = "en"`, `CONSUMER_RESOURCE = "lang/messages_en.yml"`, `SNLIB_RESOURCE = "snlib-messages.yml"` and `ACTIONBAR_REFRESH_TICKS = 40` (re-send period of the persistent actionbar). Per-instance state of the persistent actionbar: `Map<UUID, TaskHandle> persistentBars` (ConcurrentHashMap, one timer per player).

#### Internal logic

Private methods, in load-flow order:

- `private void load()` - Orchestrates the load: clears `warnedKeys`, seeds the English, merges the snlib keys, parses the fallback, decides the desired code (if it is `en`, active = fallback; otherwise it loads the translation), caches the prefix, rebuilds the caches and finally runs `warnLiteralPrefixToken` and `warnInteractiveTagDrift`.
- `private void warnLiteralPrefixToken()` (1.5.0) - Defense-in-depth: SnLib prepends the configured `prefix` to single-line messages automatically, so a value that also embeds the literal prefix placeholder token (`LITERAL_PREFIX_TOKEN`, the `prefix` key in `{ }` placeholder form) would render it verbatim. Scans every leaf value of the active language file once per load and, when one or more keys carry the token, logs ONE summary WARN naming how many keys are affected (`N message key(s) in lang/messages_<code>.yml embed the literal ... token; ... remove it from those values`). One warning per load, never per key. Pure helpers `countKeysWithLiteralPrefixToken(Map)` and `carriesLiteralPrefixToken(List)` (a multi-line value counts once) are package-private and unit-tested.
- `private void warnInteractiveTagDrift()` (1.8.0) - Defense-in-depth: the always-merge updater never rewrites an existing lang value, so a `<click:...>`/`<hover:...>` tag present in the jar reference but lost from the live value (admin edit, translation) keeps the button LOOK while the click silently dies. Compares the consumer jar resource (`CONSUMER_RESOURCE`) against the resolved templates once per load and, when any key lost a tag kind its default carries, logs ONE summary WARN naming the affected keys (`N message key(s) in lang/messages_<code>.yml lost the <click>/<hover> tag their jar default carries (keys); the button still renders but clicking it does nothing`). Pure helpers `lostInteractiveMarker(List, List)` and `containsMarker(List, String)` (case-insensitive, per tag kind via `INTERACTIVE_MARKERS`) are package-private and unit-tested.
- `private @Nullable YamlConfiguration parseResource(String path)` (1.8.0) - Parses a jar resource as YAML (tab-tolerant via `YamlPreprocessor`); null when absent or unreadable.
- `private void seedEnglish(File dir, File enFile)` - Seeds `lang/messages_en.yml` from the consumer's jar via `YamlUpdater.update(plugin, CONSUMER_RESOURCE, enFile, false)` (always-merge, gated by `update-configs`). If the consumer's jar does NOT include the resource and the file does not exist, it creates a minimal two-comment-line file and emits a WARN ("The X jar does not include lang/messages_en.yml; a minimal file was created").
- `private void mergeSnlibKeys(File enFile)` - Merges SnLib.jar's `snlib-messages.yml` resource into the on-disk `messages_en.yml` via `YamlUpdater.merge(resource, disk)`. Always on and EXEMPT from the `update-configs` gate: the `snlib.*` keys are the library's own message contract. If the disk does not parse as YAML (checked with `YamlUpdater.isParseable` over the preprocessed text) it skips the merge with a WARN; if the merge changes nothing, it does not rewrite the file. A resource missing from SnLib.jar produces a WARN and no merge.
- `private void loadTranslation(File dir, File enFile, String code)` - Loads a non-English translation: if `messages_<code>.yml` does not exist, WARN and fallback to English (activeCode returns to `en`); if it exists, it merges against the on-disk English, parses, and if it ended up empty or corrupt it also falls back to English with a WARN.
- `private void mergeTranslation(File enFile, File langFile)` - Merges missing keys using the on-DISK `messages_en.yml` as reference via `YamlUpdater.updateFromLines(plugin, reference, langFile, config.file())` (gated by `update-configs`). If there were changes it logs INFO "[update-configs] New keys from messages_en.yml added to lang/<file>; translate them when convenient".
- `private String desiredCode()` - Reads the main config's `lang` key; null, blank or absent config return `en`; the value is trimmed and lowercased with `Locale.ROOT`.
- `private YamlConfiguration parseFile(File file)` - Tab-tolerant parse via `YamlPreprocessor`: corrected indentation tabs are reported with a WARN naming the lines; unreadable content produces an empty configuration plus a WARN.
- `private void cachePrefix()` - Caches the top-level `prefix` value as a raw string: first from the active language, otherwise from the fallback (only if they are different objects); absent leaves `""`.
- `private void buildCaches()` - Clears and rebuilds `templates` (raw lines per key, fallback already resolved) and `rendered` (pre-rendered Components for keys whose lines have no tokens). It walks the union of leaf keys of fallback and active (LinkedHashSet, fallback first).
- `private static Set<String> leafKeys(YamlConfiguration cfg)` - Leaf keys (not sections) of the yml, in order of appearance.
- `private @Nullable List<String> linesFor(String key)` - Active language first; a key present only in English falls back with ONE single WARN per key ("Key 'X' missing from messages_<code>.yml; using the messages_en.yml value"), deduplicated via `warnedKeys` with the mark `fallback:<key>`.
- `private static @Nullable List<String> readLines(YamlConfiguration cfg, String key)` - A YAML list as-is, a string as a one-element list, any other type returns null.
- `private static boolean isStatic(List<String> lines)` - Static means renderable once: no line contains `%` (PAPI tokens) or `{` (local placeholders).
- `private void deliver(Audience audience, @Nullable Player viewer, String key, Ph... phs)` - Common delivery of send/broadcast: a missing key sends the marker; a single-line message gets the prefix (and uses the pre-rendered cache only when no placeholders arrived AND the prefix will not be inserted - either none is configured or the line opts out via `[noprefix]`); multiline messages send line by line WITHOUT prefix.
- `private String withPrefix(String line)` - Inserts the configured prefix AFTER any leading `[center]`/`[rgb]`/`[small]` tag (case-insensitive matching, in a loop, supports chained tags; `[small]` is skipped with its 7 chars): a prefixed message keeps its tags at position 0 so they keep rendering. The prefix inserted after the tag falls INSIDE the scope of `[small]` and comes out in small caps, consistent with `[rgb]` (which applies the gradient to the prefix). A `[noprefix]` tag anywhere in the leading tag run (1.9.0) makes it return the line unchanged; the tag itself is stripped later by the SnText render.
- `static boolean skipsPrefix(String line)` (1.9.0) - True when the line's leading tag run carries `[noprefix]`, case-insensitive and in any order among `[center]`/`[rgb]`/`[small]`. A tag after the first visible character does NOT opt out, consistent with every other prefix tag being leading-only. Package-private for tests.
- `private Component renderLine(String line, @Nullable Player viewer, Ph... phs)` - Fixed pipeline: `SnText.color(resolveLine(...))`.
- `private String resolveLine(String line, @Nullable Player viewer, Ph... phs)` - Locals via `SnText.applyLocals`, then per-viewer PAPI via `ctx.papi().apply`, then `SnText.normalizePapiOutput`.
- `private Component missing(String key)` - Returns `Component.text("<missing:" + key + ">")` and emits ONE single WARN per key ("Message key 'X' does not exist in lang/messages_en.yml"), deduplicated with the mark `missing:<key>` in `warnedKeys`.
- `private static long ticksPart(String[] parts, int index, long def)` - Numeric part of the title format; absent, blank or non-numeric returns the default.
- `private @Nullable List<String> snlibResourceLines()` - Reads `/snlib-messages.yml` from SnLib's classpath as UTF-8 lines; a missing resource or IOException return null.

#### Notes and gotchas

- Two-level fallback semantics: a key absent from the active language falls back to English with one WARN per key; a key ALSO absent from English renders as `<missing:key>` with another per-key WARN. Both WARNs are emitted once per key per load (the `warnedKeys` set clears on every `load()`/`reload()`).
- The prefix applies ONLY in `send`/`broadcast` and ONLY to single-line messages; `get`, `getLegacy`, `getList`, `actionbar` and `title` never add it, and list messages send line by line as-is. A single-line value can opt out with a leading `[noprefix]` tag (1.9.0); in every non-prefixing path the tag is simply stripped by the render, so it is harmless on list lines.
- The `rendered` cache pays the `[rgb]` interpolation once at load. In `deliver`, the cache is used when no placeholders arrived and the prefix will not be inserted (none configured, or the line carries `[noprefix]`); otherwise the render is per-call.
- The `snlib.*` merge is at the text-LINE level (`YamlUpdater.merge`) over the disk file, preserving existing values: admins can restyle any line and their changes survive updates.
- Translations merge against the on-DISK `messages_en.yml` (not the jar's), which is why they also receive the `snlib.*` keys and any key the consumer adds later.
- An empty string value on a key produces a no-op in `send`/`actionbar`/`title` and `Component.empty()` in `get`; different from a missing key, which produces the marker.
- Threading: mutable fields are `volatile` or concurrent collections (safe reads from any thread), but loading and merging are deliberate synchronous I/O, confined to onEnable and the reload command.
- `title` parses with `split(";", -1)`, so literal `;` inside the title are not escapable; the times are ticks converted to `Duration.ofMillis(ticks * 50)`.

### snlib-messages.yml

`src/main/resources/snlib-messages.yml`

Resource packaged inside SnLib.jar with the shared `snlib.*` message keys. The always-merge updater inserts on every startup any key missing from each consumer's `lang/messages_en.yml`; existing values are never overwritten. Each key carries a comment explaining when it is sent. Keys and placeholders (`{token}` format, resolved as locals by the SnLang pipeline):

| Key | Placeholders | Usage |
|---|---|---|
| `snlib.no-permission` | (none) | Sent when the sender lacks the permission required by a command or subcommand. |
| `snlib.usage` | `{usage}` | Sent on missing or malformed arguments; `{usage}` is the correct syntax. |
| `snlib.invalid-number` | `{value}` | Sent when an argument expected a number; `{value}` is the rejected input. |
| `snlib.invalid-value` | `{value}` | Sent when a value is not one of the accepted options; `{value}` is the rejected input. |
| `snlib.out-of-range` | `{min}`, `{max}`, `{value}` | Sent when a numeric argument falls outside its allowed range. |
| `snlib.player-not-found` | `{value}` | Sent when an argument expected an online player. |
| `snlib.unknown-subcommand` | `{value}` | Sent when the given subcommand does not exist. |
| `snlib.reload-done` | (none) | Sent to the sender after a successful reload. |
| `snlib.help.header` | `{plugin}` | Header line printed before the generated help entries; `{plugin}` is the plugin name (v1.2.1). |
| `snlib.help.entry` | `{usage}`, `{description}`, `{permission}` | One line per reachable leaf visible to the sender, rendered with its full path (`{description}` added in v1.2.1; default changed to `&e{usage} &7{description}` with no `&8:` separator in v1.6). |
| `snlib.help.footer` | `{page}`, `{total}`, `{command}` | Printed after the entries only when the help spans multiple pages; `{command}` is the root command name. |
| `snlib.teleport.warmup` | `{time}` | Sent when a warmup teleport starts; `{time}` is the warmup in seconds (v1.6, section 20). |
| `snlib.teleport.cancelled-move` | (none) | Sent when a pending teleport is cancelled because the player moved (v1.6, section 20). |
| `snlib.teleport.cancelled-damage` | (none) | Sent when a pending teleport is cancelled because the player took damage (v1.6, section 20). |
| `snlib.selection.pos1-set` | `{x}`, `{y}`, `{z}`, `{world}` | Sent when a selection wand sets position 1 (v1.1, section 18). |
| `snlib.selection.pos2-set` | `{x}`, `{y}`, `{z}`, `{world}` | Sent when a selection wand sets position 2 (v1.1, section 18). |
| `snlib.selection.different-worlds` | (none) | Sent when the two positions of a selection end up in different worlds (v1.1, section 18). |
| `snlib.selection.too-big` | `{volume}`, `{max}` | Sent when the selected cuboid exceeds the spec's `maxVolume` (v1.1, section 18). |
| `snlib.selection.no-permission` | (none) | Sent when the player lacks the selection wand's permission (v1.1, section 18). |
| `snlib.selection.timeout` | (none) | Sent when a selection session expires via the spec's timeout (v1.1, section 18). |

Default values use legacy `&` color codes (e.g. `&cYou do not have permission to use this command.`).

### SnDebug

`src/main/java/com/sn/lib/debug/SnDebug.java`

Runtime debug service of a consumer context (`public final` class), reached via `sn.debug()`: toggleable without restart, with string categories, lazy suppliers and persistence of every toggle. Output goes to the server logger prefixed by channel: `info` emits with `[<PluginName>][INFO] `, `log` with `[<PluginName>][DEBUG] ` and `trace` with `[<PluginName>][TRACE] `.

Public enum:

- `public enum Level { OFF, INFO, DEBUG, TRACE }` - Verbosity threshold, ascending ladder `OFF < INFO < DEBUG < TRACE`. Each channel emits from its rung: `info` from `INFO`, `log` from `DEBUG` and `trace` only at `TRACE`; `OFF` silences all channels.

Public methods:

- `public SnDebug(JavaPlugin plugin, @Nullable SnYml storage)` - Constructor: builds the three prefixes (`[Plugin][INFO] `/`[Plugin][DEBUG] `/`[Plugin][TRACE] `) and, if there is a backing yml (the mounted main config), restores `debug.enabled` (default false), `debug.level` (default `DEBUG`; an invalid value logs WARN "Invalid value in debug.level: 'X', using DEBUG" and uses `DEBUG`) and `debug.categories` (normalized to lowercase trim). With a null storage the toggles live in memory only.
- `public void info(String message)` - Logs on the INFO channel when the master toggle is on and the level is at least `INFO`.
- `public void info(Supplier<String> message)` - Lazy variant of the INFO channel (the supplier is not evaluated if the channel does not emit).
- `public void log(String message)` - Logs the message when debug output is enabled.
- `public void log(Supplier<String> message)` - Builds and logs the message lazily, ONLY if output is enabled (the supplier is not evaluated when off).
- `public void log(String category, Supplier<String> message)` - Builds and logs lazily under a category, honoring the category filter; the output is additionally prefixed with `[<normalized category>] `.
- `public void trace(Supplier<String> message)` - Lazy variant of the TRACE channel: emits only with the master toggle on and level `TRACE`.
- `public void trace(String category, Supplier<String> message)` - TRACE channel under a category, honoring the SAME category filter as `log(category, ...)` (an empty filter lets everything through); the output is additionally prefixed with `[<normalized category>] `.
- `public boolean enabled()` - True while output emits: master toggle on and level at least `DEBUG` (compared by `ordinal()`).
- `public boolean tracing()` - True while the TRACE channel emits: master toggle on and level `TRACE` (analogous to `enabled()`).
- `public boolean enabled(String category)` - True when the category passes: `enabled()` and an empty filter or one containing the (normalized) category.
- `public boolean toggle()` - Inverts the master toggle, persists it and returns the new state.
- `public boolean toggle(String category)` - Adds the category to the filter, or removes it if it was already there, and persists. Returns true when the category ended up inside the filter; an empty filter lets every category through.
- `public void setLevel(Level level)` - Sets the verbosity threshold and persists it; null is treated as `OFF`, which silences everything.
- `public Level level()` - Current verbosity threshold.

#### Internal logic

- `private void print(String prefix, String message)` - Emits via `Bukkit.getLogger().info(prefix + message)`: the server's GLOBAL logger, not the plugin's, and always at java.util.logging INFO severity (the "INFO"/"DEBUG"/"TRACE" is part of the channel's textual prefix).
- `private boolean infoEnabled()` - Internal gate of the INFO channel: master toggle on and level at least `INFO`.
- `private void persist()` - No-op without storage. With storage it writes `debug.enabled`, `debug.level` (enum name) and `debug.categories` (alphabetically sorted list) via `SnYml.set` plus `SnYml.save()`. Per the class Javadoc, the save is coalesced async at runtime and synchronous when the owning context is shutting down.
- `private Level parseLevel(String raw)` - `Level.valueOf` over the trimmed + `Locale.ROOT`-uppercased value; invalid falls to `DEBUG` with a WARN in the plugin's logger.
- `private static String normalize(String category)` - Trim + lowercase `Locale.ROOT`; applied to every category input and query.

#### Notes and gotchas

- Three channels with real thresholds: `info` emits from `INFO`, `log` from `DEBUG` and `trace` only at `TRACE`. At level `INFO` only `info` emits; at `DEBUG` `info` and `log` emit; at `TRACE` all three do; `OFF` silences everything.
- An empty category filter means "everything passes"; adding the first category with `toggle(String)` NARROWS the output to only the filtered ones.
- Categories normalize (trim + lowercase) both when persisting/toggling and when querying, so matching is case-insensitive.
- Thread-safe state: `enabled` and `level` are `volatile`, categories live in a concurrent set (`ConcurrentHashMap.newKeySet()`); `log` can be called from any thread, and `persist()` delegates the I/O cost to SnYml's coalesced save.
- Without a declared config module (null storage) the toggles work but do NOT survive a restart.

#### TODOs and limitations

None. There are no TODO/FIXME/placeholder markers in `SnLang.java`, `SnDebug.java` or `snlib-messages.yml`. Limitation documented in the code itself: SnLang's synchronous I/O is deliberate (confined to onEnable/reload).
---

# (Automatically generated section - current state of SnLib v1.1.0)

## 06. Scheduler, SoftDependency and Cron

This module groups three pieces of SnLib infrastructure: `SnScheduler` (Folia-aware scheduler with one instance per `Sn` context, plus its uniform `TaskHandle`), the reactive hook system `SoftDependency` (with its shared listener `HookListener` and the version comparator `SemverComparator`), and the calendar scheduler `SnCron` (with its pure parser `CronExpr`). The Folia claim is honest and bounded: detection plus no-crash (tasks go through the global region and async schedulers when `SnVersion.isFolia()` is true), but it is NOT a complete region-aware port; the GUI and items modules remain Paper-only. Both hooks and cron jobs live in per-owner registries (`TenantRegistry`), so a consumer's disable sweeps its resources without touching the others.

### SnScheduler
`src/main/java/com/sn/lib/scheduler/SnScheduler.java`

Folia-aware task scheduler bound to ONE owning plugin (one instance per `Sn` context). Every method forks on `SnVersion.isFolia()`: on Folia it uses `Bukkit.getGlobalRegionScheduler()` (sync) or `Bukkit.getAsyncScheduler()` (async); on Paper it uses `Bukkit.getScheduler()`. Delays/periods in ticks are forced to a minimum of 1; in Folia's async methods ticks convert to milliseconds (`ticks * 50L`, `TimeUnit.MILLISECONDS`). Always returns a `TaskHandle` (internal records `FoliaHandle` over `ScheduledTask` and `BukkitHandle` over `BukkitTask`).

- `public SnScheduler(JavaPlugin plugin)` - builds the scheduler bound to the owning plugin; all tasks schedule on that plugin's behalf.
- `public TaskHandle sync(Runnable task)` - runs on the main thread (global region on Folia).
- `public TaskHandle async(Runnable task)` - runs off the main thread (`runNow` of the async scheduler on Folia).
- `public TaskHandle syncLater(long delayTicks, Runnable task)` - runs on the main thread after `delayTicks` (minimum 1).
- `public TaskHandle asyncLater(long delayTicks, Runnable task)` - runs off the main thread after `delayTicks` (minimum 1; on Folia the delay is in ms).
- `public TaskHandle timer(long delayTicks, long periodTicks, Runnable task)` - repeating on the main thread; delay and period in ticks (minimum 1 each).
- `public TaskHandle timerAsync(long delayTicks, long periodTicks, Runnable task)` - repeating off the main thread; on Folia delay and period convert to ms.
- `public <T> CompletableFuture<T> supplyAsync(Supplier<T> supplier)` - computes a value off the main thread; a supplier failure (or scheduling against an already disabled plugin, `IllegalPluginAccessException`) completes the future exceptionally instead of throwing.
- `public <T> void thenSync(CompletableFuture<T> future, Consumer<T> consumer)` - consumes the future's value hopping to the main thread; the hop is skipped if the owning plugin is already disabled, the disable race inside the scheduler is absorbed catching `IllegalPluginAccessException`, and an exceptional completion logs ONE WARN ("Async task finished with an error: ...") and never reaches the consumer. The discarded-hop WARN is "Hop to main discarded: plugin disabled during scheduling".
- `public void cancelAll()` - cancels all tasks scheduled by the owning plugin (on Folia it cancels in the global region scheduler AND in the async scheduler; on Paper via `Bukkit.getScheduler().cancelTasks(plugin)`).

Internal (private) classes:
- `private record FoliaHandle(ScheduledTask task) implements TaskHandle` - `cancel()` delegates to `task.cancel()`; `isCancelled()` to `task.isCancelled()`.
- `private record BukkitHandle(BukkitTask task) implements TaskHandle` - the same pair of delegations over `BukkitTask`.

#### Notes and gotchas
- Honest Folia claim (literal Javadoc): support is "detection plus no-crash"; when `SnVersion.isFolia()` is true, tasks go through the global region scheduler and the async scheduler so scheduling never throws. It is NOT a complete region-aware port: there is no per-region or per-entity scheduling, and the GUI and items modules remain Paper-only.
- The tick-to-ms conversion in Folia async assumes a fixed 50 ms per tick.
- `supplyAsync`/`thenSync` are the recommended pattern for the async-compute + main-thread-apply cycle without exception leaks or Bukkit access off main.

### TaskHandle
`src/main/java/com/sn/lib/scheduler/TaskHandle.java`

Cancelable handle over a scheduled task, uniform across the Bukkit scheduler and Folia's. Two-method interface.

- `void cancel()` - cancels the task if still pending or repeating.
- `boolean isCancelled()` - true when the task was cancelled.

### SoftDependency
`src/main/java/com/sn/lib/hook/SoftDependency.java`

Reactive soft-dependency hook parameterized on `T` (the adapter type the factory produces), keyed by its owning plugin. It resolves lazily against the target plugin (present + enabled + semver gate + optional required class) and activates/deactivates live via `HookListener` when the target enables or disables. Each instance registers under its owner in a static `TenantRegistry<SoftDependency<?>>` (justified server-wide static: the registry itself; the content is keyed by owning plugin), whose sweep callback is `SoftDependency::forceDisable`: a consumer's disable removes its hooks and force-disables them without touching other consumers'. Internal state: `instance`, `resolved` and `disabled` are `volatile`; resolution (`resolve()`) is `synchronized` and idempotent.

Static methods:
- `public static <T> SoftDependency<T> of(JavaPlugin owner, String pluginName, Supplier<T> factory)` - creates a hook from `owner` against the plugin named `pluginName` and enrolls it in the per-owner registry. The factory must be the ONLY code referencing target classes, so an absent target never triggers a `NoClassDefFoundError` outside the isolated instantiation boundary.
- `public static void forEachRegistered(Consumer<SoftDependency<?>> action)` - applies the action to every registered hook of every owner; it is `HookListener`'s iteration source.
- `public static void targetDisabled(String pluginName)` - "parks" (deactivates) every hook of any owner pointing at `pluginName`; notification used by the sweeper. Name comparison is case-insensitive.

Instance methods:
- `public SoftDependency<T> minVersion(String version)` - requires the target's version to be at least `version` (semver gate via `SemverComparator`, with pre-release precedence: an installed `-SNAPSHOT` target does NOT satisfy the bare release); invalidates the current resolution and returns `this` (fluent).
- `public SoftDependency<T> requiresClass(String className)` - requires `className` to be loadable from the target plugin's classloader (`Class.forName(name, false, loader)`, without initializing); also invalidates and returns `this`.
- `public JavaPlugin owner()` - the hook's owning plugin; used for deferred enrollment in the per-owner registry.
- `public String pluginName()` - name of the target plugin this hook binds to.
- `public boolean isAvailable()` - true when the hook is active (resolving it first if needed); equivalent to `get().isPresent()`.
- `public Optional<T> get()` - the active adapter, resolving first if needed; empty when unavailable or the hook is `disabled`.
- `public void invalidate()` - discards the current adapter (`instance = null`, `resolved = false`); the next `get()` re-resolves.
- `public void forceDisable()` - disables the hook PERMANENTLY (consumer teardown): `disabled = true`, `instance = null`, `resolved = true`; it never resolves again.

Package-private methods (called by `HookListener`):
- `void refresh()` - re-resolves immediately (`invalidate()` + `resolve()`); called when the target enables.
- `void deactivate()` - parks the hook as unavailable WITHOUT lazy re-resolution (`instance = null`, `resolved = true`); called when the target disables, when the target can still report `isEnabled()` during its own `PluginDisableEvent`.

#### Internal logic
- `resolve()` (private, `synchronized`): if neither `disabled` nor `resolved`, it looks up the target via `Bukkit.getPluginManager().getPlugin(pluginName)`; it only instantiates if the target exists, is enabled, passes `versionOk` and passes `classOk`. It always leaves `resolved = true` (negative result cached until an `invalidate`/`refresh`).
- `versionOk(Plugin)`: without `minVersion` it always passes; if `SemverComparator.compareVersions(installed, required) < 0` it logs a WARN in the OWNER's logger: "Hook '<target>' requires version >= X (installed: Y); hook disabled". The comparison applies semver pre-release precedence: an installed `2.0.0-SNAPSHOT` target no longer passes a `minVersion("2.0.0")` (behavior change documented for the 1.1.0 changelog).
- `classOk(Plugin)`: catches `ClassNotFoundException` and `LinkageError`; on failure it logs WARN "Hook '<target>': required class <class> not found; hook disabled".
- `instantiate()`: isolated instantiation boundary; the factory runs ONLY here and any `Throwable` (including a `NoClassDefFoundError` from an adapter compiled against an absent API) is caught with WARN "Hook '<target>' failed to instantiate: ..." returning null, so a broken hook never propagates to the caller.

#### Notes and gotchas
- Full lifecycle: `of` (creates + registers per owner) -> fluent configuration `minVersion`/`requiresClass` (each invalidates) -> `get`/`isAvailable` (cached lazy resolution) -> live `refresh`/`deactivate` via `HookListener` -> manual `invalidate` if the consumer wants to force re-resolution -> terminal `forceDisable` via the `TenantRegistry` sweep when the owner disables.
- `deactivate` exists separately from `invalidate` on purpose: during the target's `PluginDisableEvent` it can still report `isEnabled() == true`, so an immediate lazy re-resolution would re-hook a dying plugin; `deactivate` leaves `resolved = true` with a null instance to avoid that.
- WARNs go through the OWNER plugin's logger (not the target's nor SnLib's), so each consumer sees its own diagnostics.

### HookListener
`src/main/java/com/sn/lib/hook/HookListener.java`

Shared listener that live-activates/deactivates each registered `SoftDependency` when its target plugin enables or disables. The iteration source is injected via constructor (unit-testable without the registry); the production instance enrolled in the ListenerHub bridges to `SoftDependency::forEachRegistered`.

- `public HookListener(Consumer<Consumer<SoftDependency<?>>> forEachDependency)` - receives the function that applies an action to every registered SoftDependency of every owner.
- `public void onPluginEnable(PluginEnableEvent event)` - (`@EventHandler`) calls `refresh()` on every hook whose `pluginName()` equals (case-insensitive) the enabled plugin's name.
- `public void onPluginDisable(PluginDisableEvent event)` - (`@EventHandler`) calls `deactivate()` on every hook whose target matches the disabled plugin.

#### Notes and gotchas
- Literal wiring note from the Javadoc: this listener is defined here unit-testable and ENROLLED in the ListenerHub; the `registerEvents` call happens SOLELY in the SnLibPlugin bootstrap (step 31). Never register it anywhere else.

### SemverComparator
`src/main/java/com/sn/lib/hook/SemverComparator.java`

Pure version comparator for plugin version gates, with semver pre-release precedence. No Bukkit dependency. Implements `Comparator<String>`.

- `public int compare(String left, String right)` - delegates to `compareVersions` (allows using it as a `Comparator`).
- `public static int compareVersions(String left, String right)` - negative when `left` is older than `right`, zero when equivalent, positive when newer. Ignores build metadata (`+...`) on both sides.

#### Internal logic
- Build metadata: everything from the first `+` is ignored (`1.0.0+build.5` == `1.0.0`, semver.org item 10).
- The core compares segment by segment as NUMBERS (any digit count per segment, so `1.10 > 1.9`); missing trailing segments count as 0 (`1.2 == 1.2.0`).
- With tied cores, a version WITH a pre-release (everything from the first `-`) PRECEDES the bare release: `2.0.0-SNAPSHOT < 2.0.0`. An empty pre-release after the `-` (`"1.0.0-"`) leniently counts as no qualifier.
- Two pre-releases compare by `.`-separated identifiers left to right: both all-digits -> numeric comparison without overflow (by string length without leading zeros and then lexicographic); numeric < alphanumeric; both alphanumeric -> case-sensitive ASCII order (`String.compareTo`). If all shared identifiers tie, the one with MORE identifiers wins (`1.0.0-alpha < 1.0.0-alpha.1`).
- The full semver.org ladder is covered by test: `1.0.0-alpha < 1.0.0-alpha.1 < 1.0.0-alpha.beta < 1.0.0-beta < 1.0.0-beta.2 < 1.0.0-beta.11 < 1.0.0-rc.1 < 1.0.0`.
- Tolerant parsing: `null` is treated as an empty string; from each core segment only the numeric prefix is taken (`leadingNumber`), and a segment without a numeric prefix counts as 0. It never throws.

### SnCron
`src/main/java/com/sn/lib/cron/SnCron.java`

Calendar scheduler of a consumer context, reached via `sn.cron()`. Each job pairs an id with a `CronExpr` (5-field cron subset or the `daily`/`hourly` shortcuts) and a task that runs ON THE MAIN THREAD at every matching instant: the delay to the next run is computed and scheduled through the context's Folia-aware scheduler (`ctx.scheduler().syncLater(...)`), and the job reschedules itself after every run, so wall-clock drift never accumulates. Jobs live in a static `TenantRegistry<Job>` keyed by owning plugin (justified server-wide static; sweep = `SnCron::sweep`): a disable sweeps them even if the owner never cancelled. Private constants: `DATA_FILE = "cron-data.yml"`, `LAST_RUN_PREFIX = "last-run."`.

- `public SnCron(Sn ctx)` - builds the context's cron; the module's entire life hangs off that `Sn` (scheduler, logger, yml, shutdown flag).
- `public void schedule(String id, String expr, Runnable task)` - schedules `task` under `id` at every instant matching `expr`, replacing any previous job with the same id; an invalid expression WARNs ("Cron job '<id>' not scheduled: <reason>") and schedules nothing. Equivalent to `create(id, expr).schedule(task)`.
- `public Builder create(String id, String expr)` - starts a job definition; nothing schedules until `Builder.schedule`.
- `public void cancel(String id)` - cancels the job and forgets its id (removes it from the per-owner registry and sweeps it); unknown ids are a no-op.

Public inner class `SnCron.Builder` (job definition builder, returned by `create`):
- `public Builder catchUp(boolean catchUp)` - persists the last-run and fires the missed run ONCE on reschedule (default false).
- `public void schedule(Runnable task)` - registers and arms the job, replacing any previous job with the same id.

Private inner class `SnCron.Job`: job struct (`id`, `expr`, `task`, `catchUp` final; `cancelled` and `handle` volatile). No methods.

#### Internal logic
- `scheduleJob(...)`: parses the expression (WARN and return on `IllegalArgumentException`), atomically replaces in the `byId` map (the previous job is removed from the registry and swept), enrolls the new one in `JOBS` under `ctx.plugin()`, runs the catch-up if applicable and arms the first run.
- `scheduleNext(Job)`: if the job is not cancelled and the context is not shutting down, computes `nextRun(now)` and schedules with `syncLater` a tick delay of `max(1, (millis + 49) / 50)` (rounded up to ticks). An `IllegalPluginAccessException` (owner disabled while arming) is swallowed: the job simply stops.
- `runJob(Job)`: checks cancelled/shutdown, records the run (if catchUp), runs the task catching `Throwable` (WARN "Cron job '<id>' threw an error: ...") and reschedules itself. A task exception does NOT stop the cycle.
- Catch-up (`catchUpIfMissed`): with `catchUp(true)`, the last-run persists to the owning plugin's `cron-data.yml`. When scheduling again (typically the next startup), if the persisted last-run already had a due instant in the past (`!expr.nextRun(last).isAfter(now)`), it fires ONCE immediately via `sync` (`runMissed`, with its own WARN "... threw an error in the catch-up: ..."). A job WITHOUT a persisted last-run only records the current instant as baseline: a fresh install never fires retroactively.
- `recordRun(Job)`: only with catchUp; writes `last-run.<id> = System.currentTimeMillis()` and calls `SnYml.save()`, which flips to synchronous writing during the context teardown, so a run recorded while shutting down is never lost.
- `dataFile()`: mounts the data file lazily under `dataLock`; if the yml module is absent (`UnsupportedOperationException` from `ctx.yml()`), it marks `dataUnavailable` and WARNs exactly ONCE: "catchUp(true) requires the yml module (SnSpec.builder().config(...)): the last-run does not persist". The job still runs, just without persistence.
- `sweep(Job)` (static): full release of a job; marks `cancelled = true` and cancels the pending handle catching any `Throwable` (scheduler already gone during shutdown).

#### Notes and gotchas
- All jobs run on the MAIN THREAD (via the context scheduler's `syncLater`/`sync`); a heavy task must delegate to async itself.
- The post-run reschedule recomputes against the real clock on every iteration, which is why it does not accumulate drift (unlike a fixed-period timer).
- `catchUp` fires at most ONE missed run, not one per skipped instant.
- Time zones come from `ZonedDateTime.now()` (system default zone).

### CronExpr
`src/main/java/com/sn/lib/cron/CronExpr.java`

Parsed calendar expression: a 5-field cron subset plus two shortcuts. Pure time math over `java.time`, no Bukkit. Internal state: per-field boolean arrays (`minutes[60]`, `hours[24]`, `daysOfMonth[32]`, `months[13]`, `daysOfWeek[7]`) plus the `anyDayOfMonth`/`anyDayOfWeek` flags (true when the field was literally `"*"`). Private constant `MAX_ADJUSTMENTS = 5000` (adjustment bound per search; only reached by expressions that never match).

- `public static CronExpr parse(String expr)` - parses a 5-field cron expression or one of the `daily`/`hourly` shortcuts; throws `IllegalArgumentException` when the expression is malformed or a value falls outside its field's range (messages such as "Empty cron expression", "expected 5 fields...", "out of range", "invalid step", "is not a number", etc.).
- `public ZonedDateTime nextRun(ZonedDateTime from)` - next instant matching STRICTLY after `from`, in `from`'s zone; throws `IllegalStateException` when the expression never matches (for example February 31): "Cron expression '<expr>' has no reachable next run".
- `public String toString()` - the original expression text this instance was parsed from.

#### Supported grammar
Fields in order `minute hour day-of-month month day-of-week` (ranges 0-59, 0-23, 1-31, 1-12, 0-7):
- `*` (any), lists `1,15`, ranges `1-5` and steps `*/10` (also over ranges, `10-30/5`), combinable per field (comma-separated list of atoms, each atom with an optional step). A single value with a step (`5/10`) expands from that value to the field maximum.
- Day of week 0-7 where BOTH 0 and 7 mean Sunday (7 collapses onto 0). When day-of-month AND day-of-week are BOTH restricted, a day matching EITHER runs (standard cron OR semantics); if only one is restricted, that one rules.
- `daily HH:mm` shortcut (time optional, default 00:00) and `hourly :mm` shortcut (minute optional, default :00; also accepts `mm` without the colon). Case-insensitive.
- NOT supported: seconds field, names (`JAN`, `MON`), `@daily`/`@yearly` macros, Quartz's `L`/`W`/`#`.

#### DST and end of month (nextRun)
`nextRun` truncates to the minute, adds 1 minute (strictly after) and iterates field by field over `ZonedDateTime`, from largest to smallest (month -> day -> hour -> minute), resetting the smaller fields on each jump (`plusMonths`/`plusDays` with `atStartOfDay(zone)`, `plusHours(...).withMinute(0)`, `plusMinutes(1)`). Since all advancement goes through the zone rules:
- A wall-clock hour erased by a DST gap is SKIPPED to the next matching day (it never fires at a nonexistent instant).
- A day absent from a month (the 31st in a 30-day month, February 29 outside a leap year) waits for the next month that has it.
- The `MAX_ADJUSTMENTS = 5000` bound cuts the search for impossible expressions with `IllegalStateException` instead of looping forever.

#### Internal logic
- `fill(boolean[] field, String spec, int min, int max, String name)` (private static): fills a field from its spec; validates step >= 1 and ranges `min <= lo <= hi <= max`.
- `fillDayOfWeek(String spec)` (private): parses over 0-7 into a raw array of 8 and collapses 7 onto Sunday (0) with `% 7`; matching uses `dayOfWeek.getValue() % 7` (ISO Monday=1..Sunday=7 -> Sunday=0).
- `dayMatches(ZonedDateTime)` (private): implements the OR/AND semantics described above using `anyDayOfMonth`/`anyDayOfWeek`.
- `parseClock` / `parseHourlyMinute` / `parseInt` (private statics): parsers for the shortcuts and for integers with contextual error messages.

### TODOs and limitations
There are no TODO/FIXME/placeholder markers in this module's files. Limitations documented in the code itself:
- Folia: `SnScheduler`'s support is detection + no-crash only (global region and async schedulers); it is NOT a complete region-aware port and the GUI and items modules remain Paper-only (`SnScheduler` Javadoc).
- `SnCron` with `catchUp(true)` requires the context's yml module (`SnSpec.builder().config(...)`); without it, the job still runs but WARNs once and the last-run does not persist.
- The catch-up fires at most ONE missed run per reschedule; it does not replay every skipped instant.
- `CronExpr` is a subset: no seconds, no month/day names, no `@...` macros or Quartz extensions (`L`/`W`/`#`); expressions that never match cut off via `MAX_ADJUSTMENTS = 5000` with `IllegalStateException`.
- `SemverComparator` applies semver pre-release precedence (a `-SNAPSHOT` compares LOWER than the bare release; behavior change relative to 1.0.0: the `minVersion` gate now rejects a `-SNAPSHOT` target when the hook requires the release) and ignores build metadata (`+...`); non-numeric core segments still count as 0 (a tolerant comparator, not a strict semver validator).
---

## 07. Utils

The `com.sn.lib.util` package groups 13 static utility classes (all `final` with a private constructor, except `WeightedRandomPool` which is an immutable class instantiable via builder). Almost half are pure (no Bukkit dependency): `SlotParser`, `TimeUtil`, `NumberFormatter`, `WeightedRandomPool` and `MathUtil`; `PlayerLookup` (v1.1) does not touch Bukkit either but does async HTTP against Mojang; the rest touch Bukkit/Paper API (`LocationSerializer`, `Experience`, `SoundUtil`, `HeadUtil`, `ArmourUtil`, `TagIo`, `InvUtil`). Three classes (`SoundUtil`, `HeadUtil` and `PlayerLookup`) keep server-wide static state, explicitly justified by the SnLib contract: they cache facts about the server (sound id resolution, content-addressed texture profiles, Mojang name->UUID mapping) that are identical for every consumer. The module's general philosophy is "never crash the consumer": invalid inputs produce no-ops, nulls or delegable/deduplicated WARNs instead of exceptions (with the documented exceptions case by case: `NumberFormatter.parseFormatted`, `MathUtil.convertToRoman`, `WeightedRandomPool.pick` on an empty pool).

### SlotParser

`src/main/java/com/sn/lib/util/SlotParser.java`

Parses inventory slot definitions coming from YML into `int[]` indices. Accepts a lone int, a numeric string, a range (`"0-8"`), a comma-separated mix (`"0,2,4-6"`) or a list (any `Iterable`) combining all of the above (processed recursively). Pure class: it does not touch Bukkit; warnings delegate to a sink the caller provides.

- `public static int[] parse(Object raw)` - parses discarding warnings (delegates to the overload with `warn = null`).
- `public static int[] parse(Object raw, Consumer<String> warn)` - parses `raw` into distinct slot indices, in first-seen order (backed by a `LinkedHashSet`). Returns an empty array when nothing valid was found; `warn` may be null.

Internal logic:

- `Number` converts with `Math.toIntExact(longValue())`; `Iterable` recurses; any other object goes through `toString()`, gets trimmed and splits by commas.
- A token with a hyphen from index 1 onward (`indexOf('-', 1)`, so as not to confuse a leading minus sign) is interpreted as a range; `from` and `to` may come in any order (normalized with min/max).
- Private constant `MAX_RANGE_SPAN = 10_000`: a range whose span exceeds that limit is rejected whole with a WARN ("exceeds 10000 slots; ignored"), as protection against typos like `"0-999999"`.
- Negative slots are ignored with a WARN ("Negative slot N ignored"); non-numeric tokens generate a WARN ("Invalid slot token '...'" / "Invalid slot range '...'"); `null` and empty string also warn ("Slot definition is null" / "is empty").

Notes and gotchas:

- Duplicates deduplicate silently (it is a `Set`), but the output order is first-seen, not numerically sorted.
- Empty tokens between commas (`"1,,3"`) skip without a WARN.

### TimeUtil

`src/main/java/com/sn/lib/util/TimeUtil.java`

Duration parsing and humanization (pure, no Bukkit). Parses compact strings like `"1d 2h 30m 15s"` and renders milliseconds back to text, with injectable labels for i18n.

Public enum `Unit`: `DAY, HOUR, MINUTE, SECOND` (the components `humanize`/`humanizeShort` render).

Public interface `Labels` (label provider for i18n):

- `String longLabel(Unit unit, boolean plural)` - long label, e.g. `"day"`/`"days"`.
- `String shortLabel(Unit unit)` - compact suffix, e.g. `"d"`.

Public constant: `Labels ENGLISH` - default English implementation (day/hour/minute/second and d/h/m/s).

- `public static long parseTicks(String text)` - parses to server ticks (20 per second); it is `parseMillis(text) / 50`.
- `public static long parseMillis(String text)` - parses to milliseconds. Reads each `<number><unit>` pair tolerating spaces, commas and full words (`"1 day 2 hours"`). Unknown units and garbage are skipped; null or unparseable input returns 0.
- `public static String humanize(long millis)` - long English render, e.g. `"1 day 2 hours 30 minutes 15 seconds"`.
- `public static String humanize(long millis, Labels labels)` - long render with injectable labels; zero components are omitted; if everything is zero it returns `"0 <plural seconds>"`.
- `public static String humanizeShort(long millis)` - compact English render, e.g. `"1d 2h 30m 15s"`.
- `public static String humanizeShort(long millis, Labels labels)` - compact render with injectable labels; a total of zero returns `"0s"` (with the short seconds label).

Format accepted by the parser:

- Units by first letter (case-insensitive): `d` = days, `h` = hours, `m` = minutes, `s` = seconds, `t` = ticks (50 ms); the exception is any word starting with `ms` = milliseconds (checked before the first letter, so `"500ms"` does not read as minutes).
- Decimal numbers allowed (`"1.5h"`); each pair's result rounds with `Math.round(value * unitMillis)`.
- A number without a unit = seconds (`"90"` -> 90000 ms).
- The total result clamps to a minimum of 0.

Notes and gotchas:

- Since the unit resolves by first letter, full words work (`"minutes"`, `"days"`), but so does any word starting the same (`"month"` would read as minutes).
- `humanize`/`humanizeShort` truncate to whole seconds: leftover millis are discarded (there is no ms component in the output).

### NumberFormatter

`src/main/java/com/sn/lib/util/NumberFormatter.java`

Abbreviated number formatting (`K/M/B/T/Qa/Qi` suffixes in powers of 1000) and comma grouping (pure, no Bukkit). Private suffix array: `{"", "K", "M", "B", "T", "Qa", "Qi"}` (up to 10^18).

- `public static String format(double value)` - formats with the largest applicable suffix and up to 2 decimals with trailing zeros removed (`1500 -> "1.5K"`, `HALF_UP` rounding via `BigDecimal`). Non-finite values (`NaN`, infinities) return as `String.valueOf(value)`. The negative sign is preserved.
- `public static String formatComma(double value)` - (v1.1) full number with comma thousand grouping: rounds `HALF_UP` to 2 decimals and removes trailing zeros (same contract as `format`; `toPlainString` avoids the scientific notation of `1000.00 -> 1E+3`) and groups the integer part in 3s from the right with `,`, preserving the `-` sign and decimals with `.` (`1234567 -> "1,234,567"`, `1234.567 -> "1,234.57"`, `1000.10 -> "1,000.1"`, `-1234567.5 -> "-1,234,567.5"`, `999 -> "999"`). NaN/infinities as `String.valueOf(value)`. Locale-independent through manual composition with StringBuilder: `DecimalFormat` is FORBIDDEN (not thread-safe, the ManticFormatter bug) as is `String.format` with the JVM locale.
- `public static double parseFormatted(String text)` - tolerant inverse of `format`: case-insensitive suffixes (`"1.5k"` works) and accepts both comma and dot as decimal or grouping separator. Throws `NumberFormatException` on null, empty, unknown suffix or unparseable number.

Internal logic:

- `format` has a second scaling pass: if after rounding to 2 decimals the value reaches 1000 (e.g. `999999 -> 1000.00K`), it climbs one more suffix and rescales (`-> "1M"`), avoiding outputs like `"1000K"`.
- `parseFormatted`'s suffix is taken as the string's trailing run of letters; the multiplier is `1000^i` per its position in the suffix array.
- Separator heuristic in `normalizeSeparators`: if both comma and dot exist, the later of the two is the decimal and the other is grouping; with commas only, a single comma followed by exactly 3 digits counts as grouping (`"1,500" -> 1500`) while `"1,5" -> 1.5`; multiple commas are always grouping. Internal spaces are removed. If several dots remain, only the last survives as the decimal.

Notes and gotchas:

- The `"1,500"` ambiguity resolves in favor of grouping (1500), not decimal (1.5): a deliberate decision documented in the class Javadoc.

### LocationSerializer

`src/main/java/com/sn/lib/util/LocationSerializer.java`

Serializes Bukkit `Location`s to the compact form `world;x;y;z;yaw;pitch` and back.

- `public static String serialize(Location location)` - serializes to `world;x;y;z;yaw;pitch`; a null location or one without a world returns null. Numbers use `Double.toString`/`Float.toString`, which are locale-independent, so the round-trip is stable under any JVM locale.
- `public static Location deserialize(String raw)` - null-safe inverse. Accepts 6 parts, or 4 parts (`world;x;y;z`) with yaw and pitch at 0. Returns null on any failure: null/blank input, a part count other than 4 or 6, an unloaded world (`Bukkit.getWorld` null) or a malformed number. Never throws.

Notes and gotchas:

- `deserialize` requires the world to be loaded at call time; if the world has not loaded yet (e.g. very early deserialization at startup) the result is a silent null.
- Each part is trimmed individually, so `"world ; 1 ; 2 ; 3"` also parses.

### WeightedRandomPool

`src/main/java/com/sn/lib/util/WeightedRandomPool.java`

Immutable weighted random selector (pure, no Bukkit), generic in `T`. Backed by a `TreeMap` of cumulative weights: `pick` draws `r` uniform in `[0, totalWeight)` and resolves the entry in O(log n) via `ceilingEntry(r)`. Weights are `double` end to end (never truncated to int), so fractional weights keep their exact relative probabilities. Built with `builder()`.

- `public static <T> Builder<T> builder()` - creates an empty builder.
- `public T pick(Random random)` - weighted pick with the given randomness source; throws `NoSuchElementException` if the pool is empty. If `ceilingEntry` returned null (floating-point edge at effective `r == totalWeight`), it falls to `lastEntry()`.
- `public T pick()` - weighted pick using `ThreadLocalRandom.current()`.
- `public int size()` - entry count.
- `public boolean isEmpty()` - whether the pool has no entries.
- `public double totalWeight()` - sum of weights.
- `public Collection<T> values()` - values in cumulative weight order (unmodifiable collection).

Public nested class `Builder<T>`:

- `public Builder<T> add(T value, double weight)` - adds an entry; non-positive or non-finite weights (`<= 0`, `NaN`, infinity) are silently ignored (contract inherited from RandomCollection). Returns `this` for chaining.
- `public WeightedRandomPool<T> build()` - builds the pool copying the cumulative map (the builder can keep being used afterwards without affecting the built pool).

Notes and gotchas:

- If two entries make the cumulative weight land on exactly the same `double`, the second would overwrite the `TreeMap` key; in practice that only happens with weights that are ignored (0 or negative), already filtered in `add`.
- Being immutable, the instance is safe to share across threads; the no-arg `pick()` is thread-safe via `ThreadLocalRandom`.

### Experience

`src/main/java/com/sn/lib/util/Experience.java`

Player XP math over vanilla's exact piecewise formula. Total XP to reach a level: `level^2 + 6*level` for 0-15, `2.5*level^2 - 40.5*level + 360` for 16-30 and `4.5*level^2 - 162.5*level + 2220` for 31+. Cost of one level: `2*level + 7`, `5*level - 38` and `9*level - 158` in the same brackets. Inverse thresholds: 315 total XP = level 15, 1395 total XP = level 30 (private constants `BRACKET_15_TOTAL` and `BRACKET_30_TOTAL`).

- `public static long getExp(Player player)` - total XP the player has right now (full levels plus the progress bar fraction, rounded).
- `public static long getExpFromLevel(int level)` - total XP required to reach `level` from zero; levels `<= 0` return 0.
- `public static int getExpToNext(int level)` - XP to go from `level` to `level + 1` (per-bracket linear formula; negative levels clamp to 0 in the low bracket).
- `public static double getLevelFromExp(long exp)` - fractional level for a total XP amount (integer part = level, decimal = bar progress); uses the per-bracket quadratic inverses (`sqrt`); `exp <= 0` returns 0.
- `public static int getIntLevelFromExp(long exp)` - integer (truncated) level for a total XP.
- `public static void changeExp(Player player, int amount)` - adds (or subtracts, if negative) XP safely: recomputes the player's level and progress bar from the resulting total, clamped at 0.

Notes and gotchas:

- The Javadoc explicitly says to use `changeExp` instead of `Player#giveExp(int)`: by recomputing everything from the total, it never over-levels and the clamp at 0 prevents negative XP.
- `changeExp` normalizes floating-point edges: if the computed progress reaches `>= 1.0` it goes up a level and resets the bar; negative progress clamps to 0. It requires the main thread in practice (it mutates `Player` state).

### MathUtil

`src/main/java/com/sn/lib/util/MathUtil.java`

Math helpers: "fair" probabilistic rounding and Roman numerals (pure, no Bukkit). Fair rounding resolves the fractional part probabilistically so the result's expected value equals the input: `2.3` yields 3 with 30% probability and 2 otherwise.

- `public static int fairIntFromDouble(double value)` - fair rounding to `int` using `ThreadLocalRandom`.
- `public static int fairIntFromDouble(double value, Random random)` - fair rounding to `int` with an injectable RNG: takes `floor` and adds 1 with probability equal to the fraction.
- `public static long fairLongFromDouble(double value)` - fair rounding to `long` using `ThreadLocalRandom`.
- `public static long fairLongFromDouble(double value, Random random)` - fair rounding to `long` with an injectable RNG.
- `public static String convertToRoman(int number)` - Roman numeral for `number` in 1-3999 (greedy algorithm over the `ROMAN_VALUES`/`ROMAN_SYMBOLS` tables, with subtractives CM/CD/XC/XL/IX/IV); throws `IllegalArgumentException` outside the range.

Notes and gotchas:

- Fair rounding also works with negatives thanks to `Math.floor` (e.g. `-2.3` yields -2 with 70% and -3 with 30%, keeping the expected value -2.3).
- The `Random` overloads exist for testing with a deterministic RNG.

### SoundUtil

`src/main/java/com/sn/lib/util/SoundUtil.java`

Plays sounds from YML-style specs: `"SOUND_ID [volume] [pitch]"` (whitespace-separated; volume and pitch default to 1.0). Resolution treats `Sound` as an open set (never switch/EnumSet): first `Sound.valueOf` for enum-style ids (`ENTITY_PLAYER_LEVELUP`), then `Registry.SOUNDS` by `NamespacedKey` for key-style ids (`minecraft:entity.player.levelup`), so ids added by newer servers keep working. An unresolvable id logs a single WARN and the call becomes a no-op. Null, blank and `"none"` (case-insensitive) specs are silent no-ops.

- `public static void play(Player player, String spec)` - plays the spec for that player only, at the player's own location; a null player is a no-op.
- `public static void playAt(Location location, String spec)` - plays the spec for all players near the location (via `World.playSound`); a null location or one without a world is a no-op.

Internal logic:

- `resolve(String)`: uppercases, strips the `MINECRAFT:` prefix and replaces `.` with `_` to try `Sound.valueOf`; on failure, tries `NamespacedKey.fromString(id.toLowerCase())` against `Registry.SOUNDS`.
- Malformed volume/pitch do not cancel the sound: a WARN is logged ("Invalid volume/pitch in '...'; using 1.0") and 1.0 is used.
- `warnOnce`: WARNs deduplicate in a static concurrent `Set` (`WARNED`) with tags `"id:..."` / `"num:..."`; each problem logs once per server lifetime, with the `[SnLib]` prefix on `Bukkit.getLogger()`.

Notes and gotchas:

- Server-wide static state allowed by the SnLib contract: whether a sound id resolves is a fact about the server, not the consumer.
- WARN messages read e.g. "Invalid sound '...': it did not resolve via enum nor Registry.SOUNDS; ignored".

### HeadUtil

`src/main/java/com/sn/lib/util/HeadUtil.java`

Builds `PLAYER_HEAD` stacks from texture values or from an `OfflinePlayer` owner, without NMS. Accepted texture inputs: `texture-`/`texture:` prefixes, `base64-`/`base64:`, `basehead-` (unnested recursively, e.g. `basehead-eyJ...`), raw base64 payloads (`eyJ...`) and http(s) skin URLs (wrapped in the standard textures JSON and base64-encoded).

- `public static ItemStack fromBase64(String value)` - creates an amount-1 `PLAYER_HEAD` stack showing the given texture; null or empty returns a default head.
- `public static void applyBase64(SkullMeta meta, String value)` - applies a texture to a `SkullMeta` with a deterministic profile UUID derived from the texture bytes; unparseable values leave the meta intact with a single WARN; a null meta is a no-op.
- `public static ItemStack fromPlayer(@Nullable OfflinePlayer owner)` - creates an amount-1 `PLAYER_HEAD` stack with the given player's skin; a null owner returns the default head. Zero NMS and zero own HTTP: the server resolves the skin from its profile cache (transient Steve while the profile is not cached).
- `public static void applyOwner(SkullMeta meta, @Nullable OfflinePlayer owner)` - applies an owner to a `SkullMeta` via `setOwningPlayer`; a null meta or owner is a no-op. Same resolution semantics as `fromPlayer` (server profile cache, transient Steve, no NMS/HTTP).
- `public static void applyProfile(SkullMeta meta, @Nullable PlayerProfile profile)` - applies a fully resolved `PlayerProfile` (textures included) to a `SkullMeta` via `setPlayerProfile`; a null meta or profile is a no-op. Used to re-apply the profile the async skin resolver (`com.sn.lib.item.internal.SkinResolver`) fetched off-thread with `PlayerProfile.update()` when a `skull-owner` had no cached textured profile.
- `public static @Nullable String extractTextureValue(String value)` - normalizes a raw input to its base64 payload: strips prefixes (recursively), encodes http(s) URLs to the `{"textures":{"SKIN":{"url":"..."}}}` JSON in base64, accepts `eyJ...` as-is; returns null when the input is not a texture.
- `public static void clearCache()` - empties the bounded profile cache; invoked by the SnLib plugin in its teardown (onDisable).

Internal logic:

- Deterministic UUID: identical textures share the same profile UUID via `UUID.nameUUIDFromBytes(texture.getBytes(UTF_8))`, so the client caches the skin only once across all heads using that texture (fewer downloads and flicker).
- Bounded LRU cache: `PROFILE_CACHE` is an access-order `LinkedHashMap` with `removeEldestEntry` over `CACHE_CAP = 512`; all access synchronizes on the map itself (including `get`, because in access-order a get mutates the internal order).
- Multi-tier application: first Paper `PlayerProfile` (`meta.setPlayerProfile`); if it throws `Throwable`, a legacy reflective fallback that instantiates `com.mojang.authlib.GameProfile` (name `"SnLibHead"`) and injects it into the meta's private `profile` field, with no compile-time dependency. If both tiers fail, the head stays default and a single WARN with both causes is logged.
- WARNs deduplicate in `WARNED` (concurrent set), with the `[SnLib]` prefix and values abbreviated to 40 characters in the message ("Invalid head texture '...'; leaving the default head").

Notes and gotchas:

- Server-wide static justified by the SnLib contract: the texture -> profile mapping is content-addressed and identical for any consumer.
- In `extractTextureValue`, after stripping a prefix it tries to unnest recursively; if the rest matches no known format, the rest is returned as-is (the explicit prefix is taken as a declaration of intent that this IS a texture).
- An arbitrary string with no prefix, not starting with `eyJ` and not a URL, returns null (no guessing).

### PlayerLookup

`src/main/java/com/sn/lib/util/PlayerLookup.java`

(v1.1) Async name -> UUID lookup against Mojang's profile endpoint (`https://api.mojang.com/users/profiles/minecraft/<name>`), no Bukkit and no NMS. It complements the deliberate gap of `Args.offlinePlayerUuid`, which remains cache-only by design and is not touched; SnLib NEVER calls it internally.

- `public static CompletableFuture<Optional<UUID>> fetchUuid(String name)` - resolves the name's UUID. **Threading contract**: the future completes on the HttpClient's executor, NEVER on the main thread; the consumer hops back to main with `ctx.scheduler().thenSync(...)` or `sync(...)` (same contract as SnDb). An invalid name (null or not matching `[A-Za-z0-9_]{1,16}`) completes immediately with `Optional.empty()` without HTTP or cache. Transient failures (unexpected status, unparseable 200 body, network error) complete EXCEPTIONALLY with `IOException` and are NOT cached.
- `public static void clearCache()` - empties the LRU and shuts down/nulls the HttpClient; invoked by `SnLibPlugin`'s onDisable alongside `HeadUtil.clearCache()`.

Internal logic:

- Bounded static LRU cache (EXACT HeadUtil pattern): access-order `LinkedHashMap(64, 0.75f, true)` with `removeEldestEntry` over cap 512, all access `synchronized` on the map itself; key `name.toLowerCase(Locale.ROOT)`. It ALSO stores misses (204/404 -> `Optional.empty()`) so nonexistent names are not re-queried. Justified server-wide static: the name->UUID mapping is content-addressed and identical for every consumer.
- In-flight dedup: `ConcurrentHashMap<String, CompletableFuture<Optional<UUID>>> IN_FLIGHT` with `computeIfAbsent`; a `whenComplete` removes the entry on completion (two-arg conditional remove), so N concurrent callers of the same name generate ONE single request.
- HTTP: lazy static HttpClient with double-checked locking, connect timeout 5s, request timeout 10s, `sendAsync(request, BodyHandlers.ofString())`. Status 200 -> `parseUuid(body)` (successful parse caches `Optional.of(uuid)`; failed parse completes exceptionally with IOException WITHOUT caching); 204/404 -> caches and completes `Optional.empty()`; any other status or network exception -> IOException (`"Mojang lookup failed: ..."`) without caching (transient).
- PURE package-private helpers for JUnit: `static boolean validName(String)` (regex `[A-Za-z0-9_]{1,16}`) and `static @Nullable UUID parseUuid(String body)` (scans the `"id"` field by hand with a private `jsonString` duplicated from UpdateChecker following the self-contained-utils precedent, validates 32 hex and inserts dashes in 8-4-4-4-12 format before `UUID.fromString`).

### ArmourUtil

`src/main/java/com/sn/lib/util/ArmourUtil.java`

Armor helpers over stacks and over the player's worn set. `final` class with a private constructor and ZERO static state. Matching is by `Material` name suffix (enum treated as open: name checks, never switch/EnumSet over its constants, no `Material.values()` scan at class-load), so new Paper constants never break the mapping. Typical use: full-set checks inside an `SnArmourEquipEvent` handler.

- `public static @Nullable EquipmentSlot slotOf(@Nullable ItemStack item)` - armor slot the item auto-equips to, by Material name suffix (`_HELMET`/`_HEAD`/`_SKULL`/`CARVED_PUMPKIN` -> HEAD; `_CHESTPLATE`/`ELYTRA` -> CHEST; `_LEGGINGS` -> LEGS; `_BOOTS` -> FEET); null, air or a non-armor material returns null. It is the source of truth for the logic that used to live in `ArmourEquipListener.matchType` (which now delegates here).
- `public static boolean isArmour(@Nullable ItemStack item)` - whether the item maps to an armor slot (`slotOf(item) != null`).
- `public static boolean isWearingFullSet(Player player)` - true if the player's 4 armor slots (helmet, chestplate, leggings, boots) are non-empty (null or air counts as empty); a null player returns false.
- `public static boolean isWearingFullSet(Player player, String materialPrefix)` - like the above and additionally each piece's `getType().name()` starts with the prefix normalized to uppercase `Locale.ROOT` (e.g. `"DIAMOND_"`); a null or blank prefix delegates to the prefixless overload.

### TagIo

`src/main/java/com/sn/lib/util/TagIo.java`

String-typed tags on items via `PersistentDataContainer`. Every key is a `NamespacedKey(owner, key)`, so two consumers using the same key name never collide: the tag data is always owned by the plugin that wrote it (ownership per `JavaPlugin`). Values stored as `PersistentDataType.STRING`. Null items, air and items without meta are guarded no-ops.

- `public static ItemStack set(ItemStack item, JavaPlugin owner, String key, String value)` - writes `value` under the owner's namespaced key; a null `value` delegates to `remove` (deletes the tag). Returns the same item instance, for chaining.
- `public static @Nullable String get(ItemStack item, JavaPlugin owner, String key)` - reads the tag value, or null when absent or the item is null/air/without meta.
- `public static boolean has(ItemStack item, JavaPlugin owner, String key)` - whether the tag is present on the item (checked as STRING).
- `public static ItemStack remove(ItemStack item, JavaPlugin owner, String key)` - removes the tag if present; returns the same instance for chaining.

Notes and gotchas:

- The key normalizes to lowercase (`key.toLowerCase(Locale.ROOT)`) before creating the `NamespacedKey`, because `NamespacedKey` does not admit uppercase; thus `"MyKey"` and `"mykey"` are the same tag.
- `set`/`remove` mutate the passed item (they call `setItemMeta` on the same instance); the return is just sugar for chaining.

### InvUtil

`src/main/java/com/sn/lib/util/InvUtil.java`

Inventory helpers to give items to players.

- `public static void giveItems(Player player, ItemStack... items)` - adds the items to the player's inventory; whatever does not fit drops naturally (`World.dropItemNaturally`) at the player's location, so nothing is ever lost. Null and air stacks are skipped; a null player, null or empty array are no-ops.

Notes and gotchas:

- It uses `Inventory.addItem`, which can merge with existing partial stacks; the leftover `Map` Bukkit returns is what gets dropped.
- Must be called on the main thread (it mutates the inventory and spawns item entities).

### TODOs and limitations

None. There are no TODO/FIXME/HACK/placeholder markers in any file of the `com.sn.lib.util` package. Design limitations already documented above per class: 10000-slot max range in `SlotParser`, `NumberFormatter` suffixes capped at `Qi` (10^18), `convertToRoman` limited to 1-3999, `LocationSerializer.deserialize` dependent on the world being loaded, and `HeadUtil`/`PlayerLookup` caches bounded to 512 entries.
---

## 08. Actions, Requirements and PAPI

This module covers three pieces almost every consumer plugin uses from YML: the action engine (`com.sn.lib.action.ActionEngine`, reached via `sn.actions()`, one instance per Sn context) which runs lists of `[tag] argument` lines with click, surface (BLOCK/AIR) and chance guards; the requirement engine (`RequirementEngine`, static) which parses comparison expressions with `&&`/`||` once at load and evaluates them against placeholders at runtime with a fail-open policy; and the PAPI service (`com.sn.lib.papi.SnPapi` + `ExpansionBuilder` + the internal holder `PapiHolder`) which resolves PlaceholderAPI tokens and registers declarative expansions without a PAPI-less server ever loading a PAPI class. The whole module is per-plugin ownership: each Sn context has its own `ActionEngine` and its own `SnPapi`/`PapiHolder`, and the context teardown releases the Bungee channel and unregisters the expansions.

### ActionEngine
`src/main/java/com/sn/lib/action/ActionEngine.java`

Runs YML action lists of the form `[tag] argument`. It is `final`, one instance per context (`sn.actions()`). It keeps a `ConcurrentHashMap<String, ActionHandler>` of handlers (built-ins registered in the constructor), a `warned` set for per-key WARN-once, and an `AtomicBoolean bungeeRegistered` for the outgoing `"BungeeCord"` channel (private constant `BUNGEE_CHANNEL`). It exposes no public constants or enums.

- `public ActionEngine(Sn ctx)` - creates the engine for the given context with the built-in catalog already registered; takes the `JavaPlugin` from `ctx.plugin()`.
- `public void run(Player player, List<String> actions, Ph... phs)` - runs the lines for the player with local placeholders and no page or click data (builds an `ActionContext` with `pageTarget` and `clickType` null).
- `public void run(Player player, List<String> actions, ActionContext context)` - runs the lines under the given context. Null `player`/`actions` or an empty list return without doing anything; a null `context` throws NPE (`Objects.requireNonNull`). Threading: if the caller is already on the main thread it executes inline; from any other thread it hops via `ctx.scheduler().sync(...)`. If scheduling fails with `IllegalPluginAccessException` (plugin disabled) it logs WARN "Actions discarded: plugin disabled during scheduling" and discards.
- `public void register(String tag, ActionHandler handler)` - registers a custom action under `tag` (with or without brackets, case-insensitive: the tag normalizes with trim + lowercase + `[...]` strip), replacing any previous handler INCLUDING a built-in. An empty tag after normalizing throws `IllegalArgumentException("Empty action tag")`; a null tag or handler throws NPE.
- `public void shutdown()` - releases the outgoing plugin channel `[connect]` registered on first use; invoked by the context teardown. Idempotent (CAS over `bungeeRegistered`).

#### Internal logic: line anatomy and guards

Each line processes as follows (`executeLine`):

1. The initial `[tag]` head is parsed (`head(...)`: the line must start with `[`, have `]` at position >= 1 and a non-empty tag; the tag lowercases with `Locale.ROOT`).
2. While the head is a guard, it is evaluated and the rest is re-parsed (guards can be CHAINED, e.g. `[right-click] [chance=50] [message] hello`). Full guard x ClickType matrix:

   | Guard | ClickTypes that pass |
   |---|---|
   | `[right-click]` | RIGHT, SHIFT_RIGHT (`isRightClick()`; inclusive v1.0.0 semantics intact) |
   | `[left-click]` | LEFT, SHIFT_LEFT, DOUBLE_CLICK, CREATIVE (`isLeftClick()`; inclusive) |
   | `[shift-right-click]` | SHIFT_RIGHT exact |
   | `[shift-left-click]` | SHIFT_LEFT exact |
   | `[right-click-only]` | RIGHT exact (excludes SHIFT_RIGHT and DOUBLE_CLICK) |
   | `[left-click-only]` | LEFT exact (excludes SHIFT_LEFT, DOUBLE_CLICK and CREATIVE) |
   | `[middle-click]` | MIDDLE exact |
   | `[double-click]` | DOUBLE_CLICK exact |
   | `[drop-click]` | DROP exact (the Q key; CONTROL_DROP does not pass) |
   | `[number-key]` | NUMBER_KEY exact (hotbar keys 1-9) |
   | `[swap-offhand]` | SWAP_OFFHAND exact (the F key) |
   | `[click=TYPE,...]` | exactly the listed enum names, comma-separated |
   | `[click-block]` / `[click-air]` | equality against `ActionContext.clickSurface()` (BLOCK/AIR), not against ClickType |
   | `[chance=N]` | probabilistic, click-independent |

   Rules per family:
   - `[click=TYPE,...]` - `ClickType` enum names, case-insensitive and with `-` equivalent to `_` (e.g. `[click=number-key]`). FAIL-CLOSED, unlike `[chance=]`: an invalid spec (unknown name, empty token, empty spec) WARNs once ("Guard [click=<spec>] with an invalid type; line skipped: <line>") and the line does NOT run, so a typo never fires actions on unintended clicks.
   - `[click-block]` / `[click-air]` (positional guards) - match by equality against the context's `ClickSurface`. Only world item interactions carry a surface; in GUI clicks and clickless runs the surface is null and the line is SKIPPED with a debug note.
   - `[chance=N]` - rolls `ThreadLocalRandom.current().nextDouble(100.0) < N` (N allows decimals, 0-100 scale). A malformed N WARN-onces ("Invalid [chance=...] guard; the action runs anyway") and lets the line run (fail-open).
   - Any click guard evaluated with a null `context.clickType()` (outside a GUI click or item interaction) SKIPS its line with a debug note (no WARN).
   - Testable package-private helpers: `matchesExactClickGuard(String, ClickType)` (named guard matching) and `parseClickTypes(String)` (parses the `[click=]` spec; returns null on any invalid token). Covered by `ClickGuardTest`.
3. If the line does not start with a valid tag, it runs whole as `[message]`.
4. The argument goes through local placeholders (`SnText.applyLocals(arg, context.phs())`) and then viewer-aware PAPI (`ctx.papi().apply(player, ...)`) BEFORE reaching the handler.
5. Unknown tag: per-tag WARN-once ("Unknown action '[tag]'; line ignored: ...").

Each line runs inside a `Throwable` try/catch: an exploding action logs WARN "Action failed in '<line>': <t>" and does NOT stop the rest of the list. Null or blank lines are skipped.

#### Full built-in tag catalog

"Message-like" render = `SnText.color(SnText.normalizePapiOutput(arg))`: PAPI output normalization plus the full SnText pipeline, including `[rgb]` and `[center]` (per the class Javadoc).

| Tag | Syntax | Exact semantics |
|---|---|---|
| `[player]` | `[player] command` | `Bukkit.dispatchCommand` as the player. A leading `/` is stripped. Empty argument: WARN-once "Command action without an argument; ignored". |
| `[player-as-op]` | `[player-as-op] command` | If the player is already OP, dispatches directly. Otherwise `setOp(true)`, dispatches, and restores `setOp(false)` in a `finally`. |
| `[console]` | `[console] command` | Dispatches with `Bukkit.getConsoleSender()`. Same `/` strip and empty-argument WARN. |
| `[message]` | `[message] text` (or a tagless line) | `player.sendMessage(render(arg))`. It is the implicit tag of headless lines. |
| `[broadcastmessage]` | `[broadcastmessage] text` | `Bukkit.getServer().sendMessage(render(arg))` to the whole server. |
| `[actionbar]` | `[actionbar] text` | `player.sendActionBar(render(arg))`. |
| `[title]` | `[title] title;subtitle;fadeIn;stay;fadeOut` | Split by `;` (limit -1). Subtitle default `Component.empty()`. Times IN TICKS, defaults 10/70/20; converted to `Duration.ofMillis(ticks * 50)`. A blank or missing part uses the default; an invalid number WARN-onces and uses the default. |
| `[sound]` | `[sound] SOUND_ID [vol] [pitch]` | Delegates to `SoundUtil.play(player, arg)` (id/volume/pitch parsing lives in SoundUtil). |
| `[close]` | `[close]` | `player.closeInventory()`. |
| `[open]` | `[open] gui-id` | Opens the context's GUI via `ctx.guis().get(id)`. Empty id: WARN-once. guis module not declared in the spec (`UnsupportedOperationException`): WARN-once "[open] action ignored: guis module not declared in the spec". Nonexistent gui: WARN-once "gui '<id>' does not exist". |
| `[connect]` | `[connect] server` | Sends the BungeeCord `Connect` + server plugin message on the `"BungeeCord"` channel. Registers the outgoing channel on FIRST use (CAS); `shutdown()` releases it. Empty server or `IOException` building the message: WARN-once. |
| `[next-page]` | `[next-page]` | `PageTarget.nextPage()` of the context. |
| `[previous-page]` | `[previous-page]` | `PageTarget.previousPage()`. |
| `[set-page]` | `[set-page] n` | `PageTarget.setPage(n)`; an invalid `n` WARN-onces and uses 1. |
| `[refresh-page]` | `[refresh-page]` | `PageTarget.refreshPage()` (re-render of the current page). |
| `[refresh-menu]` | `[refresh-menu]` | `PageTarget.refreshMenu()` (re-render of the whole menu). |
| `[particle]` | `[particle] TYPE [count] [offX offY offZ] [extra] [key=value...]` | Spawns in the player's world, at their location +1.0 in Y. Every token with `=` is an option (`color`, `size`, `to`, `block`, `item`; lowercase key, split at the first `=`); the rest are positionals with the usual thresholds: `count` default 1 with >= 1 positional, the three offsets only with >= 4 positionals, `extra` only with >= 5. See type and data resolution below. |
| `[potion]` | `[potion] EFFECT [seconds] [amplifier]` | `player.addPotionEffect(new PotionEffect(type, seconds * 20, amplifier))`. Defaults: 10 seconds, amplifier 0. Invalid effect: WARN-once "Invalid potion effect". Resolution: `NamespacedKey.fromString(lowercase)` against `Registry.EFFECT` first, fallback to the deprecated `PotionEffectType.getByName` for old configs. |
| `[remove-item]` | `[remove-item] [n] [selector]` | Without a selector: removes `n` (default 1) from the MAIN HAND item, byte-identical to v1.0.0 (empty hand = silent no-op; if the amount in hand is greater than `n` it decrements; otherwise it empties the slot with `setItemInMainHand(null)`). A single token that parses as an integer is `n`; if it does not parse, it is the selector with `n` = 1. Selectors: `offhand` (case-insensitive, same logic mirrored on the offhand), `id:<item-id>` (deducts stacks that `ctx.items().is(stack, id)`, sweeping storage slots 0-35 and then the offhand; an empty or unregistered id = WARN-once and line ignored) and any other token as a Material name (`Material.matchMaterial`; null or `!isItem()` = WARN-once and line ignored; matches by `getType()` ignoring meta BUT excludes every stack tagged by SnLib from ANY context: for custom items there is `id:`). Partial removal allowed in all modes: with fewer than `n` units it removes what there is, without error or WARN. |

`[particle]` details:

- Type resolution (`resolveParticle`): uppercase, `MINECRAFT:` prefix strip, `.` and `-` become `_`, then `Particle.valueOf`. Since Particle is an open set, there is a lenient `REDSTONE` <-> `DUST` alias (WARN-once "using alias '...'") so specs written before or after the 1.20.5 rename work on both sides. Invalid type: WARN-once "Invalid particle"; ignored.
- Particle data, resolved by `particle.getDataType()` against the `key=value` options:
  - `Void`: null data, as always. If the user passed `color=`/`size=`/`to=`/`block=`/`item=`, WARN-once per incompatible option (key `"particle-opt:TYPE:key"`) and the option is ignored; the line still runs.
  - `Particle.DustOptions` (DUST): `color=#RRGGBB` or `color=R,G,B` (default `Color.RED`) and `size=F` float (default `1.0f`). With no options the result is identical to v1.0.0 (`Color.RED`, `1.0f`).
  - `Particle.DustTransition` (DUST_COLOR_TRANSITION): `from` = the `color` option (default `Color.RED`), `to` = the `to` option (same two color formats; default = `from`), `size` default `1.0f`.
  - `BlockData` (BLOCK, BLOCK_MARKER, FALLING_DUST, DUST_PILLAR): requires `block=MATERIAL` (`Material.matchMaterial`; a null material or `!isBlock()` = WARN-once and the LINE is ignored; data = `mat.createBlockData()` with a defensive `IllegalArgumentException` catch). Without `block=`: WARN-once "requires block=MATERIAL; ignored" and the line is skipped.
  - `ItemStack` (ITEM): requires `item=MATERIAL` (`matchMaterial` + `isItem()`; data = `new ItemStack(mat)`); same error policy as `block=`.
  - Any other `dataType`: WARN-once "requires unsupported data; ignored", as before.
- Colors (`parseColor`): `#RRGGBB` (6 hex) or `R,G,B` (three integers 0-255); an invalid value = WARN-once "Invalid color" and the default is used. An invalid `size=` = WARN-once and `1.0f`. An unknown option key (not color/size/to/block/item) = WARN-once "Unknown option"; the line still runs.

Pagination: the five page tags go through `withPagination(...)`: with a null `context.pageTarget()` or `paginationEnabled()` false they are NO-OPs with the debug note "pagination not enabled (opt-in per menu)". Pagination is opt-in per menu.

#### Custom action registration

`register(tag, handler)` accepts the tag with or without brackets and case-insensitive; the handler replaces the previous one, including built-ins (intentional override allowed by contract). The handler receives the ALREADY resolved argument (locals + PAPI) and always runs on the main thread.

#### Notes and gotchas

- `[right-click]` uses `ClickType.isRightClick()`, which is also true for SHIFT_RIGHT; `[left-click]` uses `isLeftClick()`, which additionally passes with DOUBLE_CLICK and CREATIVE. To distinguish, there are the exact shift guards and the pure `[right-click-only]`/`[left-click-only]` (exact naming, user decision).
- `warnOnce` dedupes per key within EACH engine instance (that is, per consumer plugin): the same error in two plugins logs twice, once per plugin's logger.
- `[player-as-op]` opens a temporary OP window; the `finally` guarantees the `setOp(false)` even if the command throws, but only when the player was NOT op beforehand.
- In `[particle]`, giving `TYPE count offX` (without the three offsets) ignores the offset silently: offsets are only read with >= 4 POSITIONAL tokens (`key=value` options do not count toward that threshold).
- The argument resolution order is locals FIRST, PAPI SECOND: a local placeholder can expand to a `%...%` token that PAPI then resolves.

### ActionContext
`src/main/java/com/sn/lib/action/ActionContext.java`

Immutable record with the execution context of an action run.

- `public record ActionContext(Player player, Sn ctx, @Nullable PageTarget pageTarget, @Nullable ClickType clickType, @Nullable ClickSurface clickSurface, Ph[] phs)` - components: target player, owning SnLib context, pagination target (a GUI session, or null outside a paginated menu), click that fired the run (null outside a GUI click; click guards skip their line when it is null), click surface for world interactions (BLOCK/AIR, or null in GUI clicks and clickless runs; the `[click-block]`/`[click-air]` guards skip their line when it is null), and local placeholder pairs applied to each argument.
- `public ActionContext(Player player, Sn ctx, @Nullable PageTarget pageTarget, @Nullable ClickType clickType, Ph[] phs)` - compatibility overload without a surface (GUI clicks and clickless runs): delegates to the canonical constructor with a null `clickSurface`. Every v1.0.0 caller compiles and behaves identically.
- Compact constructor: `phs = phs == null ? new Ph[0] : phs` - normalizes a null `phs` to an empty array.

The record's generated accessors (`player()`, `ctx()`, `pageTarget()`, `clickType()`, `clickSurface()`, `phs()`) are the public API.

### ClickSurface
`src/main/java/com/sn/lib/action/ClickSurface.java`

Enum of a world click's surface (physical items): the interaction hit a block or the air. GUI clicks have no surface and leave `ActionContext.clickSurface()` null, so the positional guards `[click-block]`/`[click-air]` skip their line there.

Values: `BLOCK`, `AIR`.

### ActionHandler
`src/main/java/com/sn/lib/action/ActionHandler.java`

Functional interface (`@FunctionalInterface`) behind each tag, built-in or registered by a consumer via `ActionEngine#register`.

- `void run(Player player, String arg, ActionContext context)` - runs the action for the player, ALWAYS on the main thread. `arg` is the post-tag argument with locals and PAPI already resolved.

### PageTarget
`src/main/java/com/sn/lib/action/PageTarget.java`

Pagination controls the page actions delegate to; GUI sessions implement it. Pagination is opt-in per menu: with `paginationEnabled()` false the `ActionEngine` turns every page action into a no-op with a debug note.

- `void nextPage()` - advances to the next page.
- `void previousPage()` - goes back to the previous page.
- `void setPage(int page)` - jumps to the given page; implementations clamp out-of-range values.
- `void refreshPage()` - re-renders the current page.
- `void refreshMenu()` - re-renders the whole menu.
- `boolean paginationEnabled()` - true when the menu declared pagination; otherwise page actions do nothing.

### RequirementEngine
`src/main/java/com/sn/lib/action/RequirementEngine.java`

Static `final` class (private constructor) that parses requirement expressions over placeholders into immutable `Requirement` trees. Parsing happens ONCE at load; placeholders stay raw tokens and resolve on each `Requirement#test` through the caller's resolver.

- `public static Requirement parse(List<String> lines)` - parses the lines (implicit AND between lines) sending warnings to the shared logger `Logger.getLogger("SnLib")`.
- `public static Requirement parse(@Nullable List<String> lines, @Nullable Consumer<String> warn)` - same with a delegable warning sink (plugin logger, SnDebug, etc.); with a null `warn` it uses the shared logger deduplicated by message content. Null, empty or blank-lines-only input produces a requirement that ALWAYS passes.

Internal (private) enum `Op` with the comparison operators: `GE(">=")`, `LE("<=")`, `EQ_STRICT("==")`, `NE("!=")`, `GT(">")`, `LT("<")`, `EQ("=")`. The two-character symbols are declared first so the scan prefers them over `>`, `<` and `=`. Private static constant `WARNED` (server-wide set): the SnLib contract allows this static because it only dedupes logs, it holds no consumer data.

#### Grammar and precedence

- Per line: `left OP right` comparisons joined by `&&` and `||`, with AND binding TIGHTER than OR, and groupable with parentheses. Recursive descent over the token list: `expr := and ('||' and)*` (-> `AnyOf`), `and := primary ('&&' primary)*` (-> `AllOf`), `primary := '(' expr ')' | comparison`.
- Between lines of a list: implicit AND.
- Quoting: an operand may be wrapped in `'` or `"`; inside the quoted region the connectors, parentheses and operator symbols are LITERAL (both the tokenizer and the operator scan track quote state). When parsing the comparison, ONE pair of balanced quotes wrapping the whole operand is stripped (first char == last char, both `'` or both `"`, length >= 2); the inner content is NOT re-trimmed (a quoted operand preserves inner and edge spaces). An unclosed quote leniently extends the region to the end of the line, with no WARN of its own.
- Operator detection (`parseComparison`): the text scans left to right OUTSIDE quotes and at each index the operators try in enum declaration order, so `>=` wins over `>`. An empty operand (after quote stripping) on either side, or no operator, makes the comparison invalid.
- Fail-open: any malformation (invalid comparison, unclosed `(`, stray `)` or leftover tokens at expression end, empty parentheses, dangling connector) turns the WHOLE LINE into always-true with a single WARN ("Malformed requirement: '<line>'; it evaluates as true"), so a broken config never locks players out.
- Interpretation change vs 1.0.0 (accepted): an operand that contained literal `(`, `)`, `&&` or `||` without quotes now requires quoting; unquoted, the line falls into fail-open (true + WARN), it never blocks players. Every expression without quotes or parentheses produces a tree IDENTICAL to 1.0.0's.

#### Coercion at evaluation

On each `test`, both tokens resolve via the resolver (a null resolver or null result leaves the raw token). Then:

- If BOTH sides parse as `Double`, the comparison is numeric (`Double.compare`).
- Otherwise: `=`/`==` and `!=` compare lexicographically case-insensitive (`equalsIgnoreCase`); `=` and `==` are semantically identical.
- Relational operators (`>`, `<`, `>=`, `<=`) on non-numeric values evaluate to FALSE with a warn ("Non-numeric comparison with '<op>': ... evaluates as false").

#### Internal logic

The tree nodes are private records: `AllOf` (short-circuit AND, defensive `List.copyOf`), `AnyOf` (short-circuit OR) and `Comparison` (leaf that retains the raw, already unquoted tokens and the warn sink). Per-line parsing is a ONE-pass tokenizer (tokens `LPAREN`/`RPAREN`/`AND`/`OR`/`TEXT`, quote-aware; whitespace is preserved inside TEXT runs but whitespace-only runs between structural tokens are dropped) followed by recursive descent with a mutable index; malformations cut off via an internal exception (`MalformedLineException`) that `parseLine` translates into the single WARN. `ALWAYS_TRUE` is the constant requirement for empty inputs and malformed lines.

### Requirement
`src/main/java/com/sn/lib/action/Requirement.java`

Functional interface (`@FunctionalInterface`) of an immutable pre-parsed requirement, evaluated against placeholder values at runtime. Instances are built once at load via `RequirementEngine#parse` and keep placeholders as raw tokens: each evaluation resolves them again, so ONE instance serves any player.

- `boolean test(@Nullable Player player, @Nullable Function<String, String> resolver)` - evaluates the requirement. `player` may be null for server-level checks; `resolver` resolves each operand token to its current value (typically locals + PAPI bound to `player`); a null resolver leaves the tokens untouched.

### SnPapi
`src/main/java/com/sn/lib/papi/SnPapi.java`

PlaceholderAPI service of a consumer context, reached via `sn.papi()`. `final` class; the isolation lives in the internal holder: with PlaceholderAPI absent the text comes back untouched and NO PAPI class is ever loaded.

- `public SnPapi(Sn ctx)` - creates the service for the context; builds a `PapiHolder(ctx.plugin())`. PAPI presence is probed lazily.
- `public String apply(@Nullable Player viewer, String text)` - resolves PAPI tokens in `text` against the viewer, or against the server when the viewer is null. Fast path: null text or text without `'%'` returns as-is without touching the holder. PAPI absent: text untouched. Main-thread ONLY resolution: off the primary thread the tokens stay intact and the skip is recorded via the context's debug service ("PAPI skipped off the main thread; tokens untouched: ...").
- `public List<String> apply(@Nullable Player viewer, List<String> lines)` - list overload, resolves line by line; a null or empty list returns the same reference.
- `public SnFuture<String> applyOnMain(@Nullable Player viewer, String text)` - (v1.1) async-safe bridge to `apply`. On the primary thread it resolves INLINE and returns an already-completed future (`SnFuture.wrap(ctx, CompletableFuture.completedFuture(...))`); off it, it schedules a hop to the main thread via `ctx.scheduler().sync(...)` and completes the future there. Fail-open: a resolver error (`Throwable` try/catch with a debug note) or a scheduling failure (`IllegalPluginAccessException`: plugin disabled before the hop) complete with the ORIGINAL unresolved text; null text completes with null. Canonical consumption: `thenSync(...)`, just like db futures.
- `public SnFuture<List<String>> applyOnMain(@Nullable Player viewer, List<String> lines)` - (v1.1) list overload: resolves the WHOLE list in ONE hop with `apply(viewer, lines)`; fail-open to the original list, null lines completes with null.
- `public boolean available()` - true when the PlaceholderAPI plugin is present and enabled (delegated to the holder, cached).
- `public void invalidate()` - discards the cached presence probe; the next apply or register probes again (useful when the PAPI plugin toggles).
- `public ExpansionBuilder expansion(String identifier)` - starts a declarative expansion under `identifier`. Builder defaults: author = the plugin's authors joined with ", " (or the plugin name if the list is empty), version = the `plugin.yml` version.
- `public void unregisterAll()` - unregisters every expansion this context registered; invoked by the context teardown.
- `boolean registerExpansion(String identifier, String author, String version, Map<String, Function<OfflinePlayer, String>> exact, Map<String, BiFunction<OfflinePlayer, String, String>> prefixed)` - (package-private) the builder's bridge to `PapiHolder.register`.

### ExpansionBuilder
`src/main/java/com/sn/lib/papi/ExpansionBuilder.java`

Declarative builder of a PlaceholderAPI expansion, obtained via `SnPapi#expansion(String)`. The built expansion reports `persist() = true` (it survives PlaceholderAPI expansion reloads and only the context teardown removes it) and null-checks the requesting `OfflinePlayer` before touching any resolver: a null player leaves the token unresolved. Cache-only contract: resolvers run on the main thread inside PAPI's parse, so they must read precomputed in-memory state and never touch disk, database or network.

Design decision (v1.1, documented in the class Javadoc): async resolvers are NOT supported BY DESIGN - PlaceholderAPI's parse is synchronous and main-thread by PAPI contract, there is no way to wait for I/O inside a resolver. The supported pattern is precomputing a cache (e.g. LeaderboardCache) and resolving with lock-free reads; for the reverse path (composing text WITH PAPI tokens from async flows: db, leaderboards, Discord) there is `SnPapi.applyOnMain`.

- `public ExpansionBuilder placeholder(String param, Function<OfflinePlayer, String> resolver)` - binds `%<identifier>_<param>%` to the resolver. Case-insensitive matching (the key lowercases with `Locale.ROOT`); exact placeholders WIN over prefixed ones.
- `public ExpansionBuilder prefixed(String prefix, BiFunction<OfflinePlayer, String, String> resolver)` - binds every `%<identifier>_<prefix><rest>%` to the resolver, which receives the rest after the prefix as its second argument. Prefixes try IN REGISTRATION ORDER (LinkedHashMap), after the exacts.
- `public ExpansionBuilder author(String author)` - author reported to PlaceholderAPI; defaults to the plugin's authors.
- `public ExpansionBuilder version(String version)` - version reported to PlaceholderAPI; defaults to the plugin version.
- `public boolean register()` - registers the expansion in PlaceholderAPI, first unregistering any previous one under the same identifier (lookup-before-register: a consumer's second enable never fails). The maps copy defensively (`new LinkedHashMap<>(...)`) at registration. The registered instance is tracked in the context for unregistration at shutdown. Returns false with a WARN when PlaceholderAPI is absent or rejects the registration.

### PapiHolder (internal)
`src/main/java/com/sn/lib/papi/internal/PapiHolder.java`

Lazy PlaceholderAPI isolation layer of ONE consumer context. Key classloader design: EVERY bytecode reference to PAPI classes lives in the nested classes `Bridge` and `BuiltExpansion`, which load only after the presence probe is positive; the outer class references no PAPI type (the registered list is a `List<Object>`), so a server without PlaceholderAPI never triggers `NoClassDefFoundError`. The presence flag is probed lazily, cached in a `volatile Boolean`, and discarded via `invalidate()` when the target plugin toggles.

- `public PapiHolder(JavaPlugin owner)` - creates the holder for the owning plugin (its logger receives the WARNs).
- `public boolean available()` - true when the `"PlaceholderAPI"` plugin is present and enabled (`getPlugin` + `isEnabled`); lazy probe, cached result.
- `public void invalidate()` - discards the cached flag; the next call probes again.
- `public String apply(@Nullable OfflinePlayer player, String text)` - resolves PAPI tokens via `Bridge.setPlaceholders`, or returns the text untouched when PAPI is unavailable. A `LinkageError` marks the module as broken (`present = FALSE`, WARN "PlaceholderAPI inaccessible (...); papi module degraded") and returns the text untouched.
- `public boolean register(String identifier, String author, String version, Map<String, Function<OfflinePlayer, String>> exact, Map<String, BiFunction<OfflinePlayer, String, String>> prefixed)` - registers a declarative expansion, first unregistering any previous one with the same identifier (lookup-before-register). PAPI absent: WARN "PlaceholderAPI absent: expansion '<id>' not registered" and false. Registration rejected by PAPI: WARN "PlaceholderAPI rejected the expansion '<id>'" and false. The instance is tracked (CopyOnWriteArrayList) for `unregisterAll()`. A `LinkageError` degrades the module and returns false.
- `public void unregisterAll()` - unregisters every expansion this holder registered (context teardown); it drains the list first and tolerates `LinkageError` by degrading the module.

#### Internal logic

- `Bridge` (static, private): together with `BuiltExpansion` it is the ONLY class whose constant pool references PAPI types; it loads exclusively behind a successful presence probe. Methods: `setPlaceholders(player, text)` delegates to `PlaceholderAPI.setPlaceholders`; `register(...)` looks up the existing expansion via `PlaceholderAPIPlugin.getInstance().getLocalExpansionManager().getExpansion(identifier.toLowerCase(Locale.ROOT))`, unregisters it if it exists (this is the concrete lookup-before-register), builds the `BuiltExpansion` and returns null if `expansion.register()` fails; `unregister(expansion)` casts to `PlaceholderExpansion` and unregisters.
- `BuiltExpansion` (static, private, extends `PlaceholderExpansion`): the expansion built from the declarative maps. `getIdentifier()`/`getAuthor()`/`getVersion()` return the configured values. `persist()` returns TRUE: it survives PlaceholderAPI expansion reloads (e.g. `/papi reload`) and only the context teardown removes it. `onRequest(player, params)`: a null player returns null (unresolved token); the key lowercases; it first looks for an EXACT resolver, then walks the prefixes in registration order with `startsWith`, passing the rest after the prefix as the argument; no match returns null. `resolveSafe` wraps each resolver in a `Throwable` try/catch: an exception logs WARN "Placeholder '%<id>_<params>%' failed to resolve: <t>" and returns null instead of breaking PAPI's parse.

#### Notes and gotchas

- The holder pattern exists because a normal PAPI `import` in an always-loaded class would break on servers without PlaceholderAPI; the probe (`available()`) uses only Bukkit API and the loading of `Bridge`/`BuiltExpansion` stays gated behind it.
- `persist() = true` + lookup-before-register together guarantee that restarting/re-enabling the consumer plugin or reloading PAPI never leaves duplicate expansions or registrations failed over a taken identifier.
- `markBroken` on `LinkageError` is a defense against runtime PAPI version mismatches: it degrades the module to a no-op (text untouched) instead of spamming errors.

### TODOs and limitations

There are no explicit TODO/FIXME markers in any file of this scope. Limitations documented in the code:

- `[particle]` supports the dataTypes `Void`, `Particle.DustOptions` (`color=`/`size=` options, defaults Color.RED and 1.0f), `Particle.DustTransition` (`color=`/`to=`/`size=`), `BlockData` (`block=MATERIAL` mandatory) and `ItemStack` (`item=MATERIAL` mandatory); any other `dataType` (e.g. Vibration, Trail) is still ignored with a WARN ("requires unsupported data").
- `[remove-item]` covers main hand (default), `offhand`, material (`MATERIAL`, excluding stacks tagged by SnLib) and `id:<item-id>`; the selector sweep reaches storage slots 0-35 plus the offhand (it does not touch armor or cursor) and there is no arbitrary-slot support.
- The requirement grammar supports grouping with `( )` and quoting of operands with `'` or `"`; the flip side is that an operand with literal `(`, `)`, `&&`, `||` or operator symbols now MUST be quoted (unquoted, the line falls into fail-open with a WARN, it never blocks players).
- PAPI resolution is main-thread only by design: off the primary thread `apply` leaves the tokens intact (with a debug note); for async flows the bridge is `SnPapi.applyOnMain` (v1.1), which hops the resolution to the main thread and returns an `SnFuture`.
- Expansion resolvers have a cache-only contract (precomputed memory); the holder offers no async variant for I/O resolvers (design decision ratified in v1.1: PAPI's parse is synchronous by contract).
---

# 09

## 09. Multi-tenant, cleanup and reload

SnLib is a shared library: a single jar (a single classloader) serves ~57 consumer plugins at once. This module guarantees that each consumer's state (GUIs, commands, cooldowns, callbacks, open inventories) lives under its owning `Plugin` and dies with it, without leaking classloaders or touching the other consumers. The pillars are: `TenantRegistry` (per-owner base container with sweep), `ListenerHub` (single event registration point), `TenantSweeper` (double safety net when a plugin disables), `QuitCleanupListener` (single quit/kick listener), `Cooldowns` (boxing-free per-player state with a relog policy) and `ReloadManager` (per-context reload in 7 strict phases).

### Hard rule of the shared classloader

Documented in `TenantRegistry`'s Javadoc: statics WITHOUT an owner namespace are only allowed for server-wide data (SnVersion/SnCompat). Anything containing plugin, player or session data goes through a `TenantRegistry` instance with an explicit `Plugin` owner. `TenantRegistry` instances are static fields of library classes and live as long as the library; one is never created per context. Every server-wide static in the module carries a "Server-wide static justified: ..." comment justifying the exception (the `REGISTRIES` enumeration for the sweep, the sweeper's `OPEN_HOLDERS`, the quit-cleanup `CALLBACKS`).

### Non-interference criterion

Cross-cutting rule of the module: every cleanup or reload operation affects EXCLUSIVELY the owner involved.

- `TenantRegistry.removeOwner(owner)` touches only that owner's key; the registrations of every other plugin stay intact.
- `TenantSweeper`'s per-consumer sweep clears only that owner's registrations.
- `ReloadManager.reloadPlugin()` rebuilds only the modules of the context's owning plugin: it never touches other consumers' state nor the library's own.

### TenantRegistry

`src/main/java/com/sn/lib/tenant/TenantRegistry.java`

Generic multi-tenant registry keyed by the owning plugin: the base container of all per-plugin state the library keeps (GUIs, commands, cooldowns, listener callbacks, expansions, recipes, holograms, bossbars, soft dependencies). Internally it is a `Map<Plugin, Set<T>>` over `ConcurrentHashMap`, with an optional `Consumer<T> onSweep`. Each instance self-enrolls in the static `REGISTRIES` set so `sweepOwner` can enumerate all existing registries.

- `public TenantRegistry()` - registry without a sweep callback.
- `public TenantRegistry(@Nullable Consumer<T> onSweep)` - registry whose values receive `onSweep` when their owner key is removed via `removeOwner`; lets a sweep release resources (force-disable of hooks, inventory closing) even when the owner never cleaned up.
- `public void add(Plugin owner, T value)` - registers a value under its owner. The mutation goes inside `compute`: atomic per key against the `remove()` drop, so a concurrent add never lands in a set whose key was just removed.
- `public void remove(Plugin owner, T value)` - unregisters a value, dropping the owner key when its set becomes empty.
- `public Set<T> forOwner(Plugin owner)` - unmodifiable view of the owner's values; empty when it has none.
- `public Set<T> removeOwner(Plugin owner)` - removes the owner's WHOLE key and returns the values it had, applying the sweep callback (if configured) to each one. Every callback failure is caught as `Throwable` and logged WARN "Sweep of a registration failed: ...". Only that owner's key is touched (non-interference).
- `public void forEachOwner(BiConsumer<Plugin, Set<T>> action)` - applies the action to each owner with an unmodifiable view of its values.
- `public static void sweepOwner(Plugin owner)` - sweeps an owner from ALL existing registries; each registry loses only that owner's key.

#### Notes and gotchas

- Why `removeOwner` removes the whole key and not just the values: keeping the `Plugin` key in the map would keep the disabled plugin's classloader reachable (the Javadoc calls it "the ManticCommand leak"). Removing the whole key cuts that reference.
- `onSweep` is defensive by design: it runs value by value with an individual try/catch, so a broken value does not prevent sweeping the rest.

### OwnedHolder

`src/main/java/com/sn/lib/tenant/OwnedHolder.java`

Marker interface (`extends InventoryHolder`) of every inventory the library creates on behalf of a consumer plugin. The tenant sweeper and the quit cleanup listener compile against this interface to identify library inventories and their owner without depending on the GUI module; the GUI module's holder implements it.

- `Plugin owner()` - plugin the inventory belongs to.

### ListenerHub (internal)

`src/main/java/com/sn/lib/tenant/internal/ListenerHub.java`

Single enrollment point of all the library's shared listeners. Fixed mechanics: each module enrolls its shared listener here (accumulated in a `CopyOnWriteArrayList` from the static initializer, before any bootstrap call) and `registerAll` performs the ONLY `registerEvents` call of the whole library, invoked once from the `SnLibPlugin` bootstrap. No library code may register events anywhere else.

- `public static void inscribe(Listener listener)` - adds a shared listener to the hub; it stays dormant until `registerAll`.
- `public static void registerAll(SnLibPlugin plugin)` - registers each enrolled listener against the SnLib plugin. Idempotent: it first does `HandlerList.unregisterAll(plugin)` to drop SnLib's previous registrations, so a double call or a re-enable never duplicates handlers (a SnLib disable also unregisters them all).

#### Canonical enumeration of the 16 listeners (with their origin package)

Exact static initializer order:

1. `new HookListener(SoftDependency::forEachRegistered)` - `com.sn.lib.hook` (hooks / soft dependencies).
2. `new TenantSweeper()` - `com.sn.lib.tenant.internal` (sweep on plugin disable).
3. `new QuitCleanupListener()` - `com.sn.lib.internal` (quit/kick cleanup).
4. `new ArmourEquipListener()` - `com.sn.lib.event.internal` (armor events).
5. `new ChunkMoveListener()` - `com.sn.lib.event.internal` (SnChunkMoveEvent synthesis, D3, v1.1).
6. `new ItemPropertyListener()` - `com.sn.lib.item.internal` (item properties).
7. `new ItemInteractListener()` - `com.sn.lib.item.internal` (item interactions).
8. `new LockedItemListener()` - `com.sn.lib.item.internal` (locked items).
9. `new GuiClickListener()` - `com.sn.lib.gui.internal` (GUI clicks).
10. `new GuiProtectionListener()` - `com.sn.lib.gui.internal` (GUI protection).
11. `PlayerDataCache.joinListener()` - `com.sn.lib.db` (player data cache, join).
12. `UpdateChecker.joinListener()` - `com.sn.lib.update` (update notice on join, D4, v1.1).
13. `new HologramChunkListener()` - `com.sn.lib.hologram.internal` (chunk load/unload for holograms).
14. `new SelectionWandListener()` - `com.sn.lib.region.internal` (cuboid selection wand, v1.1).
15. `new TeleportMoveListener()` - `com.sn.lib.teleport.internal` (warmup teleport cancel on move, v1.6).
16. `new TeleportDamageListener()` - `com.sn.lib.teleport.internal` (warmup teleport cancel on damage, v1.6).

### TenantSweeper (internal)

`src/main/java/com/sn/lib/tenant/internal/TenantSweeper.java`

Double-net shared listener, owned by SnLib: when a consumer disables, every per-owner registration is swept EXCLUSIVELY for that owner (non-interference), its open library inventories close and its context key is removed; when SnLib itself disables, the full cascade shuts down all live contexts in reverse registration order.

Internal interface `ContextAccess` (access to the context registry, installed by SnLib's static initializer):

- `boolean detach(Plugin owner, Sn expected)` - removes the owner's context key only if it still maps to `expected`.
- `List<Sn> detachAllReversed()` - removes and returns all contexts, in reverse registration order.

Public methods:

- `public static void bindContexts(ContextAccess access)` - installs the access to SnLib's context registry; called from SnLib's static init.
- `public static void trackInventory(OwnedHolder holder)` - tracks an open library inventory so a disable of its owner closes it (goes to the static `TenantRegistry<OwnedHolder> OPEN_HOLDERS`, whose sweep callback closes the inventory evicting each viewer).
- `public static void untrackInventory(OwnedHolder holder)` - stops tracking a library inventory once closed.
- `public static void forEachOpenInventory(Consumer<OwnedHolder> action)` - applies the action to every tracked open library inventory, across all owners.
- `public void onPluginDisable(PluginDisableEvent event)` - `@EventHandler(priority = EventPriority.MONITOR)` handler. If the disabled plugin is `SnLibPlugin`, it fires `cascadeAll()` immediately. If it is a consumer with a context (`SnLib.context(owner) != null`), it defers the sweep 1 tick.
- `public static void cascadeAll()` - full shutdown cascade: detaches and shuts down every live context in reverse registration order. Idempotent (the registry drains on the first pass); it is fired by SnLib's disable event and invoked again from the bootstrap's `onDisable` as a double net in case the listener never got registered. For each context: forced-shutdown WARN if it was not already closing (and is not SnLib itself), `shutdownQuietly(ctx)`, `OPEN_HOLDERS.removeOwner(owner)` and `TenantRegistry.sweepOwner(owner)`.

#### Internal logic

- 1-tick deferred net: Bukkit fires `PluginDisableEvent` BEFORE the plugin's own `onDisable`. That is why the per-consumer sweep defers one tick (via `Bukkit.getGlobalRegionScheduler().run(...)` on Folia or `Bukkit.getScheduler().runTask(...)` on Bukkit, always with SnLib as the scheduling plugin): the tidy teardown (the consumer's `onDisable` calling `Sn.shutdown()`) runs first over live modules, and the deferred pass only re-checks what the owner left behind. If SnLib is no longer enabled, or the scheduler is unavailable (server shutdown race), the defer aborts silently and the leftovers are caught by SnLib's cascade, which runs after every consumer that hard-depends on the lib has already been disabled.
- `sweep(Plugin owner, Sn captured)` (private): compares the current context against the one captured at disable time; if the owner already re-registered a NEW context (re-enable within the same tick), it does nothing. If the context is still alive and not shutting down, it logs the WARN "SnLib context not closed in onDisable; shutdown forced by the sweeper (double net)" and shuts it down. Then `OPEN_HOLDERS.removeOwner(owner)`, `TenantRegistry.sweepOwner(owner)`, `SoftDependency.targetDisabled(owner.getName())` and `access.detach(owner, captured)` (detach conditioned on the captured context).
- `shutdownQuietly(Sn ctx)` (private): `ctx.shutdown()` with a `Throwable` catch and WARN "Context shutdown failed: ...".
- `closeHolder(OwnedHolder holder)` (private, `OPEN_HOLDERS` sweep callback): closes the inventory iterating a copy of its viewers (`List.copyOf(holder.getInventory().getViewers())`) and calling `viewer.closeInventory()`; on failure, WARN "Could not close a lib inventory: ...".

#### Notes and gotchas

- The "shutdown forced by the sweeper (double net)" WARN is the symptom of a consumer that did not call `Sn.shutdown()` in its `onDisable`: the lib covers it, but warns.
- The conditioned detach (`detach(owner, expected)`) prevents a deferred sweep from clobbering a context freshly re-registered by a quick re-enable of the same plugin.

### QuitCleanupListener (internal)

`src/main/java/com/sn/lib/internal/QuitCleanupListener.java`

The ONLY `PlayerQuitEvent`/`PlayerKickEvent` listener of the whole library, owned by SnLib. Modules register per-owner cleanup callbacks via `register`; on quit or kick, the listener forces the player's open inventory closed when its holder is a library `OwnedHolder` and then runs all owners' callbacks with the player's UUID.

- `public static void register(Plugin owner, Consumer<UUID> callback)` - registers a callback that runs with the departing player's UUID; it sweeps per-owner (callbacks live in a static `TenantRegistry<Consumer<UUID>>`).
- `public void onQuit(PlayerQuitEvent event)` - `@EventHandler(priority = EventPriority.HIGHEST)` handler; delegates to the private cleanup.
- `public void onKick(PlayerKickEvent event)` - `@EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = true)` handler; delegates to the private cleanup.

#### Internal logic

- `cleanup(Player player)` (private): first closes the library inventory if the player is looking at one, then walks all owners' callbacks with an individual try/catch; on failure WARN "Quit-cleanup callback failed: ...".
- `closeLibraryInventory(Player player)` (private): detects whether the player is viewing a lib inventory by walking the tracked `OwnedHolder`s and their viewers (via `TenantSweeper.forEachOpenInventory`) instead of using the player's open-view API, whose view type is binary-incompatible across the 1.20.4/1.21 boundary and is forbidden in the codebase. If they are viewing one, `player.closeInventory()`.

#### Notes and gotchas

- A kicked player fires BOTH events (kick and quit), so registered callbacks MUST be idempotent (documented in the class Javadoc).

### Cooldowns

`src/main/java/com/sn/lib/cooldown/Cooldowns.java`

Per-context cooldown store, keyed by category and player. The state is `Map<String, Map<UUID, long[]>>` where each one-element `long[]` cell holds the expiry epoch millis: no `Long` boxing on the hot path. Private constant `SWEEP_PERIOD_TICKS = 5L * 60L * 20L` (5 minutes).

- `public Cooldowns(Sn ctx)` - constructor; registers `this::clearSession` in `QuitCleanupListener` under the context's plugin.
- `public boolean tryUse(UUID player, String category, Duration cooldown)` - arms the category's cooldown for the player unless it is still running. Returns true when the action may run (cooldown armed or re-armed); false while the player is still cooling down.
- `public boolean tryUseTicks(UUID player, String category, long cooldownTicks)` - tick variant of `tryUse` (1 tick = 50 ms).
- `public long remainingMillis(UUID player, String category)` - remaining millis of the player's cooldown; 0 when expired or never armed. Lazy purge: an expired entry is removed on read (with `remove(player, expiry)` conditioned on the same array, race-safe).
- `public void registerSessionCategory(String category)` - marks a category as session-scoped: its entries clear when the player quits or is kicked. Entries of every other category survive relogs by design.
- `public void clearSession(UUID player)` - drops the player's entries in every session category; persistent categories stay.
- `public void clearAll()` - drops every entry of every category and stops the sweep task (cancel with a `Throwable` catch: the scheduler may no longer exist during shutdown).

#### Internal logic

- Relog policy: non-expired entries are NEVER dropped when a player quits, so a relog does not reset cooldowns. The only explicit exception are the categories registered via `registerSessionCategory`, cleared on quit/kick by the quit cleanup listener.
- `tryUseMillis` (private): with `cooldownMillis <= 0` it returns true directly. Arming is atomic via `entries.compute(player, ...)`: if a live expiry exists (`expiry[0] > now`) the existing one wins and it returns false; otherwise the new `armed` array wins and it returns true (identity comparison `winner == armed`).
- `ensureSweepScheduled` (private): double-checked (volatile + synchronized) and does not schedule if the context is shutting down. It schedules `ctx.scheduler().timerAsync(SWEEP_PERIOD_TICKS, SWEEP_PERIOD_TICKS, this::sweepExpired)` on first use; `sweepScheduled` becomes true ONLY after a successful schedule. On failure, `sweepScheduled` stays false (the next `tryUse*` retries scheduling) and the WARN "Could not schedule the cooldown sweep; only the lazy purge remains: ..." is emitted exactly once (flag `sweepWarned`, accessed only under `synchronized(this)`; `clearAll` resets it along with `sweepScheduled`).
- `sweepExpired` (private, runs ASYNC every 5 minutes): `removeIf(expiry -> expiry[0] <= now)` over every category. Safe because the maps are `ConcurrentHashMap`.

#### Notes and gotchas

- Double purge strategy: lazy on read (`remainingMillis`) plus a periodic async sweep; if the sweep could not be scheduled, the lazy purge remains and the next `tryUse*` retries scheduling (single WARN).
- The one-element `long[]` is a deliberate performance decision: it avoids `Long` boxing on the `tryUse`/`remainingMillis` hot path while providing an object identity for `compute`'s logical CAS and the conditioned `remove`.

### ReloadManager

`src/main/java/com/sn/lib/reload/ReloadManager.java`

Reload orchestrator of a consumer context, reached via `sn.reload()`. Invoked by the default `reload` subcommand, `/snlib reload <plugin>` and programmatic code; `Sn.reloadAll()` delegates here. Main-thread only. A reload NEVER reloads classes: updating SnLib.jar requires a server restart. The synchronous I/O re-read is accepted ONLY because reload is an administrative command that never runs during gameplay.

- `public ReloadManager(Sn ctx)` - creates the manager for the given context; instantiated by the context.
- `public void register(Reloadable reloadable)` - registers a consumer component for re-dispatch (typed re-cache) on every `reloadPlugin()` of this context. Ignores null. Internal list is a `CopyOnWriteArrayList`.
- `public ReloadManager reopenGuis(boolean reopen)` - opt-in of the reload's final step: when enabled, GUIs open at reload time re-open for their viewers at their page afterwards. Default off: reloaded GUIs stay closed. Returns `this` (fluent).
- `public void reloadPlugin()` - reloads every module of the owning plugin in the documented strict order (see below).

#### Strict order of the 7 phases of `reloadPlugin()`

Before phase 1, if `reopenGuis` is on, an immutable snapshot of the open sessions (viewer UUID, guiId, page) is captured via the private `OpenGui` record.

1. Close this context's GUIs BEFORE re-reading ymls (`guis.closeAll()`); closing each per-viewer session also cancels its render/update `TaskHandle`s.
2. Cancel the remaining per-context render/update tasks (`ctx.items().cancelTasks()`, the held-effects timer).
3. Re-read ymls in order: config first (managed re-merge, `yml.reloadAll()`), then lang (`lang.reload()`), guis (`guis.load()`) and items (`ctx.items().reload()`).
4. Re-register this owner's command roots (`ctx.commands().reregisterAll()`); each registration pass refreshes the client trees via `player.updateCommands()`.
5. Re-dispatch the registered `Reloadable`s (typed re-cache), each with a `Throwable` try/catch and SEVERE log "A registered Reloadable failed during the reload"; per-file `onReload` hooks already fired during the re-read.
6. Recipe cycle on the main thread (`ctx.items().reloadRecipes()`): unregister every recipe key of this owner and re-add the recipes from the reloaded definitions.
7. Re-open the captured GUIs only with opt-in; by default they stay closed. A GUI only reopens if the viewer is still online and the reloaded GUI still exists (`gui.open(player, open.page())`).

#### Internal logic

- `guisOrNull()` / `ymlOrNull()` / `langOrNull()` (private): access the context's modules catching `UnsupportedOperationException`; a module not declared by the consumer is simply skipped in the flow (the reload does not require the plugin to use GUI, yml or lang).
- `capture(List<GuiSession>)` (private static): immutable snapshot of the open sessions before the reload closes them.
- Private record `OpenGui(UUID viewer, String guiId, int page)`.

#### Notes and gotchas

- The config -> lang -> guis -> items order in phase 3 is not accidental: later layers depend on values of the earlier ones.
- Phase 5 clarifies that per-file `onReload` hooks already ran during phase 3's re-read; `Reloadable`s are for the consumer's typed re-caches, not for re-reading files.

### Reloadable

`src/main/java/com/sn/lib/reload/Reloadable.java`

Functional interface: a component capable of rebuilding its state from its sources (files, registries, caches).

- `void reload()` - rebuilds the component's state; invoked by its owning context's reload flow.

### Registrable

`src/main/java/com/sn/lib/reload/Registrable.java`

Interface: a component that can attach and detach from a server-side registry.

- `void register()` - attaches the component to its registry.
- `void unregister()` - detaches the component from its registry; safe to call when not registered.

### TODOs and limitations

There are no TODO/FIXME/placeholder markers in this module's files. Limitations documented in Javadoc/code:

- A reload NEVER reloads classes: updating SnLib.jar requires a server restart (`ReloadManager`).
- The reload's synchronous I/O is accepted only because it is an administrative command; it must not run during gameplay (`ReloadManager`).
- A kicked player fires kick and quit: `QuitCleanupListener.register` callbacks must be idempotent.
- If `Cooldowns`' async sweep cannot be scheduled, the lazy read purge remains and the next `tryUse*` retries scheduling; the WARN emits only once.
- When the scheduler is unavailable (server shutdown race), `TenantSweeper`'s 1-tick deferred net aborts silently and the leftovers are handled by SnLib's cascade.
- The player's open-view API is forbidden in the codebase due to 1.20.4/1.21 binary incompatibility; open inventory detection is done by walking the tracked `OwnedHolder`s.
---

# (Generated section - SnLib v1.1.0 documentation)

## 10. Custom events

The `com.sn.lib.event` package defines the infrastructure of SnLib's own events: two self-firing abstract bases (`SnEvent` and `SnPlayerEvent`, both `Cancellable`, with the `call()` method that dispatches via `PluginManager` and returns whether the event survived), the concrete events `SnArmourEquipEvent` (armor equip/unequip through any vector) and `SnChunkMoveEvent` (chunk crossing via movement, v1.1), and the `EquipMethod` enum with the 8 input vectors. Each event's synthesis is done by its shared listener (`internal/ArmourEquipListener` and `internal/ChunkMoveListener`), owned by SnLib: they enroll exactly once in the `ListenerHub` (`src/main/java/com/sn/lib/tenant/internal/ListenerHub.java`) and the `registerEvents` happens SOLELY in the `SnLibPlugin` bootstrap, so the ~57 consumer plugins listen to the events without registering their own sources. The whole flow runs on the main thread (the source Bukkit/Paper events are synchronous); the bases additionally expose `async` constructors for subclasses that need them.

### SnEvent
`src/main/java/com/sn/lib/event/SnEvent.java`

Self-firing, cancelable abstract base for the library's custom events. Extends `org.bukkit.event.Event` and implements `Cancellable`. Concrete subclasses fire themselves via `call()` and must still provide Bukkit's handler-list pair: the instance method `getHandlers()` plus the static `getHandlerList()` (the base does not provide them).

- `protected SnEvent()` - synchronous constructor (main-thread event, Bukkit's default).
- `protected SnEvent(boolean async)` - constructor delegating to `Event(boolean)` to mark the event as asynchronous when the subclass requires it.
- `public boolean call()` - dispatches the event via `Bukkit.getPluginManager().callEvent(this)` and returns `!isCancelled()`: `true` if the event "survived" (nobody cancelled it), `false` if some listener cancelled it.
- `public boolean isCancelled()` - returns the internal cancellation flag.
- `public void setCancelled(boolean cancelled)` - sets the internal cancellation flag.

### SnPlayerEvent
`src/main/java/com/sn/lib/event/SnPlayerEvent.java`

Twin abstract base of `SnEvent` for events that always carry a player: extends `org.bukkit.event.player.PlayerEvent` (inherits `getPlayer()`) and implements `Cancellable`. Same contract as `SnEvent`: subclasses fire via `call()` and must provide the handler-list pair.

- `protected SnPlayerEvent(Player who)` - synchronous constructor with the carrying player.
- `protected SnPlayerEvent(Player who, boolean async)` - variant allowing the event to be marked asynchronous.
- `public boolean call()` - dispatches via `Bukkit.getPluginManager().callEvent(this)` and returns `!isCancelled()`.
- `public boolean isCancelled()` - returns the internal cancellation flag.
- `public void setCancelled(boolean cancelled)` - sets the internal cancellation flag.

### SnArmourEquipEvent
`src/main/java/com/sn/lib/event/SnArmourEquipEvent.java`

`final` event fired when a player equips or unequips an armor piece through any vector. Synthesized by the library's shared listener (`ArmourEquipListener`); no consumer plugin builds it by hand in the normal flow. Cancellation is binding ONLY when the underlying source is cancelable (`EquipMethod.DISPENSER`, where cancelling the SnArmourEquipEvent cancels the `BlockDispenseArmorEvent`); events reporting an already-applied change (primary source `PlayerArmorChangeEvent` and `EquipMethod.DEATH`) expose cancellation only as a consumer-level signal (a listener may mark it cancelled so others ignore it, but the armor change is not reverted).

- `public SnArmourEquipEvent(Player player, EquipMethod method, EquipmentSlot slot, @Nullable ItemStack oldPiece, @Nullable ItemStack newPiece)` - builds the event: player whose armor changed, input vector, affected slot, piece leaving the slot (or `null` if the slot was empty) and piece entering (or `null` if the slot empties).
- `public EquipMethod getMethod()` - input vector of the change.
- `public EquipmentSlot getSlot()` - affected armor slot.
- `public @Nullable ItemStack getOldPiece()` - piece leaving the slot, or `null` if the slot was empty.
- `public void setOldPiece(@Nullable ItemStack oldPiece)` - replaces the reported outgoing piece (mutator exposed to listeners; it does not alter the real inventory).
- `public @Nullable ItemStack getNewPiece()` - piece entering the slot, or `null` if the slot empties.
- `public void setNewPiece(@Nullable ItemStack newPiece)` - replaces the reported incoming piece (mutator exposed to listeners; it does not alter the real inventory).
- `public HandlerList getHandlers()` - returns the class's shared static `HandlerList`.
- `public static HandlerList getHandlerList()` - the static pair Bukkit requires; same instance as `getHandlers()`.

Note: the `normalize` contract in the listener guarantees `oldPiece`/`newPiece` never arrive as air; air normalizes to `null`, and an event with both `null` simply is not fired.

### EquipMethod
`src/main/java/com/sn/lib/event/EquipMethod.java`

Public enum with the input vector through which an armor piece was equipped or unequipped. Constants (8):

- `SHIFT_CLICK` - shift-click of the piece between the inventory and its armor slot.
- `DRAG` - dragging the piece to the armor slot inside the inventory.
- `PICK_DROP` - cursor pick-up and drop into/out of the armor slot. It is also the generic manual vector reported when the synthesized source does not expose the exact input.
- `HOTBAR` - right-click equip of the piece in hand, without opening the inventory.
- `HOTBAR_SWAP` - number-key swap while hovering the armor slot.
- `DISPENSER` - auto-equip by a dispenser.
- `BROKE` - the piece broke when its durability ran out.
- `DEATH` - the piece left its slot because the player died.

Synthesizable TODAY vs API constants:

| EquipMethod | Synthesizable today | Real source |
|---|---|---|
| `PICK_DROP` | Yes | `PlayerArmorChangeEvent` (best-effort generic manual vector: the primary source does not expose the exact input) |
| `BROKE` | Yes | `PlayerArmorChangeEvent` when `newPiece == null` and the outgoing piece ran out of durability (`Damageable.getDamage() >= getMaxDurability()`) |
| `DISPENSER` | Yes | `BlockDispenseArmorEvent` (dedicated source, binding cancellation) |
| `DEATH` | Yes | `PlayerDeathEvent` (one emission per equipped piece, except keepInventory) |
| `SHIFT_CLICK` | No | API constant: no current source emits it |
| `DRAG` | No | API constant: no current source emits it |
| `HOTBAR` | No | API constant: no current source emits it |
| `HOTBAR_SWAP` | No | API constant: no current source emits it |

The four fine manual vectors (`SHIFT_CLICK`, `DRAG`, `HOTBAR`, `HOTBAR_SWAP`) exist in the API so consumers can switch exhaustively and for finer future synthesis, but today every manual change arrives collapsed into `PICK_DROP` (or `BROKE`).

### ArmourEquipListener (internal)
`src/main/java/com/sn/lib/event/internal/ArmourEquipListener.java`

Shared `final` listener, owned by SnLib, that synthesizes `SnArmourEquipEvent` from three real sources. It enrolls in the `ListenerHub` and the `registerEvents` happens SOLELY in the `SnLibPlugin` bootstrap: there is a single instance for the whole server, with no per-consumer state.

Primary source: `com.destroystokyo.paper.event.player.PlayerArmorChangeEvent`. That event is `@ApiStatus.Obsolete` (~1.21.4) but was NOT removed: it is present and functional across the whole 1.20.4-1.21.8+ range (SnGens uses it in production). Its use here is DELIBERATE; the Javadoc says to migrate to `io.papermc.paper.event.entity.EntityEquipmentChangedEvent` ONLY when the version floor/baseline rises (that class exists neither in 1.21.1 nor in 1.20.4).

Types and internal state:

- `private record DispenseMark(EquipmentSlot slot, int tick)` - dispenser dedup mark: slot a dispenser equipped and the tick it happened.
- `private final Map<UUID, DispenseMark> dispensed = new ConcurrentHashMap<>()` - transient dedup state, bounded by online players; not per-consumer data.

Methods:

- `@EventHandler(priority = EventPriority.MONITOR) public void onArmorChange(PlayerArmorChangeEvent event)` - primary source (best-effort). Ignores dead players (`player.isDead()`: DEATH synthesizes from `PlayerDeathEvent`), resolves the slot with `slotOf` (if it yields `null`, no emission), discards the event if a live `DispenseMark` exists for that slot (already reported as DISPENSER), normalizes old/new (air -> `null`; if both end up `null` no emission) and fires `SnArmourEquipEvent` with the method `classify` returns (`BROKE` or `PICK_DROP`). Runs at MONITOR priority: it observes the already-decided change, which is why its cancellation is not binding.
- `@EventHandler(ignoreCancelled = true) public void onDispense(BlockDispenseArmorEvent event)` - dedicated `DISPENSER` source. Only acts if the target is a `Player`; resolves the slot with `matchType(event.getItem())` (if `null`, no emission); reads `oldPiece` from the player's inventory at that slot (the equip has not been applied yet). It fires the `SnArmourEquipEvent` and, if some listener cancels it (`!equip.call()`), cancels the `BlockDispenseArmorEvent`: it is the ONLY vector with binding cancellation. If it survives, it records `DispenseMark(slot, Bukkit.getCurrentTick())` to dedup the `PlayerArmorChangeEvent` Paper will fire next for the same change.
- `@EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true) public void onDeath(PlayerDeathEvent event)` - dedicated `DEATH` source. If the event has `getKeepInventory()` on, it emits nothing; otherwise it walks HEAD/CHEST/LEGS/FEET of the player's inventory and delegates to `fireDeath` one emission per present piece.
- `private static void fireDeath(Player player, EquipmentSlot slot, @Nullable ItemStack piece)` - normalizes the piece and, if not `null`, fires `SnArmourEquipEvent(player, DEATH, slot, oldPiece, null).call()` (informational: cancellation reverts nothing).
- `static @Nullable EquipmentSlot matchType(@Nullable ItemStack item)` - (package-private, testable) one-line delegation to `ArmourUtil.slotOf` (the source of truth of the item -> slot mapping by `Material` name suffix); kept so its internal call sites (`onDispense` and `slotOf(PlayerArmorChangeEvent)`) stay untouched.
- `private static @Nullable EquipmentSlot slotOf(PlayerArmorChangeEvent event)` - maps `event.getSlotType().name()` (HEAD/CHEST/LEGS/FEET) to `EquipmentSlot` by name, keeping the source's slot enum open: on an unknown value it falls back to `matchType` over the new item and then the old one; if nothing matches it returns `null` and the event is discarded silently.
- `private static EquipMethod classify(@Nullable ItemStack oldPiece, @Nullable ItemStack newPiece)` - `BROKE` if the piece left (`newPiece == null`) and `broke(oldPiece)` is true; in any other case `PICK_DROP` (generic manual vector).
- `private static boolean broke(@Nullable ItemStack oldPiece)` - true if the `Material` has `getMaxDurability() > 0` and the meta is `Damageable` with `getDamage() >= max` (the piece exhausted its durability).
- `private boolean consumeDispenseMark(UUID uuid, EquipmentSlot slot)` - per-tick dedup: with no mark it returns `false`; if the mark is more than 1 tick old (`Bukkit.getCurrentTick() - mark.tick() > 1`) it removes it (conditional `remove(uuid, mark)`) and returns `false` (expired); if the slot does not match it returns `false` without consuming it; if it matches within the window, it consumes (removes) it and returns `true` (the change was already reported as DISPENSER).
- `private static @Nullable ItemStack normalize(@Nullable ItemStack item)` - normalizes `null` and air (`getType().isAir()`) to `null`; any other piece passes as-is.

#### Internal logic

- Per-tick dedup (dispenser vs primary source): when a dispenser equips a piece, Paper fires `BlockDispenseArmorEvent` and then `PlayerArmorChangeEvent` for the same change. `onDispense` first emits the `SnArmourEquipEvent` with `DISPENSER` and leaves a `DispenseMark(slot, tick)`; `onArmorChange` consults `consumeDispenseMark` and, if the mark is for the same slot and at most 1 tick old, suppresses the duplicate emission. The 1-tick tolerance window covers the case of the `PlayerArmorChangeEvent` arriving on the next tick. A stale mark cleans up lazily on the next query.
- The `dispensed` map is a `ConcurrentHashMap` even though all handlers run on the main thread: cheap defense for shared transient state bounded by online players.
- DEATH never comes from the primary source: `onArmorChange` bails on `player.isDead()` precisely so pieces emptied on death report only from `PlayerDeathEvent`, one emission per piece and with `newPiece = null`.

#### Notes and gotchas

- Obsolescence note: `PlayerArmorChangeEvent` is marked `@ApiStatus.Obsolete` (~1.21.4) but NOT removed; it works across the entire supported 1.20.4-1.21.8+ range and its use is a deliberate decision documented in the Javadoc. The migration to `EntityEquipmentChangedEvent` is explicitly deferred until the version baseline allows it (that class does not exist in 1.20.4 or 1.21.1).
- The primary source does NOT expose the input vector: every manual change reports as `PICK_DROP` (or `BROKE` when the piece exhausted durability). `SHIFT_CLICK`, `DRAG`, `HOTBAR` and `HOTBAR_SWAP` today are API constants with no source emitting them.
- Asymmetric cancellation semantics: cancelling the event only has real effect with `DISPENSER` (it cancels the physical dispense). With `PICK_DROP`/`BROKE`/`DEATH` the change already happened; cancellation is only a signal between consumers.
- Third-party enums treated as open: both `Material` (in `matchType`, via `ArmourUtil.slotOf`) and `PlayerArmorChangeEvent`'s slot type (in `slotOf`) resolve by name with a fallback, so a new Paper constant never breaks the listener; the unknown is ignored silently.
- `onDeath` respects `keepInventory`: if the inventory is kept, no `DEATH` is emitted.
- `SnArmourEquipEvent`'s `setOldPiece`/`setNewPiece` setters only mutate what later listeners see; they do not write to the inventory.
- This module logs nothing: no WARNs or messages of its own.

#### TODOs and limitations

There are no literal TODO/FIXME markers in the module's files. Limitations documented in code/Javadoc:

- The fine manual vectors (`SHIFT_CLICK`, `DRAG`, `HOTBAR`, `HOTBAR_SWAP`) are not synthesizable today: the primary source collapses everything into `PICK_DROP`/`BROKE`.
- Pending migration (deliberately deferred) from `PlayerArmorChangeEvent` to `io.papermc.paper.event.entity.EntityEquipmentChangedEvent` once the supported version baseline rises above 1.21.4.
- Cancellation is not binding for the already-applied vectors (`PICK_DROP`, `BROKE`, `DEATH`); only `DISPENSER` can be reverted.

### SnChunkMoveEvent
`src/main/java/com/sn/lib/event/SnChunkMoveEvent.java`

`final` event (v1.1) fired when a player crosses from one chunk to another via movement. Synthesized by the library's shared listener (`ChunkMoveListener`) from `PlayerMoveEvent`; no consumer plugin builds it by hand in the normal flow. Cancellation is BINDING: cancelling this event cancels the source `PlayerMoveEvent` (same pattern as `SnArmourEquipEvent`'s `DISPENSER` vector). SCOPE: only movement emits it; teleports, joins and respawns do NOT fire it (`PlayerTeleportEvent` has its own HandlerList and does not go through the move handler).

- `public SnChunkMoveEvent(Player player, Location from, Location to)` - builds the event with the crossing player and the origin and destination locations. Both locations are stored as `clone()` (the event's own snapshots): mutating them NEVER affects the source `PlayerMoveEvent`.
- `public Location fromLocation()` - cloned snapshot of the movement's origin location.
- `public Location toLocation()` - cloned snapshot of the movement's destination location.
- `public Chunk fromChunk()` - chunk the player leaves; resolves the origin location's `getChunk()` at invocation (in practice always loaded: the player comes from there).
- `public Chunk toChunk()` - chunk the player enters; resolves the destination location's `getChunk()` at invocation (in practice always loaded: the player is standing there).
- `public HandlerList getHandlers()` - returns the class's shared static `HandlerList`.
- `public static HandlerList getHandlerList()` - the static pair Bukkit requires; same instance as `getHandlers()`.

### ChunkMoveListener (internal)
`src/main/java/com/sn/lib/event/internal/ChunkMoveListener.java`

Shared `final` listener (v1.1), owned by SnLib, that synthesizes `SnChunkMoveEvent`. A single handler `@EventHandler(ignoreCancelled = true) public void onMove(PlayerMoveEvent event)` with an allocation-FREE hot-path quick exit: it compares `getBlockX() >> 4` and `getBlockZ() >> 4` of from/to and the worlds by IDENTITY (`==`; Bukkit `World`s are per-server singletons), and if no chunk crossing happened it returns without creating any object (the overwhelming majority of moves). Only on a crossing does it fire `new SnChunkMoveEvent(player, from, to).call()` and, if some listener cancels it, cancels the source `PlayerMoveEvent` (binding cancellation). It runs at default priority (NORMAL) on purpose: the cancellation must be applicable before MONITOR. It enrolls in the `ListenerHub` (`inscribe(new ChunkMoveListener())`) and the `registerEvents` happens SOLELY in the `SnLibPlugin` bootstrap.
---

## 11. Items

SnLib's physical items module, accessible via `sn.items()`. It covers the complete golden spec (`docs/item-example.yml`): appearance (SnItem, a fluent builder with 1.20.4+ compatibility probes), behavior definition (ItemDef, a universal builder 100% programmatic or parsed from YML), per-context registry (ItemRegistry, with the PDC tag `snlib_item_id` namespaced by owning plugin), binary serialization that survives over-stacked amounts (ItemSerializer) and the obtain mode (ObtainMode). Runtime execution is done by the classes in `internal/`: two shared listeners for properties and interactions, the locked-mode enforcement with 7 vectors, custom durability via PDC, write-through backup of displaced equipment, the held-effects timer and the loader of the 7 recipe variants. The shared listeners are owned by SnLib (registered ONCE in the SnLibPlugin bootstrap via ListenerHub) and resolve each stack's owner by the namespace of its PDC key.

### SnItem
`src/main/java/com/sn/lib/item/SnItem.java`

Fluent builder of physical stacks covering the entire appearance section of the golden spec. Strings (name, lore) go through SnLib's text pipeline (`[rgb]`, `[center]`, legacy codes, MiniMessage) and render non-italic unless the input asks for italics. Materials, enchantments, potion effects and trims resolve leniently via Registry/NamespacedKey with a fallback to legacy names; an unresolvable id logs ONE WARN (deduped by the static concurrent `WARNED` set) and is skipped, never throwing.

- `public static SnItem builder(Material material)` - Starts the builder; a null material falls to `STONE`.
- `public static SnItem fromConfig(SnYml yml, @Nullable Player viewer, Ph... phs)` - Reads every appearance field from the yml root; delegates to the path overload.
- `public static SnItem fromConfig(SnYml yml, @Nullable String path, @Nullable Player viewer, Ph... phs)` - Maps every appearance field of the spec under `path`: display-name, material (with the head convention `texture-`/`basehead-`/`base64-` detected via `HeadUtil.extractTextureValue`, which forces `PLAYER_HEAD`), custom-model-data (only if set), amount, glow, lore, enchantments, flags, color, trim-pattern/trim-material, potion-effects, unbreakable, max-stack-size, equipment-slot, skull-owner (goes through `yml.getString(..., viewer)` + `applyLocals(phs)`, so `skull-owner: "%player_name%"` resolves per viewer), attributes (`ATTRIBUTE OPERATION amount [slot-group]` lines tokenized by whitespace, WITHOUT placeholders: static values of the definition) and damage (only if set). Strings resolve with `viewer` plus the local placeholders `phs`.
- `public SnItem name(String name)` - Display name; rendered by the text pipeline, non-italic unless requested.
- `public SnItem lore(List<String> lines)` - Adds lore lines (null becomes ""); each goes through the text pipeline.
- `public SnItem lore(String... lines)` - Varargs convenience of the above.
- `public SnItem amount(int amount)` - Stack amount, floored at 1 when building.
- `public SnItem glow()` - Enchantment glint. Uses `setEnchantmentGlintOverride` if it exists (1.20.5+, via `SnCompat.probe`); on 1.20.4 it degrades to a real vanilla enchantment (`LURE` level 1, only if there are no enchantments) plus `HIDE_ENCHANTS`, with ONE WARN.
- `public SnItem enchant(String id, int level)` - Adds an enchantment by lenient id (Registry key or legacy Bukkit name).
- `public SnItem flags(List<String> names)` - Adds ItemFlags by name. `HIDE_ALL` expands to this server's `ItemFlag.values()`; an unknown name tries the `HIDE_POTION_EFFECTS`/`HIDE_ADDITIONAL_TOOLTIP` alias (bidirectional) before ONE WARN.
- `public SnItem hideAllTooltipFlags()` - Adds every tooltip-hiding flag known to this server (the 10-name `TOOLTIP_FLAGS` array, resolved one by one with `valueOf` in try/catch; absent ones skip without WARN).
- `public SnItem color(String color)` - Tint for colorable metas (leather armor, potions). Accepts `"R, G, B"` and hex `"RRGGBB"`/`"#RRGGBB"`; a material without tint support or a malformed color logs ONE WARN and is ignored.
- `public SnItem trim(String pattern, String material)` - Armor trim; the two values must come together, `NONE` or empty disables. The lookup prefers `RegistryAccess` (RegistryKey.TRIM_PATTERN/TRIM_MATERIAL, only if `SnVersion.supports(20, 6)`) with the legacy `Registry.TRIM_*` fields (deprecated since 1.20.6) as a lenient fallback; a meta that is not `ArmorMeta` or an invalid trim WARNs once and is ignored.
- `public SnItem potionEffects(List<String> effects)` - Custom potion effects for items with `PotionMeta`. Flat spec form `[effect-id, level, duration]`; level default 1 (the amplifier is `level - 1`) and duration default 200 ticks.
- `public SnItem modelData(int modelData)` - Custom model data; it only stamps the meta when explicitly set.
- `public SnItem headBase64(String value)` - Head texture accepted by `HeadUtil.extractTextureValue`; requires `PLAYER_HEAD` (another material WARNs and is ignored).
- `public SnItem skullOwner(String nameOrUuid)` - Head by player (name or UUID, trimmed; null/blank is a no-op); requires `PLAYER_HEAD`. Synchronous resolution in `build()` stays non-blocking: first `UUID.fromString` in try/catch -> `Bukkit.getOfflinePlayer(UUID)`; if it does not parse as UUID -> `Bukkit.getOfflinePlayerIfCached(name)`. `Bukkit.getOfflinePlayer(String)` is FORBIDDEN (it can block the main thread with an HTTP lookup). Precedence: if `headBase64` is also present, skull-owner wins and the base64 texture is ignored with a single WARN (`skull-owner-conflict`). Async upgrade (1.6): when the server has no cached textured profile, the head shows the best-effort default now and an OFF-THREAD `PlayerProfile.update()` fetch (via `SkinResolver`) upgrades it - a live GUI re-renders the slot when it lands, a direct build shows it on the next build once the shared cache warms; the uncached-name WARN is GONE, only a genuinely unresolvable owner still WARNs once. See `applySkullOwner` internal logic below.
- `public SnItem skinRefresh(@Nullable Consumer<String> onDeferred)` (1.6) - Registers a callback fired at `build()` when this item's `skull-owner` has no cached textured profile yet: it receives the raw owner and is expected to schedule the off-thread resolution and, for a live GUI, re-render the affected slot once the texture lands. The GUI module wires this automatically (see section 12); a direct builder may leave it unset, in which case `build()` falls back to `SkinResolver.requestSelf` to warm the shared cache for the next build. Null clears it. Returns `this`.
- `public SnItem attribute(String attributeId, String operation, double amount, @Nullable String slotGroup)` - Adds an attribute modifier line (trimmed ids; a null/blank attributeId or operation is ignored). The attribute resolves leniently via `attributeKeyCandidates` (see internal logic); the operation is an `AttributeModifier.Operation` name (ADD_NUMBER, ADD_SCALAR, MULTIPLY_SCALAR_1); slotGroup is an `EquipmentSlotGroup` name (null/blank = ANY).
- `public SnItem damage(int damage)` - Initial spent VANILLA durability; clamped to `[0, maxDurability]` when applied. Independent from ItemDef's `custom-durability` system. A material without vanilla durability or a meta that is not `Damageable` WARNs once and is ignored.
- `public SnItem unbreakable(boolean unbreakable)` - Vanilla unbreakable flag.
- `public SnItem maxStackSize(int maxStackSize)` - Max stack size via the `setMaxStackSize` probe (1.20.5+); on 1.20.4 the value is omitted with ONE WARN (already emitted by the probe). The value clamps to 1..99 when applied.
- `public SnItem equipmentSlot(String slot)` - Declared spec slot (MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET). Validated leniently in `build()` with ONE WARN on typos; the stack itself is not altered, enforcement belongs to the definition layer.
- `public ItemStack build()` - Builds the stack applying every configured field with lenient degradation; if the meta is null (meta-less materials) it returns the bare stack.
- `public static @Nullable EquipmentSlot parseEquipmentSlot(String raw)` - Lenient spec name to `EquipmentSlot`: `MAINHAND` maps to `HAND` and `OFFHAND` to `OFF_HAND`; an unknown name returns null.

#### Internal logic
- `applySkullOwner(ItemMeta)` (1.6, three non-blocking tiers): (1) a hit in the shared `SkinResolver.cachedProfile(owner)` is applied outright via `HeadUtil.applyProfile` (this is how every consumer ends up textured once the cache is warm); (2) otherwise the owner resolves non-blocking (`resolveSkullOwner`) and is applied as the best-effort look now, and if that profile already `hasTextures()` it stops; (3) when the look still lacks textures, an off-thread fetch is scheduled - through the wired `skinRefresh` hook for a live GUI, or `SkinResolver.requestSelf(owner)` for a direct build. The genuinely-unresolvable WARN is deferred to the fetch, so an uncached name no longer warns just for being uncached. A non-`SkullMeta` material still WARNs once (`skull-owner-meta`).
- `readEnchantments` and `applyPotionEffects` walk the spec's flat `[id, level, ...]` form: `tokenize` splits each entry by spaces/commas/semicolons, so the flat and inline forms parse the same. A number without a preceding id WARNs with the expected format and is ignored.
- `resolveMaterial`: `Material.matchMaterial` first, then `Registry.MATERIAL` by NamespacedKey; invalid WARNs and uses `STONE`.
- `resolveEnchant`/`resolveEffect`: Registry by key with the legacy `getByName` fallback (deprecated on purpose, resolves names like `FAST_DIGGING`).
- `attributeKeyCandidates(String)` (package-private, pure, tested in `SnItemAttributeParseTest`): normalizes (trim, lower, `-`->`_`, strips `minecraft:`) and returns in order without duplicates: (1) the normalized form as-is, (2) without the `generic_`/`player_`/`zombie_` prefix (1.21.2+ keys), (3) the dotted form at the first `_` if it had a prefix (pre-1.21.3 keys, e.g. `generic.movement_speed`), (4) `generic.` + the bare form (inverse alias: `ARMOR` resolves as `generic.armor` on old servers). `resolveAttribute` iterates the candidates against `Registry.ATTRIBUTE`; first hit wins. This implements the bidirectional `GENERIC_ARMOR` <-> `ARMOR` alias of the 1.21.2+ rename without hardcoded tables.
- `buildModifier`: dual branch. Modern (gated by `SnVersion.supports(21, 0)` + a Throwable catch, because `SnCompat.probe` only covers methods, not constructors): `new AttributeModifier(NamespacedKey.fromString("snlib:" + keyName), amount, op, EquipmentSlotGroup)` with the deterministic key `snlib:attr_<i>_<sanitized-attr>` (fixed `snlib` namespace via `fromString`: SnItem is a static builder with no plugin reference). Legacy (1.20.4 or Throwable): the deprecated constructor with `UUID.nameUUIDFromBytes(keyName)` and `legacySlot` (package-private, tested: null/blank/ANY/ARMOR/BODY -> null = any slot; the rest delegates to `parseEquipmentSlot`).
- `ItemFlag` is treated as an open enum: individual `valueOf` in try/catch, never switch/EnumSet, to tolerate different version branches.
- `warnOnce(tag, message)` dedupes the WARNs in the static `WARNED` set (`[SnLib] ` prefix via `Bukkit.getLogger()`). Server-wide static allowed by the SnLib contract: it records facts about THIS server's registries, not a consumer's.

#### Notes and gotchas
- The `TOOLTIP_FLAGS` field is private; it includes names from several version branches (`HIDE_POTION_EFFECTS` and `HIDE_ADDITIONAL_TOOLTIP` at once) because not all exist on every server.
- `glow()` on an already-enchanted item in 1.20.4 does not add `LURE` (only if `!meta.hasEnchants()`).

### ItemDef
`src/main/java/com/sn/lib/item/ItemDef.java`

Immutable definition of a physical item covering the full golden spec: appearance, behavior properties (droppable, moveable, placeable, tradeable, despawnable, keep-on-death, cooldown), locked-mode fields (locked, no-drop, no-manual-equip, obtain-via), custom durability, the 12 interaction action lists with their Java callbacks (right/left x plain/shift/block/air/shift-block/shift-air: the 8 from v1.0.0 plus the 4 shift-positional variants of 1.1.0) with the priority flag `shift-overrides-generic`, interaction requirements with deny-actions, pickup/drop actions, held effects, equipment slot and recipe. `builder()` is a first-class universal constructor: every field is settable programmatically without a YML file. YML-backed definitions re-read their appearance section on each `ItemRegistry.create`, so placeholders resolve per viewer. `max-stack-size` belongs to the appearance layer (`SnItem.maxStackSize`); it is not duplicated here. Interact-requirements parse ONCE at construction into an immutable `Requirement` tree via `RequirementEngine.parse`.

- `public static Builder builder()` - Starts the universal programmatic builder; requires no YML.
- `static @Nullable ItemDef fromYml(SnYml yml, String path, Consumer<String> warn)` - (package-private) Parses the complete definition from the section at `path`; warnings go to `warn`. Returns null if the section does not exist (with an "item ignored" WARN). Defaults: droppable/moveable/placeable/tradeable/despawnable true, keep-on-death false, cooldown 0, locked/no-drop/no-manual-equip false, obtain-via "", shift-overrides-generic true.
- `ItemStack buildStack(@Nullable Player viewer, Ph... phs)` - (package-private) Builds the physical stack without the id tag: a YML definition re-reads its appearance section with viewer and phs; a programmatic definition renders its captured `SnItem` or clones its template; with neither it returns `new ItemStack(Material.STONE)`.
- `public boolean droppable()` - Whether the player can drop the item.
- `public boolean moveable()` - Whether the item can move in inventories.
- `public boolean placeable()` - Whether the item can be placed as a block.
- `public boolean tradeable()` - Whether the item can be traded with villagers.
- `public boolean despawnable()` - Whether the item despawns when left on the ground.
- `public boolean keepOnDeath()` - Whether the item is kept on death and returned on respawn.
- `public int cooldownTicks()` - Cooldown between interactions in ticks; 0 disables it (floored at 0 in construction).
- `public boolean locked()` - Whether the item is pinned to its slot (locked mode).
- `public boolean noDrop()` - Hard alias of `droppable: false`; blocks drops and drag-outs.
- `public boolean noManualEquip()` - Whether manual equipping to armor slots or offhand is blocked.
- `public ObtainMode obtainVia()` - How it can legitimately enter circulation.
- `public int durabilityMax()` - Custom durability maximum; 0 disables the system (floored at 0).
- `public int durabilityDamagePerUse()` - Durability lost per use; floored at 1.
- `public String durabilityLoreFormat()` - Format of the lore line with `%durability%`/`%max_durability%`; empty hides it.
- `public List<String> breakActions()` - Actions run when custom durability reaches 0.
- `public List<String> rightClickActions()` - Right-click actions.
- `public List<String> leftClickActions()` - Left-click actions.
- `public List<String> shiftRightClickActions()` - Shift + right-click actions.
- `public List<String> shiftLeftClickActions()` - Shift + left-click actions.
- `public List<String> rightClickBlockActions()` - Right-click-on-block actions.
- `public List<String> rightClickAirActions()` - Right-click-in-air actions.
- `public List<String> leftClickBlockActions()` - Left-click-on-block actions.
- `public List<String> leftClickAirActions()` - Left-click-in-air actions.
- `public List<String> shiftRightClickBlockActions()` - Shift + right-click-on-block actions.
- `public List<String> shiftRightClickAirActions()` - Shift + right-click-in-air actions.
- `public List<String> shiftLeftClickBlockActions()` - Shift + left-click-on-block actions.
- `public List<String> shiftLeftClickAirActions()` - Shift + left-click-in-air actions.
- `public boolean shiftOverridesGeneric()` - Priority rule between a declared shift variant and its base variant. True (default): on shift-click, the shift variant WITH behavior runs INSTEAD of the plain generic/positional one. False: BOTH run, shift first and base after, lists and callbacks in that order. It applies equally to the shift-positionals over the plain positionals.
- `public List<String> interactRequirements()` - Raw requirement lines as declared.
- `public Requirement interactRequirement()` - Requirement tree parsed once; never null.
- `public List<String> denyActions()` - Actions run when the requirements are not met.
- `public List<String> pickupActions()` - Actions on picking up the item.
- `public List<String> dropActions()` - Actions on dropping the item.
- `public List<String> heldEffectsMainhand()` - Effect lines (`"EFFECT amplifier"`) applied with the item in the main hand.
- `public List<String> heldEffectsOffhand()` - Effect lines applied with the item in the offhand.
- `public List<String> heldEffectsArmor()` - Effect lines applied with the item worn as armor.
- `public String equipmentSlotName()` - Slot name declared in the spec; empty allows any slot.
- `public @Nullable EquipmentSlot equipmentSlot()` - Parsed slot, or null when there is no restriction or the name was invalid.
- `public @Nullable Recipe recipe()` - The item's crafting recipe, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onRightClick()` - Right-click Java callback, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onLeftClick()` - Left-click Java callback, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onShiftRightClick()` - Shift + right-click Java callback, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onShiftLeftClick()` - Shift + left-click Java callback, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onRightClickBlock()` - Right-click-on-block Java callback, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onRightClickAir()` - Right-click-in-air Java callback, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onLeftClickBlock()` - Left-click-on-block Java callback, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onLeftClickAir()` - Left-click-in-air Java callback, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onShiftRightClickBlock()` - Shift + right-click-on-block Java callback, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onShiftRightClickAir()` - Shift + right-click-in-air Java callback, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onShiftLeftClickBlock()` - Shift + left-click-on-block Java callback, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onShiftLeftClickAir()` - Shift + left-click-in-air Java callback, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onApply()` - Java hook running after `ItemRegistry.apply` injects the item, or null.
- `public @Nullable BiConsumer<Player, ItemStack> onRemove()` - Java hook running after `ItemRegistry.unapply` removes the item, or null.

#### ItemDef.Builder (public inner class)

Universal builder: every spec field is settable programmatically. Appearance comes from a captured `SnItem` (rendered on each create) or a fixed `ItemStack` template (cloned on each create); setting one cancels the other.

- `public Builder item(SnItem item)` - Appearance from an SnItem builder, freshly rendered on each create.
- `public Builder item(ItemStack stack)` - Appearance from a fixed stack, cloned on each create.
- `public Builder droppable(boolean droppable)` - Default true.
- `public Builder moveable(boolean moveable)` - Default true.
- `public Builder placeable(boolean placeable)` - Default true.
- `public Builder tradeable(boolean tradeable)` - Default true.
- `public Builder despawnable(boolean despawnable)` - Default true.
- `public Builder keepOnDeath(boolean keepOnDeath)` - Default false.
- `public Builder keepOnDeath()` - Shortcut for `keepOnDeath(true)`.
- `public Builder locked()` - Pins the item to its slot: none of the 7 extraction vectors (click, drag, manual equip, hand swap, drop, death drops, hopper movement) can take it out. Created stacks carry the PDC flag `snlib_locked`.
- `public Builder locked(boolean locked)` - Parameterized version; default false.
- `public Builder noDrop()` - Blocks dropping the item (hard alias of `droppable: false`). Created stacks carry the PDC flag `snlib_no_drop`.
- `public Builder noDrop(boolean noDrop)` - Parameterized version; default false.
- `public Builder noManualEquip()` - Blocks manual equipping to armor slots. Created stacks carry the PDC flag `snlib_no_manual_equip`.
- `public Builder noManualEquip(boolean noManualEquip)` - Parameterized version; default false.
- `public Builder obtainVia(ObtainMode mode)` - How it can enter circulation; default `UNRESTRICTED` (null also falls there). Restricted stacks carry the PDC key `snlib_obtain_via`.
- `public Builder cooldownTicks(int cooldownTicks)` - Cooldown between interactions in ticks; default 0 (disabled).
- `public Builder customDurability(int max, int damagePerUse, String loreFormat, List<String> breakActions)` - Custom durability separate from vanilla's: `max` 0 disables it, `loreFormat` renders `%durability%`/`%max_durability%` and `breakActions` run when reaching 0.
- `public Builder rightClickActions(List<String> actions)` - Right-click actions.
- `public Builder leftClickActions(List<String> actions)` - Left-click actions.
- `public Builder shiftRightClickActions(List<String> actions)` - Shift + right-click actions.
- `public Builder shiftLeftClickActions(List<String> actions)` - Shift + left-click actions.
- `public Builder rightClickBlockActions(List<String> actions)` - Right-click-on-block actions.
- `public Builder rightClickAirActions(List<String> actions)` - Right-click-in-air actions.
- `public Builder leftClickBlockActions(List<String> actions)` - Left-click-on-block actions.
- `public Builder leftClickAirActions(List<String> actions)` - Left-click-in-air actions.
- `public Builder shiftRightClickBlockActions(List<String> actions)` - Shift + right-click-on-block actions.
- `public Builder shiftRightClickAirActions(List<String> actions)` - Shift + right-click-in-air actions.
- `public Builder shiftLeftClickBlockActions(List<String> actions)` - Shift + left-click-on-block actions.
- `public Builder shiftLeftClickAirActions(List<String> actions)` - Shift + left-click-in-air actions.
- `public Builder shiftOverridesGeneric(boolean shiftOverridesGeneric)` - Shift-over-base priority rule; default true (on shift-click the declared shift variant REPLACES its base), false runs both (shift first, base after). It also applies to the shift-positionals over the plain positionals.
- `public Builder interactRequirements(List<String> requirements)` - Requirement expressions checked before running any interaction action.
- `public Builder denyActions(List<String> actions)` - Actions when the requirements are not met.
- `public Builder pickupActions(List<String> actions)` - Actions on picking up the item.
- `public Builder dropActions(List<String> actions)` - Actions on dropping the item.
- `public Builder heldEffectsMainhand(List<String> effects)` - Effect lines (`"EFFECT amplifier"`) in the main hand.
- `public Builder heldEffectsOffhand(List<String> effects)` - Effect lines in the offhand.
- `public Builder heldEffectsArmor(List<String> effects)` - Effect lines as armor.
- `public Builder equipmentSlot(String slotName)` - Slot restriction (MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET).
- `public Builder recipe(Recipe recipe)` - The item's crafting recipe.
- `public Builder onRightClick(BiConsumer<Player, ItemStack> callback)` - Right-click Java callback, runs alongside the YML action list.
- `public Builder onLeftClick(BiConsumer<Player, ItemStack> callback)` - Left-click Java callback.
- `public Builder onShiftRightClick(BiConsumer<Player, ItemStack> callback)` - Shift + right-click Java callback.
- `public Builder onShiftLeftClick(BiConsumer<Player, ItemStack> callback)` - Shift + left-click Java callback.
- `public Builder onRightClickBlock(BiConsumer<Player, ItemStack> callback)` - Right-click-on-block Java callback.
- `public Builder onRightClickAir(BiConsumer<Player, ItemStack> callback)` - Right-click-in-air Java callback.
- `public Builder onLeftClickBlock(BiConsumer<Player, ItemStack> callback)` - Left-click-on-block Java callback.
- `public Builder onLeftClickAir(BiConsumer<Player, ItemStack> callback)` - Left-click-in-air Java callback.
- `public Builder onShiftRightClickBlock(BiConsumer<Player, ItemStack> callback)` - Shift + right-click-on-block Java callback.
- `public Builder onShiftRightClickAir(BiConsumer<Player, ItemStack> callback)` - Shift + right-click-in-air Java callback.
- `public Builder onShiftLeftClickBlock(BiConsumer<Player, ItemStack> callback)` - Shift + left-click-on-block Java callback.
- `public Builder onShiftLeftClickAir(BiConsumer<Player, ItemStack> callback)` - Shift + left-click-in-air Java callback.
- `public Builder onApply(BiConsumer<Player, ItemStack> callback)` - Java hook with the injected stack after `ItemRegistry.apply`.
- `public Builder onRemove(BiConsumer<Player, ItemStack> callback)` - Java hook with the removed stack after `ItemRegistry.unapply`.
- `public ItemDef build()` - Builds the immutable definition.

#### ItemDef.Recipe (public inner class)

Immutable recipe declaration of the golden spec. Material names are stored raw and resolved leniently by the recipe-loading layer (RecipeLoader).

- `public static Recipe shaped(List<String> shape, Map<Character, String> ingredients)` - SHAPED recipe of up to three rows and a symbol-to-material map.
- `public static Recipe shapeless(List<String> ingredients)` - SHAPELESS recipe from a flat material list.
- `public static Recipe cooking(String type, String input, double experience, int cookingTimeTicks)` - Cooking recipe: `type` is FURNACE, SMOKING, BLASTING or CAMPFIRE.
- `public static Recipe stonecutting(String input)` - STONECUTTING recipe from a single input material.
- `static @Nullable Recipe fromSection(ConfigurationSection sec, Consumer<String> warn)` - (package-private) Parses the `recipe:` section; an empty or unknown type returns null (with a WARN for the unknown). SHAPED demands shape and ingredients; SHAPELESS demands ingredients; the cooking types demand input (defaults: experience 0.0, cooking-time 200); STONECUTTING demands input.
- `public String type()` - Type: SHAPED, SHAPELESS, FURNACE, SMOKING, BLASTING, CAMPFIRE or STONECUTTING.
- `public List<String> shape()` - Shape rows of a SHAPED; empty otherwise.
- `public Map<Character, String> ingredients()` - Symbol-to-material map of a SHAPED; empty otherwise.
- `public List<String> shapelessIngredients()` - Flat material list of a SHAPELESS; empty otherwise.
- `public @Nullable String input()` - Input material of cooking and stonecutting recipes, or null.
- `public double experience()` - Experience granted by cooking recipes.
- `public int cookingTime()` - Cooking time in ticks.

#### Notes and gotchas
- ItemDef's private constructor clones the template (`b.template.clone()`) and defensively copies each list via `copy()` (which filters nulls and returns `List.copyOf`), guaranteeing real immutability.
- The class Javadoc includes the field-by-field checklist of the golden spec noting where each block parses and who executes it (SnItem/ItemPropertyListener/LockedItemListener/DurabilityTracker/ItemInteractListener/RequirementEngine/HeldEffectsTask/RecipeLoader).

### ItemRegistry
`src/main/java/com/sn/lib/item/ItemRegistry.java`

Per-context item definition registry, reached via `sn.items()`. It works with ZERO files: 100% programmatic definitions via `ItemDef.builder()`, from a YML section via `register(String, SnYml)`, or in bulk from the items file via `loadAll`. Created stacks are tagged with the owner-namespaced PDC key `snlib_item_id` (via `TagIo`), which is how the shared listener resolves any stack back to its owning context. The constructor creates the context's `EquipmentBackup`, `RecipeLoader` and `HeldEffectsTask` and tracks itself in `ItemPropertyListener.track`.

Public constants:
- `public static final String TAG_KEY = "snlib_item_id"` - PDC key with the item id; namespaced by owning plugin via TagIo.
- `public static final String TAG_LOCKED = "snlib_locked"` - PDC flag of stacks of a locked definition.
- `public static final String TAG_NO_DROP = "snlib_no_drop"` - PDC flag of no-drop stacks.
- `public static final String TAG_NO_MANUAL_EQUIP = "snlib_no_manual_equip"` - PDC flag of no-manual-equip stacks.
- `public static final String TAG_KEEP_ON_DEATH = "snlib_keep_on_death"` - PDC flag of keep-on-death stacks.
- `public static final String TAG_OBTAIN_VIA = "snlib_obtain_via"` - PDC key with the obtain mode of restricted definitions.

Methods:
- `public ItemRegistry(Sn ctx)` - Creates the registry of the given context and tracks it for owner resolution.
- `public void register(String id, SnYml yml)` - Parses and registers the definition from the yml's top-level section `id`; a missing section logs ONE WARN and registers nothing. Re-registering an id replaces the previous definition.
- `public void register(String id, ItemDef def)` - Registers a definition under `id`, replacing the previous one. Null id or def WARN and are ignored. A declared recipe adds to the server under `snlib_recipe_<id>` (with a prior lookup, so re-registrations never throw) and held-effects lines lazily start the per-context timer.
- `public void loadAll(SnYml itemsFile)` - Registers every top-level section of `itemsFile` as a definition; stores the source for `reload()`.
- `public void cancelTasks()` - Cancels the module's per-context tasks (the held-effects timer); the reload flow restarts them after re-reading the definitions.
- `public void reload()` - Re-registers every definition of the file loaded by `loadAll` (fresh parse of the reloaded yml) and restarts the held-effects timer if any tracked definition remains. Programmatic definitions stay registered untouched.
- `public void reloadRecipes()` - Recipe cycle of the reload flow, main thread ONLY: removes every recipe key of this owner from the server and re-adds the recipes of every registered definition.
- `public @Nullable ItemDef def(String id)` - Definition registered under `id`, or null.
- `public @Nullable ItemStack create(String id, @Nullable Player viewer, Ph... phs)` - Builds the physical stack of `id`, tagged with the owner-namespaced `snlib_item_id`, plus whatever locked-mode flags the definition declares. Appearance placeholders resolve against `viewer` plus the locals `phs`. Definitions with custom durability come out seeded at full durability with their lore line rendered. An unknown id logs ONE WARN and returns null.
- `public boolean apply(Player player, String id, EquipmentSlot slot)` - Injects the item registered under `id` into the player's equipment slot (the command/API path of `obtain-via: COMMAND_ONLY`). The displaced real item is backed up write-through in the equipment backup, whose restoration runs on quit and on shutdown. It fires a cancelable `SnArmourEquipEvent` (`EquipMethod.PICK_DROP`, marked programmatic via `LockedItemListener.markProgrammatic`) BEFORE touching the slot and the definition's `onApply` hook after. Returns true when the item ended up equipped.
- `public boolean unapply(Player player, String id)` - Removes every applied instance of `id` from the player's equipment slots (the 6 of `PLAYER_SLOTS`), restoring each slot's backed-up real item (null empties it). It fires a cancelable `SnArmourEquipEvent` per slot and the `onRemove` hook after each removal. Returns true when at least one slot was restored.
- `public int durability(ItemStack item)` - Remaining custom durability of the stack; a tagless stack of a durability item counts as full. Returns -1 when the stack was not created by this context or its definition has no custom durability.
- `public int damage(ItemStack item, int amount)` - Subtracts `amount` of custom durability (floored at 0), updating the tag and re-rendering the lore line. Returns the remaining durability (0 = broken), or -1 if the stack is not from this context or has no custom durability. This playerless overload does NOT run break-actions or remove the stack; for a programmatic break with a player there is `damage(Player, ItemStack, int)`.
- `public int damage(Player user, ItemStack item, int amount)` - Like `damage(ItemStack, int)` but when THIS call breaks the stack (it was alive, `before > 0`, and reached 0) it additionally runs the definition's break-actions and removes the stack from the player's inventory by IDENTITY via `DurabilityTracker.breakFor` (hands first, then the 36 storage slots); if the caller passed a copy it logs to debug and removes nothing. An already-broken stack does not re-fire the break flow. A null `user` delegates to the 2-arg overload.
- `public @Nullable String idOf(ItemStack item)` - The stack's registered id when this context created it, or null.
- `public boolean is(ItemStack item, String id)` - Whether the stack is an instance of the item registered under `id`.
- `public void give(Player player, String id, int amount)` - Gives `amount` units of the item, splitting into max-stack chunks; whatever does not fit drops at the player's feet (via `InvUtil.giveItems`).
- `public int take(Player player, String id, int amount)` (1.8.0) - Removes up to `amount` units of the item from the player's inventory - every slot (storage, armor, off hand) plus the stack on the open cursor - matched by the owner-namespaced `snlib_item_id` tag. The symmetric counterpart of `give`: removal is programmatic, so locked/no-drop flags never block it and no cancellable event fires; the equipment backup is untouched (applied equipment is removed with `unapply`, never with this). Returns how many units were actually removed.
- `public int removeAll(Player player, String id)` (1.8.0) - Removes every unit of the item from the player's inventory and open cursor (`take` with `Integer.MAX_VALUE`); returns how many units were removed. The removal path every command-given locked item MUST have (a give-only locked item is unremovable by design of the lock it opted into).

#### Notes and gotchas
- `PLAYER_SLOTS` is a fixed list of the 6 player slots (HAND, OFF_HAND, HEAD, CHEST, LEGS, FEET): it keeps the source enum open (it does not iterate `EquipmentSlot.values()`, which in newer versions includes BODY/SADDLE).
- `defs` is a `ConcurrentHashMap` and `itemsSource` is volatile; the registry tolerates concurrent reads.
- `apply` normalizes the displaced item (null if air) before passing it to the event; `unapply` uses `backup.peek` for the event and only `backup.take` if the event was not cancelled.

### ItemSerializer
`src/main/java/com/sn/lib/item/ItemSerializer.java`

Binary stack serialization that survives over-stacked amounts. `ItemStack.serializeAsBytes()` clamps the amount to the material's max stack size, silently losing over-stacked amounts (the SnLootBoxes gotcha). Therefore the real amount is written as a 4-byte big-endian prefix and the body serializes with amount 1, so `deserialize` restores the exact original amount. Final class with a private constructor (statics only).

- `public static byte[] serialize(ItemStack stack)` - Serializes to bytes: a 4-byte amount prefix plus Paper's byte form of the amount-1 copy. Throws `IllegalArgumentException` on null or AIR stacks, which have no byte form.
- `public static ItemStack deserialize(byte[] data)` - Restores a stack from `serialize`'s output, reapplying the real amount even when it exceeds the material's max stack size (floored at 1). Throws `IllegalArgumentException` if data is null or 4 bytes or fewer.
- `public static String serializeBase64(ItemStack stack)` - Base64 form of `serialize`, for text storage (yml, database columns).
- `public static ItemStack deserializeBase64(String data)` - Inverse of `serializeBase64`; throws `IllegalArgumentException` on null or blank data.

### ObtainMode
`src/main/java/com/sn/lib/item/ObtainMode.java`

Enum of how a registered item may legitimately enter circulation (spec field `obtain-via`).

Values:
- `UNRESTRICTED` - No restriction; every acquisition path allowed. Spec default (`""`).
- `COMMAND_ONLY` - Only via the plugin's command or API; the other paths (crafting, mob pickup and similar) are cancelled by the locked-items enforcement layer.

Methods:
- `static ObtainMode parse(@Nullable String raw, @Nullable Consumer<String> warn)` - (package-private) Lenient parse: null or blank yields `UNRESTRICTED`; normalizes uppercase and `-` to `_`; an unknown value sends ONE warning to `warn` and also yields `UNRESTRICTED`.

### ItemPropertyListener (internal)
`src/main/java/com/sn/lib/item/internal/ItemPropertyListener.java`

Single shared listener, owned by SnLib, that enforces the behavior properties of registered items (droppable/no-drop, moveable, placeable, tradeable, despawnable, keep-on-death), the best-effort `equipment-slot` restriction (direct click to an incompatible slot, shift-click auto-equip and dispenser equips; the right-click auto-equip vector lives in the interact listener) and runs the pickup/drop action lists. Enrolled in the ListenerHub; the `registerEvents` happens SOLELY in the SnLibPlugin bootstrap. Owner resolution is via PDC: the namespace of the `snlib_item_id` key maps back to the consumer plugin and its `ItemRegistry` tracked in a static multi-tenant `TenantRegistry` (the tenant sweeper deletes the whole key when the owner disables). Hot-path contract: this listener sees EVERY inventory event of the server across all consumers, so each handler quick-exits in layers: null/air first, then `hasItemMeta()`, then the PDC tag, then the logic. `ItemSpawnEvent` filters by `hasItemMeta()` before anything else.

- `public static void track(JavaPlugin owner, ItemRegistry registry)` - Tracks a context's registry so PDC tags resolve to their owner.
- `public void onDrop(PlayerDropItemEvent event)` - Cancels the drop if `!droppable()` or `noDrop()`; if droppable it runs the drop-actions.
- `public void onInventoryClick(InventoryClickEvent event)` - Cancels the click if the current item, the cursor, the hotbar (NUMBER_KEY via `getHotbarButton`, SWAP_OFFHAND via the offhand item) or the equipment-slot enforcement deny it (non-moveable, or non-tradeable inside the MERCHANT inventory including `MOVE_TO_OTHER_INVENTORY`).
- `public void onDispenseArmor(BlockDispenseArmorEvent event)` - Dispenser vector of the equipment-slot enforcement: cancels equips whose vanilla destination does not match the declared slot.
- `public void onInventoryDrag(InventoryDragEvent event)` - Cancels the drag if `!moveable()`, or if `!tradeable()` and some raw slot falls in the top of a MERCHANT inventory.
- `public void onBlockPlace(BlockPlaceEvent event)` - Cancels placement if `!placeable()`.
- `public void onDeath(PlayerDeathEvent event)` - (priority HIGH) Takes keep-on-death stacks out of the drops and stores them in the `keptOnDeath` stash by UUID; respects `getKeepInventory()`.
- `public void onRespawn(PlayerRespawnEvent event)` - (priority MONITOR) Returns the keep-on-death stash to the player via `InvUtil.giveItems`.
- `public void onItemSpawn(ItemSpawnEvent event)` - If the spawned item is not despawnable, sets `setUnlimitedLifetime(true)` on the entity.
- `public void onPickup(EntityPickupItemEvent event)` - Cancels pickups of registered items by non-player entities; for players it runs the pickup-actions.
- `record Match(JavaPlugin owner, ItemRegistry registry, ItemDef def, String id)` - (package-private) The registered item behind a stack: owning plugin, its registry, definition and id.
- `static @Nullable Match match(@Nullable ItemStack item)` - (package-private) Layered quick-exit resolution: null/air, `hasItemMeta`, PDC tag (walks the keys looking for `snlib_item_id`), lookup in the registry of the owner whose lowercased name matches the namespace.
- `static @Nullable EquipmentSlot vanillaEquipSlot(@Nullable ItemStack stack)` - (package-private) The material's vanilla auto-equip destination, matched by name suffix (`_HELMET`/`_HEAD`/`_SKULL`/`CARVED_PUMPKIN` -> HEAD, `_CHESTPLATE`/`ELYTRA` -> CHEST, `_LEGGINGS` -> LEGS, `_BOOTS` -> FEET); Material treated as an open enum, never a switch over its constants.

#### Notes and gotchas
- The equipment-slot click enforcement only applies in the own-inventory view (`InventoryType.CRAFTING`); raw slots 5-8 are armor and 45 is the offhand.
- Actions run via `SnLib.context(match.owner()).actions().run(...)`: the item's OWNER context, not the server's.
- The `keptOnDeath` stash is bounded by dead players awaiting respawn; it is not per-consumer data (which is why the instance state is allowed).

### ItemInteractListener (internal)
`src/main/java/com/sn/lib/item/internal/ItemInteractListener.java`

Single shared listener, owned by SnLib, that dispatches item interactions. Enrolled in the ListenerHub; `registerEvents` SOLELY in the SnLibPlugin bootstrap. It only consults `PlayerInteractEvent.getItem()` and `getHand()`, so each dispatch belongs to the event whose hand carries the item and a dual-fire (main hand + offhand) never runs an item twice. Same hot-path contract with layered quick exits.

- `public void onInteract(PlayerInteractEvent event)` - (priority HIGH) Per-interaction flow: (0) ignores `Action.PHYSICAL`, null/air/meta-less items, null hand, unmatched stack or a downed context; a denied incompatible auto-equip (the equipment-slot right-click vector) CUTS the whole flow with a return (no cooldown, no requirement, no dispatch, no durability); (1) the item's cooldown (category `"item:" + id` via `ctx.cooldowns().tryUseTicks`) returns silently while cooling; (2) the interact-requirements evaluate with a locals-plus-PAPI resolver; if unmet, the deny-actions run with the REAL `ActionContext` of the interaction (ClickType + BLOCK/AIR surface), so click and surface guards inside that list evaluate just like in a GUI click (behavior fix of the 1.1.0 changelog: they used to run without click context and those guards silently skipped their line; previously dead guarded lines now run when they match); (3) the applicable variants dispatch, each running its YML action list through the ActionEngine AND its Java callback. A successful use subtracts custom durability afterwards; at 0 the break flow ALWAYS goes through `DurabilityTracker.breakFor(..., context)` with the real context (same fix), runs the break-actions and empties the hand that used the item.

#### Internal logic
- `dispatch`: runs the generic pair and then the positional block/air pair of the clicked surface, which runs IN ADDITION to the generic. The 12 variants pair up under ONE uniform shift priority rule (`runPair`): on shift-click, a shift variant WITH behavior (non-empty list OR callback) runs INSTEAD of its base variant; without behavior it falls to the base; with `shift-overrides-generic: false` BOTH run (shift first, base after) in both phases; without shift only each pair's base runs. The `ActionContext` carries the computed `ClickType` (RIGHT/LEFT/SHIFT_RIGHT/SHIFT_LEFT per `player.isSneaking()`) and the `ClickSurface` (BLOCK/AIR per the event's `Action`).
- `denyIncompatibleAutoEquip`: on right-click, if the material's vanilla auto-equip destination is not the declared slot, it sets `setUseItemInHand(Event.Result.DENY)` and returns true; `onInteract` returns right there, so the denied equip consumes no cooldown, spends no durability and runs no action or callback. Returns false on all other paths (not right-click, no declared slot, compatible or null vanilla slot).
- `applyDurability`: subtracts `damage-per-use` via `DurabilityTracker.damage`; if > 0 remains it re-sets the item in the used hand, if it reaches 0 it delegates to `DurabilityTracker.breakFor(ctx, def, player, item, hand, context)` with the interaction's real context, so click/surface guards inside the break-actions evaluate (runs the break-actions and empties that hand).

### DurabilityTracker (internal)
`src/main/java/com/sn/lib/item/internal/DurabilityTracker.java`

Custom durability state of registered items, separate from vanilla damage. The remaining durability lives in the owner-namespaced PDC key `snlib_durability` (int), seeded to `custom-durability.max` when the stack is created. Each damage application re-renders the `lore-format` line with `%durability%`/`%max_durability%` resolved; the line's position is remembered in a second int PDC key (`snlib_durability_line`, private) so re-renders replace in place. Reaching 0 is reported to the caller: the break flow (break-actions + stack removal) is centralized in `breakFor`, shared by the interact listener and the programmatic overload `ItemRegistry.damage(Player, ItemStack, int)`. Final statics-only class.

Public constants:
- `public static final String DURABILITY_KEY = "snlib_durability"` - PDC key with the remaining durability; namespaced by owning plugin.

Methods:
- `public static void initialize(JavaPlugin owner, ItemDef def, ItemStack stack)` - Seeds the tag to full max and renders the initial lore line. No-op if the definition has no custom durability or the stack already carries the tag.
- `public static int durability(JavaPlugin owner, ItemDef def, ItemStack stack)` - Remaining durability; a tagless stack counts as full. Returns -1 if the definition has no custom durability or the stack has no meta.
- `public static int damage(JavaPlugin owner, ItemDef def, ItemStack stack, int amount)` - Subtracts `amount` (floored at 0), updates the tag and re-renders the lore line. Returns the remainder (0 = broken), the current value untouched if `amount` is not positive, or -1 without custom durability.
- `public static boolean breakFor(Sn ctx, ItemDef def, Player player, ItemStack stack, @Nullable EquipmentSlot hand, @Nullable ActionContext context)` - Shared break flow of a stack that reached 0: (1) runs the definition's break-actions with `context` when present, so click/surface guards see the real click (the interact listener passes the interaction's real context, 1.1.0 fix; the programmatic overload `ItemRegistry.damage(Player, ItemStack, int)` passes null because there is no click); (2) removes the stack: with a non-null `hand` (interact flow) it empties that hand and returns true; with a null `hand` (programmatic flow) it looks for the stack by IDENTITY with an `equals` fallback (on Paper the inventory getters return a fresh mirror wrapper per call, so identity alone practically never matches; an equals match is the same broken-item state) in the main hand, then offhand and then the 36 storage slots (`getStorageContents` indices match `setItem`'s). If not found it logs a debug note and returns false without removing anything.

#### Notes and gotchas
- `renderLore` replaces the line at the remembered index only if still valid (`0 <= index < lore.size()`); otherwise it appends it at the end and remembers the new position.
- The lore line renders via `SnText.color(SnText.applyLocals(...))` with `Ph.of("durability", ...)` and `Ph.of("max_durability", ...)`, non-italic unless requested.

### HeldEffectsTask (internal)
`src/main/java/com/sn/lib/item/internal/HeldEffectsTask.java`

Per-context sync timer that applies registered items' held effects. It is a TIMER, not a listener: it never goes through the ListenerHub. Lazy by design: it only starts when a tracked definition declares at least one held-effect line. Every 40 ticks (`PERIOD_TICKS`, private constant) it checks each online player's main hand, offhand and worn armor against its own context's definitions (owner-namespaced PDC id, so contexts never interfere) and applies the matching `PotionEffect`s, ambient and particle-free, with a duration of 80 ticks (`DURATION_TICKS`, private: 60 plus margin) that outlives the sweep period, so the effect is continuous while held and expires only on release.

- `public HeldEffectsTask(Sn ctx, ItemRegistry registry)` - Constructor; starts nothing.
- `public void track(String id, ItemDef def)` - Tracks (or re-tracks) a definition: parses its held-effect lines ONCE (never per tick) and lazily starts the timer with the first definition that has any. A definition without held effects removes any previous tracking of the same id.
- `public synchronized void stop()` - Cancels the timer; the next tracked definition with held effects restarts it.
- `public synchronized void restart()` - Restarts the timer when some tracked definition remains; the reload's re-track path.

#### Internal logic
- Lines have the form `"EFFECT amplifier"`; invalid effect ids or amplifiers WARN ONCE (the task's own `warned` set, the context plugin's logger) and are skipped; an invalid amplifier uses 0.
- `apply` does the layered quick-exit: null/air, `hasItemMeta`, this context's PDC id (`registry.idOf`), tracked effects.
- `resolveEffect`: Registry by NamespacedKey with the legacy `getByName` fallback.
- The timer is created via `ctx.scheduler().timer(PERIOD_TICKS, PERIOD_TICKS, this::tick)` (main thread).

### RecipeLoader (internal)
`src/main/java/com/sn/lib/item/internal/RecipeLoader.java`

Per-context loader of the recipes declared by item definitions: SHAPED, SHAPELESS, FURNACE, SMOKING, BLASTING, CAMPFIRE and STONECUTTING (7 types). Each recipe registers under `NamespacedKey(plugin, "snlib_recipe_" + itemId)` with the consumer plugin as owner, and it ALWAYS looks up the key before registering (gate `Bukkit.getRecipe(key) == null`) so a second enable never throws. Registered keys are tracked in a static `TenantRegistry` whose sweep callback removes the recipe from the server (`Bukkit::removeRecipe`): the tenant sweeper cleans an owner's recipes on disable, and `unregisterAll()`/`registerAll` give the reload manager its unregister/re-register cycle. Ingredient materials resolve leniently with a WARN.

- `public RecipeLoader(JavaPlugin plugin, ItemRegistry registry)` - Constructor with the owner and its registry.
- `public void register(String itemId, ItemDef def)` - Registers the recipe declared by `def` under `snlib_recipe_<itemId>`; a recipeless definition is a no-op. If the key already exists on the server it only tracks it (never re-adds). The result stack is the registry-created item (PDC id included, `registry.create(itemId, null)`). Invalid declarations WARN and register nothing; a rejected `addRecipe` or `IllegalStateException`/`IllegalArgumentException` also WARN.
- `public void registerAll(Map<String, ItemDef> defs)` - Registers each definition's recipe; the re-register half of the reload manager.
- `public void unregisterAll()` - Removes every recipe key of this owner from the server.
- `public static void unregisterAll(Plugin owner)` - Removes every recipe key of `owner` from the server (teardown path).

#### Internal logic
- `build` dispatches by type: SHAPED applies shape and ingredients (an invalid ingredient discards the WHOLE recipe); SHAPELESS skips invalid ingredients individually but discards the recipe if none valid remain; cooking maps to `FurnaceRecipe`/`SmokingRecipe`/`BlastingRecipe`/`CampfireRecipe` with `cookingTime` floored at 1; STONECUTTING creates a `StonecuttingRecipe`.
- `keyFor` normalizes the id to the key charset (`[^a-z0-9/._-]` -> `_`); an id that still yields no valid key WARNs and is ignored.
- `resolveMaterial`: direct `matchMaterial` and then with uppercase and spaces/hyphens normalized to `_`.

### LockedItemListener (internal)
`src/main/java/com/sn/lib/item/internal/LockedItemListener.java`

Single shared listener, owned by SnLib, that enforces the locked mode of registered items (EdToolsArmors 2.0 pattern): a locked piece stays pinned to its slot and cannot leave through any of the seven extraction vectors. It also listens to `SnArmourEquipEvent` to block foreign equips of `COMMAND_ONLY` items: only `ItemRegistry.apply` (which marks the change as programmatic for a one-tick window) can equip them. Enrolled in the ListenerHub; `registerEvents` SOLELY in the SnLibPlugin bootstrap. Same hot-path contract via `ItemPropertyListener.match`. Keep-on-death of NON-locked items is enforced by the property listener; the death vector here covers locked pieces, which never enter circulation via drops.

- `public static void markProgrammatic(UUID uuid, EquipmentSlot slot)` - Marks the player's slot as programmatically changed for the current tick's window (static `PROGRAMMATIC` map of `ApplyMark(slot, tick)`).
- `static boolean isProgrammatic(UUID uuid, EquipmentSlot slot)` - (package-private) Whether the slot change matches a live programmatic mark. Marks expire after ONE tick: the primary armor source echoes a programmatic setItem on the next tick, so the window covers the synthetic event and its echo.
- `public void onInventoryClick(InventoryClickEvent event)` - Vector 1: a locked item on the cursor or under the click (armor slots included) never moves; it also denies hotbar (NUMBER_KEY/SWAP_OFFHAND) and manual equip (cursor drop onto an ARMOR slot or shift-click of an armor piece in the own view) of no-manual-equip pieces.
- `public void onInventoryDrag(InventoryDragEvent event)` - Vector 2: drags of a locked piece, or of a no-manual-equip one toward raw slots 5-8 (the own view's armor, private constants `ARMOR_RAW_FIRST`/`ARMOR_RAW_LAST`).
- `public void onInteract(PlayerInteractEvent event)` - Vector 3: manual right-click equip of a no-manual-equip or locked armor piece; denies with `setUseItemInHand(Event.Result.DENY)`.
- `public void onSwapHands(PlayerSwapHandItemsEvent event)` - Vector 4: cancels hand swaps involving a locked piece in either hand.
- `public void onDrop(PlayerDropItemEvent event)` - Vector 5: no-drop and locked pieces stay in the inventory.
- `public void onDeath(PlayerDeathEvent event)` - Vector 6 (priority HIGH): locked pieces are taken out of the death drops and stashed by UUID; respects `getKeepInventory()`.
- `public void onRespawn(PlayerRespawnEvent event)` - Vector 6, second half (priority MONITOR): the extracted locked pieces return on respawn via `InvUtil.giveItems`.
- `public void onHopperMove(InventoryMoveItemEvent event)` - Vector 7: hoppers and any container-to-container movement of a locked piece is cancelled.
- `public void onArmourEquip(SnArmourEquipEvent event)` - Blocks foreign equips of COMMAND_ONLY pieces (only `ItemRegistry.apply` can equip them, detected via the programmatic mark) and dispenser equips of locked or no-manual-equip pieces; it is the synthesized event's only binding cancelable source.

#### Notes and gotchas
- `manualEquipDenied(def)` also returns true for `COMMAND_ONLY`: COMMAND_ONLY implies no manual equip. `PlayerArmorChangeEvent` arrives post-hoc (non-binding), so the denial has to happen in the click/drag/interact vectors.
- `isArmourPiece` matches equipable pieces by Material name suffix (enum treated as open: name checks, never switch/EnumSet over its constants).
- The static `PROGRAMMATIC` map is justified as a server-wide static: one-tick transient marks, not per-consumer data.

### EquipmentBackup (internal)
`src/main/java/com/sn/lib/item/internal/EquipmentBackup.java`

Per-context backup of the real items displaced by `ItemRegistry.apply`, with GUARANTEED restoration on quit (registered in the QuitCleanupListener) and on shutdown (`restoreAll`, invoked by the context teardown). Persistence is write-through and default-on: every store writes the displaced item into the context's `data/equipment-backup.yml` via `SnYml.save` (which turns synchronous during teardown due to the context's shutting-down flag) and every take/restore deletes it, so a crash without onDisable never loses the real item. Persisted entries reload at construction; the only opt-out is not declaring the yml module, which degrades to in-memory backups with ONE WARN on first use.

- `public EquipmentBackup(Sn ctx)` - Creates a context's backup service, reloads its persisted entries and registers the quit restore callback. Mounts the store via `ctx.yml().data("data/equipment-backup.yml")`; without the yml module (`UnsupportedOperationException`) it stays null.
- `public void store(Player player, EquipmentSlot slot, @Nullable ItemStack displaced)` - Backs up the slot's displaced item, write-through (memory + Base64 from `ItemSerializer.serializeBase64` in the yml under `backups.<uuid>.<SLOT>`). An empty slot stores nothing, so a persisted entry from a previous crash stays authoritative; a locked piece applied by the lib is never backed up (it is not the player's real item).
- `public @Nullable ItemStack peek(UUID uuid, EquipmentSlot slot)` - A copy of the slot's backed-up item without consuming it, or null.
- `public @Nullable ItemStack take(UUID uuid, EquipmentSlot slot)` - Consumes the slot's backed-up item, deleting its persisted entry (the whole player branch if it was the last), or null.
- `public void restore(UUID uuid)` - Restores each backed-up slot of the player: the locked piece applied by THIS owner (or an empty slot) is replaced by the real item; any other occupant is respected and the real item goes to the inventory instead (`InvUtil.giveItems`). Offline players are skipped so their persisted entries survive to the next session. Idempotent: a kick fires kick and quit and the second pass finds nothing. It marks each slot programmatic before touching it.
- `public static void restoreAll(Plugin owner)` - Restores the backups of every online player of the owner; the teardown entry point. During teardown the write-through save runs synchronously.

#### Notes and gotchas
- The static `BACKUPS` (`TenantRegistry<EquipmentBackup>`) is justified: backup instances keyed by owner for the teardown.
- `loadPersisted` tolerates broken entries (invalid UUID, invalid slot, corrupt Base64) with one WARN per entry ("Unreadable equipment backup...") and ignores them.
- The memory-degradation WARN ("EquipmentBackup without the yml module declared...") is emitted exactly once (`AtomicBoolean.compareAndSet`).

### SkinResolver (internal, v1.6)
`src/main/java/com/sn/lib/item/internal/SkinResolver.java`

Off-thread skin resolution for player heads: fills the texture of a `skull-owner` whose profile the server has not cached, then re-applies the textured head. The synchronous `SnItem` path stays non-blocking; this is the async completion it was missing. Server-wide statics (a shared `SkinCache<PlayerProfile>` and a `WARNED` dedupe set) justified by contract note (a): skin data is content-addressed and identical for every consumer. `PlayerProfile.update()` runs the network fetch on Paper's own executor, never HTTP on the calling thread. `clearCache()` runs on the SnLib plugin teardown.

- `public static @Nullable String normalizeKey(String owner)` - Canonical cache key: a UUID normalizes to its lowercase string form, any other value to its lowercase trimmed form, so the same player keyed by UUID or by name (any case) shares one slot. Null/blank -> null.
- `public static @Nullable PlayerProfile cachedProfile(String owner)` - Cheap cache read (any thread) of a textured profile, or null on a miss. Consumed by `SnItem.build()`.
- `public static void request(Sn ctx, String owner, Runnable onLanded)` - Requests an off-thread fetch owned by `ctx`'s scheduler and runs `onLanded` on the main thread when a textured profile lands. Gated by the shared cache (`beginFetch`): a no-op when the key is already cached, in flight, or inside its negative window. The GUI path passes an `onLanded` that re-renders the affected slot.
- `public static void requestSelf(String owner)` - Fire-and-forget warm-up for a direct (non-GUI) build: schedules the fetch through SnLib's own context so the NEXT build of the same owner shows the texture, no re-render. No-op when SnLib is disabled or the call is off the main thread.
- `public static void clearCache()` - Empties the shared cache and the WARN dedupe; called by the SnLib plugin on disable.
- Internal: `baseProfile` builds a `Bukkit.createProfile(UUID)` or name-only profile to `update()`; `hop` bounces the completion (on Paper's executor) onto the owner's main thread; `land` releases the in-flight mark, then caches a textured result and runs `onLanded`, or negative-caches the failure and WARNs once per owner ("skull-owner '...' could not be resolved to a textured profile").

### SkinCache&lt;V&gt; (internal, v1.6)
`src/main/java/com/sn/lib/item/internal/SkinCache.java`

Bounded, TTL-aware resolution cache backing `SkinResolver`: a positive cache (access-order LRU, size-bounded, per-entry expiry), a short negative cache of failed keys, and an in-flight guard collapsing concurrent fetches of the same key. Generic and Bukkit-free ON PURPOSE so every decision is a pure unit (`SkinCacheTest`, with an injected clock); the resolver instantiates it with `PlayerProfile` values, a 30-minute positive TTL, a 2-minute negative TTL and a cap of 512. Every method synchronizes on the instance.

- `get` / `put` (clears any prior failure) / `negativeHit` / `putNegative` - the two caches, with lazy expiry drop on read.
- `shouldFetch(key)` - pure predicate: no fresh positive hit, no live negative suppression, no fetch in flight.
- `beginFetch(key)` - atomic gate: if `shouldFetch`, marks the key in-flight and returns true (the caller owns the fetch); pair every true with a later `endFetch`. `clear()` empties both caches and the in-flight set.

### TODOs and limitations

There are no TODO/FIXME markers in the module's code. Limitations documented in the code:

- The `equipment-slot` enforcement covers direct click, shift-click auto-equip, dispenser and right-click auto-equip; the right-click vector is closed: a denied equip cuts the whole interaction flow (no dispatch, no cooldown, no durability) via ItemInteractListener.denyIncompatibleAutoEquip.
- `ItemRegistry.damage(ItemStack, int)` (playerless) does NOT run break-actions or remove the stack at 0; the `damage(Player, ItemStack, int)` overload covers programmatic breaking (break-actions + identity removal via `DurabilityTracker.breakFor`), provided the caller passes the ORIGINAL inventory stack (a copy only logs to debug).
- `PlayerArmorChangeEvent` arrives post-hoc (non-binding), which is why the manual-equip denial of COMMAND_ONLY/no-manual-equip pieces has to happen in the click/drag/interact vectors (LockedItemListener.manualEquipDenied).
- Without the yml module declared, EquipmentBackup degrades to memory-only backups with ONE WARN: a crash without onDisable can lose the displaced real item.
- 1.20.4 compat: `glow()` degrades to a real enchantment (LURE) plus HIDE_ENCHANTS, and `max-stack-size` is omitted; both with ONE WARN via the SnCompat probe.
- `ItemStack.serializeAsBytes()` clamps over-stacked amounts (SnLootBoxes gotcha); ItemSerializer exists exactly for that, with its 4-byte amount prefix.
- LockedItemListener's programmatic window lasts ONE tick (covers the synthetic event and its echo); an external programmatic setItem outside `ItemRegistry.apply`/`EquipmentBackup.restore` that does not call `markProgrammatic` would be cancelled for COMMAND_ONLY pieces.
---

# (Section 12 of the SnLib v1.1.0 documentation)

## 12. GUI

SnLib's declarative menu module (`com.sn.lib.gui`), accessible per context via `sn.guis()`. Each `.yml` file in the consumer plugin's `guis/` folder parses into an immutable `GuiDef` (golden spec `docs/menu-example.yml`), and each viewer opening a menu gets THEIR OWN `GuiSession` with its own `Inventory`, its own `SnGuiHolder` and its own page state: N players on the same GUI are N independent sessions, there is no per-GUI shared inventory. Library inventory identification is ALWAYS `holder instanceof SnGuiHolder` (never by title, which resolves per viewer and can collide across plugins). Two shared internal listeners (registered ONCE in the SnLibPlugin bootstrap via ListenerHub) dispatch clicks/closes and apply the anti-theft protection over stacks marked with the PDC key `snlib_gui_item`. The whole module is main-thread only; open sessions register per owner in a `TenantRegistry`, guaranteeing non-interference between consumer plugins.

### SnGuiHolder
`src/main/java/com/sn/lib/gui/SnGuiHolder.java`

`InventoryHolder` of every GUI inventory the library creates: one per `GuiSession`, shared by every inventory that session recreates. It implements `OwnedHolder` (tenant module), so it carries the owning plugin: that is how the tenant sweeper and the quit cleanup listener close inventories of exactly ONE owner. Package-private constructor (`SnGuiHolder(Plugin owner, String guiId, GuiSession session)`); the `inventory` field is `volatile`.

- `public Plugin owner()` - Consumer plugin whose context opened this GUI (override of `OwnedHolder`).
- `public String guiId()` - Id of the GUI definition (file name without extension).
- `public GuiSession session()` - The per-viewer session behind this holder.
- `public Inventory getInventory()` - The session's current inventory; throws `IllegalStateException` ("The session of gui '<id>' has not created its inventory yet") if the session has not created it yet.
- `void inventory(Inventory inventory)` (package-private) - Swaps the backing inventory; only the owning session calls it.

#### Notes and gotchas
- The session recreates the inventory on title or size changes keeping THIS same holder, so the `instanceof` identification survives every recreation.

### GuiItemDef
`src/main/java/com/sn/lib/gui/GuiItemDef.java`

One item of a GUI definition: the entire appearance section of the golden spec plus `slots`, per-item `update-interval`, view/click requirements and click/deny action lists. Appearance is NOT pre-built: the definition keeps its yml section and re-reads it on every `render`, so name, lore and every string resolve per viewer through the SnYml pipeline (locals, PAPI, `[rgb]`, `[center]`, MiniMessage). Requirements parse ONCE at load from the raw section (they bypass placeholder resolution, so tokens reach evaluation intact); action lines stay raw for the action engine, which resolves them at runtime.

Per-click matrix (1.1.0): besides the generic `click-actions`/`click-requirements`/`deny-actions`, five click keys (`right`, `left`, `shift-right`, `shift-left`, `middle`) each read three optional lists (`*-click-actions`, `*-click-requirements`, `*-click-deny-actions`; 15 keys total, a 5x3 matrix stored in an immutable `EnumMap` of the package-private `ClickKey` enum with the private record `PerClick(actions, requirement, denyActions)`). A list counts as declared only if non-empty; a specific requirement parses ONCE and only if its list is non-empty (an absent one stays null so it can fall to the generic). Resolution is field-by-field and specific-over-generic: the click's exact shift entry wins, then the side entry (RIGHT groups RIGHT/SHIFT_RIGHT; LEFT groups LEFT/SHIFT_LEFT/DOUBLE_CLICK/CREATIVE, consistent with `ClickType.isLeftClick()`; MIDDLE goes alone), and finally the generic field. Each field falls to the generic independently: an item can declare `right-click-actions` without `right-click-requirements` and its requirement still resolves from the generic `click-requirements`.

Package-private enum `NavKind` (navigation role, detected from the action lists when parsing): `NONE`, `PREVIOUS`, `NEXT`.

- `static @Nullable GuiItemDef parse(SnYml yml, String path, String id, @Nullable Map<Character, int[]> layout, Consumer<String> warn)` (package-private) - Parses the item at `path`; warnings go to `warn`. Returns null when the section does not exist ("Section '<path>' does not exist in <file>; item ignored"). Parses slots via `SlotParser`, `update-interval` (clamped to >= 0), `view-requirements`/`click-requirements` via `RequirementEngine`, `click-actions`/`deny-actions` and the per-click matrix (one entry per click key that declared at least one of its three lists); it detects `NavKind` by looking for `[previous-page]`/`[next-page]` in ALL the action lists (the generic one and the five per-click entries) and, if it is a navigation item and the `nav-disabled` subsection exists, parses it recursively as an override (with a null `layout`: key does not apply there). The `layout` parameter (1.1.0) is the character -> slots map of the menu's `layout:` (an empty map for a layoutless menu, null for templates and nav-disabled) and resolves the `key:` field as an alternative to `slots:`: an empty key changes nothing; a null `layout` WARNs "Item '<id>': 'key' does not apply in this section; ignored" and the item parses normally; with slots already declared it WARNs "Item '<id>': declares 'slots' and 'key'; slots wins and key is ignored"; a key whose trim is not exactly 1 character WARNs "Item '<id>': key 'RAW' invalid (must be 1 character); item ignored" and returns null; a character absent from the map (which also covers the layoutless menu) WARNs "Item '<id>': key 'C' does not appear in layout; item ignored" and returns null; a present character assigns `slots = layout.get(c).clone()`, so a key appearing in N cells renders in all N (replacing other libs' fillKey/fillEmptyKey without extra API). Returning null in the ignored cases avoids the double WARN with `GuiDef.parse`'s `hasSlots()` check.
- `public List<String> clickActionsFor(@Nullable ClickType click)` - Action lines for the given click: the declared shift entry wins, then the side one, then the generic `clickActions()`. A null click resolves to the generic list.
- `public Requirement clickRequirementFor(@Nullable ClickType click)` - Requirement for the given click, same shift -> side -> generic `clickRequirement()` resolution; never null. Each field resolves independently (a specific action list can pair with the generic requirement).
- `public List<String> denyActionsFor(@Nullable ClickType click)` - Deny lines for the given click, same shift -> side -> generic `denyActions()` resolution.
- `boolean specificActionsFor(@Nullable ClickType click)` (package-private) - True when the click resolves its actions from a declared specific list (shift or side entry) instead of the generic fallback; consumed by `GuiSession.runClick`'s strict-clicks gate.
- `static @Nullable ClickKey shiftKey(@Nullable ClickType click)` / `static @Nullable ClickKey sideKey(@Nullable ClickType click)` / `static boolean basicClick(@Nullable ClickType click)` (package-private) - Pure resolution mappings: the exact shift key (only SHIFT_RIGHT/SHIFT_LEFT), the side key (RIGHT groups RIGHT/SHIFT_RIGHT; LEFT groups LEFT/SHIFT_LEFT/DOUBLE_CLICK/CREATIVE; MIDDLE alone; keyboard and unknown clicks, NUMBER_KEY/DROP/CONTROL_DROP/SWAP_OFFHAND/UNKNOWN, have no side and return null) and the predicate of the 4 basic mouse clicks (LEFT/RIGHT/SHIFT_LEFT/SHIFT_RIGHT). Covered by `ClickResolutionTest`.
- `public String id()` - The item's id (its key inside `items:` or `templates:`).
- `public int[] slots()` - Slots where it renders (defensive copy via `clone()`); empty for templates, whose slots come from binds.
- `boolean hasSlots()` (package-private) - True when the item declared at least one slot.
- `public int updateInterval()` - Per-item re-render interval in ticks; 0 disables the item's timer.
- `public Requirement viewRequirement()` - Requirement deciding whether the item renders for a viewer; always passes when absent.
- `public Requirement clickRequirement()` - Requirement gating clicks; if it fails the `denyActions()` run instead.
- `public List<String> clickActions()` - Raw action lines executed on a click that passes the click requirement.
- `public List<String> denyActions()` - Raw action lines executed when the click requirement fails.
- `NavKind navKind()` (package-private) - Navigation role; sessions gate disabled arrows through it.
- `@Nullable GuiItemDef navDisabled()` (package-private) - Appearance override rendered INSTEAD of this navigation item when there is no page to go to (first page for previous, last for next), or null if not declared. A disabled navigation item never fires any action.
- `public ItemStack render(@Nullable Player viewer, Ph... phs)` - Builds the physical stack for `viewer` re-reading every appearance field of the yml section (via `SnItem.fromConfig(yml, path, viewer, phs)`), so placeholders resolve per viewer plus the extra locals `phs`. Delegates to the skin-hook overload with a null hook.
- `ItemStack render(@Nullable Player viewer, @Nullable Consumer<String> skinRefresh, Ph... phs)` (package-private, 1.6) - Render variant that threads a skin-refresh hook into the built `SnItem` (`item.skinRefresh(skinRefresh)`): when the item carries an unresolved `skull-owner`, `skinRefresh` receives the owner so the caller (the `GuiSession`) can schedule the off-thread fetch and re-render. A null hook is the plain `render(Player, Ph...)`.

### GuiDef
`src/main/java/com/sn/lib/gui/GuiDef.java`

Immutable definition of ONE GUI, parsed from a file under `guis/` following the golden spec (`docs/menu-example.yml`): `title`, `rows`, lenient `inventory-type`, `open-sound`, `close-sound` (1.1.0), `close-actions` (1.1.0), menu `update-interval`, the opt-in flags `pagination` and `strict-clicks`, the optional ASCII layout (`layout:` + `paged-key:`, 1.1.0), the `items:` section and the `templates:` section. Both flags resolve ONCE at load and default to `false`; page actions and paginated binds over sessions of an unpaginated GUI are no-ops. The definition and its templates are immutable and shared by every per-viewer `GuiSession`. The class Javadoc includes the field-by-field checklist of the golden spec (which class parses which field).

- `static GuiDef parse(Sn ctx, String id, SnYml yml)` (package-private) - Parses the whole file; every malformed field emits a WARN prefixed `[gui <id>]` and falls back, never throwing. Empty/unreadable file: WARN "Empty or unreadable file; using a default gui with no items" and returns a default GUI (title "Menu", 3 rows, no items, empty `pagedSlots`). `rows` outside 1-6 WARNs and uses 3 (a check that only applies WITHOUT a layout). Items without valid slots WARN ("Item '<key>' without valid slots; not rendered") and are discarded; templates parse without requiring slots. The items loop passes the layout's key map to `GuiItemDef.parse` (an empty map when there is no layout, NEVER null for items); the templates loop passes null (key does not apply to templates).
- ASCII layout (1.1.0, `truncateLayout` + `layoutKeys`): `layout:` is an optional list of 1-6 strings of up to 9 characters, read top-down over the 9-column chest grid (cell row i / column j = slot i*9+j, same geometry as `GuiMask`). An empty list = no layout (empty key map, empty `pagedSlots`, byte-identical parse to v1.0.0). More than 6 rows: WARN "layout has N rows; truncating to 6"; a row longer than 9 characters: WARN "layout row I has N characters; truncating to 9" (I 1-based). The space ' ' never enters the key map; a key appearing in N cells accumulates all N in row-major order. With a layout present, `rows` derives from the truncated row count; a declared `rows:` with a different value WARNs "rows X contradicts a layout of Y rows; using Y". Layout + a non-chest `inventory-type` WARNs "layout assumes the 9-column chest grid; with inventory-type X out-of-range slots do not render" (renderItem already skips slots >= size).
- `paged-key:` (1.1.0, `parsePagedKey`): a menu-level layout character whose cells are the destination of the `bindPaged` overload without `int[]`. Without a layout: WARN "paged-key declared without a layout; ignored". A trim whose length is not 1: WARN "paged-key 'RAW' invalid (must be 1 character); ignored". A character absent from the layout: WARN "paged-key 'C' does not appear in layout; ignored". Valid but with `pagination: false`: WARN "paged-key declared with pagination false; bindPaged will stay ignored until pagination is enabled" and the value IS stored anyway (the real gate is the existing `bindPaged` one).
- `public int[] pagedSlots()` - Destination slots of the layout `paged-key` `bindPaged` (defensive copy via `clone()`); empty when the menu declares no paged-key.
- `public String id()` - GUI id: its file name without the `.yml` extension.
- `public String title()` - Raw title; sessions resolve its placeholders per viewer at render.
- `public int rows()` - Chest rows (1-6); only used when `inventoryType()` is null.
- `public @Nullable InventoryType inventoryType()` - Non-chest inventory type, or null for a chest sized by `rows()`.
- `public String openSound()` - Opening sound spec (`"SOUND_ID [vol] [pitch]"`); empty plays nothing.
- `public String closeSound()` (1.1.0) - Sound spec played to the viewer on menu close (`"SOUND_ID [vol] [pitch]"`, default ""); empty plays nothing.
- `public List<String> closeActions()` (1.1.0) - ActionEngine action lines run on the menu's natural close (default empty, stored with `List.copyOf`); empty runs nothing. The execution semantics (when they run and when not) live in `GuiSession.handleClose`.
- `public int updateInterval()` - Menu re-render interval in ticks; 0 disables the menu timer.
- `public boolean pagination()` - Whether this menu opted into pagination; default false.
- `public boolean strictClicks()` - Whether this menu opted into strict clicks; default false (full v1.0.0 compat). With true, a click outside the four basic mouse clicks (LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT) is discarded unless a declared specific action list covers it (the concrete gate lives in `GuiSession.runClick`).
- `public List<GuiItemDef> items()` - Items of the `items:` section, in declaration order.
- `public @Nullable GuiTemplate template(String templateId)` - Template declared under `templates:` with the given id, or null (also null if `templateId` is null).
- `public Map<String, GuiTemplate> templates()` - All templates of the `templates:` section, by id.

#### Notes and gotchas
- Lenient `inventory-type` resolution: empty or `CHEST` return null (chest by rows); unknown names WARN ("invalid inventory-type '<raw>'; using CHEST") and fall to chest. Resolved with an individual `InventoryType.valueOf` in try/catch, never a switch, so the enum stays open across Minecraft versions.

### GuiMask
`src/main/java/com/sn/lib/gui/GuiMask.java`

Pure helper (final, private constructor, zero Bukkit, testable in plain JUnit like `SlotParser`) that translates an ASCII mask to chest slot indices for code-built menus; the programmatic counterpart of the YML `layout:`.

- `public static int[] slots(char key, String... rows)` - Varargs variant; delegates to the List one (null `rows` returns empty).
- `public static int[] slots(char key, List<String> rows)` - Slots of every mask cell containing `key`, in ascending row-major order. 6x9 geometry: rows read top-down over the 9-column chest grid (cell row i / column j = slot i*9+j); at most 6 rows (extras ignored) and 9 characters per row (extras ignored). A null or empty row counts as a row of empty cells (keeps its row index); a null list returns empty. The space ' ' is ALWAYS an empty cell: `slots(' ', ...)` returns empty even if the mask has spaces. A missing key returns empty; no duplicates possible (each cell is unique). Typical use: `session.bindPaged("tpl", data, GuiMask.slots('d', "         ", " ddddddd ", " ddddddd "), mapper)` and `session.bind(GuiMask.slots('x', rows)[0], template)`. Covered by `GuiMaskTest`.

### GuiManager
`src/main/java/com/sn/lib/gui/GuiManager.java`

GUI module of a consumer context, reached via `sn.guis()`. `load()` seeds the consumer jar's bundled `guis/*.yml` into the data folder, creates the `guis/` folder if still missing, and loads ONE GUI per file (the id is the name without extension). Open sessions register per owner in a `TenantRegistry`, so a consumer's disable closes exactly that consumer's GUIs (non-interference); quit cleanup runs through the shared quit listener. Main-thread only, like the whole module.

Constants and statics:
- `public static final String ITEM_TAG = "snlib_gui_item"` - Name of the PDC key stamped on every rendered GUI stack (payload `"<guiId>:<slot>"`), namespaced by owning plugin via TagIo; the anti-theft protection listener resolves marked stacks through it.
- `static final TenantRegistry<GuiSession> SESSIONS` (package-private) - Justified server-wide static: open GUI sessions per owning plugin. The sweep callback (`GuiSession::close`) closes each session (cancels timers, untracks the holder, force-closes on the viewer) when its owner key is removed (consumer disable).

Methods:
- `public GuiManager(Sn ctx)` - Creates the module for the given context and hooks its quit cleanup via `QuitCleanupListener.register(plugin, this::closeSessionsOf)`.
- `public void load()` - Seeds the bundled `guis/*.yml` (via `seedBundledGuis`), creates `guis/` if still missing and (re)parses one GUI per `.yml` file (alphabetical order by name). Requires the yml module: without it, WARN "guis() declared without config(): the guis/ folder cannot load and sn.guis() stays empty" and returns. If the folder cannot be created: WARN "Could not create the folder <path>". If `guis()` is declared but nothing loaded because the folder is empty, WARN "guis() is declared but no menu was loaded from <path>: the guis/ folder is empty. Bundle the menus as guis/*.yml in the jar so they seed, or drop the files into the folder." (1.5.0). Synchronous I/O by design: it runs only in onEnable and the reload flow (so seeding also runs on every reload - missing files reseed, existing files re-merge). File mounts cache in a `ConcurrentHashMap` (`mounts`), so the reload can re-read from disk.
- `private void seedBundledGuis(YmlManager files)` (1.5.0) - Resolves the consumer jar via `GuiSeeder.consumerJar(plugin)` and delegates to `GuiSeeder.seed`, passing the config file as the `update-configs` gate. A jar that cannot be located WARNs and leaves the folder to whatever is on disk.
- `public @Nullable Gui get(String id)` - GUI loaded under `id` (file name without extension, `trim()`ed), or null.
- `public void registerAction(String tag, ActionHandler handler)` - Registers a custom click action tag for this context; sugar over `sn.actions().register`.
- `public void reload()` - Reloads the module: natively closes every open GUI of this context (sessions are per-viewer, so nobody keeps a stale inventory), re-reads each mounted file from disk, re-parses the definitions and picks up new files (via `load()`).
- `public List<GuiSession> openSessions()` - Snapshot of THIS context's open sessions; the reopening source of the reload flow.
- `public void closeAll()` - Closes all of THIS context's open GUI sessions.
- `public void closeAll(Plugin owner)` - Closes all sessions registered by `owner`; those of any other plugin stay intact (non-interference).
- `void warnOnce(String key, String message)` (package-private) - Logs a GUI misuse warning once per key for this context (the `bindPaged` gating).
- `private void closeSessionsOf(UUID viewer)` - Quit/kick cleanup: closes this context's sessions of the departing viewer.

### GuiSeeder (package-private, 1.5.0)
`src/main/java/com/sn/lib/gui/GuiSeeder.java`

Seeds the bundled `guis/*.yml` of a consumer jar into its data folder before `GuiManager` lists the folder. Bukkit cannot enumerate a resource directory through `getResource`, so the seeder enumerates the CONSUMER plugin's own jar (resolved from the code source of the plugin's main class, NEVER SnLib's jar) and, for every top-level entry directly under `guis/` ending in `.yml`, applies the managed semantics reused from `YamlUpdater.updateFromLines(Logger, ...)`: a missing file is seeded, an existing file is always-merged through the same updater and gated by `update-configs`. Nested entries (`guis/sub/x.yml`) and non-yml entries are ignored. Takes a jar `File` + `Logger` (not a live `JavaPlugin`), so the whole pipeline is unit-testable against a temp jar. Synchronous I/O by design: it runs only from `GuiManager.load()`.

- `static List<String> guiResourcePaths(Iterable<String>)` - Pure filter: sorted, distinct top-level `guis/<name>.yml` paths; separators normalized to `/`, the `guis/` prefix and `.yml` suffix matched case-insensitively, entry name kept verbatim (case preserved) so it round-trips to the jar for reading and to the disk path for writing.
- `static List<String> guiResourcePaths(File jar)` - Enumerates a jar and returns its top-level `guis/*.yml` paths.
- `static List<String> seed(File jar, File dataFolder, @Nullable File gateFile, Logger logger)` - Seeds/merges every bundled `guis/*.yml` into `dataFolder/guis/`, returning the resource paths found (empty when the jar bundles no menu). Never throws: an unreadable jar yields an empty list and one WARN; a `null` gate file merges unconditionally.
- `static @Nullable File consumerJar(JavaPlugin plugin)` - Resolves the consumer jar from the code source of its main class; `null` when the location cannot be resolved (WARNed by the caller).

### Gui
`src/main/java/com/sn/lib/gui/Gui.java`

A loaded GUI definition and its live per-viewer sessions. `open(Player)` gives each viewer their OWN `GuiSession` (own inventory, own holder, own page state) over the shared immutable `GuiDef`; re-opening for a viewer with a live session re-shows that session instead of stacking a second one. Main-thread only.

- `public String id()` - GUI id: the file name without the `.yml` extension.
- `public GuiDef def()` - Immutable parsed definition shared by all sessions.
- `public void open(Player player)` - Opens the GUI for the player at page 1.
- `public void open(Player player, int page)` - Opens the GUI at the given page (clamped to at least 1 and forced to 1 while the menu has not opted into pagination). A live session of the viewer re-shows at that page (`reopen`); otherwise a new session is created, registered per owner (`GuiManager.SESSIONS.add`) and rendered. A null `player` is a no-op.
- `public @Nullable GuiTemplate template(String templateId)` - Template declared under `templates:` with the given id, or null.
- `public @Nullable GuiSession session(Player player)` - The player's live session on THIS GUI, or null when they have none.
- `void removeSession(UUID viewer, GuiSession session)` (package-private) - Releases a viewer's session mapping; only the closing session calls it (conditional `Map.remove(key, value)` so as not to clobber a newer session).

### GuiSession
`src/main/java/com/sn/lib/gui/GuiSession.java`

ONE viewer's live GUI: each viewer has their session with their OWN inventory, OWN `SnGuiHolder` and OWN page state, sharing the immutable `GuiDef` and its templates. Rendering is per viewer: view requirements, placeholders and title resolve against this session's player. It implements `PageTarget` (action module): page operations are gated by the menu's opt-in `pagination` flag. Main-thread only. Relevant internal state: `baseSlots` (slot -> declared item), `binds` (manual binds per slot), `pagedPhs` (locals per paginated slot), `tasks` (cancelable task handles), and the volatiles `inventory`, `lastTitle`, `page`, `transitioningPage`, `closed`, `pagedBind`, `pagedSlots`, `manualTotalPages` (1.1.0, total declared by `setTotalPages`; 0 = unknown).

- `void open()` (package-private) - First opening: creates the inventory, renders, tracks the holder in the `TenantSweeper`, opens the inventory to the viewer, plays the open-sound and starts the timers.
- `void reopen(int targetPage)` (package-private) - Re-entry via `Gui.open` on an existing session: sets the page (forced to 1 without pagination), does `refreshMenu()` and re-opens the inventory if the viewer was not viewing it; the open-sound only plays if they were not viewing.
- `public Player viewer()` - Player owning this session.
- `public UUID viewerId()` - UUID of the session's viewer.
- `public String guiId()` - GUI id of the backing definition.
- `public int page()` - THIS viewer's current page (1-based); always 1 while pagination is off.
- `SnGuiHolder holder()` (package-private) - Holder shared by every inventory this session recreates.
- `public boolean transitioningPage()` - True while the session swaps inventories (page change or recreation); the click listener's close handling skips removal during a transition.
- `public boolean closed()` - True once the session closed and unregistered.
- `public @Nullable GuiItemDef itemAt(int slot)` - The definition rendered at `slot` for this viewer with precedence: API bind, then paginated entry, then the slot's declared item. Null for an empty slot.
- `public void bind(int slot, GuiTemplate template, Ph... phs)` - Binds a template to a slot of THIS session with the given local placeholders and renders it immediately. The bind survives page refreshes and inventory recreations until overwritten; it takes precedence over an item declared on the same slot. A null template or negative slot: no-op.
- `public <T> void bindPaged(String templateId, List<T> data, int[] slots, BiConsumer<T, PhCollector> mapper)` - Binds a paginated data set to THIS session: an immutable snapshot of `data` paginates in chunks of `slots.length` entries and this viewer's CURRENT page renders into `slots` using the template, one entry per slot in order. The mapper fills each entry's local placeholders; leftover slots of a short page stay empty. The bind survives page changes and recreations until rebound; the page clamps to the snapshot's total pages, which also drives the YML navigation items' `nav-disabled` state. With `pagination: false` (menu default) the call is ignored with ONE warning per GUI ("bindPaged on gui '<id>' ignored: pagination false (opt-in per menu)"); an unknown template or empty slots also WARN once and ignore. A null `mapper` throws NPE (`Objects.requireNonNull`).
- `public <T> void bindPaged(String templateId, List<T> data, BiConsumer<T, PhCollector> mapper)` (1.1.0) - Slotless variant: uses as destination the cells of the `paged-key` declared in the menu's `layout:` (`def.pagedSlots()`). On a menu without a paged-key it WARNs once ("bindPaged on gui '<id>' ignored: the menu declares no paged-key in layout") and is ignored; if it resolves, it delegates to the `int[]` overload with the same rules (pagination opt-in, existing template).
- `public void handleClick(int slot, ClickType click)` - Click dispatch invoked by the shared click listener with a top-inventory raw slot: it resolves the effective definition (manual bind, paginated entry, declared item), skips disabled navigation items and delegates to `runClick`, which resolves the definition's per-click matrix (actions, requirement and deny by `ClickType`, specific-over-generic field by field) and applies the menu's opt-in strict-clicks gate, with this session as the page target and the click type in the context. No-op if the session is closed.
- `public void handleClose()` - Close handling invoked by the click listener when the viewer's client closed the inventory: same teardown as `close()` but without force-closing the screen; since 1.1.0 it additionally plays the menu's `close-sound` (inline: a sound during the InventoryCloseEvent is safe) and schedules the `close-actions` for the NEXT TICK via `ctx.scheduler().sync` (never inline: running `[open]`-like actions inside the InventoryCloseEvent itself reopens inventories mid-close and glitches the client). Semantics guaranteed by construction: they run on the natural close (ESC) and on the action engine's `[close]` (one execution per close); they do NOT run on page transitions/recreations (the listener's `transitioningPage()` guard skips `handleClose`); they do NOT run on programmatic teardown (`close()` from sweep/reload/quit-cleanup marks `closed` BEFORE force-closing, so the later close event finds `teardown()` false). The next-tick task re-checks `viewer.isOnline()` and runs with a null click `ActionContext` (click guards inside skip with debug); scheduling against a disabled owner is absorbed with `IllegalPluginAccessException` + a debug note. Documented edge: on a disconnect the server fires InventoryCloseEvent before PlayerQuitEvent; the double `isOnline()` guard cuts the normal case, but consumers must write idempotent close-actions. Page actions inside close-actions are useless (the session is already closed).
- `public void nextPage()` - (`PageTarget` override) Advances one page and refreshes; no-op with debug if pagination is off, and it does not pass the last KNOWN page (a live paginated bind or a total declared by `setTotalPages`).
- `public void previousPage()` - (override) Goes back one page if `page > 1` and refreshes; no-op with debug without pagination.
- `public void setPage(int targetPage)` - (override) Sets the page (minimum 1, clamped to the known total if there is a paginated bind) and refreshes; no-op with debug without pagination.
- `public void refreshPage()` - (override) Re-renders the current inventory's contents; no-op with debug without pagination.
- `public void refreshMenu()` - (override) Full re-render WITHOUT the pagination gate; recreates the inventory when the resolved title changed (same holder and session, preserving page and binds), otherwise it only re-renders contents.
- `public boolean paginationEnabled()` - (override) Returns the definition's `pagination` flag.
- `public void setTotalPages(int total)` (1.1.0) - Declares the total pages of a manually paginated GUI (refreshPage/custom actions without `bindPaged`): it enables the `nextPage()` cap and the next item's `nav-disabled` state. Values <= 0 revert to "unknown" (0). A live `bindPaged` takes precedence over this value. Requires `pagination: true`; with pagination false it is a no-op with a debug note (`set-total-pages`). If the value changes, it clamps the current page to the new total and re-renders immediately (refreshing the next item's nav-disabled state). Main-thread only.
- `public void close()` - Closes the session: cancels its timers, unregisters it from the per-owner registries, untracks the holder and force-closes the viewer's inventory if this session is still on screen. Idempotent.

#### Internal logic
- Pagination gate (`paginationBlocked(String operation)`): with `pagination: false` every page operation is a no-op recorded by the context's debug service: `"GUI '<id>': <operation> ignored, pagination false (opt-in per menu)"`.
- Disabled nav (`navDisabledNow`): previous disables on page 1; next on the last KNOWN page (`knownTotalPages()` uses the live paginated bind if any, falling back to `setTotalPages`' `manualTotalPages`; 0 = unknown, and in that case next never disables). A disabled item renders its `nav-disabled` override (if present) and fires nothing.
- Next-nav debug note (1.1.0, `navUnknownNoted`): the FIRST time `renderItem` renders a NEXT nav with pagination on and an unknown total (`knownTotalPages() == 0`), it records via debug "GUI '<id>': next nav with unknown total pages; next never disables (use bindPaged or setTotalPages)". Once per session (main-thread field, no volatile).
- Close feedback (1.1.0, `playCloseSound` + `runCloseActions`): `playCloseSound` is symmetric to `playOpenSound` (inline); `runCloseActions` schedules the lines for the next tick via `ctx.scheduler().sync` with an `isOnline()` re-check and a null click `ActionContext`, wrapping the scheduling in a try/catch of `IllegalPluginAccessException` with a debug note ("close-actions of '<id>' discarded: owner disabled").
- Recreation (`recreate(Component title)`): sets `transitioningPage = true`, creates the new inventory with the SAME holder, re-renders, re-opens to the viewer and lowers the flag in a `finally`; the guard prevents the swap's `InventoryCloseEvent` from tearing the session down.
- `createInventory`: tries the declared `inventory-type`; on `Throwable` it WARNs once per session (`typeWarned`) "[gui <id>] inventory-type <X> could not be created (<t>); using CHEST" and falls to a `rows * 9` chest.
- Render (`renderContents`): clears the inventory and renders in three phases with precedence: declared items (skipping slots taken by binds or the paginated bind), then the paginated bind's current page (skipping slots with a manual bind, clamping `page` to the total), then the manual binds. Each definition's view requirement tests against the viewer's resolver (PAPI + locals); if it fails, the slot stays null.
- Async skin hook (1.6, `skinHook`): every render path - `renderItem` (declared item into all its slots), `renderPagedSlot` (one paged entry, refactored out of the old `renderPaged` loop) and `renderBinding` (a manual bind) - passes the `GuiItemDef.render(viewer, skinRefresh, phs)` overload a hook `owner -> SkinResolver.request(ctx, owner, reRender)`. On an unresolved `skull-owner` head the resolver fetches the texture off-thread and, when it lands on the main thread, runs the matching guarded re-render: `reRenderItem` (skips if closed or the inventory changed), `reRenderPagedSlot(atPage, index)` (skips if closed, the page changed, or the bind is gone) or `reRenderBinding(slot, binding)` (skips if closed or the slot was re-bound). So an offline head pops its skin in without a full-menu refresh.
- Anti-theft marker (`stamp`): every rendered stack stamps via `TagIo.set(stack, ctx.plugin(), GuiManager.ITEM_TAG, def.id() + ":" + slot)` (PDC key `snlib_gui_item`, namespaced by owning plugin, payload `"<guiId>:<slot>"`).
- Timers (`startTimers`): if the menu `update-interval` > 0 it starts a menu timer; for each item with `update-interval` > 0 it starts an item timer. Both ticks first check `closed`, and if the viewer is no longer viewing the inventory they close the session (self-cleanup). The menu tick calls `refreshMenu()`: it re-evaluates the title (and thus recreates the inventory preserving session, page and binds when it changed); the item tick re-renders only that item.
- Clicks (`runClick`): the single funnel for declared items, manual binds and paginated entries. Strict-clicks gate FIRST: with `strict-clicks: true`, a click outside the 4 basic mouse clicks with no declared specific list covering it (`GuiItemDef.basicClick` + `specificActionsFor`) is discarded BEFORE the requirement test, with a debug note: neither the click actions nor the deny actions run (the listener already cancelled the event). `middle-click-actions` enables MIDDLE and a declared `left-click-actions` enables DOUBLE_CLICK and CREATIVE (a vanilla double click is two lefts; deliberate); NUMBER_KEY, DROP, CONTROL_DROP, SWAP_OFFHAND and UNKNOWN have no possible specific list and stay always discarded in strict. With strict false (default) behavior is identical to v1.0.0. Past the gate, it builds an `ActionContext(viewer, ctx, this, click, phs)` (the surfaceless compat overload: GUI clicks have no `ClickSurface`) and runs `clickActionsFor(click)` if `clickRequirementFor(click)` passes, or `denyActionsFor(click)` if not, via `ctx.actions().run` (specific-over-generic resolution field by field).
- Private records: `Binding(GuiTemplate template, Ph[] phs)` (a manual bind with its locals captured at bind time) and `PagedBind<T>(GuiTemplate template, Pagination<T> pagination, int[] slots, BiConsumer<T, PhCollector> mapper)` (the live paginated bind).

#### Notes and gotchas
- The "native" close covers all paths: quit/kick (QuitCleanupListener -> `GuiManager.closeSessionsOf`), reload (`GuiManager.reload()` -> `closeAll()`), consumer disable (`TenantRegistry` sweep with the `GuiSession::close` callback) and manual client close (`handleClose` via the click listener). In all of them, timers cancelled and registries clean. Only the `handleClose` path (natural close or `[close]`) fires `close-sound`/`close-actions`: the programmatic paths mark `closed` before force-closing and are excluded by design (running actions during shutdown is dangerous).
- `renderPaged` clears `pagedPhs` and repopulates it per slot (each `renderPagedSlot` puts the resolved locals or removes the entry for an empty slot), so the locals `handleClick` sees always correspond to the current page - and a single async skin re-render of one slot keeps the rest intact.

### GuiTemplate
`src/main/java/com/sn/lib/gui/GuiTemplate.java`

A reusable GUI item WITHOUT slots, declared under a GUI file's `templates:` section: the config user customizes appearance and actions freely while the plugin decides at runtime which slot each template goes to via `GuiSession.bind(int, GuiTemplate, Ph...)`. It supports exactly the same fields as a regular item except `slots:`, and typically uses plugin-defined local placeholders (for example `%index%`) provided as `Ph` pairs at bind time.

- `public String id()` - Template id (its key inside the `templates:` section).
- `public ItemStack render(@Nullable Player viewer, Ph... phs)` - Builds the physical stack for `viewer` with the given local placeholders (delegates to `GuiItemDef.render`).
- `GuiItemDef item()` (package-private) - Backing definition (requirements and action lists) used by the click flow.

### Pagination
`src/main/java/com/sn/lib/gui/Pagination.java`

Immutable 1-based pager over a fixed data snapshot (port of SnGens' pagination helper): the list copies once at creation and slices per page on demand. Pages always clamp: asking for a page below 1 returns the first and asking beyond `totalPages()` returns the last. An empty data set still reports one (empty) page, so navigation math never divides by zero.

- `public static <T> Pagination<T> of(List<T> data, int pageSize)` - Creates a pager over a snapshot of `data` (null means empty; elements must be non-null per `List.copyOf`) with `pageSize` entries per page (values below 1 raise to 1).
- `public int pageSize()` - Entries per page.
- `public int size()` - Total elements of the snapshot.
- `public int totalPages()` - Total pages; at least 1 even with an empty snapshot.
- `public List<T> page(int page)` - Slice of the given 1-based page, clamped to range; possibly shorter than `pageSize` (returns a view via `subList`, or `List.of()` if empty).

### PhCollector
`src/main/java/com/sn/lib/gui/PhCollector.java`

Local placeholder pair accumulator handed to `bindPaged`'s mapper: the mapper fills one collector per paginated entry and the session renders the template with the collected pairs.

- `public PhCollector add(String key, Object value)` - Adds a pair via `Ph.of`; null or empty keys are ignored. Returns `this` (chainable).
- `public Ph[] toArray()` - Collected pairs in insertion order.

### GuiClickListener (internal)
`src/main/java/com/sn/lib/gui/internal/GuiClickListener.java`

The single shared click listener, owned by SnLib, for EVERY library GUI of every consumer. Enrolled in the ListenerHub; `registerEvents` happens SOLELY in the SnLibPlugin bootstrap. Identification is ALWAYS `holder instanceof SnGuiHolder`, never by title.

- `public void onClick(InventoryClickEvent event)` - (`@EventHandler(priority = HIGH, ignoreCancelled = true)`) Clicks on a library GUI: `COLLECT_TO_CURSOR` (double-click stacking) is cancelled UNCONDITIONALLY before anything else, closing the double-click extraction vector; then every click is cancelled and, if the raw slot falls inside the top inventory (`0 <= rawSlot < size`), it dispatches to `holder.session().handleClick(rawSlot, event.getClick())`.
- `public void onDrag(InventoryDragEvent event)` - (`@EventHandler(priority = HIGH, ignoreCancelled = true)`) Drags on a library GUI: always cancelled.
- `public void onClose(InventoryCloseEvent event)` - (`@EventHandler`) Natural close: it tears the session down (`handleClose`) UNLESS it is swapping inventories from a page change or recreation, guarded per viewer by `GuiSession.transitioningPage()`.

### GuiProtectionListener (internal)
`src/main/java/com/sn/lib/gui/internal/GuiProtectionListener.java`

The single shared anti-theft listener, owned by SnLib (generalization of the EdToolsArmors protection): any stack stamped with the PDC marker key `snlib_gui_item` (payload `"<guiId>:<slot>"`, namespaced by owning plugin, so detection scans PDC keys BY NAME across all namespaces) is a rendered GUI item and must NEVER circulate outside a library GUI. Marked stacks are DELETED on detection, not returned. Enrolled in the ListenerHub; single registration in the SnLibPlugin bootstrap. Hot-path contract (this listener sees every inventory event of the server): each check quick-exits in layers: null/air first, then `hasItemMeta()`, then the PDC scan.

Static: `private static volatile boolean reactiveSweep` - opt-in toggle of the reactive sweep (justified server-wide: not per-consumer data), off by default.

- `public static void reactiveSweep(boolean enabled)` - Enables or disables the reactive sweep on inventory open and player join.
- `public void onInventoryClick(InventoryClickEvent event)` - (`priority = HIGH`) Vector 1: a click on a marked stack outside a library GUI deletes it (current item), a marked cursor is cleared, `NUMBER_KEY` deletes the destination hotbar slot if marked, `SWAP_OFFHAND` deletes the marked offhand. If it acted on anything, it cancels the event.
- `public void onInventoryDrag(InventoryDragEvent event)` - Vector 2: drags of a marked stack (old cursor) are cancelled.
- `public void onInteract(PlayerInteractEvent event)` - (`priority = HIGHEST`) Vector 3: using a marked stack clears that hand and denies the interaction (both results at `DENY`, which amounts to cancelling the interaction event).
- `public void onSwapHands(PlayerSwapHandItemsEvent event)` - (`priority = HIGHEST`) Vector 4: hand swaps involving a marked stack are cancelled and the stack is deleted from the hand where it really lives (the item headed to the main hand lives in the current offhand, and vice versa).
- `public void onDrop(PlayerDropItemEvent event)` - (`priority = HIGHEST`) Vector 5: dropped marked stacks disappear (the drop entity is removed); the drop is NOT cancelled.
- `public void onDeath(PlayerDeathEvent event)` - Vector 6: marked stacks never reach the death drops (`removeIf` over the drop list).
- `public void onItemSpawn(ItemSpawnEvent event)` - Vector 7, catch-all: a marked item entity is removed the moment it spawns; filters by `hasItemMeta()` before anything else (hot path).
- `public void onInventoryOpen(InventoryOpenEvent event)` - Reactive sweep (flag): opening an inventory NOT belonging to the library purges marked stacks from that inventory. No-op if the flag is off.
- `public void onJoin(PlayerJoinEvent event)` - Reactive sweep (flag): a joining player's inventory is purged of marked stacks.

#### Internal logic
- `private static void sweep(Inventory inventory)` - Walks the contents and nulls every slot with a marked stack.
- `private static boolean insideGui(@Nullable Inventory inventory)` - True if the inventory's holder is an `SnGuiHolder`.
- `private static boolean marked(@Nullable ItemStack stack)` - Layered quick-exit detection: null/air, `hasItemMeta`, and then a PDC key scan that matches `snlib_gui_item` under ANY namespace (the stamp is namespaced by owning plugin, so a fixed-namespace lookup would miss it).

### TODOs and limitations
- There are no TODO/FIXME/placeholder markers in the module's code.
- Limitations documented in the code:
  - The total page count is known with a live `bindPaged` or a total declared via `GuiSession.setTotalPages(n)` (1.1.0, for manual pagination); with neither, `knownTotalPages()` returns 0 = unknown, the "next" navigation item never disables and `nextPage()` advances without an upper cap (with a debug note the first time the next nav renders in that state).
  - `bindPaged`, the page operations and the nav items require `pagination: true` in the menu YML (opt-in per menu, default false): without the flag they are no-ops (bindPaged with a once-per-GUI WARN; page ops with a debug note).
  - `GuiManager.load()` requires the context's yml module (`config()`): without it, `sn.guis()` stays empty with a WARN.
  - `load()`/`reload()` do synchronous I/O by design (only in onEnable and the reload flow).
  - The whole module is main-thread only.
  - The protection listener's reactive sweep is opt-in and off by default.
---

## 13. Commands

Command module of the consumer context, reached via `sn.commands()`. It provides a fluent root/subcommand tree builder (`SnCommands` -> `RootBuilder` -> `SubCommandBuilder`) that materializes into a Bukkit `RootCommand` with permission check first, argument count validation against the generated usage, typed parsing per `Arg` and generated paginated help with `Page`. Subcommands NEST (1.6): a subcommand that owns children (declared through `SubCommandBuilder.sub(name, spec)`) is a GROUP that dispatches on the next token (`/clan admin disband <clan>`); a childless subcommand is a LEAF that parses its positional args. Every root injects the `reload` and `help` subcommands by default (and `debug` if the spec declared it), replaceable or omittable with `withoutDefaults()`; a bare-root invocation runs the optional `onEmpty` hook or, without one, the generated help. Registration against Bukkit is done by `internal/BukkitCommandRegistry` with `Plugin` ownership (reload-safe, tenant sweep when the consumer disables) and client tree refresh via `updateCommands()`. The library itself registers its diagnostic `/snlib` command through this same path (`internal/SnLibCommand`). All execution and tab-complete run on the server's main thread (standard Bukkit dispatch).

### SnCommands
`src/main/java/com/sn/lib/command/SnCommands.java`

Command module of a consumer context. Every root built here injects a `reload` subcommand (permission `<plugin>.admin.reload`, delegates to `Sn.reloadAll()` and confirms with `snlib.reload-done`) and a generated `help`; both are replaceable by declaring subcommands with those names and removable via `withoutDefaults()`. If the spec declared `debugCommand()`, a `debug` subcommand (permission `<plugin>.admin.debug`) is additionally injected that toggles the runtime debug service; that one is gated by the spec, not by the defaults opt-out.

Constant: `public static final String CONFIG_ALIASES_KEY = "command.aliases"` (1.5.0) - conventional config key read by `RootBuilder.aliasesFromConfig()`.

- `public SnCommands(Sn ctx, @Nullable SnLang lang, boolean debugCommand)` - constructor; instantiated by the context. `lang` may be null (the shared default `snlib.*` templates render); `debugCommand` says whether the spec declared the debug command.
- `public RootBuilder root(String name)` - starts a root tree with that name; validates non-null and non-empty (`IllegalArgumentException` "Empty command name").
- `public void unregisterAll()` - unregisters all of the owning plugin's roots and refreshes the client trees; invoked by the context teardown.
- `public void reregisterAll()` - re-registers all of the owning plugin's roots; it is the re-registration step of the context's reload flow. Each root re-sources its dynamic aliases (the config binding re-reads `command.aliases` from the just-reloaded config).
- `private @Nullable Collection<String> configAliases(String key)` (1.5.0) - root alias list read from the config, or null when it cannot act as the authority (config module absent, or the key not set). A set key returns its list as-is (an empty list is authoritative and clears the aliases). Backs `aliasesFromConfig`.

#### SnCommands.RootBuilder (public inner class)
Builder of a root tree.

- `public RootBuilder aliases(String... aliases)` - adds STATIC aliases to the root (trim + lowercase `Locale.ROOT`). When a supplier or the config binding is also set, these act as the FALLBACK used only while the authoritative source has no opinion.
- `public RootBuilder aliases(Supplier<Collection<String>> supplier)` (1.5.0) - supplies the aliases dynamically, re-evaluated on every register pass (so a reload re-sources them). A non-null result is AUTHORITATIVE and an empty list clears the aliases; a null result falls back to the static / plugin.yml aliases. Aliases that disappear between passes are unregistered.
- `public RootBuilder aliasesFromConfig()` (1.5.0) - sources the aliases from the plugin config list at the conventional key `command.aliases` (`SnCommands.CONFIG_ALIASES_KEY`). The config is AUTHORITATIVE when the key is set (even to an empty list); when the key is absent, or the config module was not declared, the static / plugin.yml aliases apply. Internally binds a supplier that re-reads the just-reloaded config each pass.
- `public RootBuilder aliasesFromConfig(String key)` (1.5.0) - same against a custom config key.
- `public RootBuilder permission(String permission)` - root permission, inherited by every subcommand without its own. Without a permission the root is public.
- `public RootBuilder description(String description)` - root description (null normalizes to "").
- `public SubCommandBuilder sub(String name)` - starts a subcommand; closed with `SubCommandBuilder.and()`. Validates a non-empty name.
- `public RootBuilder withoutDefaults()` - omits the `reload` and `help` defaults. The consumer MUST then provide its own reload and help: sn-core declares them mandatory in every root.
- `public RootBuilder onEmpty(Consumer<RootContext> action)` (1.6) - action invoked when the root runs with ZERO arguments, replacing the default generated-help fallback. The handle (`RootContext`) can still trigger that help via `RootContext.help()` (for example after printing a banner). Without a hook the bare root prints the generated help. Non-null (`Objects.requireNonNull`).
- `public RootCommand register()` - builds the tree, injects the applicable defaults, binds the alias supplier to the built root (`BukkitCommandRegistry.bindAliasSupplier`, 1.5.0) and registers it against Bukkit; returns the `RootCommand`.

**Notes and gotchas**
- The root name normalizes with trim + lowercase in the builder constructor.
- `debug` is NOT disabled by `withoutDefaults()`: its only switch is whether the context's spec declared `debugCommand()`.

#### RootContext (public class, v1.6)
`src/main/java/com/sn/lib/command/RootContext.java`

Handle passed to the bare-root `onEmpty` hook: the invoking sender plus the ability to render the generated help, so a hook can print its own banner and still fall through to the standard help.

- `public CommandSender sender()` - the sender that ran the bare root command.
- `public void help()` - renders page 1 of the generated help (the default bare-root behavior).
- `public void help(int page)` - renders the given 1-based page of the generated help.

### RootCommand
`src/main/java/com/sn/lib/command/RootCommand.java`

Root of a command tree; extends `org.bukkit.command.Command` and implements `Registrable` (reload module). It dispatches to its subcommands with a permission check first, validates the argument count against the generated usage, parses typed via each `Arg` and generates the help. Subcommands NEST (1.6): a subcommand that owns children is a GROUP that dispatches on the next token among its children (with child aliases), a childless subcommand is a LEAF that parses its positional args; groups nest arbitrarily. Permission chain: each node may carry its own permission and a node without one inherits the nearest ancestor's (a group's, or ultimately the root's); the effective check enforced as the tree is descended is EVERY permission on the path from the root down to the leaf. Leaf usage strings and the generated help render the FULL path, and the help lists one entry per reachable LEAF (groups flattened) rather than one per group. Tab-complete and the generated help list ONLY nodes that are visible AND whose permission chain the sender holds. Messages resolve through the context's lang module if declared; without lang the default `snlib.*` templates embedded in the library render. The pure resolution (`resolve`) and tab (`tab`) are static and Bukkit-context-independent, covered by `NestedCommandTest`.

Constants (private, but they define the observable contract):
- `DEFAULT_MESSAGES` - static map of default templates mirroring `snlib-messages.yml` (server-wide static justified by being constant). Keys: `snlib.no-permission`, `snlib.usage`, `snlib.invalid-number`, `snlib.invalid-value`, `snlib.out-of-range`, `snlib.player-not-found`, `snlib.unknown-subcommand`, `snlib.reload-done`, `snlib.help.header`, `snlib.help.entry` (default `&e{usage} &7{description}`, no `&8:` separator since 1.6), `snlib.help.footer`.
- `HELP_PAGE_SIZE = 10` - entries per page of the generated help.
- Constructor takes an extra `@Nullable Consumer<RootContext> onEmpty` (1.6) after the defaults/debug flags.

Public methods:
- `public JavaPlugin owner()` - consumer plugin owning this tree.
- `public void register()` - (override of `Registrable`) registers this root against Bukkit under the owning plugin, via `BukkitCommandRegistry.register`.
- `public void unregister()` - (override of `Registrable`) unregisters this root and refreshes the client trees.
- `public boolean execute(CommandSender sender, String label, String[] args)` - full dispatch (see internal logic). Always returns `true`.
- `public List<String> tabComplete(CommandSender sender, String alias, String[] args)` - permission-gated tab (see internal logic).

**Internal logic (execute)**
`execute` calls the static `resolve(sender, rootPermission, subs, rootPath, args)` and switches on its `Resolution`:
1. Root permission: without it, `resolve` returns `Message("snlib.no-permission")`.
2. Zero arguments: `resolve` returns `Empty`; `execute` runs the `onEmpty` hook (1.6) with a `RootContext(sender, page -> sendHelp(sender, page))` - a failure logs `SEVERE` "Bare-root handler of '/<root>' failed" - or, without a hook, sends the help (page 1).
3. `resolve` finds the first-token node by name or alias (lowercase); unknown -> `Message("snlib.unknown-subcommand", {value})`. Then it recurses through `dispatch(sender, sub, args, matchedAt, path)`:
   - Node permission gate (its own; the chain is enforced node by node as the tree descends) -> `snlib.no-permission` on failure.
   - GROUP (has children): with no next token -> `snlib.usage` with the group usage `/path <childA|childB>` (only the children the sender may use); an unknown next token -> `snlib.unknown-subcommand` with the full path; otherwise recurse into the matched child.
   - LEAF: `subArgs = args` after the matched token. `subArgs.length < requiredArgs` -> `snlib.usage` with the full-path `{usage}`. `when(index, predicate)` conditions over the raw token (an absent optional is skipped) -> `snlib.usage` on failure. Typed parsing in declaration order via the sender-aware `parse(sender, token)` (1.5.0), the LAST arg greedy joining the remainder; an `Arg.ArgParseException` yields `Message(e.langKey(), e.phs())`. No executor -> `snlib.usage`. Otherwise `Run(sub, context, path)`.
4. `execute` runs a `Run`'s executor wrapped in a `Throwable` try/catch: a failure logs `SEVERE` with "Subcommand '<full path>' failed" and the stack trace, without propagating to Bukkit's dispatcher.

**Internal logic (tabComplete)**
Delegates to the static `tab(sender, rootPermission, subs, args)`:
- Without the root permission it returns an empty list (the sender sees NOTHING of the tree).
- `tabAt` recurses: at group depth it suggests the visible child names whose permission the sender holds, filtered by prefix and sorted (`suggestNames`); descending into a group re-checks that group's permission. At leaf depth (`tabLeaf`) it completes the positional arg: if the index exceeds the declared args only a final greedy arg keeps suggesting; otherwise it delegates to the positional arg's `Arg.suggest(sender, partial, argName)` (1.5.0), passing the declared name so a free-form arg suggests its `<argName>` hint. A `suggest` returning null normalizes to an empty list.

**Internal logic (generated help)**
Header `snlib.help.header` (placeholder `{plugin}`), then `collectHelp` FLATTENS the tree into one `HelpLine` per reachable LEAF: a node hidden or whose own permission the sender lacks (and its subtree) is skipped, a group recurses into its children, a leaf yields its full-path usage, description and effective permission (`inheritedPermission` narrowed by each node's own permission on the path). Each line renders `snlib.help.entry` (placeholders `{usage}`, `{description}`, `{permission}` - empty if public), paginated with `Page` in pages of 10; the footer `snlib.help.footer` (`{page}`, `{total}`, `{command}`) appears only with more than one page. The page token of `/cmd help <page>` parses from `context.raw(0)`; anything unparseable falls to page 1, and out-of-range pages clamp.

**Notes and gotchas**
- Defaults inject in the constructor only if no sub with that name or alias already exists (`hasSub`): a consumer's sub named `reload`/`help`/`debug` replaces the default.
- The defaults' base permission is `<lowercased-plugin-name>.admin.` + `reload`/`debug` (the `<plugin>.admin.<sub>` convention). The default `help` has no permission of its own (it inherits the root's if any).
- Extra tokens beyond the declared args are silently ignored (except a final greedy which consumes them).
- Only factory args (`Args.SnArg`) can be greedy: `isGreedy` does an instanceof of `Args.SnArg` and queries `greedy()`.
- The generated usage renders the FULL path: `/root group leaf <required> [optional]`, with `...` appended to the last arg's name if greedy; a group's usage is `/root group <childA|childB>`.
- `resolve` returns a sealed `Resolution` (`Empty`, `Message`, `Run`), so the outcome is decided independently of the Bukkit context and unit-tested without a server (`NestedCommandTest`).

#### RootCommand.Condition (package-private record)
`record Condition(int index, Predicate<String> test)` - declarative condition over the raw token at `index`, created by `SubCommandBuilder.when(int, Predicate)`; a failing token rejects the invocation with the usage message BEFORE any typed parsing.

#### RootCommand.Sub (package-private class)
Immutable subcommand node built by `SubCommandBuilder`: `name` (trim + lowercase), `aliases` (lowercased, immutable copy), nullable `permission`, nullable `usage`, `description`, `visible`, `args` (immutable `LinkedHashMap`, declaration order is parse order), `requiredArgs`, `conditions`, nullable `executor`, and `children` (1.6, immutable copy; a non-empty list makes the node a GROUP that dispatches instead of running its args/executor). `static Sub of(String name, @Nullable String permission, String description, Consumer<CommandContext> executor)` fabricates the default subs (argless, visible, no children).

The `execute` outcome is a sealed `Resolution permits Empty, Message, Run` (1.6): `Empty` (zero args, the `onEmpty` hook or help applies), `Message(key, phs)` (a lang key to send), `Run(sub, context, path)` (the resolved leaf ready to run). `HelpLine(usage, description, permission)` is the flattened help record.

### SubCommandBuilder
`src/main/java/com/sn/lib/command/SubCommandBuilder.java`

Builder of a subcommand within an `SnCommands.RootBuilder` chain; `and()` returns to the root builder to declare the next subcommand or register the tree. A subcommand may own children (1.6), turning it into a GROUP that dispatches on the next token; a node that owns children is a group, so its own `arg`/`when`/`executes` are unused at runtime (declare those on the leaf children).

- `public SubCommandBuilder aliases(String... aliases)` - adds aliases (trim + lowercase).
- `public SubCommandBuilder permission(String permission)` - its own permission; without one it inherits the root's.
- `public SubCommandBuilder usage(String usage)` - usage line shown on argument errors; without one it is generated from the args.
- `public SubCommandBuilder description(String description)` - description (null -> "").
- `public SubCommandBuilder visible(boolean visible)` - whether it appears in tab-complete and the generated help.
- `public SubCommandBuilder arg(String name, Arg<?> arg)` - declares the next REQUIRED positional argument; declaration order is parse order. Declaring a required one after an optional throws `IllegalStateException`. A duplicate name throws `IllegalArgumentException`.
- `public SubCommandBuilder argOptional(String name, Arg<?> arg)` - declares an OPTIONAL trailing positional argument: it suggests and parses when the token is present but its absence never rejects the invocation. Optionals go last.
- `public SubCommandBuilder when(int index, Predicate<String> condition)` - declarative condition over the raw token at `index` (0-based among the subcommand's arguments); a failing token rejects with the usage before typed parsing. A negative index throws `IllegalArgumentException`.
- `public SubCommandBuilder executes(Consumer<CommandContext> executor)` - handler that runs once permission, argument count, conditions and typed parsing have all passed.
- `public SubCommandBuilder sub(String name, Consumer<SubCommandBuilder> spec)` (1.6) - declares a child nested under this one, turning this node into a GROUP: at runtime it dispatches on the next token among its children. The `spec` configures the child through this same builder API (arguments, permission, `executes`, or further nested `sub(...)` children). Returns THIS builder (so more children, or `and()` on a top-level group, can follow); the child is NOT part of the fluent chain, so it uses no `and()`. Validates a non-empty name and a non-null spec.
- `public SnCommands.RootBuilder and()` - returns to the root builder. Throws `IllegalStateException` on a nested child (`parent == null`): a child declared through `sub(name, spec)` is closed by its spec block, not by `and()`.

**Notes and gotchas**
- `requiredArgs` counts only the `arg(...)` calls; `argOptional` does not increment the counter, so the minimum count check never demands them.
- An absent optional does NOT land in the value map: `CommandContext.get(name)` on it throws `IllegalArgumentException`. For optionals it is better to check presence with `context.raw(index)` before reading.
- A group's own `arg`/`when`/`executes` are ignored at runtime (`dispatch` short-circuits on a non-empty `children`); put positional args and executors on the leaf children.

### Args
`src/main/java/com/sn/lib/command/Args.java`

Factory of typed `Arg` implementations for `SubCommandBuilder.arg`. Every arg built here carries default example suggestions and accepts the `suggestCurrent(Supplier)` decorator, which prepends the real current value: with an empty partial (or a matching prefix) the current value and the examples go first; a non-empty partial filters the base options via `StringUtil.copyPartialMatches` and sorts them. The vanilla client filters suggestions by the typed prefix, so an example that does not match never reaches the screen.

Constant:
- `SUGGESTION_CAP = 100` (private) - cap on list-backed suggestions (online players, `oneOf` options).

Factory methods (all `static`):
- `public static SnArg<Player> onlinePlayer()` - online player by exact name (`Bukkit.getPlayerExact`); rejects with `snlib.player-not-found` (`{value}`) and suggests up to 100 online names.
- `public static SnArg<UUID> offlinePlayerUuid()` - player UUID resolved STRICTLY without blocking: first an exact online match, then the local offline-players cache (`Bukkit.getOfflinePlayerIfCached`). A name absent from both rejects with `snlib.player-not-found`. `Bukkit.getOfflinePlayer(String)` is never used here because it can do a BLOCKING profile lookup on the main thread; remote resolution belongs to the consumer via the async scheduler. Suggests online names.
- `public static SnArg<String> oneOf(Supplier<Collection<String>> options)` - one value from a dynamic option set, matched case-insensitively and returned in its canonical form (the collection's, not the typed one); rejects with `snlib.invalid-value` and suggests up to 100 of the current options (skips nulls). Delegates to the sender-aware overload with a function that ignores the sender.
- `public static SnArg<String> oneOf(Function<CommandSender, Collection<String>> options)` (1.5.0) - sender-aware `oneOf`: the option set is computed per invoking sender, so BOTH the suggestions and the parse-time validation are scoped to that sender (via the `parse(CommandSender, raw)` path). The parse fallback without a sender queries the function with `null`.
- `public static SnArg<String> suggesting(Supplier<Collection<String>> options)` (1.5.0) - free-form single token whose only role is to SUGGEST a dynamic set: `parse` returns the input as-is (no restriction), so the handler keeps its own not-found handling. Suggests up to 100 of the current options. Also a sender-aware `suggesting(Function<CommandSender, Collection<String>>)` overload.
- `public static SnArg<Integer> intRange(int min, int max)` - integer in `[min, max]` (`Integer.parseInt` over the trimmed token); a non-number rejects with `snlib.invalid-number` and out-of-range with `snlib.out-of-range` (`{value}`, `{min}`, `{max}`). Suggests both bounds as examples.
- `public static SnArg<Double> doubleRange(double min, double max)` - double in `[min, max]`; accepts a decimal comma (replaces `,` with `.` before parsing); non-number -> `snlib.invalid-number`; `NaN` or out of range -> `snlib.out-of-range`. Suggests both bounds.
- `public static SnArg<Long> duration()` - compact duration like `"1d 2h 30m 15s"` parsed to milliseconds via `TimeUtil.parseMillis(String)`; zero or unparseable rejects with `snlib.invalid-value`. Example `30m`; options `30s`, `5m`, `1h`, `1d`.
- `public static SnArg<Boolean> bool()` - boolean accepting `true/yes/on` and `false/no/off` (case-insensitive); anything else rejects with `snlib.invalid-value`. Suggests `true` and `false`.
- `public static SnArg<String> string()` - a free single token, returned as-is. Un-hinted: its lone suggestion is the angle-bracket hint `<argName>` derived from the declared argument name (1.5.0; the old literal `text` example is gone).
- `public static SnArg<String> string(String hint)` (1.5.0) - free single token with an explicit suggestion hint, shown in angle brackets (`<hint>`); a value already bracketed is kept verbatim.
- `public static SnArg<String> greedy()` - free text consuming all remaining tokens as a single space-joined value. Only makes sense as the LAST declared argument. Un-hinted: its lone suggestion is the angle-bracket hint `<argName>` derived from the declared name (1.5.0).
- `public static SnArg<String> greedy(String hint)` (1.5.0) - greedy free text with an explicit angle-bracket suggestion hint.

#### Args.SnArg\<T\> (public abstract class)
An arg produced by the factory: default example suggestions plus the `suggestCurrent` decorator.

- `protected SnArg(List<String> examples, boolean greedy)` - constructor with the default examples and the greedy flag.
- `public final SnArg<T> suggestCurrent(Supplier<String> current)` - prepends the (supplied) real current value to the suggestions, before the examples and base options. Returns `this` (fluent).
- `public final boolean greedy()` - whether this arg consumes all remaining tokens as one value.
- `protected List<String> options(CommandSender sender)` - base options for the sender; empty when only the examples apply (the hook factories override with dynamic lists).
- `public final List<String> suggest(CommandSender sender, String partial)` - delegates to the 3-arg variant with a null arg name.
- `public final List<String> suggest(CommandSender sender, String partial, @Nullable String argName)` (1.5.0) - final implementation of the `Arg` contract: builds the list in current-value -> hint -> examples -> base-options order, with case-insensitive dedup; an empty partial does not filter the base options, a non-empty one filters them with `StringUtil.copyPartialMatches` and sorts. The `hintToken(argName)` step only contributes for a free-form arg in hint mode: the angle-bracket hint is the explicit `hint` when set, otherwise the declared `argName`, otherwise `value`; `bracket(...)` wraps it in `<>` unless already bracketed. The hint only survives the prefix filter while the partial is empty or itself starts with `<`, so a real typed value drops the hint.

The package-private fields `hintMode` and `hint` are set by the free-form `string()` / `greedy()` factories of the enclosing `Args`; every other factory leaves them off, so only free-form args ever suggest a hint.

**Notes and gotchas**
- The `suggestCurrent` supplier runs inside a `Throwable` try/catch: a failing supplier simply contributes no current value (it never breaks the tab).
- The 100 cap applies to the list-backed base options (online names, `oneOf`, `suggesting`); the current value, hint and examples do not count against the cap.
- The current value, hint and examples filter by case-insensitive prefix (`StringUtil.startsWithIgnoreCase`) just like the options, so the list never contains entries the client would discard.

### Arg
`src/main/java/com/sn/lib/command/Arg.java`

Typed command argument interface: it parses a raw token to `T` and provides its tab suggestions. Implementations come from the `Args` factory or the consumer.

- `T parse(String raw) throws ArgParseException` - parses the raw token to the typed value; throws `ArgParseException` when the token is invalid (it carries the lang key and local placeholders the command flow sends back to the sender).
- `default T parse(CommandSender sender, String raw) throws ArgParseException` (1.5.0) - parses with the invoking sender in scope, for args whose valid set is per-sender (the sender-aware `oneOf`); the default delegates to `parse(raw)`. The command flow ALWAYS calls this variant, so an implementation that ignores the sender needs only `parse(raw)`.
- `List<String> suggest(CommandSender sender, String partial)` - tab suggestions for the partial token, resolved for that sender.
- `default List<String> suggest(CommandSender sender, String partial, String argName)` (1.5.0) - suggestions with the declared argument name in scope, so an un-hinted free-form arg can derive an `<argName>` hint from it; the default delegates to `suggest(sender, partial)`. The command flow ALWAYS calls this variant, passing the declared arg name.

#### Arg.ArgParseException (public nested class, extends `Exception`)
Rejection of a raw token, expressed as a lang key plus its local placeholders.

- `public ArgParseException(String langKey, Ph... phs)` - `langKey` is a `snlib.*` key or a consumer one; `phs` are the local placeholders (null normalizes to an empty array; defensively cloned).
- `public String langKey()` - lang key of the error message.
- `public Ph[] phs()` - local placeholders of the message (defensive copy).

### CommandContext
`src/main/java/com/sn/lib/command/CommandContext.java`

A parsed subcommand invocation: the sender plus every declared argument already parsed by its `Arg`, indexed by the name given in the builder.

- `public CommandSender sender()` - the sender, player or console.
- `public Player player()` - the sender as a player; throws `IllegalStateException` ("The sender of this command is not a player") if it is not one.
- `public <T> T get(String name)` - parsed value of a declared argument; throws `IllegalArgumentException` when there is no value with that name (includes the absent-optional case).
- `public int getInt(String name)` - parsed value as int; accepts any numeric result (`Number.intValue()`) or parses the trimmed `toString()`.
- `public double getDouble(String name)` - parsed value as double; same tolerance as `getInt`.
- `public Player player(String name)` - parsed value as a player (sugar over `get`).
- `public @Nullable String raw(int index)` - raw token at `index` among the subcommand's arguments, or null when absent. It is the safe way to check optionals' presence.

**Notes and gotchas**
- `get` does an unchecked cast to the requested type: a wrong type blows up with `ClassCastException` at the consumer's call site.

### Page
`src/main/java/com/sn/lib/command/Page.java`

Generic chat text paginator over an immutable item list; it backs the generated command help and is reusable by consumers for any paginated listing. Pages are 1-based and out-of-range requests clamp to the nearest valid page, so an empty list still exposes one (empty) page. Rendering goes through Adventure: one `Component` line per item.

- `public static <T> Page<T> of(List<T> items, int pageSize)` - paginates `items` in pages of `pageSize` entries (minimum 1); immutable copy of the list.
- `public int size()` - total item count.
- `public int pageSize()` - entries per page.
- `public int totalPages()` - total page count, at least 1.
- `public int clamp(int page)` - clamps a requested page to `[1, totalPages()]`.
- `public List<T> page(int page)` - items of the given 1-based page, clamped to range.
- `public void send(CommandSender sender, int page, Function<T, Component> renderer)` - renders and sends the given page, one `Component` line per item.

### BukkitCommandRegistry (internal)
`src/main/java/com/sn/lib/command/internal/BukkitCommandRegistry.java`

Bridge between the `RootCommand` trees and Bukkit's command system, preferring the public API through two paths: (a) a command declared in the owner's plugin.yml receives its executor and tab completer via `plugin.getCommand(name)` (the `PluginCommandAdapter` adapter); (b) undeclared roots go through Paper's public `Bukkit.getCommandMap()`, with a WARN. In BOTH paths the dynamic aliases (builder varargs, an alias supplier, or the config-driven binding) are RECONCILED against the CommandMap's known commands (1.5.0). After EVERY register and unregister, online players receive `updateCommands()` so their client trees never show ghosts. Registered roots track in a `TenantRegistry<RootCommand>` (justified server-wide static) keyed by owning plugin: the tenant sweep detaches each command and removes the owner's whole key when the consumer disables. Dynamic aliases are re-sourced on every register pass (the reload flow re-registers the same root instance): the alias supplier is re-evaluated, aliases that appeared are added with `putIfAbsent` plus a WARN, and aliases that disappeared are removed. The supplier is stored in a `ConcurrentHashMap<RootCommand, AliasState>` keyed by root identity, so the `RootCommand` core stays immutable.

- `public static void bindAliasSupplier(RootCommand command, @Nullable Supplier<Collection<String>> supplier)` (1.5.0) - binds the dynamic-alias supplier of a root before it is registered; a null supplier means the builder / plugin.yml aliases are the sole source. Called by the command builder at build time so the supplier travels with the root into every register pass.
- `public static void register(JavaPlugin owner, RootCommand command)` - registers the root for its owner. Reload-safe: a root already registered by the same owner under the same name detaches and replaces first; re-registering the SAME instance keeps it in place and only reconciles its dynamic aliases. The plugin.yml path if the command is declared; otherwise a WARN ("Command '/x' not declared in Y's plugin.yml; dynamic registration via CommandMap") and CommandMap registration with prefix = the owner's lowercased name. Then `reconcileAliases`, then `updateCommands()`.
- `public static void unregister(JavaPlugin owner, RootCommand command)` - unregisters an owner's root and refreshes the client trees.
- `public static void unregisterAll(JavaPlugin owner)` - unregisters all of the owner's roots removing the owner's WHOLE KEY; the sweep callback detaches each command and refreshes the client trees.
- `public static void reregisterAll(JavaPlugin owner)` - re-registers each of the owner's roots in place (iterates a copy); it is the re-registration step of the reload flow. Every register pass re-sources the dynamic aliases and refreshes the online players' client trees.

**Internal logic**
- `sweep(RootCommand)` - the `TenantRegistry` callback: `detach` + `updateCommands`; it also runs when the tenant sweeper removes a disabled owner's key.
- `reconcileAliases(owner, command, declared)` (1.5.0, replaces `registerDynamicAliases`) - the desired set is the alias supplier's value when it has one (authoritative, config-driven), otherwise the builder / plugin.yml aliases; the root name and the plugin.yml declared aliases are always excluded (via `AliasReconciler.resolve`). It diffs the previous pass's `active` set against the desired one (`AliasReconciler.diff`): removed aliases are dropped from `getKnownCommands()` (both `alias` and `owner:alias`), added aliases are inserted with `putIfAbsent`. An alias whose `putIfAbsent` returns another command's entry is a COLLISION, kept out with a separate WARN ("Aliases [...] collide with existing commands; kept the existing ones"); the rest WARN as before ("Aliases [...] not declared in Y's plugin.yml; dynamic registration via CommandMap").
- `evaluate(owner, command, supplier)` (1.5.0) - runs the alias supplier defensively inside a `Throwable` catch; a null supplier or a failure returns null (falls back to the static aliases with a WARN on failure).
- `detach(RootCommand)` - unhooks the command from whichever path registered it: removes by identity the knownCommands entries pointing at it, calls `command.unregister(map)`, and if the declared `PluginCommand`'s executor is a `PluginCommandAdapter` of THIS root, clears the executor and tab completer. Also drops the root's `AliasState`.
- `updateCommands()` - `player.updateCommands()` for every online player (main thread).
- `AliasState` (private static final class) - per-root dynamic-alias state: the `supplier` and the `volatile List<String> active` currently-registered alias keys.
- `PluginCommandAdapter` (private record, `CommandExecutor` + `TabCompleter`) - delegates `onCommand` to `root.execute` and `onTabComplete` to `root.tabComplete`; it is the plugin.yml path's executor.

**Notes and gotchas**
- `putIfAbsent` on dynamic aliases means an alias already taken by another command is NOT overwritten: the alias is left with the existing command and reported as a collision, not operative for this root.
- The replacement check in `register` compares by identity (`existing != command`) in addition to the name, so `reregisterAll` can re-register the same instance without detaching itself, keeping its alias state for the diff.

#### AliasReconciler (internal, 1.5.0)
`src/main/java/com/sn/lib/command/internal/AliasReconciler.java`

Pure alias reconciliation helpers, free of Bukkit types so the decision layer is unit-testable without a running server. Source policy: a non-null `supplied` collection is AUTHORITATIVE (an empty list means "no aliases"); a null `supplied` means the source has no opinion and the `fallback` (builder / plugin.yml aliases) applies. Every resolved alias is trimmed, lowercased (`Locale.ROOT`), de-duplicated in encounter order, and stripped of the root name and of the plugin.yml declared aliases (owned by Bukkit, not by this dynamic layer).

- `static List<String> resolve(@Nullable Collection<String> supplied, Collection<String> fallback, String rootName, Collection<String> declaredAliases)` - the desired dynamic alias base keys.
- `static Diff diff(Collection<String> active, Collection<String> desired)` - `added` (in desired, not active) and `removed` (in active, not desired) base keys, compared case-insensitively; returned as the `Diff(List added, List removed)` record.

### SnLibCommand (internal)
`src/main/java/com/sn/lib/command/internal/SnLibCommand.java`

Diagnostic root command of the library itself: `/snlib` registers on the bootstrap's own context (the selfCtx `SnLibPlugin` creates via the in-package `SnLib.init`), through the SAME `sn.commands()` module every consumer uses; there is no loose `SnCommands` instance nor a second config. Every subcommand is tab-gated by its `snlib.admin.*` permission from plugin.yml. Final class with a private constructor (statics only).

- `public static void register(SnLibPlugin plugin, Sn selfCtx)` - registers the `/snlib` tree on the bootstrap's own context, with 5 declared subcommands:
  - `version` (permission `snlib.admin.version`) - shows the library version (`getPluginMeta().getVersion()`), the API level (`plugin.apiLevel()`) and the server version (`Bukkit.getBukkitVersion()` plus the `SnVersion.MAJOR.MINOR[.PATCH]` detection and the Folia flag).
  - `plugins` (permission `snlib.admin.plugins`) - lists the consumers hooked to SnLib, read from the public context registry (`SnLib.context(plugin) != null`, excluding SnLib itself), sorted and with a counter; if there are none, "No consumers are hooked to SnLib.".
  - `integrations` (permission `snlib.admin.integrations`) - lists the soft-dependency hooks registered via `SoftDependency.forEachRegistered` in the format `owner -> pluginName: active/inactive`, sorted and with a counter.
  - `iteminfo` (permission `snlib.admin.iteminfo`) - players only; dumps every PDC key of the main-hand item (air rejected with a message). Keys whose namespace belongs to a loaded plugin (a map of plugins by lowercased name, which is exactly the namespace of a `NamespacedKey(plugin, key)`) are read via `TagIo` (the library's string-tag convention); the rest fall to a raw `PersistentDataType.STRING` read, and non-string tags render as `<non-string tag>`. Sorted lines with a counter.
  - `reload` (permission `snlib.admin.reload`, usage `/snlib reload [plugin]`) - `argOptional("plugin", Args.oneOf(...))` whose option set is SnLib itself plus every hooked consumer, sorted.

**Reload contracts**
- Without arguments (or with SnLib's own name): `selfCtx.reloadAll()` reloads EXCLUSIVELY the library's own surface (its `plugins/SnLib/config.yml`: the `debug` and `bstats` keys) and never touches any consumer context. It confirms with "SnLib configuration reloaded (debug + bstats)." and reminds: "A reload never reloads classes: updating SnLib.jar requires a server restart.".
- With a plugin name: it delegates to THAT plugin's reload manager (`targetCtx.reloadAll()`); confirms "Configuration of X reloaded.". Defensive errors: "Plugin not found: X" if it does not resolve to a `JavaPlugin`, "Plugin X is not hooked to SnLib." if it has no context.
- Hard rule: a reload NEVER reloads classes; updating SnLib.jar demands a server restart.

**Notes and gotchas**
- Since the optional arg is `Args.oneOf(hookedConsumerNames)`, a name not in the current set (SnLib + hooked consumers) is rejected at PARSE time with `snlib.invalid-value` before reaching the handler: the internal "Plugin not found" / "not hooked" branches are defensive and nearly unreachable by direct typing.
- The reload handler reads the target with `context.raw(0)` (raw token), not the parsed value, to tolerate the optional's absence.
- By not calling `withoutDefaults()`, the `/snlib` tree also receives the injected default `help` (the default `reload` is replaced by the declared one); in practice the command exposes 6 entries, 5 declared plus the generated help.
- In the code, the Javadoc block describing the reload contract ended up hanging over `hookedConsumerNames()` (two consecutive Javadocs); it is merely cosmetic, the documented semantics are those of the `reload` method.

### TODOs and limitations
There are no TODO/FIXME markers in the module's files. Limitations documented in code/Javadoc:
- `Args.greedy()` only makes sense as the LAST declared argument of a subcommand; in another position the token join never activates (only the last index is treated as greedy).
- Fixed cap of 100 suggestions (`SUGGESTION_CAP`) on list-backed options (online players, `oneOf`); not configurable.
- `Args.offlinePlayerUuid()` does not resolve names outside the local cache: remote profile resolution belongs to the consumer via the async scheduler (deliberate decision to avoid blocking the main thread).
- `withoutDefaults()` transfers the obligation to the consumer: sn-core declares `reload` and `help` mandatory in every root, the library does not re-validate it.
- A reload never reloads classes: updating SnLib.jar requires a server restart (`/snlib reload`'s explicit contract).
- Dynamic aliases via `putIfAbsent`: if another command already owns the alias in the CommandMap, the alias is not operative for the root (it is not overwritten; it is reported as a collision WARN).
- Config-driven aliases (1.5.0): `aliasesFromConfig()` reads the list at `command.aliases`; when the key is set it is authoritative (an empty list clears the aliases), re-sourced on every reload. `aliases(Supplier)` is the code-level equivalent. `aliases(String...)` / plugin.yml aliases are the fallback. `aliasesFromConfig()` needs the config module; without it, or with the key absent, the fallback applies.
---

## 14. Database and Economy

Dual persistence module (SQLite/MySQL via HikariCP) and economy bridge of each consumer context (`Sn`). The central threading contract is that JDBC never touches the main thread: every operation runs on a dedicated per-plugin daemon executor (`<plugin>-db`) and results come back as `SnFuture`, whose canonical consumption path is `thenSync` (a main-thread hop with a plugin-enabled guard). The Hikari pool creates lazily on first use over that executor, so constructing `SnDb` never opens a connection. On top of the base sit `UpsertBuilder` (single-row upsert with a per-backend dialect) and `PlayerDataCache` (load-on-join, save-on-quit, ordered flush at teardown). On the economy side, `EconomyBridge` selects backends in registration order (Vault, then the command backend, then custom) with the "Economy always main thread" rule. HikariCP shades relocated to `com.sn.lib.libs.hikari`; the SQLite and MySQL drivers travel unrelocated as a single server-wide copy.

### SnDb
`src/main/java/com/sn/lib/db/SnDb.java`

SQLite/MySQL database module of a consumer context, pooled with HikariCP. Each instance is owned by a plugin: the pool name is `<plugin>-db` and the executor (a daemon fixed thread pool; 1 thread for SQLite, `max(1, poolSize)` for MySQL) pins its threads' context classloader to the consumer plugin's classloader and resets it to `SnDb`'s when the task finishes. The Hikari datasource creates lazily with double-checked locking (`dataSourceLock`) on the first `submit`, never in the constructor.

Public nested interfaces:

- `public interface SqlConsumer<T> { void accept(T value) throws SQLException; }` - SQL callback consuming a JDBC object.
- `public interface SqlFunction<T, R> { R apply(T value) throws SQLException; }` - SQL callback mapping a JDBC object to a result.

Constants: `SHUTDOWN_JOIN_SECONDS = 10L` (private; the shutdown join timeout, cited in `shutdown()`'s Javadoc).

Public methods:

- `public SnDb(Sn ctx, DbConfig config)` - builds the module: decides the thread count by type (SQLITE => 1), creates the executor with a thread factory that pins/resets the context classloader and registers each worker in a set for the shutdown's forced reset.
- `public DbConfig config()` - the parsed connection settings the module runs with.
- `public SnFuture<Void> bootstrap(Schema... schemas)` - creates all tables async (one `CREATE TABLE IF NOT EXISTS` per schema, in a single `Statement`). It marks the bootstrap phase (`bootstrapping = true`) until the future completes; while pending, main-thread joins count as bootstrap phase and log no WARN. The standard enable call is `bootstrap(...).orDisablePlugin()`.
- `public <R> SnFuture<R> query(String sql, SqlConsumer<PreparedStatement> binder, SqlFunction<ResultSet, R> mapper)` - runs a prepared query off the main thread and maps its `ResultSet`.
- `public SnFuture<Integer> update(String sql, SqlConsumer<PreparedStatement> binder)` - runs a prepared update off the main thread; the value is the affected row count.
- `public SnFuture<Void> transaction(SqlConsumer<Connection> work)` - runs the work inside a transaction off the main thread: `setAutoCommit(false)`, commit on success, rollback on `SQLException | RuntimeException | Error`, and restores the previous auto-commit in the `finally`.
- `public UpsertBuilder upsert(String table)` - a dialect-aware single-row upsert builder for the given table.
- `public <T> PlayerDataCache<T> playerCache(BiFunction<SnDb, UUID, T> loader, PlayerDataCache.Saver<T> saver)` - creates a per-player cache backed by this database (load-on-join via the shared listener, save-on-quit when dirty) and registers it in the internal list for the ordered flush. The loader runs on the owner plugin's async pool, never on the database's executor, so it can join this module's queries without deadlock.
- `public void flushPlayerCaches()` - saves every dirty entry of every cache created via `playerCache` and joins the queued writes (barrier). Ordered teardown: it runs right before `shutdown()` so no write is lost with the pool closing.
- `public void shutdown()` - the module's teardown, idempotent (`AtomicBoolean closed`): rejects new operations, joins pending work up to 10 seconds (`awaitTermination`), and if it did not finish forces `shutdownNow()` with WARN `"Pool <name> did not finish in 10s; forced shutdownNow()"` and resets each worker's context classloader to `SnDb`'s so a hung query never retains (pins) the consumer plugin's classloader. The Hikari pool closes last, under `dataSourceLock`.

Package-private methods (module infrastructure):

- `boolean inBootstrap()` - true while an enable-time `bootstrap` is still pending; consulted by `SnFuture.warnIfMainThreadJoin()`.
- `SnFuture<Void> fence()` - write barrier: completes when the executor has drained every task queued before it. Exact for SQLite's single-thread executor; for a multi-thread MySQL pool it is best-effort and `shutdown()` joins the stragglers. Completes immediately if the executor already rejected the submit (`RejectedExecutionException`).

#### Internal logic

- `submit(...)`: if `closed` is set, it completes exceptionally with `IllegalStateException("SnDb closed: <pool>")` without queuing. Each task takes a pool connection with try-with-resources and completes the future with the result or any `Throwable`.
- `createDataSource()` (SQLite profile): creates the file's parent directories, driver `org.sqlite.JDBC`, URL `jdbc:sqlite:<absolutePath>`, `maximumPoolSize=1`, and datasource properties `busy_timeout=5000` and `journal_mode=WAL` applied on the first connect.
- `createDataSource()` (MySQL profile): driver `com.mysql.cj.jdbc.Driver`, URL `jdbc:mysql://host:port/database?useSSL=<ssl>&allowPublicKeyRetrieval=true&characterEncoding=utf8`, user/password, `maximumPoolSize=max(1, poolSize)` (default 4), and prepared statement caching (`cachePrepStmts=true`, `prepStmtCacheSize=250`, `prepStmtCacheSqlLimit=2048`).

#### Notes and gotchas

- The executor pins the context classloader to the consumer plugin's so the JDBC drivers (which use the TCCL) resolve classes against the right plugin; the reset in each task's `finally` and the shutdown's forced reset prevent classloader leaks after a disable.
- SQLite stays pinned to 1 connection regardless of the config's `pool-size`; real parallelism only exists with MySQL.
- `dataSource()` throws `IllegalStateException` if pool creation is attempted with the module already closed (submit/shutdown race).

### DbConfig
`src/main/java/com/sn/lib/db/DbConfig.java`

A consumer's database connection settings, parsed from the `database` section of its main config. Recognized keys: `type` (sqlite or mysql, default sqlite), `file` (SQLite path relative to the data folder, or absolute), and for MySQL `host`, `port`, `database`, `username`, `password`, `pool-size` and `ssl`. An absent section or an unknown `type` fall back to SQLite at `<dataFolder>/database.db`.

Public enum:

- `public enum Type { SQLITE, MYSQL }` - supported backends.

Constants (private, they define the defaults): `DEFAULT_SQLITE_FILE = "database.db"`, `DEFAULT_MYSQL_PORT = 3306`, `DEFAULT_MYSQL_POOL_SIZE = 4`.

Public methods:

- `public static DbConfig load(JavaPlugin plugin, @Nullable ConfigurationSection section)` - parses the `database` section; a null section yields the SQLite defaults. An unknown `type` logs a WARN (`"invalid database.type: '<raw>', using sqlite"`) and falls to SQLite. The `database` default (MySQL) is the lowercased plugin name; `username` defaults to `root`, `password` defaults empty, `pool-size` clamps to a minimum of 1, `ssl` defaults to false.
- `public Type type()` - backend type.
- `public File sqliteFile()` - the resolved SQLite file; only meaningful when `type()` is SQLITE. If the config path is absolute it is used as-is, otherwise it resolves against the plugin's data folder.
- `public String host()` - MySQL host (default `localhost`).
- `public int port()` - MySQL port (default 3306).
- `public String database()` - MySQL database name.
- `public String username()` - MySQL user.
- `public String password()` - MySQL password.
- `public int poolSize()` - MySQL pool size; SQLite always stays pinned to a single connection.
- `public boolean ssl()` - whether the MySQL connection uses SSL.

### Schema
`src/main/java/com/sn/lib/db/Schema.java`

Declarative table definition consumed by `SnDb.bootstrap`: each schema yields an idempotent `CREATE TABLE IF NOT EXISTS` statement.

- `public static Schema of(String table, String... columnDefs)` - a schema from the table name plus the column definitions; the Javadoc's example: `Schema.of("players", "uuid VARCHAR(36) PRIMARY KEY", "coins BIGINT NOT NULL")`.
- `public static Schema raw(String table, String createSql)` - a schema from raw SQL for dialect-specific definitions; the SQL must still be idempotent (`CREATE TABLE IF NOT EXISTS ...`), not validated.
- `public String table()` - table name.
- `public String createSql()` - the statement `SnDb.bootstrap` executes.

### UpsertBuilder
`src/main/java/com/sn/lib/db/UpsertBuilder.java`

Dialect-aware single-row upsert, built via `SnDb.upsert(String)`. `keys` declares the conflict columns and `set` the updatable columns; both are repeatable and all values bind positionally with `setObject` (keys first, sets after). Table and column names are code-side identifiers (never user input) and are still validated against `[A-Za-z_][A-Za-z0-9_]*` as a hard stop (`IllegalArgumentException "Invalid SQL identifier: '<name>'"`).

- `public UpsertBuilder keys(String column, Object value)` - adds a conflict key column with its value; repeatable.
- `public UpsertBuilder set(String column, Object value)` - adds an updatable column with its value; repeatable.
- `public SnFuture<Integer> run()` - renders the dialect's statement and runs it off the main thread via `SnDb.update`; the value is the affected row count. Throws `IllegalStateException` if no column was declared with `keys()` (message: `"upsert(<table>) without keys(): declare at least one key column"`).

#### Internal logic (dialects)

- SQLite: `INSERT INTO t (...) VALUES (...) ON CONFLICT(keys) DO UPDATE SET col=excluded.col`; without `set` columns it degenerates into `... DO NOTHING`. It requires a UNIQUE or PRIMARY KEY constraint over the key columns.
- MySQL: `INSERT INTO t (...) VALUES (...) ON DUPLICATE KEY UPDATE col=VALUES(col)`; it relies on the table's own unique indexes. Without `set` columns, since MySQL demands at least one assignment, it emits a no-op refresh of the first key: `key=VALUES(key)` (literal code comment: "MySQL demands at least one assignment: no-op refresh of the first key").

### PlayerDataCache
`src/main/java/com/sn/lib/db/PlayerDataCache.java`

Per-player data cache tied to an `SnDb`, created via `SnDb.playerCache` (package-private constructor). Lifecycle: the shared `PlayerJoinEvent` listener fires `load` for each registered cache of every owner, and the quit cleanup listener (`QuitCleanupListener`) saves the entry if dirty and removes it. The loader runs on the owner plugin's async pool (never on the database's executor, so it can join the module's queries) and its result installs on the main thread; the saver runs on the calling thread and is expected to queue async writes like `SnDb.upsert`.

Public nested interface:

- `public interface Saver<T> { void save(SnDb db, UUID uuid, T value); }` - persists a player's value; typically an off-main queued `SnDb.upsert`.

Public methods:

- `public static Listener joinListener()` - the shared `PlayerJoinEvent` listener, owned by SnLib, that fires load-on-join for all registered caches of all owners (via the static `TenantRegistry` `CACHES`). The `registerEvents` happens exactly once in the SnLibPlugin bootstrap.
- `public @Nullable T get(UUID uuid)` - the cached value; null while not loaded (or when the loader returned no data).
- `public void load(UUID uuid)` - loads the player's value async and installs it on the main thread. No-op if the context is shutting down, the value is already cached, or a load is already in flight (dedup via `pendingLoads.putIfAbsent`).
- `public void markDirty(UUID uuid)` - marks the player's loaded value as pending persistence; a no-op while not loaded (`data.containsKey`).
- `public void invalidate(UUID uuid)` - discards the player's entry WITHOUT saving and kills any in-flight load (removes the `pendingLoads` ticket, the data and the dirty mark).
- `public SnFuture<Void> saveAll()` - saves every dirty entry through the saver (iterating a copy of the dirty set, with `dirty.remove` as the atomic claim) and returns a barrier future (`db.fence()`) that completes when the writes queued up to that point drained; the ordered teardown joins it before `SnDb.shutdown()`.

Package-private methods:

- `void unload(UUID uuid)` - the quit/kick cleanup registered in `QuitCleanupListener`: removes the pending ticket, takes the value out and, if it was dirty and non-null, saves it. Idempotent, because a kicked player fires kick and quit.

#### Internal logic (mutation-sequence guard)

`pendingLoads: ConcurrentHashMap<UUID, Long>` plus an `AtomicLong sequence` form the mutation-sequence guard: each `load` takes a fresh ticket (`sequence.incrementAndGet()`) and maps it with `putIfAbsent` (an already-mapped ticket dedupes concurrent loads into a single in-flight attempt). The async result only installs on the main thread if `pendingLoads.remove(uuid, ticket)` removes ITS own ticket; any intervening mutation (invalidate, unload on quit) drops the ticket, so data already in flight can never clobber later state. If the load fails, the `whenComplete` removes the ticket so retries are not blocked. Saver failures are caught and logged WARN `"Player data save failed (<uuid>): <t>"` without propagating.

#### Notes and gotchas

- `CACHES` is a server-wide static justified by Javadoc: caches keyed by owning plugin, resolved by the shared join listener and swept by whole key when the owner disables.
- A loader returning null installs nothing: the player stays "not loaded" and `markDirty` will be a no-op for them.
- The `saveAll()` barrier is exact on SQLite (1-thread executor) and best-effort on multi-thread MySQL; `SnDb`'s shutdown joins the stragglers.

### SnFuture
`src/main/java/com/sn/lib/db/SnFuture.java`

The result of an asynchronous database operation; it wraps a `CompletableFuture` (the `delegate` field, package-private) together with the origin `Sn` context and `SnDb` (`@Nullable` since v1.1: futures created via `wrap` have no origin db). Package-private constructor: within the library only the db module creates direct instances; the public `wrap` factory covers the rest.

Constants: `JOIN_WARN_FRAMES = 5` (private; number of stack frames included in the join WARN).

- `public static <T> SnFuture<T> wrap(Sn ctx, CompletableFuture<T> future)` - (v1.1) wraps an arbitrary `CompletableFuture` in SnFuture's consumption surface (`thenSync`/`exceptionally`/`join`) of the given context; used by library modules outside the db package (`SnPapi.applyOnMain`) and available to consumers. The resulting SnFuture has no origin `SnDb` (null db), so the join WARN is never suppressed by a bootstrap phase.
- `public SnFuture<T> thenSync(Consumer<T> consumer)` - consumes the value on the main thread via the owner's scheduler; the hop is skipped when the owning plugin is already disabled (the scheduler's is-enabled guard), and a failed future logs a WARN instead of reaching the consumer. Returns `this` (chainable).
- `public SnFuture<T> exceptionally(Consumer<Throwable> handler)` - observes a failure with the completion wrappers (`CompletionException` / `ExecutionException`) unwrapped down to the real cause.
- `public T join()` - blocks until the value is available and returns it. Intended ONLY for the shutdown flush and the enable bootstrap: any other main-thread join (an uncompleted future, outside `ctx.isShuttingDown()` and `db.inBootstrap()`) logs a WARN `"SnFuture.join() on the main thread outside shutdown/bootstrap:"` with the first 5 caller frames.
- `public SnFuture<T> orDisablePlugin()` - disables the owning plugin when this future fails; the standard gate of `SnDb.bootstrap`. Logs SEVERE `"Critical database operation failed; disabling <plugin>: <cause>"`. If the failure arrives on the main thread it disables inline; otherwise it schedules the disable with `scheduler().sync(...)`, and if that scheduling throws `IllegalPluginAccessException` (plugin already disabled during scheduling) it logs WARN `"Deferred disable discarded: plugin already disabled during scheduling"` and discards.

#### Notes and gotchas

- The join WARN is suppressed in four cases: an already-completed future, a non-main thread, a shutting-down context, or the origin `SnDb`'s bootstrap phase (`db != null && db.inBootstrap()`; `wrap` futures have no db). The stack trims starting at frame 3 to skip the internal `getStackTrace`/`join` frames.

### EconomyBridge
`src/main/java/com/sn/lib/economy/EconomyBridge.java`

Economy service of a consumer context, accessible via `sn.economy()`. Operations resolve the FIRST available backend in registration order (`LinkedHashMap`): Vault (registered in the constructor), then the command backend configured with `useCommandBackend`, then any custom `Backend` via `registerBackend`. With no backend available, every operation warns ONCE and reports failure (balance `0`, futures `false`). Economy access is main-thread only: `getBalance(Player)` must run on the main thread (off it, it returns `0` with one WARN per CALL SITE, v1.1), while writes may be called from any thread because each backend does the main hop itself.

Public nested interface:

- `public interface Backend` - pluggable economy backend. Contract: Economy access always on the main thread; `getBalance` is only invoked on main, and writes invoked off-main must hop themselves, as the built-in backends do.
  - `double getBalance(OfflinePlayer player)` - current balance; main-thread only.
  - `CompletableFuture<Boolean> give(OfflinePlayer player, double amount)` - deposits; the future completes with the real success.
  - `CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount)` - withdraws only if the player can afford it; the future completes with the withdrawal's real success.
  - `default boolean available()` - true when the backend can serve operations right now (default true).

Constants: `VAULT = "vault"`, `COMMAND = "command"` (private; registration names of the built-in backends).

Public methods:

- `public EconomyBridge(Sn ctx)` - creates the bridge and registers the Vault backend. `VaultBackend` is the isolated hook class: its constructor links against the Vault API, so with Vault absent the instantiation throws a linkage error caught here (`catch (Throwable)`, never propagates) and the bridge starts without that backend (a debug log, not a WARN).
- `public double getBalance(Player player)` - current balance through the active backend; main-thread only. Off the main thread it returns `0` with one WARN per call site (v1.1): `"getBalance called off the main thread from <Class#method:line>; returning 0 (Economy always main thread)"`, deduped in `Set<String> warnedOffMainSites` (`ConcurrentHashMap.newKeySet()`) with the tag from the private helper `callSiteTag()` (StackWalker, first frame whose class is not `EconomyBridge`, a pattern deliberately duplicated from `SnCompat.callSiteTag`, orElse `"unknown"`); the StackWalker cost is only paid on the buggy off-main path. With no backend available, `0` with the no-backend WARN.
- `public CompletableFuture<Boolean> give(Player player, double amount)` - deposits `amount`. The future completes with the real success; false on an invalid amount (non-finite or non-positive, logged only in debug) or with no backend available.
- `public CompletableFuture<Boolean> tryTake(Player player, double amount)` - withdraws `amount` only if affordable. The future completes with the withdrawal's REAL success; false on an invalid amount, insufficient funds or no backend.
- `public synchronized void registerBackend(String name, Backend backend)` - registers (or replaces) a backend under `name` (lowercased). Selection walks the backends in first-registration order, so Vault keeps priority, the command backend follows and customs go after unless they replace one of those names.
- `public void useCommandBackend(String giveCommand, String takeCommand, String balancePlaceholder)` - configures the command-dispatch fallback backend. The templates accept the `%player%` and `%amount%` tokens; `balancePlaceholder` is the PAPI placeholder that reports the player's balance (used by `tryTake` to verify affordability and the post-take result).
- `public boolean available()` - true when at least one registered backend is available.

#### Notes and gotchas

- The "no backend" WARN is emitted once per instance (`AtomicBoolean warnedNoBackend`): `"No economy backend available: install Vault or configure useCommandBackend(...); operations return false"`. The off-main WARN dedupes PER CALL SITE (`warnedOffMainSites`, v1.1): every buggy call point warns once with its `Class#method:line`.
- `active()` is `synchronized` and re-evaluates each backend's `available()` on every operation, so a Vault appearing late (or dropping out) changes the selection dynamically.

### VaultBackend (internal)
`src/main/java/com/sn/lib/economy/internal/VaultBackend.java`

Vault-backed economy backend of a consumer context. The `Economy` provider resolves through a per-owner `SoftDependency<Economy>` over the `RegisteredServiceProvider`, so a disabled Vault never leaks a linkage error and the consumer's disable releases the hook. It is the ISOLATED hook class: it only links when the Vault API classes are present, which is why `EconomyBridge` instantiates it under `catch (Throwable)` and a Vault-less server simply runs without this backend. Every write hops to the main thread and reports the real result of `EconomyResponse.transactionSuccess()`.

- `public VaultBackend(Sn ctx)` - creates the hook: `SoftDependency.of(ctx.plugin(), "Vault", VaultBackend::resolveProvider)`, where the resolver looks up the `RegisteredServiceProvider<Economy>` in Bukkit's `ServicesManager`.
- `public boolean available()` - true when the Economy provider resolves (with the on-use re-resolution described below).
- `public double getBalance(OfflinePlayer player)` - balance via `economy.getBalance(player)`; `0` if there is no provider or Vault throws any `Throwable` (WARN `"Vault failed reading the balance: <t>"`).
- `public CompletableFuture<Boolean> give(OfflinePlayer player, double amount)` - `depositPlayer` on the main thread; the result is `transactionSuccess()`. False without a provider.
- `public CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount)` - on the main thread: false without a provider or `!economy.has(player, amount)`; if affordable, `withdrawPlayer` and returns `transactionSuccess()`.

#### Internal logic

- On-use re-resolution (`economy()`): the Economy provider can register the service AFTER the first access (Vault already enabled, late service); a miss cached by the `SoftDependency` is invalidated (`vault.invalidate()`) and re-resolved on the next use instead of staying null for the whole session (literal code comment).
- `onMain(...)`: if already on the main thread it executes inline; otherwise it schedules with `ctx.scheduler().sync(...)` and, if scheduling throws `IllegalPluginAccessException` (owner already disabled), it completes the future with `false`.
- `runSafe(...)`: any `Throwable` from the operation logs as WARN (`"Vault economy operation failed: <t>"`) and returns `false`; it never propagates to the caller.

### CommandBackend (internal)
`src/main/java/com/sn/lib/economy/internal/CommandBackend.java`

Command-dispatch economy backend for servers without Vault. Give and take run console commands built from the configured templates (`%player%` and `%amount%` tokens). `tryTake` reads the balance through the configured PAPI placeholder on the main thread, rejects unaffordable withdrawals, and verifies the post-take balance against an epsilon so the future completes with the operation's real success. Every operation hops to the main thread (PAPI and command dispatch are main-thread only).

Constants: `EPSILON = 1.0E-3` (private; double rounding tolerance when comparing balances before and after a take), `SECTION = '§'` (private; color code marker to strip).

- `public CommandBackend(Sn ctx, String giveCommand, String takeCommand, String balancePlaceholder)` - builds the backend with the two command templates and the balance placeholder.
- `public double getBalance(OfflinePlayer player)` - balance via the PAPI placeholder; `0` when the read yields NaN (unreadable).
- `public CompletableFuture<Boolean> give(OfflinePlayer player, double amount)` - dispatches the give command as console on the main thread; the result is `Bukkit.dispatchCommand`'s boolean.
- `public CompletableFuture<Boolean> tryTake(OfflinePlayer player, double amount)` - on the main thread: reads the prior balance (false if NaN or `before + EPSILON < amount`), dispatches the take command (false if the dispatch fails), re-reads the balance and returns true only if `after <= before - amount + EPSILON` (post-take verification: the reported success is the real one, not the dispatch's).

#### Internal logic

- `readBalance(...)`: resolves the placeholder via `ctx.papi().apply(online, balancePlaceholder)` (casting to an online `Player` if possible). Returns NaN when the result is null or still contains `%` (PAPI absent or unresolved token), or when `NumberFormatter.parseFormatted` throws `NumberFormatException` after stripping decorations. The unreadable WARN is emitted once (`AtomicBoolean warnedUnreadable`): `"Unreadable balance via '<placeholder>' (result: '<resolved>'); the command backend cannot verify balances"`.
- `stripDecorations(...)`: drops color codes (the `§` character plus the next one) and currency decorations, keeping digits, letters (suffixes like k/M), `.`, `,` and `-`.
- `dispatch(...)`: if the player has no known name (`getName() == null`) it logs WARN `"Player with no known name; economy command skipped"` and returns false. It replaces `%player%` and `%amount%` (the amount formatted with `BigDecimal.valueOf(amount).stripTrailingZeros().toPlainString()`, never scientific notation) and dispatches as console; any `Throwable` from the dispatch logs a WARN and returns false.
- `onMain(...)` / `runSafe(...)`: the same pattern as `VaultBackend` (hop via the owner's scheduler, `IllegalPluginAccessException` => false, `Throwable` => WARN `"Command backend operation failed: <t>"` and false).

#### Notes and gotchas

- The post-take verification exists because a command dispatch's success does NOT guarantee the economy plugin actually deducted (misconfigured command, player with no account, etc.); the epsilon absorbs double rounding.
- If the balance placeholder does not resolve (NaN), `tryTake` always returns false: without a balance read there is no way to verify affordability or the outcome.

### TODOs and limitations

There are no TODO/FIXME/XXX/HACK markers in any file of the scope. Limitations documented in the code itself:

- `SnDb.fence()` (and therefore the barrier of `PlayerDataCache.saveAll()` / `flushPlayerCaches()`) is exact only with SQLite's single-thread executor; with a multi-thread MySQL pool it is best-effort and depends on `shutdown()` joining the stragglers.
- SQLite always stays pinned to `maximumPoolSize=1`; the config's `pool-size` key only affects MySQL.
- The SQLite upsert form (`ON CONFLICT(keys)`) requires a UNIQUE or PRIMARY KEY constraint over the key columns; the MySQL form depends on the table's unique indexes (the declared keys are not used in the `ON DUPLICATE KEY UPDATE` clause).
- `CommandBackend` cannot verify balances if the configured PAPI placeholder does not resolve to a readable number (a single WARN and `tryTake` always returns false).
- `EconomyBridge.getBalance` off the main thread does not throw: it returns `0` after each call site's first WARN (v1.1: the per-call-site dedup makes every buggy call point visible, but the fail-open return can still mask threading bugs in the consumer).
---

## 15. BossBars, Holograms, Leaderboards and Discord

Four consumer-context services, accessible as `sn.bossbars()`, `sn.holograms()`, `sn.leaderboards()` and `sn.discord()`. The first three register their entries in a static `TenantRegistry` keyed by owning plugin: an owner's disable sweeps bars, holograms and refresh tasks even if the plugin never cleaned up, and the context teardown calls `hideAll()` / `deleteAll()` / `drain()` as appropriate. BossBars uses pure Adventure (zero packets), Holograms uses real `TextDisplay` entities (1.19.4+, zero NMS) with a PDC mark and orphan purging, Leaderboards caches immutable rankings behind a volatile reference with lock-free reads fit for PAPI, and Discord dispatches webhooks through a FIFO queue off the main thread with the JDK's `HttpClient` (zero external dependencies).

### BossBarUtil

`src/main/java/com/sn/lib/bossbar/BossBarUtil.java`

Per-context boss bar service. Bars are Adventure `BossBar` instances shown per player via the Audience API. Titles render through the SnText pipeline (`[rgb]` and `[center]` included). A player who disconnects or is kicked is automatically removed from all the context's bars (via `QuitCleanupListener.register(ctx.plugin(), this::dropViewer)` in the constructor). Operations on an unknown id log ONE single WARN per id (`"Unknown bossbar '<id>': the operation is ignored (missing create(\"<id>\").build())"`) and do nothing.

Public methods:

- `public BossBarUtil(Sn ctx)` - Constructor; registers the context's quit/kick cleanup.
- `public Builder create(String id)` - Starts a bar definition under `id`; nothing registers until `Builder.build()`.
- `public void show(Player viewer, String id)` - Shows the bar to the viewer (`viewer.showBossBar`); an unknown id WARNs once and is a no-op.
- `public void hide(Player viewer, String id)` - Hides the bar for that viewer only; the rest keep seeing it.
- `public void setText(String id, String text)` - Re-renders the bar's title through the SnText pipeline (`SnText.color`).
- `public void setProgress(String id, float progress)` - Sets the progress, clamped to 0..1; a running timer overwrites it on the next tick.
- `public void timer(String id, Duration duration, boolean countdown)` - Animates the progress linearly across `duration`: with `countdown` true it drains from 1 to 0, otherwise it fills from 0 to 1. A new timer replaces the previous one; when the duration expires the timer stops and the bar stays visible at its final progress until hidden.
- `public void cancelTimer(String id)` - Stops the bar's timer if there is one; the current progress is kept.
- `public void remove(String id)` - Hides the bar from all viewers, stops its timer and unregisters the id (also from the tenant registry).
- `public void hideAll()` - Hides all the context's bars from all viewers and stops their timers; the bars stay registered and can be re-shown. The context teardown calls it before releasing the owner's registrations.

#### BossBarUtil.Builder

Bar definition builder returned by `create(String)`. Defaults: text `""`, progress `1.0f`, color `BossBar.Color.WHITE`, overlay `BossBar.Overlay.PROGRESS`.

- `public Builder text(String text)` - The bar's title, rendered by SnText (`[rgb]` included); null normalizes to `""`.
- `public Builder progress(float progress)` - Initial progress, clamped to 0..1 (default 1).
- `public Builder color(BossBar.Color color)` - Bar color (default WHITE); null keeps the default.
- `public Builder overlay(BossBar.Overlay overlay)` - Bar overlay (default PROGRESS); null keeps the default.
- `public BossBar build()` - Builds and registers the bar under its id, replacing (and hiding/sweeping) any previous bar with the same id. The bar starts with no viewers; use `show`.

Internal logic:

- Static `TenantRegistry<BarEntry> BARS` with sweep `BossBarUtil::sweep`: when the owning plugin disables, each entry sweeps (timer cancelled, all viewers removed).
- The timer runs with `ctx.scheduler().timer(1L, 2L, ...)` (delay 1 tick, period 2 ticks) and computes the fraction against wall clock (`System.currentTimeMillis()`), not against ticks; the effective minimum duration is 50 ms.
- `dropViewer(UUID)`: if the player is still resolvable via `Bukkit.getPlayer`, it uses `player.hideBossBar`; otherwise it iterates each bar's `viewers()` and does `removeViewer` by UUID matching.
- `viewersOf(BossBar)` takes a snapshot of the viewers as an `Audience` list so removal is possible while iterating without ConcurrentModification.
- `cancelTimer(BarEntry)` swallows `Throwable` from the cancel: during shutdown the scheduler may no longer exist.

Notes and gotchas:

- `setProgress` on a bar with an active timer is ephemeral: the timer rewrites the progress on its next tick (documented in the method's Javadoc).
- Zero packets: everything goes through the Adventure/Paper Audience API.
- The unknown-id WARN emits once per id (the `warnedIds` set), so loops do not spam the console.

### HologramUtil

`src/main/java/com/sn/lib/hologram/HologramUtil.java`

Per-context hologram service. Holograms are real `TextDisplay` entities (1.19.4+ API, zero NMS and zero packets). Each entity carries the PDC mark `snlib:snlib_hologram` (key `NamespacedKey("snlib", "snlib_hologram")`, type `PersistentDataType.STRING`) with the value `<PluginName>:<id>`. A marked entity whose marker no live registration claims is an orphan (a previous run, a crash, or a delete that could not reach an unloaded chunk) and the internal purge removes it; entities of live markers re-bind their fresh instance after a chunk reload. Lines render through SnText with PAPI resolved serverside (null viewer). An unknown id WARNs once (`"Unknown hologram '<id>': the operation is ignored (missing spawn(\"<id>\", ...))"`).

Public methods:

- `public HologramUtil(Sn ctx)` - Constructor.
- `public void spawn(String id, Location location, List<String> lines)` - Spawns hologram `id` at the location, replacing (previous delete) an earlier hologram with the same id. The entity is created with `setPersistent(true)`, CENTER billboard by default, the PDC mark and the already-rendered text. A location with a null world logs a WARN (`"Hologram '<id>': invalid location, spawn ignored"`) and does nothing. The expected model is re-spawning on every enable: the previous run's entity purges as an orphan when its chunk loads.
- `public void setLines(String id, List<String> lines)` - Replaces the lines and re-renders immediately.
- `public void setBillboard(String id, Display.Billboard billboard)` - The entity's billboard mode (default `CENTER`, the classic hologram behavior); null is a no-op.
- `public void refreshEvery(String id, long intervalTicks)` - Re-renders the hologram every `intervalTicks` (PAPI tokens included); an interval of 0 or less cancels the refresh. One task handle per hologram; a new interval replaces the previous task.
- `public void showTo(Player viewer, String id)` - Makes the hologram visible again for that viewer (`viewer.showEntity(plugin, display)`); holograms are visible by default.
- `public void hideFrom(Player viewer, String id)` - Hides the hologram from that viewer only (`viewer.hideEntity`). Per-viewer visibility is NOT persistent: it resets when the entity re-binds after a chunk reload or a re-spawn.
- `public void delete(String id)` - Deletes the hologram: cancels its refresh task and removes the entity. If the entity is in an unloaded chunk it cannot be touched here; since its marker stops being live, the orphan purge removes the persisted copy at the chunk's next load.
- `public void deleteAll()` - Deletes all the context's holograms; the context teardown calls it.
- `public static boolean adopt(TextDisplay display)` - The adoption contract at chunk load, used by the orphan purge (internal listener and startup scan). A display WITHOUT the lib's marker is foreign and left alone (returns true). A marked display whose marker claims a live registration re-binds as that hologram's fresh instance (same UUID after a chunk reload, or a never-bound entry) and gets its current text (`lastText`) and billboard reapplied; returns true. Returns **false** when the display carries a lib marker that no live hologram claims, or that is already bound to ANOTHER entity (a stale duplicate): the caller must remove those orphans.

Internal logic:

- Static `TenantRegistry<HologramEntry> HOLOGRAMS` with sweep `HologramUtil::sweep`: it cancels the refresh task and removes the entity if reachable (swallows `Throwable` if the entity is already invalid or its chunk unloaded; the orphan purge covers that case).
- Render: each line goes through `SnText.color(SnText.normalizePapiOutput(ctx.papi().apply(null, line)))` and lines join with `Component.join(JoinConfiguration.newlines(), ...)` into a single multiline `TextDisplay`. PAPI resolves with a null viewer (serverside).
- `findByMarker(String)` walks the whole tenant registry (`HOLOGRAMS.forEachOwner`) looking for the entry whose `marker` matches; it stops at the first match.
- `HologramEntry` keeps `marker` final and volatile fields: `rawLines`, `billboard`, `entity`, `lastText` (the already-rendered Component, reapplied on adoption) and `refreshTask`.
- `copyOf(lines)` normalizes null to `List.of()` and makes an immutable copy (`List.copyOf`).

Notes and gotchas:

- The PDC marker makes the system survive crashes: there is no state file, the source of truth is the persisted entity itself plus the set of live in-memory registrations.
- `adopt` is static and crosses contexts: it resolves against the global registry, not a particular `HologramUtil`.
- The "bound to another entity" case (false return) covers stale duplicates: if a live tracked entity with another UUID already exists for that marker, the freshly loaded one is an old copy and gets purged.

### HologramChunkListener

`src/main/java/com/sn/lib/hologram/internal/HologramChunkListener.java`

Shared purge of orphan hologram entities, owned by SnLib. Enrolled in the ListenerHub; the `registerEvents` happens SOLELY in the SnLibPlugin bootstrap (a single server-wide instance, not one per consumer).

- `@EventHandler public void onEntitiesLoad(EntitiesLoadEvent event)` - Listens to `EntitiesLoadEvent`, the chunk load signal that actually carries the chunk's entities (ChunkLoadEvent fires BEFORE they attach); delegates to `purge(event.getEntities())`.
- `public static int purge(Collection<Entity> entities)` - Purges hologram orphans among the given entities: every `TextDisplay` for which `HologramUtil.adopt` returns false is removed (`display.remove()`). Returns how many it removed. Claimed ones re-bind their fresh instance via `adopt`.
- `public static int purgeLoadedWorlds()` - Runs the same pass over every `TextDisplay` of every loaded world; returns how many it removed. The bootstrap runs it DEFERRED to the first tick after enabling, so worlds are loaded and every consumer has had its chance to register its holograms (running earlier would purge legitimate holograms not yet re-spawned).

### LeaderboardCache

`src/main/java/com/sn/lib/leaderboard/LeaderboardCache.java`

Per-context leaderboard cache. Each board pairs an id with an asynchronous query fired at a fixed interval: the supplier runs on the MAIN thread and must only DISPATCH the async work (an `SnDb` query already does), and the fresh result folds into an immutable `Snapshot` swapped behind a volatile reference. Reads (`getTop`, `positionOf`, `valueOf`) are lock-free cache lookups, safe for PlaceholderAPI resolvers under their cache-only contract. Relevant internal constant: `MIN_REFRESH_TICKS = 20L` (a one-second refresh floor: a leaderboard query is never a per-tick loop).

Public methods:

- `public LeaderboardCache(Sn ctx)` - Constructor.
- `public void register(String id, Duration refreshInterval, Supplier<SnFuture<List<Entry>>> query)` - Registers the board and arms its periodic refresh (first run next tick, `timer(1L, periodTicks, ...)`), replacing (and sweeping) any previous board under the same id. The interval converts to ticks rounding up (`(millis + 49) / 50`) and clamps to a minimum of 20 ticks (1 second); a null `refreshInterval` counts as 0 and falls to the minimum. Until the first query completes, every read sees an empty snapshot. If the owner disabled while the timer was being armed (`IllegalPluginAccessException`), the board stays empty without blowing up.
- `public void unregister(String id)` - Cancels the periodic refresh and forgets the id; unknown ids are a no-op.
- `public List<Entry> getTop(String id, int n)` - Top `n` entries of the current snapshot, best first; unknown id -> empty list.
- `public int positionOf(String id, UUID uuid)` - The player's 1-based position; 0 when unranked or the id is unknown.
- `public double valueOf(String id, UUID uuid)` - The player's cached value; 0 when unranked or the id is unknown.
- `public boolean exposePlaceholders(String identifier)` - Registers a PlaceholderAPI expansion exposing all of this cache's boards: `%<identifier>_top_<id>_<n>_name%`, `%<identifier>_top_<id>_<n>_value%` and `%<identifier>_pos_<id>%`. The resolvers only read the in-memory snapshots. Returns false with a WARN when PlaceholderAPI is absent or rejects the expansion (via `ctx.papi().expansion(identifier)`).

#### LeaderboardCache.Entry

- `public record Entry(UUID uuid, String name, double value)` - One ranked row: the player's uuid, display name and ranked value. The compact constructor normalizes a null `name` to `""`.

#### LeaderboardCache.Snapshot

Immutable ranking snapshot; pure logic, no Bukkit. Instances never mutate: the cache swaps whole snapshots behind a volatile reference, so readers are lock-free.

- `public static Snapshot empty()` - A snapshot with no entries (the `EMPTY` singleton).
- `public static Snapshot of(List<Entry> entries)` - Builds the snapshot from unordered entries: null rows are skipped, the rest sorts by value DESCENDING with name ASCENDING as tiebreak (the sort is stable), and a uuid appearing twice keeps its BEST (first) position (`putIfAbsent` over the position map). An empty list or result -> `EMPTY`.
- `public List<Entry> top(int n)` - The first `n` entries, best first; the full ranking when `n` exceeds it; `n <= 0` -> empty list.
- `public int positionOf(@Nullable UUID uuid)` - The uuid's 1-based position; 0 when unranked (or a null uuid).
- `public double valueOf(@Nullable UUID uuid)` - The uuid's ranked value; 0 when unranked.
- `public int size()` - Ranked entry count.

Internal logic:

- `refresh(Board)`: bails if the board was cancelled or `ctx.isShuttingDown()`. If the supplier throws, WARN `"Leaderboard query '<id>' threw an error: <t>"` and the cycle is skipped; if it returns null, WARN `"Leaderboard query '<id>' returned null; refresh skipped"`. The result folds with `future.thenSync(...)` (back on the main thread) and the snapshot only swaps if the board is still alive and the entries are non-null.
- `resolveTop(String rest)` parses `<id>_<n>_(name|value)` from the END (two `lastIndexOf('_')`), so the board id may contain underscores. An invalid token (non-numeric rank, unknown id, rank < 1, a suffix other than name/value) -> null (PAPI leaves the token unresolved). A valid rank beyond the snapshot size -> `""` (empty string).
- `formatValue(double)`: integral values render without the trailing `.0`.
- `sweep(Board)`: marks `cancelled = true` and cancels the task handle (swallowing `Throwable` at shutdown). The static `TenantRegistry<Board> BOARDS` guarantees the sweep on the owner's disable even if it never called `unregister`.

Notes and gotchas:

- Key threading contract: the supplier runs on the main thread; it must NEVER block, only dispatch (the Javadoc emphasizes it: "must only DISPATCH the async work").
- The name tiebreak is deterministic: two players with the same value rank alphabetically by name, and since the sort is stable the order is reproducible across refreshes.
- `top(n)` returns a `subList` of the internal immutable list (not a copy): cheap, and safe because the snapshot never mutates.

### DiscordWebhook

`src/main/java/com/sn/lib/discord/DiscordWebhook.java`

Per-context Discord webhook dispatcher. Zero external dependencies: payloads POST with the JDK's `HttpClient` (lazy, double-checked locking, 5 s connect timeout). Delivery is fire-and-forget over a FIFO queue (`ConcurrentLinkedDeque<Pending>`) processed OFF the main thread (enqueueing from any thread is non-blocking): an HTTP 429 re-queues the message AT THE FRONT and waits the `Retry-After` the endpoint requested, any other failure discards the message with ONE single WARN per endpoint, and the webhook token is trimmed from every log line. Internal constants: `CONNECT_TIMEOUT` 5 s, `SEND_TIMEOUT` 10 s, `DRAIN_DEADLINE_MILLIS` 3000, `MAX_EMBEDS` 10.

Public methods:

- `public DiscordWebhook(Sn ctx)` - Constructor.
- `public Message message(String webhookUrl)` - Starts a message for the webhook URL; nothing queues until `Message.send()`.
- `public void send(String webhookUrl, String content)` - Queues a plain content message; shortcut for `message(url).content(text).send()`.
- `public Embed embed()` - Starts a standalone embed to attach via `Message.embed(Embed)`.
- `public void drain()` - Best-effort synchronous flush of the queue on the calling thread, invoked by the context teardown after cancelling the scheduler. It runs under a 3000 ms deadline: each send uses the remaining time as its timeout; a 429 whose Retry-After fits before the deadline is waited ONCE (`Thread.sleep` + re-queue at the front), everything undeliverable in time is discarded, and at the end a WARN counts the losses (`"Webhook drain cut the flush short: N message(s) discarded by the 3000ms deadline"`). It also releases the `HttpClient` (`shutdown()`).

#### DiscordWebhook.Message (inner class, non-static)

Builder of a webhook payload; `send()` queues it FIFO and returns immediately.

- `public Message content(String content)` - The message's plain text.
- `public Message username(String username)` - Overrides the webhook's display name for this message.
- `public Message avatarUrl(String avatarUrl)` - Overrides the webhook's avatar for this message.
- `public Message embed(Embed embed)` - Attaches an embed; Discord accepts up to 10 per message, extras are silently ignored.
- `public void send()` - Queues the message for asynchronous delivery; empty payloads (no content and no embeds) are discarded without queuing.

#### DiscordWebhook.Embed (static nested)

Builder of a Discord embed; attached via `Message.embed(Embed)`. Private constructor: obtained with `DiscordWebhook.embed()`.

- `public Embed title(String title)` - Embed title.
- `public Embed description(String description)` - Embed description.
- `public Embed color(int rgb)` - Accent color as `0xRRGGBB` (masked with `& 0xFFFFFF`).
- `public Embed field(String name, String value, boolean inline)` - Adds a name/value field, optionally inline; nulls normalize to `""`.
- `public Embed footer(String footer)` - Footer text.
- `public Embed timestampNow()` - Stamps the embed with the current instant (`Instant.now().toString()`).

Internal logic:

- Queue and worker: `enqueue` does `addLast` + `pump()`. `pump()` arms nothing if `ctx.isShuttingDown()` or a worker is already running (`working.compareAndSet(false, true)`); if the owner is already disabled (`IllegalPluginAccessException` from the scheduler), it lowers the flag and does not arm. The worker (`work()`) runs on `ctx.scheduler().async(...)`: a FIFO loop with `pollFirst`; when the queue empties it lowers the flag and RE-CHECKS the queue (a message queued between the poll and the flag flip must not stay stuck: if something is there, re-pump).
- Rate limit: `deliver` returns 0 when the payload was consumed (delivered, or discarded with its WARN) or the millis the endpoint asked to wait (HTTP 429). On a 429 the worker re-queues AT THE FRONT (`addFirst`, preserving FIFO order) and re-arms with `ctx.scheduler().asyncLater((retryAfterMillis + 49) / 50, this::work)`.
- `retryAfterMillis(response)`: parses the `Retry-After` header as a double of seconds (decimals allowed), floored at 1000 ms; a missing or non-numeric header -> 1000 ms.
- Failures: an invalid URL (`IllegalArgumentException` building the request), a non-2xx HTTP other than 429 (`"the endpoint answered HTTP <status>"`) and `IOException` (`"network failure: <e>"`) discard the message with `warnOnce`; `InterruptedException` re-interrupts the thread and discards silently.
- `warnOnce(url, reason)`: one WARN per sanitized endpoint (`"Discord webhook <endpoint> failed (<reason>); later errors from this endpoint are omitted from the log"`); later failures of the same endpoint stay silent.
- `sanitize(url)`: trims the last path segment (the webhook's secret token) and replaces it with `/***`, so secrets never reach the console.
- Handwritten JSON: `Message.toJson()` and `Embed.appendJson` build the JSON with `StringBuilder`; `escape` handles quotes, backslashes, `\n` `\r` `\t` and every control char `< 0x20` as `\uXXXX`. Null fields are omitted from the payload.
- `Pending(String url, String json)` is a private record: the JSON serializes at `send()` time, not at POST time.

Notes and gotchas:

- `drain()` runs on the teardown thread (the scheduler is already cancelled, the async worker can no longer run: `pump` and `work` bail on `ctx.isShuttingDown()`), which is why the flush is synchronous and short-deadlined; queued webhooks are "never lost silently" (the drop WARN counts the losses).
- There is no delivery confirmation to the caller: `send()` returns void immediately (fire-and-forget by design).
- The 10-embed limit applies silently in `Message.embed` (extras are not even added to the list).

### TODOs and limitations

There are no TODO/FIXME/HACK markers in this module's code. Limitations documented in Javadoc/code:

- BossBarUtil: `setProgress` on a bar with an active timer is overwritten by the timer on the next tick; the timer animates against wall clock with a 2-tick resolution.
- HologramUtil: per-viewer visibility (`hideFrom`) is not persistent, it resets when the entity re-binds after a chunk reload or re-spawn; `delete` on an entity in an unloaded chunk cannot touch it and defers the removal to the orphan purge at the chunk's next load.
- LeaderboardCache: a 1-second refresh floor (`MIN_REFRESH_TICKS = 20`); until the first completed query every read sees an empty snapshot; the supplier runs on the main thread and must only dispatch (not block).
- DiscordWebhook: the shutdown drain may discard messages that do not fit in the 3000 ms deadline (with a WARN counting the losses); a single WARN per endpoint and then silence; at most 10 embeds per message (extras are ignored); no delivery confirmation to the caller (fire-and-forget).
---

# (Section 16 of the SnLib v1.1.0 documentation)

## 16. Build, tests, golden specs and TODOs

This module closes the documentation with the infrastructure that sustains the lib: the `pom.xml` (exact dependencies, internal shading with relocations and deliberate exclusions, an additive-only API gate with japicmp ACTIVE against the 1.0.0 baseline, and a manifest with Sn metadata), the five `docs/` files that act as golden specs and templates for consumers (the menu schema, the physical item schema, the selection wand spec, the consumer pom template and the consumer ProGuard rules), the JUnit 5 suites of `src/test/java/com/sn/lib/` (211 tests, all green, verified with `mvn test` via surefire) and the complete pending-work inventory: what the TODO/FIXME/placeholder grep over the code yields plus the known handoff pendings (1.20.4 degradation, repo/release, pilots and canary; the bStats one was resolved in v1.1 with the real service id 32541). It also records the smoke gate result on Paper 1.21.8 build 60 and 1.20.4 build 499: green on both for the 1.0.0 and 1.1.0 releases (gate re-run with each release's jar).

### pom.xml (SnLib build)
`pom.xml`
Coordinates `com.sn:snlib:1.3.0`, packaging `jar`, name `SnLib`, description "Common library core for Sn plugins, shipped as a standalone hard-depend plugin.". Compiles with Java 21 (`maven.compiler.release=21`) and defines the property `sn.api.level=2`, which the pom itself clarifies is the manifest's informational value: the real handshake constant is `com.sn.lib.SnApi.LEVEL` (2 since the 1.1.0 release, unchanged through 1.3.0; the Velocity base is a separate surface outside the level; history in SnApi's Javadoc).

Declared repositories:

- `papermc` (`https://repo.papermc.io/repository/maven-public/`)
- `extendedclip` (`https://repo.extendedclip.com/content/repositories/placeholderapi/`)
- `jitpack` (`https://jitpack.io`)

`dependencyManagement`: imports `net.kyori:adventure-bom:4.25.0` (scope `import`, type `pom`). Reason documented in the pom: paper-api's POM pins adventure-api 4.18.0 while Paper ships 4.25.0 serializers; without this pin the MiniMessage pipeline risks `NoSuchMethodError`/`NoClassDefFoundError` at runtime.

Exact dependencies:

- `io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT` (provided) - 1.21.1 compile baseline (availability of `setMaxStackSize`); runtime floor 1.20.4, target 1.21.8 via `SnCompat.probe`.
- `net.kyori:adventure-api` (provided, BOM version 4.25.0).
- `net.kyori:adventure-text-minimessage` (provided, BOM version 4.25.0).
- `me.clip:placeholderapi:2.11.6` (provided).
- `com.github.MilkBowl:VaultAPI:1.7.1` (provided, excludes `org.bukkit:bukkit`).
- `com.zaxxer:HikariCP:6.3.0` (compile) - shaded relocated to `com.sn.lib.libs.hikari`.
- `org.slf4j:slf4j-api:2.0.16` (provided) - Paper already provides slf4j-api; declaring it provided keeps it OUT of the shaded jar and avoids the `SLF4JServiceProvider` `NoSuchMethodError`.
- `org.slf4j:slf4j-jdk14:2.0.16` (compile, excludes `org.slf4j:slf4j-api`) - the binding shaded WITHOUT relocation so the relocated HikariCP finds a provider and does not print "No SLF4J providers were found".
- `org.xerial:sqlite-jdbc:3.46.1.3` (compile) - SHADED, NEVER RELOCATE: the JNI binding `org.sqlite.core.NativeDB` breaks under relocation.
- `com.mysql:mysql-connector-j:8.4.0` (compile, excludes `com.google.protobuf:protobuf-java`) - SHADED, NEVER RELOCATE: a binary driver, a single copy on the server.
- `org.bstats:bstats-bukkit:3.1.0` (compile) - shaded relocated to `com.sn.lib.libs.bstats`.
- `org.junit.jupiter:junit-jupiter:5.10.2` (test).

Build:

- `finalName`: `SnLib-${project.version}` (produces `SnLib-1.3.0.jar`).
- Resources with `filtering=true` over `src/main/resources` (Maven property expansion in `plugin.yml`/`config.yml`).
- `maven-compiler-plugin:3.13.0` and `maven-surefire-plugin:3.2.5` without extra configuration.
- `maven-jar-plugin:3.4.1` - a manifest with two custom entries: `Sn-Lib-Version: ${project.version}` and `Sn-Api-Level: ${sn.api.level}`.
- `maven-shade-plugin:3.6.0` (phase `package`, goal `shade`):
  - Relocation `com.zaxxer.hikari` -> `com.sn.lib.libs.hikari`, with excludes `org.sqlite.**` and `com.mysql.**` (JNI NativeDB / binary driver).
  - Relocation `org.bstats` -> `com.sn.lib.libs.bstats`, with the same excludes.
  - `ServicesResourceTransformer`: preserves/merges `META-INF/services` (the jdk14 binding's `SLF4JServiceProvider` and the sqlite/mysql JDBC drivers).
  - Global `*:*` filter excluding `META-INF/*.SF`, `META-INF/*.DSA`, `META-INF/*.RSA`, `module-info.class` and `META-INF/versions/*/module-info.class` (post-shade invalid signatures and foreign module descriptors).
- `japicmp-maven-plugin:0.21.2` (phase `verify`, goal `cmp`) - additive-only public API gate, ACTIVE since the 1.1.0 release:
  - Explicit `oldVersion`: compares against `com.sn:snlib:1.0.0` (jar), the baseline installed into the local `.m2` by the 1.0.0 release.
  - `ignoreMissingOldVersion=false`: a missing baseline = a broken build (no more silent skip; until 1.0.0 the gate was vacuous because no previous version existed).
  - `ignoreMissingClasses=true`: the shaded jar includes an unrelocated mysql-connector-j whose X DevAPI classes reference protobuf (excluded from the shade on purpose); japicmp must not demand that classpath.
  - Analysis excludes: `com.sn.lib.**.internal.**`, `com.sn.lib.libs.**` (relocated); `com.sn.lib.velocity.**` (the Velocity base is a separate Velocity-only surface, kept outside the Paper additive gate while it settles); and the shaded-but-unrelocated ones that are not SnLib API: `com.mysql.**`, `org.sqlite.**`, `org.slf4j.**`, `google.protobuf.**`.
  - `onlyModified=true`, `breakBuildOnBinaryIncompatibleModifications=true`, `breakBuildOnSourceIncompatibleModifications=false`: it breaks the build only on BINARY incompatibility (the additive-only rule), tolerating source incompatibilities.

#### Notes and gotchas

- The shading matrix has three distinct, deliberate regimes: relocated (HikariCP, bStats), shaded without relocation (sqlite-jdbc, mysql-connector-j, slf4j-jdk14) and provided (paper-api, adventure, PAPI, VaultAPI, slf4j-api). Moving a dependency between regimes breaks concrete things already documented in the pom comments (JNI, SLF4J providers, duplicate driver copies).
- The `adventure-bom` 4.25.0 pin exists solely to align compile time with what Paper actually ships; adventure remains provided and does not travel in the jar.
- The manifest's `Sn-Api-Level` is informational; the real consumer/lib compatibility gate is the runtime handshake against `SnApi.LEVEL`.
- The `protobuf-java` exclude on mysql-connector-j is the counterpart of japicmp's `ignoreMissingClasses=true`: a jar with dangling protobuf references is accepted because the X DevAPI code never runs on the server.

### docs/menu-example.yml (GUI golden spec)
`docs/menu-example.yml`
Golden spec of the menu configuration schema (Menu Lib): one GUI per file inside the consumer plugin's `guis/` folder. The file's explicit contract: every field documented here is natively supported by SnLib; if the config user sets a supported field, it ALREADY works without plugin code. It documents:

- Root fields: `title` (default "Menu"), `rows` 1-6 (default 3), `open-sound` (default ''), `close-sound` (1.1.0, default ''; plays to the viewer when they close the menu), `close-actions` (1.1.0, default empty; same grammar as click-actions, runs once per close on the natural close and on `[close]`, NEVER on page changes or programmatic closes; click guards inside skip with debug), `update-interval` in ticks (0 = no auto-update; refreshes items, title and rows), `inventory-type` (default CHEST; CHEST, DISPENSER, DROPPER, HOPPER, FURNACE, WORKBENCH, ENCHANTING, BREWING, ANVIL, BEACON, SHULKER_BOX, BARREL, etc).
- `pagination` (opt-in per menu, default false): with `true` the GUI keeps ONE GuiSession + ONE Inventory PER VIEWER (real per-player page state; the same GUI serves N players on different pages at once), `[next-page]`/`[previous-page]`/`[set-page]`/`[refresh-page]` work and `bindPaged` fills the paginated slots via API. With `false` (default) pagination actions are silent no-ops with a debug note and `bindPaged` WARNs once. Without a live `bindPaged` the total page count is unknown and the next nav never disables, unless the plugin declares the total via `GuiSession.setTotalPages(n)` (1.1.0).
- `strict-clicks` (opt-in per menu, default false): with `true` the generic `click-actions` list only fires on the 4 basic mouse clicks (LEFT, RIGHT, SHIFT_LEFT, SHIFT_RIGHT); the other ClickTypes stay cancelled with no action, unless a declared specific list covers them (`middle-click-actions` enables MIDDLE; a declared `left-click-actions` enables DOUBLE_CLICK and CREATIVE). With `false` (default) the behavior is the historical v1.0.0 one.
- ASCII layout (1.1.0): `layout:` (an optional list of 1-6 strings of up to 9 characters over the chest grid; ' ' = an empty cell; `rows` derives from the length and a contradictory `rows:` WARNs), a menu-level `paged-key:` (a layout character whose cells are the destination of the `int[]`-less `bindPaged`) and a per-item `key:` as an alternative to `slots:` (the item renders in ALL the layout cells with that character; with both key and slots declared slots wins with a WARN; a key absent from the layout ignores the item with a WARN; templates and nav-disabled do not support key). Documented with the example item `example-layout-item`.
- The `items` schema: `display-name`, `material` (resolves basehead-base64), `custom-model-data`, `amount`, `slots` (int, a "0-2" range or a "0, 2, 4-6" mix), `glow`, `enchantments` (id/level pairs), `flags` (HIDE_ENCHANTS, HIDE_ATTRIBUTES, HIDE_UNBREAKABLE, HIDE_DESTROYS, HIDE_PLACED_ON, HIDE_POTION_EFFECTS, and HIDE_ALL as the combination), `color` (RGB "235, 64, 52" or HEX), `trim-pattern`/`trim-material` (armor), `potion-effects` (effect/level/duration triples), per-item `update-interval`, `lore`, `click-requirements` and `view-requirements` (expressions `%placeholder% > 0 && %placeholder% < 10`, `=`, `!=`), `deny-actions` (same actions, they run if the click-requirements are NOT met) and `click-actions`.
- The per-click matrix (1.1.0): 15 optional keys per item, five click keys (`right`, `left`, `shift-right`, `shift-left`, `middle`) times three lists (`*-click-actions`, `*-click-requirements`, `*-click-deny-actions`), with the specific-over-generic field-by-field resolution rule documented in the spec (exact shift -> side -> generic; right covers RIGHT/SHIFT_RIGHT, left covers LEFT/SHIFT_LEFT/DOUBLE_CLICK/CREATIVE, middle covers MIDDLE).
- The full click action catalog: `[player]`, `[player-as-op]`, `[right-click]`, `[left-click]`, `[shift-left-click]`, `[shift-right-click]`, `[right-click-only]`, `[left-click-only]`, `[click=TYPE,...]`, `[middle-click]`, `[double-click]`, `[drop-click]`, `[number-key]`, `[swap-offhand]` (guards; the spec notes the inclusive compat of `[right-click]`/`[left-click]` and that `[click-block]`/`[click-air]` in a GUI always skip the line), `[console]`, `[message]`, `[sound]`, `[close]`, `[open] gui-id`, `[connect]` (BungeeCord switch), `[broadcastmessage]`, `[actionbar]`, `[title]` (format `title;subtitle;fadeIn;stay;fadeOut`), `[next-page]`, `[previous-page]`, `[refresh-page]`, `[set-page]` (only with pagination: true), `[refresh-menu]` and `[custom]` (plugin-registrable actions with any string).
- Pagination navigation items (the example `previous-page`/`next-page`): normal items whose click-actions use the pagination actions, with an optional `nav-disabled` section: an appearance override rendered in the SAME slots INSTEAD of the navigation item when there is no page to go to (first page for previous, last for next). `nav-disabled` supports the same appearance fields as a normal item but no slots or actions: a disabled arrow never fires anything.
- Text pipeline examples (SnText): `[small]` substitutes a-z/A-Z with small caps glyphs (de-accents vowels, the enye keeps its default glyph; digits, symbols, color codes and MiniMessage tags pass intact; it runs BEFORE `[rgb]`); `[rgb]` applies a per-character interpolated gradient over 7 fixed anchors (#F300F3, #5555FF, #55FFFF, #55FF55, #FCFF21, #FF9B00, #FF5327), OVERRIDES pre-existing COLOR codes and PRESERVES FORMAT ones (&l &o &n &m &k); `[center]` centers the line to 154px over the already-colored legacy string (gradient already interpolated) as the LAST step before rendering to Component. The three prefix tags ([small]/[rgb]/[center]) are composable in ANY order. Legacy codes (&a, &#RRGGBB) and MiniMessage tags render together.
- The `templates` section: items IDENTICAL to normal ones but WITHOUT `slots:`; the plugin developer decides the slot dynamically via the Java API and the config user customizes the appearance freely. They can use plugin-defined local placeholders (e.g. `%index%`, `%warp_name%`). They support the same per-click matrix keys as regular items. It includes the usage example of a backpack plugin (the plugin assigns per player which template goes to which slot).

### docs/item-example.yml (physical items golden spec)
`docs/item-example.yml`
Golden spec of the PHYSICAL items schema (Item Lib): items given to players (inventory, drops, etc), NOT GUI items (menu-example.yml covers those). Every item-id defined here can be given via the API `sn.items().give(player, "item-id", amount)`, and any ItemDef can also be built 100% programmatically via `ItemDef.builder()` without YML. It documents, by block:

- APPEARANCE: `display-name`, `material` (resolves basehead-base64), `custom-model-data`, `amount`, `glow`, `lore`, `enchantments`, `flags` (same set as menus, with HIDE_ALL), `color` RGB/HEX, `trim-pattern`/`trim-material`, `potion-effects`.
- PROPERTIES: `unbreakable` (default false), `max-stack-size` 1-64 (the material's vanilla default), `droppable` (default true), `moveable` (default true), `placeable` (default true, blocks only), `tradeable` (default true), `despawnable` (default true), `keep-on-death` (default false), `cooldown` in ticks (0 = no cooldown).
- LOCKED MODE AND OBTAIN CONTROL: `locked` (default false) pins the item to its slot and blocks extraction through the 7 theft vectors (drag, number-key swap, offhand swap, shift-move, drop, cursor pickup, hopper/inventory transfer); the real item displaced by a locked one restores on quit and shutdown via a write-through backup (default-on: the backup survives a server crash without onDisable). `no-drop` is a hard alias of `droppable: false` (it blocks Q/drop and drag-out). `no-manual-equip` prevents manually equipping to armor or offhand (right-click equip, inventory click, number-key and drag). `obtain-via` restricts how the item enters circulation: "" (default) unrestricted; `COMMAND_ONLY` only via the plugin's command or API, cancelling crafting/mob-pickup/other paths.
- Custom DURABILITY (separate from vanilla's, useful for durability-less items like sticks): `custom-durability.max` (0 = disabled), `damage-per-use` (default 1), `break-actions` (actions when reaching 0; since 1.1.0 they run with the real ClickType and BLOCK/AIR surface of the interact that broke the item, so click/surface guards inside the list evaluate) and `lore-format` with `%durability%`/`%max_durability%` automatically updated.
- INTERACT ACTIONS (world, not GUI): 12 lists: `right-click-actions`, `left-click-actions`, `shift-right-click-actions`, `shift-left-click-actions`, `right-click-block-actions`, `right-click-air-actions`, `left-click-block-actions`, `left-click-air-actions`, and the 4 shift-positionals of 1.1.0 `shift-right-click-block-actions`, `shift-right-click-air-actions`, `shift-left-click-block-actions`, `shift-left-click-air-actions` (with shift, a shift-positional WITH behavior runs instead of the plain positional; without behavior it falls to the plain one). Flag `shift-overrides-generic` (default true; with false BOTH variants of the pair run on shift-click, shift first). Besides the common actions it adds `[particle] TYPE [count] [offX offY offZ] [extra] [key=value...]`, `[potion] EFFECT duration(ticks) amplifier`, `[remove-item]` (1 unit), `[remove-item] [N] [offhand|id:<item-id>|MATERIAL]` and the example positional/exact guards (`[click-block]`, `[click-air]`, `[right-click-only]`, `[click=RIGHT]`).
- INTERACT REQUIREMENTS + `deny-actions` when unmet (since 1.1.0 the deny-actions run with the interact's real ClickType and surface, so guards inside the list evaluate).
- PICKUP/DROP ACTIONS: `pickup-actions` and `drop-actions`.
- HELD EFFECTS: continuous effects while holding or wearing the item, per slot: `held-effects.mainhand`, `offhand`, `armor`; format "EFFECT amplifier" (amplifier = level - 1).
- `equipment-slot`: restricts where it can be placed (MAINHAND, OFFHAND, HEAD, CHEST, LEGS, FEET; default "" = anywhere).
- RECIPE: an optional custom recipe; `type` SHAPED (with `shape` + letter-mapped `ingredients`), SHAPELESS (`ingredients` list), or FURNACE/SMOKING/BLASTING/CAMPFIRE/STONECUTTING (`input`, `experience`, `cooking-time` in ticks).

### docs/consumer-pom-template.xml (consumer pom template)
`docs/consumer-pom-template.xml`
`pom.xml` template for Sn plugins consuming SnLib under the standalone hard-depend model. Its header documents the full consumption contract:

- Resolution of `com.sn:snlib`: 1) publish SnLib to the local `.m2` with `mvn install -f <path>/SnLib/pom.xml`; 2) JitPack is NOT supported (the SnLib repo is private and JitPack does not build private repos): the ONLY resolution path is the local `.m2`; 3) at runtime NOTHING of SnLib shades into the consumer: the server loads `SnLib.jar` as a standalone plugin in `plugins/` and the consumer declares `depend: [SnLib]` in its plugin.yml. That is why the scope is `provided` and the template does NOT include maven-shade-plugin for the lib; if the consumer shades its own dependencies, NEVER include `com.sn:snlib` in the shade.
- The consumer's minimal `plugin.yml` block: `name`, `main`, `version`, `api-version: '1.20'`, `depend: [SnLib]`, the main command and the `myplugin.admin` permission tree (default op) with the child `myplugin.admin.reload`.
- The consumer's main class (the only init path: extending `SnPlugin`), with the contract's three signatures: `protected int requiredApiLevel()` returning `SnApi.LEVEL`, `protected SnSpec buildSpec()` (example: `SnSpec.builder().config("config.yml").lang().guis().build()`) and `protected void onInnerEnable()` where commands, guis, items, db, etc register on the Sn context.
- The pom itself: `com.sn:myplugin:1.0.0`, Java 21, the papermc repo, dependencies `com.sn:snlib:1.3.0` (provided, from the local .m2; at runtime `SnLib.jar` in `plugins/` provides it) and `io.papermc.paper:paper-api:1.21.1-R0.1-SNAPSHOT` (provided), and only `maven-compiler-plugin:3.13.0` in build (no shade).

### docs/snlib-consumer-rules.pro (consumer ProGuard rules)
`docs/snlib-consumer-rules.pro`
ProGuard rules for Sn plugins that consume SnLib and obfuscate with sn-obfuscate. Premise: `SnLib.jar` is a LIBRARY at runtime (a standalone plugin in `plugins/`), it is NEVER obfuscated or shaded into the consumer; it declares as `-libraryjars` just like paper-api. Rules:

- `-libraryjars <user.home>/.m2/repository/com/sn/snlib/1.1.0/snlib-1.1.0.jar` (adjust the path to the local `.m2` or the release jar).
- `-dontwarn com.sn.lib.**`: SnLib does not travel inside the consumer's jar; silence warnings about references to lib classes.
- Entrypoint keep: `-keep public class * extends com.sn.lib.SnPlugin` preserving `public <init>()`, `protected int requiredApiLevel()`, `protected com.sn.lib.SnSpec buildSpec()`, `protected void onInnerEnable()` and `protected void onInnerDisable()`. Reason: Bukkit instantiates the class by reflection (plugin.yml's main) and SnLib invokes the `requiredApiLevel()` handshake.
- Keeps for classes registered by reflection or by Bukkit's framework: `* implements org.bukkit.event.Listener`, `* implements org.bukkit.command.CommandExecutor`, `* implements org.bukkit.command.TabCompleter` and `* extends me.clip.placeholderapi.expansion.PlaceholderExpansion` (all with `{ *; }`).
- `-keepclassmembers class * { @org.bukkit.event.EventHandler <methods>; }`: preserves `@EventHandler` methods in any class, in case a listener does not implement `Listener` directly but via an intermediate class.

### Test suites (21 suites, 204 tests, green)

The 21 suites live in `src/test/java/com/sn/lib/` (the flat `com.sn.lib` package, plus the subpackages `com.sn.lib.item` for `SnItemAttributeParseTest` and `ItemDefVariantsTest`, `com.sn.lib.action` for `ClickGuardTest`, `com.sn.lib.gui` for `ClickResolutionTest`, `com.sn.lib.update` for `UpdateCheckerJsonTest` and `com.sn.lib.util` for `PlayerLookupParseTest`, which need package-private access to the helpers they cover), run with JUnit Jupiter 5.10.2 under surefire 3.2.5 and are 100% pure JVM: none starts a server or mocks Bukkit; they cover exactly the lib pieces that are pure logic (text, parsing, cron, yml, leaderboard, attribute resolution, guard matching, click matrix resolution, layout masks, the update check parse, the Mojang lookup parse, and the cuboid core with LocationUtil's null-safe paths). Total verified with `mvn test`: 204 tests, 0 failures, 0 errors, 0 skipped. Fixtures in `src/test/resources/yml/`: `tabs-broken.yml` (tab-indented YAML YamlPreprocessor must repair, with tabs inside quoted values and block scalars it must preserve), `merge-resource.yml` / `merge-old.yml` / `merge-expected.yml` (the golden trio of YamlUpdater's merge: the jar's new resource, the user's old file with its own values and an extra key, the expected result) and `corrupt.yml` (deliberately invalid YAML: an unclosed quote and flow collection).

### RgbGradientTest
`src/test/java/com/sn/lib/RgbGradientTest.java`
7 tests over `com.sn.lib.text.RgbGradientUtil.applyRgbTag(String)`: the per-character `[rgb]` gradient. It verifies against the exact extreme anchors `F300F3` and `FF5327`.

- `void emitsOneHexPerVisibleCharacter()` - emits exactly one `&#RRGGBB` per visible character (5 for "Hello", 8 for "Gradient").
- `void extremesUseExactAnchors()` - the first character receives the `#F300F3` anchor and the last `#FF5327`, no interpolation at the extremes.
- `void spacesDoNotConsumeGradientPositions()` - spaces do not consume gradient positions: "A B" produces the same hexes as "AB".
- `void formatPreservedAndColorOverridden()` - format codes (`&l`) are preserved and re-emitted per character; color ones (`&a`) are discarded.
- `void resetClearsAccumulatedFormat()` - `&r` clears the accumulated format: the following character comes out without `&l`.
- `void existingHexColorIsDiscarded()` - a pre-existing legacy hex (`&#123456`) is discarded and overridden by the gradient.
- `void singleVisibleCharacterGetsFirstAnchor()` - with a single visible character the first anchor is used.

### SemverComparatorTest
`src/test/java/com/sn/lib/SemverComparatorTest.java`
10 tests over `com.sn.lib.hook.SemverComparator` (the static `compareVersions(String, String)` method and the class as a `Comparator<String>`), the version comparison the hook system uses.

- `void comparesSegmentsNumericallyNotLexically()` - numeric segments, not lexicographic: 1.9 < 1.10, 1.99.9 < 1.100.0.
- `void supportsSegmentsOfAnyDigitCount()` - segments of any digit count (1.2.345 < 1.2.1000).
- `void missingTrailingSegmentsCountAsZero()` - missing trailing segments count as 0: "1.2" == "1.2.0", "1" == "1.0.0"; supports 4 segments ("1.2" < "1.2.0.1").
- `void preReleaseComparesLowerThanRelease()` - a pre-release precedes the bare release: "1.0.0-SNAPSHOT" < "1.0.0", "2.11.6-DEV-SNAPSHOT" < "2.11.6" (the pre-release "DEV-SNAPSHOT" is ONE identifier: the split is by `.`).
- `void semverOrgPrecedenceTable()` - the full semver.org ladder pair by pair: alpha < alpha.1 < alpha.beta < beta < beta.2 < beta.11 < rc.1 < release.
- `void numericIdentifiersCompareNumerically()` - numeric identifiers compare as numbers: alpha.9 < alpha.10.
- `void numericIsLowerThanAlphanumeric()` - a numeric identifier is lower than an alphanumeric one: "1.0.0-1" < "1.0.0-alpha".
- `void buildMetadataIsIgnored()` - the `+...` build metadata is ignored: "1.0.0+build.5" == "1.0.0", "1.0.0-alpha+001" == "1.0.0-alpha".
- `void equalVersionsCompareAsZero()` - equal versions compare 0 ("0.0.0" == "0").
- `void comparatorInstanceSortsAscending()` - the instance as a Comparator sorts lists in ascending semver.

### SlotParserTest
`src/test/java/com/sn/lib/SlotParserTest.java`
13 tests over `com.sn.lib.util.SlotParser.parse(Object)` and `parse(Object, Consumer<String>)`: the tolerant slot parser of the GUI YMLs.

- `void parsesSingleInt()` - a lone int (including 0) produces that slot.
- `void parsesNumericString()` - a numeric string, with space trim (" 13 ").
- `void parsesRange()` - the ranges "0-8" and spaced "10 - 12" expand inclusive.
- `void normalizesReversedRange()` - a reversed range "2-0" normalizes to 0,1,2.
- `void parsesCommaSeparatedMix()` - a comma mix "0,2,4-6".
- `void parsesListOfMixedElements()` - a heterogeneous YAML list `[1, "3-5", "7"]`.
- `void deduplicatesKeepingFirstSeenOrder()` - dedups preserving first-seen order ("4-6,5,3" -> 4,5,6,3).
- `void invalidInputYieldsEmptyAndDelegatesWarn()` - invalid input returns an empty array and delegates ONE warn to the consumer, including the offending text.
- `void nullYieldsEmptyAndDelegatesWarn()` - null returns empty and warns.
- `void negativeSlotsAreWarnedAndSkipped()` - negative slots skip with a warn.
- `void invalidTokensAreSkippedButValidOnesKept()` - "1,x,2" keeps 1 and 2 with a single warn (fail-soft per token).
- `void oversizedRangeIsRejected()` - an outsized range ("0-999999999") is rejected whole with a warn (memory protection).
- `void nullWarnConsumerIsSafe()` - the consumerless overload does not throw on garbage.

### GuiMaskTest
`src/test/java/com/sn/lib/GuiMaskTest.java`
9 tests over `com.sn.lib.gui.GuiMask.slots(char, String...)` and `slots(char, List<String>)`: the programmatic ASCII mask of chest slots (6x9 geometry, slot = row*9 + column).

- `void keyInMultipleCellsReturnsAllSlotsRowMajor()` - a key repeated across a 3-row mask returns all its cells with the exact i*9+j values in ascending row-major order (10-16 and 19-25).
- `void secondRowFirstColumnIsSlotNine()` - the row 2 / column 1 cell is slot 9.
- `void spaceKeyAlwaysReturnsEmpty()` - `slots(' ', ...)` returns empty even if the mask has spaces (the space is always an empty cell).
- `void missingKeyReturnsEmpty()` - a key that does not appear in the mask returns empty.
- `void rowsBeyondSixAreIgnored()` - with 7 rows, a key only in the 7th returns empty; in the 6th (slot 45) it does count.
- `void columnsBeyondNineAreIgnored()` - in a 10-character row, the key in column 10 returns empty and in column 9 (index 8) returns slot 8.
- `void nullRowListReturnsEmpty()` - a null list and null varargs return empty.
- `void nullAndEmptyRowsCountAsEmptyRows()` - a null or empty row in the middle does not shift the numbering of the following ones (a key in rows 1 and 3 with row 2 null -> slots 0 and 18).
- `void varargsAndListOverloadsAgree()` - both overloads return exactly the same for the same mask.

### TimeUtilTest
`src/test/java/com/sn/lib/TimeUtilTest.java`
10 tests over `com.sn.lib.util.TimeUtil`: `parseMillis`, `parseTicks`, `humanize`, `humanizeShort` and the i18n interface `TimeUtil.Labels` (with `longLabel(Unit, boolean)` and `shortLabel(Unit)` over the `Unit` enum DAY/HOUR/MINUTE/SECOND). It defines a Spanish test Labels.

- `void parsesCanonicalDurationString()` - "1d 2h 30m 15s" parses to exact millis and `parseTicks` divides by 50.
- `void parsesCompactAndSpacedVariants()` - compact ("1d2h30m15s") and ultra-spaced ("1 d 2 h ...") variants.
- `void parsesFullUnitWords()` - full words ("1 day 2 hours 30 minutes 15 seconds").
- `void bareNumberCountsAsSeconds()` - a bare number is seconds ("45" -> 45000 ms).
- `void supportsDecimalsTicksAndMillis()` - decimals ("1.5h"), ticks ("1t" -> 50 ms) and millis ("250ms").
- `void invalidInputYieldsZero()` - null, empty, garbage and an unknown unit ("5x") return 0 (they never throw).
- `void humanizesLongForm()` - the long form with default English plurals, including "0 seconds".
- `void humanizesShortForm()` - the short form "1d 2h 30m 15s", "1m", "0s".
- `void labelsAreInjectableForI18n()` - labels are injectable: "1 minuto 1 segundo", "2 dias", "1min 1seg", "0seg".
- `void shortFormRoundTripsThroughParse()` - round-trip: `parseMillis(humanize(x)) == x` and the same with humanizeShort for several samples.

### NumberFormatterTest
`src/test/java/com/sn/lib/NumberFormatterTest.java`
14 tests over `com.sn.lib.util.NumberFormatter`: `format(double)` (K/M/B/T/Qa/Qi suffixes), `formatComma(double)` (thousand grouping, v1.1) and `parseFormatted(String)` (the separator-tolerant inverse).

- `void formatsPlainNumbersBelowThousand()` - below 1000 no suffix; decimals rounded to 2 (12.345 -> "12.35").
- `void formatsEachSuffixMagnitude()` - each magnitude: 1.5K, 1M, 2.5B, 1T, 1Qa (1e15), 1Qi (1e18).
- `void formatsNegativesAndRoundsToTwoDecimals()` - negatives ("-1.5K") and rounding to 2 decimals ("1.23M").
- `void promotesWhenRoundingReachesNextMagnitude()` - promotion on rounding: 999999 -> "1M", 999.999 -> "1K" (never "1000K").
- `void parsesSuffixedInputCaseInsensitively()` - case-insensitive suffix parsing ("1.5k", "2m", "1qa", "2.5Qi", "-2.5B").
- `void toleratesCommaAndDotSeparators()` - tolerates a decimal comma ("1,5K"), comma thousands ("1,500"), US format ("1,234,567.89") and European format ("1.234.567,89").
- `void rejectsGarbage()` - null, empty, letters and an unknown suffix ("1.5X") throw `NumberFormatException`.
- `void formatCommaGroupsThousands()` - grouping in 3s from the right: 1234567 -> "1,234,567", 1000 -> "1,000", 1234567890 -> "1,234,567,890".
- `void formatCommaRoundsHalfUpToTwoDecimals()` - HALF_UP rounding to 2 decimals: 1234.567 -> "1,234.57", 0.005 -> "0.01".
- `void formatCommaStripsTrailingZeros()` - trailing zeros removed: 1000.00 -> "1,000" (no scientific notation), 1000.10 -> "1,000.1", 0 -> "0".
- `void formatCommaNegativeValues()` - sign preserved: -1234567.5 -> "-1,234,567.5", -1000 -> "-1,000", -999 -> "-999".
- `void formatCommaBelowThousandUngrouped()` - below 1000 no commas: "999", "999.99", "1.5".
- `void formatCommaNaNAndInfinityAsString()` - NaN and infinities as `String.valueOf`: "NaN", "Infinity", "-Infinity".
- `void roundTripsWithinSuffixPrecision()` - format->parse round-trip within 0.5% for samples of every magnitude including negatives.

### PlayerLookupParseTest
`src/test/java/com/sn/lib/util/PlayerLookupParseTest.java`
4 pure JUnit tests (no Bukkit init, no HTTP) over the package-private helpers `parseUuid` and `validName` of `com.sn.lib.util.PlayerLookup` (v1.1).

- `void parseUuidInsertsDashes()` - `{"id":"069a79f444e94726a5befca90e38aaf5","name":"Notch"}` -> `069a79f4-44e9-4726-a5be-fca90e38aaf5`.
- `void parseUuidRejectsBadLengthOrNonHex()` - 31 or 33 char ids and non-hex ('g') return null.
- `void parseUuidMissingFieldReturnsNull()` - a missing `id` field, a non-string value and a null body return null.
- `void validNameAcceptsValidRejectsInvalid()` - accepts `Notch` and `a_1`; rejects null, empty, 17 chars, `bad-name` and names with spaces.

### YamlPreprocessorTest
`src/test/java/com/sn/lib/YamlPreprocessorTest.java`
8 tests over `com.sn.lib.yml.YamlPreprocessor` (`preprocess(String)` returning the `Result` record with `cleanText()` and `fixedLines()`, and `read(Path)`): the layer that repairs tab-indented YAML before SnakeYAML. Uses the `/yml/tabs-broken.yml` fixture.

- `void rawFixtureIsRejectedBySnakeYaml()` - golden control: the raw fixture does NOT parse with SnakeYAML unpreprocessed.
- `void preprocessedFixtureParsesAndPreservesValues()` - the preprocessed text parses and preserves values: the tab INSIDE a quoted value ("Sn\tLib"), mixed tab/space indentation, tabs in list items and block scalar content byte for byte.
- `void reportsFixedLinesOneBased()` - `fixedLines()` reports the corrected lines 1-based (3, 4, 6, 8, 9 in the fixture).
- `void rewritesIndentTabsButNotBlockScalarContent()` - it rewrites ONLY indentation tabs; the inner lines of a block scalar stay intact (even a leading tab inside the block).
- `void normalizesCrlfToLf()` - normalizes CRLF to LF besides the tabs.
- `void isIdempotentOnCleanText()` - idempotency: preprocessing already-clean text changes nothing and reports no fixes.
- `void neverThrowsOnDegenerateInput()` - null and empty return an empty Result; degenerate input ("\t: weird") repairs without ever throwing.
- `void readsUtf8AndStripsBom(@TempDir Path)` - `read(Path)` reads UTF-8 and strips the leading BOM.

### RequirementEngineTest
`src/test/java/com/sn/lib/RequirementEngineTest.java`
22 tests over `com.sn.lib.action.RequirementEngine.parse(List<String>)` / `parse(List<String>, Consumer<String>)` and the immutable tree `com.sn.lib.action.Requirement` evaluated with `test(player, resolver)`: the click/view/interact-requirements engine. Uses a mock resolver that replaces `%key%` tokens.

- `void numericAndChainWithinOneLine()` - `>` and `<` chained with `&&` on one line; the bounds stay excluded.
- `void linesJoinWithImplicitAnd()` - several list lines join with implicit AND.
- `void allNumericOperators()` - the 6 numeric operators: `>=`, `<=`, `=`, `==`, `!=` (and their negatives).
- `void integerAndDecimalCompareNumerically()` - "5" == "5.0": equality compares numerically when both sides are numbers.
- `void equalityFallsBackToCaseInsensitiveLexicographic()` - `=`/`!=` with non-numeric operands fall back to case-insensitive lexicographic comparison ("VIP" == "vip").
- `void nonNumericRelationalIsFalseWithWarn()` - a relational (`>`) with a non-numeric operand evaluates false and warns (including operator and value in the message).
- `void andBindsTighterThanOr()` - `&&` binds tighter than `||` (standard precedence).
- `void malformedLineWarnsAndEvaluatesTrue()` - a line without an operator warns and evaluates TRUE (fail-open: a broken config does not lock the menu).
- `void emptyOperandIsMalformed()` - an empty left operand ("> 5") is malformed: warn + true.
- `void malformedBranchTurnsWholeLineTrue()` - a malformed branch inside an `&&` line turns the WHOLE line TRUE.
- `void nullEmptyAndBlankInputAlwaysPass()` - null, an empty list and blank lines always pass.
- `void nullResolverLeavesTokensUntouched()` - a null resolver leaves the tokens intact (literals evaluate, `%x%` do not match).
- `void placeholdersResolveAtTestTimeNotParseTime()` - placeholders resolve on each `test`, not at parse time: the same Requirement yields different results with different values.
- `void parenthesesGroupOrOverAnd()` - `(a || b) && c` groups differently than the same line without parentheses (the group changes the result).
- `void nestedParenthesesParse()` - nested parentheses `((a && b) || c)` parse and evaluate.
- `void quotedOperandKeepsConnectorsLiteral()` - `%rank% = 'VIP && MVP'`: the `&&` inside quotes is literal, not a connector.
- `void quotedOperandKeepsParensLiteral()` - `%tag% = "(admin)"`: parentheses inside quotes are literal.
- `void quotesAreStrippedFromOperand()` - quotes are stripped from the final operand (the resolved value matches unquoted).
- `void operatorInsideQuotesIsNotAnOperator()` - `%x% = 'a >= b'` evaluates EQ, not GE: operator symbols inside quotes are literal.
- `void unbalancedParenFailsOpenWithWarn()` - an unclosed `(` falls open: always-true with ONE warn in the sink.
- `void strayCloseParenFailsOpenWithWarn()` - a stray `)` at the end of the line falls open with a warn.
- `void unquotedLegacyExpressionsKeepTheirTree()` - 1.0.0-style expressions (no quotes or parentheses) produce the same tree and the same results as before.

### CronNextRunTest
`src/test/java/com/sn/lib/CronNextRunTest.java`
13 tests over `com.sn.lib.cron.CronExpr.parse(String)` and `nextRun(ZonedDateTime)`: the 5-field cron plus `daily HH:MM` and `hourly :MM` shortcuts, with real timezone and DST handling (tested in UTC and America/New_York).

- `void stepFieldMatchesNextMultiple()` - step fields (`*/15`) match the next multiple, crossing the hour if needed.
- `void listAndRangeFieldsCombine()` - combined lists and ranges ("0,30 9-17 * * *"), including the jump to the next day when the hour range runs out.
- `void dayOfWeekFieldWaitsForMatchingDay()` - the day-of-week field waits for the next matching day (Friday -> Monday).
- `void sundayMatchesBothZeroAndSeven()` - Sunday matches with both 0 and 7.
- `void dailyShortcutIsStrictlyAfterFrom()` - `daily 04:00` is strictly after `from`: at the exact instant it returns the next day.
- `void hourlyShortcutMatchesEveryHour()` - `hourly :30` matches every hour, also strictly after.
- `void dayThirtyOneSkipsShorterMonths()` - day 31 skips short months (from April, the next 31st is May 31).
- `void februaryTwentyNinthWaitsForLeapYear()` - February 29 waits for the next leap year (2028 from 2026).
- `void springForwardShortensTheRealDelay()` - US DST start: the real delay shortens to 19h because the 02:00-02:59 hour does not exist.
- `void fallBackLengthensTheRealDelay()` - DST end: the real delay lengthens to 21h because the 01:00-01:59 hour repeats.
- `void wallClockErasedByDstGapSkipsToNextDay()` - a `daily 02:30` whose wall-clock hour is erased by the DST gap falls to the next day, not to a shifted hour.
- `void invalidExpressionsThrow()` - invalid expressions throw `IllegalArgumentException`: minute 61, 3 fields, letters, `daily 25:00`, `hourly :75`, empty and step 0.
- `void impossibleDateNeverMatches()` - an impossible date (February 31) throws `IllegalStateException` in `nextRun` instead of looping forever.

### LeaderboardSnapshotTest
`src/test/java/com/sn/lib/LeaderboardSnapshotTest.java`
9 tests over `com.sn.lib.leaderboard.LeaderboardCache.Snapshot` (`Snapshot.of(List<Entry>)`, `Snapshot.empty()`, `top(int)`, `positionOf(UUID)`, `valueOf(UUID)`, `size()`) and the `LeaderboardCache.Entry(UUID, String name, double value)` record: the immutable cache-only view fit for PlaceholderAPI resolvers.

- `void ordersByValueDescending()` - orders by value descending.
- `void tiesBreakByNameAscending()` - value ties break by ascending name.
- `void topClampsToSizeAndRejectsNonPositive()` - `top(n)` clamps to the real size and returns empty for n <= 0.
- `void positionsAreOneBasedAndZeroWhenUnranked()` - 1-based positions; 0 for an unranked or null uuid.
- `void valueOfReturnsCachedValueAndZeroWhenUnranked()` - `valueOf` returns the cached value; 0.0 for unranked or null.
- `void duplicateUuidKeepsBestPosition()` - a duplicate uuid keeps its best position and value.
- `void snapshotIsImmutable()` - `top`'s lists are immutable (`UnsupportedOperationException` on mutation).
- `void emptyAndNullInputsYieldEmptySnapshot()` - an empty or null list produces an empty snapshot; `Snapshot.empty()` answers empty and position 0.
- `void nullRowsAndNullNamesAreTolerated()` - null rows are discarded and null names normalize to "".

### CenterUtilTest
`src/test/java/com/sn/lib/CenterUtilTest.java`
9 tests over `com.sn.lib.text.CenterUtil.center(String)`: pixel-exact centering against the chat's 154px half-width, where color codes are invisible while measuring, bold widens glyphs and lines wider than the window pass intact.

- `void centersShortLineWithExactPixelMath()` - exact pixel math: "ab" (12px) compensates 148px in 4px spaces -> 37 leading spaces.
- `void emptyAndNullPassThrough()` - "" and null pass untouched (null returns the SAME reference).
- `void lineWiderThanWindowIsUnchanged()` - a line wider than the window returns unchanged (same instance, `assertSame`).
- `void colorCodesAreIgnoredWhileMeasuring()` - `&a` and hex `&#RRGGBB` codes count no pixels: same padding as the plain version.
- `void sectionSignCodesAreIgnoredWhileMeasuring()` - same with `§` codes.
- `void boldWidensTheMeasuredLine()` - `&l` adds 1px per non-space glyph: a long bold line needs fewer leading spaces.
- `void resetStopsBoldMeasurement()` - `&r` cuts bold measurement for the following characters.
- `void smallCapsLineMeasuresLikeUppercase()` - a small caps line (`SnText.smallCaps`) receives the same padding as its uppercase version: small glyphs base 5 = uppercase base 5 ("HELLO") and U+026A base 3 = 'I' base 3 ("HI").
- `void centeredGradientLineKeepsPayloadIntact()` - a line as it exits the `[rgb]` phase (one hex per character) keeps its payload intact and only the visible glyphs count (H+i+! = 10px -> 38 spaces). It documents the pipeline's real order: `[center]` runs after `[rgb]`.

### SmallCapsTest
`src/test/java/com/sn/lib/SmallCapsTest.java`
16 tests over `com.sn.lib.text.SmallCapsUtil.applySmallTag(String)` and the `[small]` tag composition in `com.sn.lib.text.SnText` (`applyPrefixTags`): the 1:1 small caps substitution with verbatim skipping of color codes, section-sign sequences and MiniMessage tags. Expected glyphs are written with `\uXXXX` escapes.

- `void lowercaseAlphabetMapsToSmallCaps()` - the full a-z alphabet maps exactly to the 26-glyph SMALL dictionary.
- `void uppercaseMapsLikeLowercase()` - "ABCXYZ" produces the same as "abcxyz" (case does not exist in small caps).
- `void enyeKeepsDefaultGlyph()` - the lowercase enye stays intact and the uppercase one lowers to the default lowercase enye U+00F1.
- `void accentedVowelsLoseAccent()` - accented vowels (and the u with diaeresis) of both cases de-accent to the small glyphs.
- `void digitsSymbolsAndSpacesPassThrough()` - digits, symbols and spaces pass intact.
- `void legacyColorCodesSkipped()` - `&a`/`&l` codes stay intact (the code's 'a' and 'l' do not map) and the visible text transforms.
- `void legacyHexCodesSkipped()` - `&#ff9b00` stays intact (the 6 lowercase hex digits do not map) and the following text transforms.
- `void sectionSignCodesSkipped()` - `§a` and the full 14-char bungee sequence stay intact; the visible text transforms.
- `void miniMessageTagsSkipped()` - `<bold>` and `</bold>` stay intact and the content between tags transforms.
- `void literalAngleBracketStillTransforms()` - a `<` without a closing `>` is literal and does not stop the transformation ("i<3" -> the i maps).
- `void outputLengthAlwaysEqualsInput()` - the 1:1 invariant: the output measures the same as the input for representative inputs (alphabet, codes, tags, a mixed line).
- `void unchangedLineReturnsSameInstance()` - `assertSame`: a line with no mappable letters and an already small caps string return the same instance.
- `void nullAndEmptyPassThrough()` - null returns null and "" returns the same instance.
- `void tagIsCaseInsensitive()` - `[SMALL]` and `[small]` render identically in `SnText.applyPrefixTags`.
- `void smallAndRgbComposeInAnyOrder()` - `[small][rgb]` == `[rgb][small]` (fixed internal application order).
- `void centerMarkSurvivesSmall()` - `[center][small]hi` re-emits the leading `[center]` mark with the rest already in small caps.

### YamlUpdaterTest
`src/test/java/com/sn/lib/YamlUpdaterTest.java`
12 golden tests over `com.sn.lib.yml.YamlUpdater` (`merge(List<String>, List<String>)`, `prune(List<String>, List<String>)`, `isParseable(String)`): the always-merge config updater. Contract tested: missing keys land WITH their comments at the anchored position, the user's values and extra keys stay intact, no version marker key exists, and key quoting normalizes on comparison. All assertions compare `List<String>` (line-by-line text, not parsed trees). Fixtures: `merge-resource.yml`, `merge-old.yml`, `merge-expected.yml`, `corrupt.yml`.

- `void mergeMatchesGoldenExpected()` - merging the new resource over the old disk reproduces the golden `merge-expected.yml` byte for byte.
- `void mergePreservesUserValuesAndExtraKeys()` - the user's values (`rows: 3`, a custom title, prefix) and their extra `custom-flag` key survive.
- `void mergeInsertsNewKeysWithTheirComments()` - a new key lands with its comment attached on the previous line, anchored between its resource neighbors.
- `void mergeInsertsWholeMissingSubsection()` - a whole missing subsection (`storage:` with header, `type` and `table-prefix`) inserts between its resource siblings (`settings:` before, `messages:` after).
- `void pruneIsOptInAndRemovesOnlyKeysAbsentFromResource()` - `prune` is opt-in: the default merge keeps `custom-flag`; the prune removes it along with its comment, keeps the user's values in shared keys and the result still parses.
- `void pruneIsANoOpWhenDiskMatchesResourceStructure()` - prune over an identical structure is an exact no-op.
- `void mergeIsIdempotentOnUpToDateFile()` - a merge over an already-updated file is idempotent (equal to the expected).
- `void mergedResultHasNoVersionMarkerAndStaysParseable()` - the result contains `config-version` in no line and is parseable YAML.
- `void corruptYamlIsDetectedAsUnparseable()` - `isParseable` detects the corrupt fixture as invalid and the healthy fixture as valid.
- `void quotedAndUnquotedKeysCompareEqualOnMerge()` - resource `foo: 1` against disk `'foo': 2`: the merge inserts nothing and the disk value stays intact.
- `void quotedResourceKeyInsertsWithItsTextualForm()` - a quoted resource key (`"bar": 3`) absent from disk inserts keeping the resource's quotes.
- `void pruneKeepsKeyWhenOnlyQuotingDiffers()` - a prune with resource `foo:` and disk `"foo":` does not delete the block (only the quoting differs).

### SnItemAttributeParseTest
`src/test/java/com/sn/lib/item/SnItemAttributeParseTest.java`
9 tests over the package-private helpers of `com.sn.lib.item.SnItem` for attribute modifiers: `attributeKeyCandidates(String)` (the lenient resolution candidates of the 1.21.2+ rename) and `legacySlot(String)` (the slot-group to legacy `EquipmentSlot` mapping). Pure JVM: the helpers do not touch Bukkit runtime.

- `void genericPrefixedNameYieldsModernAndLegacyCandidates()` - "GENERIC_MOVEMENT_SPEED" produces "generic_movement_speed", "movement_speed" and "generic.movement_speed".
- `void bareModernNameYieldsLegacyAlias()` - "ARMOR" produces "armor" and the inverse alias "generic.armor".
- `void playerPrefixYieldsDottedForm()` - "PLAYER_BLOCK_INTERACTION_RANGE" produces "player.block_interaction_range" and "block_interaction_range".
- `void namespacedInputNormalizes()` - "minecraft:generic.armor" normalizes to "generic.armor".
- `void candidatesHaveNoDuplicates()` - the candidate list has no duplicates for representative inputs.
- `void anyArmorAndBodyMapToNull()` - ANY/ARMOR/BODY/null/blank map to null (no slot = any slot).
- `void mainhandMapsToHand()` - MAINHAND (both cases) maps to `EquipmentSlot.HAND`.
- `void offhandMapsToOffHand()` - OFFHAND maps to `EquipmentSlot.OFF_HAND`.
- `void feetMapsDirect()` - FEET maps directly to `EquipmentSlot.FEET`.

### ClickGuardTest
`src/test/java/com/sn/lib/action/ClickGuardTest.java`
7 tests over the package-private helpers of `com.sn.lib.action.ActionEngine` for click guards: `matchesExactClickGuard(String, ClickType)` (the named guard matrix) and `parseClickTypes(String)` (the fail-closed parse of the `[click=...]` spec). Pure JVM: it only uses the Bukkit API's `ClickType` enum.

- `void rightClickOnlyExcludesShiftAndDouble()` - `[right-click-only]` passes only with RIGHT: excludes SHIFT_RIGHT, DOUBLE_CLICK and CREATIVE.
- `void leftClickOnlyExcludesShiftDoubleAndCreative()` - `[left-click-only]` passes only with LEFT: excludes SHIFT_LEFT, DOUBLE_CLICK and CREATIVE.
- `void inclusiveGuardsKeepLegacySemantics()` - `[right-click]`/`[left-click]` keep the inclusive v1.0.0 semantics: right passes with SHIFT_RIGHT and left passes with SHIFT_LEFT, DOUBLE_CLICK and CREATIVE.
- `void sugarGuardsMatchExactly()` - the sugar guards match exactly ONE ClickType: `[middle-click]` MIDDLE, `[double-click]` DOUBLE_CLICK, `[drop-click]` DROP (CONTROL_DROP does not pass), `[number-key]` NUMBER_KEY, `[swap-offhand]` SWAP_OFFHAND.
- `void shiftGuardsUnchanged()` - `[shift-right-click]`/`[shift-left-click]` stay exact: they do not pass with plain RIGHT/LEFT.
- `void parseClickTypesAcceptsCaseInsensitiveAndDashes()` - the spec accepts case-insensitive names and `-` as `_` ("right", "number-key", "MIDDLE,DROP", "swap_offhand").
- `void parseClickTypesRejectsInvalidWholesale()` - total fail-closed: an unknown name ("RIGTH"), an empty spec or an empty token ("RIGHT,,LEFT") return null (the whole line does not run).

### ClickResolutionTest
`src/test/java/com/sn/lib/gui/ClickResolutionTest.java`
6 tests over the package-private helpers of `com.sn.lib.gui.GuiItemDef` that support the per-click matrix resolution and the strict-clicks gate: `shiftKey(ClickType)`, `sideKey(ClickType)` and `basicClick(ClickType)`. Pure JVM.

- `void shiftKeyMapsOnlyShiftClicks()` - `shiftKey` maps ONLY SHIFT_RIGHT/SHIFT_LEFT to their keys; everything else (including null) returns null.
- `void sideKeyGroupsRightFamily()` - the RIGHT side groups RIGHT and SHIFT_RIGHT.
- `void sideKeyGroupsDoubleClickAndCreativeWithLeft()` - the LEFT side groups LEFT, SHIFT_LEFT, DOUBLE_CLICK and CREATIVE (consistent with `isLeftClick()`).
- `void sideKeyMapsMiddle()` - MIDDLE maps to its own side.
- `void sideKeyNullForKeyboardAndUnknownClicks()` - NUMBER_KEY, DROP, CONTROL_DROP, SWAP_OFFHAND and UNKNOWN have no side (null: they fall to the generic and in strict they stay discarded).
- `void basicClickIsExactlyTheFourMouseClicks()` - `basicClick` is true exactly for LEFT/RIGHT/SHIFT_LEFT/SHIFT_RIGHT and false for everything else including null (the strict-clicks gate predicate).

### ItemDefVariantsTest
`src/test/java/com/sn/lib/item/ItemDefVariantsTest.java`
4 tests over the shift-positional variants and the `shift-overrides-generic` flag of a builder-built `com.sn.lib.item.ItemDef`. Pure JVM (the callbacks are lambdas never invoked).

- `void shiftPositionalListsDefaultEmpty()` - the 4 shift-positional lists default empty and their 4 callbacks default null.
- `void builderSetsShiftPositionalListsAndCallbacks()` - the builder sets the 4 lists and the 4 callbacks and the getters return exactly what was set (same callback instances).
- `void shiftOverridesGenericDefaultsTrue()` - `shiftOverridesGeneric()` defaults true.
- `void builderDisablesShiftOverridesGeneric()` - the builder can turn it off (on shift-click both variants of the pair run).

### CuboidTest
`src/test/java/com/sn/lib/CuboidTest.java`
14 tests over the pure core of `com.sn.lib.region.Cuboid` (containment, iteration, size, expand and serialization); pure JVM: no test touches the bridge methods that require a World.

- `void normalizesCornersOnConstruction()` - the factory normalizes inverted corners (min <= max per axis).
- `void containsIsEdgeInclusive()` - contains accepts the 8 corners and the 6 face centers (inclusive edges).
- `void containsRejectsOutsidePoints()` - a block outside each of the 6 sides is rejected.
- `void containsIsWorldAware()` - the worldName variant rejects another world and null.
- `void sizeMatchesInclusiveVolume()` - size is the inclusive volume in long (a 2M x 301 x 2M cuboid exceeds Integer.MAX_VALUE without overflow).
- `void forEachVisitsEveryBlockExactlyOnce()` - forEach visits every block exactly once (27 in a 3x3x3).
- `void forEachOnSingleBlockVisitsOne()` - a one-block cuboid visits exactly that block.
- `void intersectsDetectsOverlapTouchingAndDisjoint()` - intersects: overlapping true (symmetric), touching true, disjoint false, another world false.
- `void expandGrowsBothDirectionsAndClampsCollapse()` - expand grows in both directions per axis, a collapsing shrink clamps to 1 width without throwing, and the original instance stays intact (immutability).
- `void serializeUsesNormalizedMinMaxOrder()` - serialize emits a normalized `world;minX;minY;minZ;maxX;maxY;maxZ`.
- `void serializeDeserializeRoundTripPreservesEquality()` - the serialize/deserialize round-trip preserves equals and hashCode.
- `void deserializeTrimsPartsLikeLocationSerializer()` - deserialize trims spaces around each part.
- `void deserializeReturnsNullOnMalformedInput()` - null, empty, a wrong part count, an invalid number or an empty world return null without throwing.
- `void deserializeRenormalizesSwappedCorners()` - a string with inverted corners re-normalizes on deserialization.

### LocationUtilTest
`src/test/java/com/sn/lib/LocationUtilTest.java`
3 tests over the null-safe paths of `com.sn.lib.util.LocationUtil` (the only ones testable without a World: `new Location(null, ...)` touches no Bukkit statics); the world-aware positive paths are covered by the delegation to Cuboid (already tested) plus the release's manual smoke.

- `void inCuboidNullSafePathsReturnFalse()` - inCuboid with a null location/corner or a worldless location returns false without throwing.
- `void distance2dNullSafePathsReturnInfinity()` - distance2d/distance2dSquared with null or worldless return Double.POSITIVE_INFINITY.
- `void distanceToBoxNullSafePathsReturnInfinity()` - distanceToBoxSquared with a null box/location or a worldless location returns Double.POSITIVE_INFINITY.

### Runtime smoke gate

Besides the JVM suites, the lib passed the manual smoke gate on a real server, at both ends of the supported matrix, for both the 1.0.0 and the 1.1.0 release:

- Paper 1.21.8 build 60 (target): green on 1.0.0 and on 1.1.0.
- Paper 1.20.4 build 499 (runtime floor): green on 1.0.0 and on 1.1.0.

On 1.20.4 startup runs in degraded mode via `SnCompat.probe` (1.21+ features off with a WARN); the smoke validates that SnLib as a standalone plugin turns on and off cleanly on both versions.

Record of the v1.1.0 gate (Step 22 of the v1.1 plan; JVM Java 21 Temurin 21.0.8, the `SnLib-1.1.0.jar` installed in each local Paper's `plugins/`): on both versions a startup without errors or exceptions with `SnLib 1.1.0 enabled (API level 2)`; `/snlib version` answers `SnLib version: 1.1.0` + `API level: 2` + the server version (`1.21.8-R0.1-SNAPSHOT (detected: 1.21.8)` and `1.20.4-R0.1-SNAPSHOT (detected: 1.20.4)`); `/snlib plugins` and `/snlib integrations` respond; bStats (service id 32541) initializes without exception (data appearing on the bstats.org panel is asynchronous: NON-blocking post-deploy verification); zero `NoSuchMethodError`/`NoClassDefFoundError` on 1.20.4; clean shutdown without leaks in the console. The 1.20.4 degradation WARNs (setMaxStackSize/glint and the AttributeModifier UUID fallback) only fire from a consumer exercising those probes: the lib alone has none to fire and they stay covered by the pilots, same criterion as in v1.0.0. The selection wand's DUST particle resolves on 1.20.4 via the bidirectional DUST/REDSTONE alias of `SelectionRenderer.resolveParticle` (FLAME fallback with a single WARN if the name does not resolve).

### TODOs and limitations

Result of the `TODO|FIXME|XXX|placeholder|PENDIENTE` grep over `src/` and `README.md`: NO TODO/FIXME/XXX comment exists in the source code. All "placeholder" matches are domain terminology (PlaceholderAPI, local placeholders), the README "TODO" match (line 156) was the Spanish word "todo" ("everything") in the phrase "root/sub tree, everything tab-completable", and the only "pendiente" matches are runtime WARN message text from `YamlUpdater` ("[update-configs] update-configs is false: prune pending in <file>") and README prose about write coalescing ("at most one pending write per file"), not pending code tasks.

Known real pendings (v1.0.0 handoff):

- bStats: RESOLVED in v1.1 - the real service id `32541` registered on bstats.org (`private static final int BSTATS_SERVICE_ID = 32541` in `src/main/java/com/sn/lib/SnLibPlugin.java`); only the post-deploy verification that the panel receives data remains.
- The 1.20.4 degradation WARN (SnCompat-gated features off) is not exercisable end to end without a consumer plugin using those features: deferred to the pilots.
- Private GitHub repo + v1.0.0 release: pending confirmation.
- Post-release update of `sn-core/SKILL.md` and the `sn-deploy`/`sn-change` skills for the standalone hard-depend model: pending.
- SnTags and SnCrates pilots consuming SnLib, with a 48h canary on a production server: pending.
- japicmp: RESOLVED in v1.1 - the additive-only gate is ACTIVE with an explicit `oldVersion` `com.sn:snlib:1.0.0` and `ignoreMissingOldVersion=false` (missing baseline = broken build); the baseline re-pins to 1.1.0 in the next release plan.
---
## 17. UpdateChecker (v1.1)

Update-check module FOR THE CONSUMER PLUGINS (not for SnLib itself): each consumer configures it against ITS GitHub repo and receives notices when a release newer than the installed version exists.

### UpdateChecker
`src/main/java/com/sn/lib/update/UpdateChecker.java`

`public final` class of the new `com.sn.lib.update` package; one instance per context, reached via `sn.updates()` (an always-available accessor, it never throws).

The module's hard contract:

- **STRICT and PERMANENT notify-only**: it never downloads artifacts, never touches the running jar, never does any kind of auto-swap (also incompatible with the reload-never-reloads-classes model). The only outputs are a console INFO on detecting a new version and a chat notice to permissioned players on join.
- **100% opt-in**: a consumer that declares no `SnSpec.builder().updates("owner/repo")` and calls no `watch()`/`checkNow()` generates NO traffic or state (the accessor returns an inert instance).
- **Two repo modes (v1.4)**: a repo dedicated to one plugin (`updates(ownerRepo)`, polls `releases/latest`), or a repo SHARED by several plugins (`updates(ownerRepo, tagPrefix)`, polls the `releases` list and keeps only tags starting with `tagPrefix`, taking the highest matching version). The shared mode exists so an ecosystem of plugins can publish to ONE public releases repo instead of one public repo per plugin; tags there follow `<pluginId>-vX.Y.Z` (for example `snclans-v1.4.0`, prefix `"snclans-"`).

Public API:

- `public UpdateChecker(Sn ctx, @Nullable SnYml config)` - instantiated by the context at construction; `config` is the consumer's mounted main config (or `null` without the yml module).
- `public void watch(String ownerRepo)` / `public void watch(String ownerRepo, @Nullable String tagPrefix)` - arms the notify-only periodic check: the first check 60s after enable (`INITIAL_DELAY_TICKS = 1200`), then every 6 hours (`PERIOD_TICKS = 432000`), ALWAYS off-main (`timerAsync`). `tagPrefix == null` (or the single-arg overload) means "repo dedicated to this plugin"; a non-null prefix means "shared repo, filter by prefix". An invalid repo format (regex `^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`): WARN "updates: invalid repo '<x>'; expected owner/repo format" and it does nothing. A re-watch of the same repo replaces and cancels the previous timer. The watch lives per enable: the consumer's reload neither re-arms nor duplicates it.
- `public void checkNow(String ownerRepo)` / `public void checkNow(String ownerRepo, @Nullable String tagPrefix)` - ONE immediate off-main check without a timer; the "explicit call" path for consumers that do not declare the repo in their spec. Same format validation and prefix semantics as `watch`.
- `public static Listener joinListener()` - the shared `PlayerJoinEvent` listener defined here and enrolled in the ListenerHub (the `registerEvents` happens solely in the SnLibPlugin bootstrap).
- `public void shutdown()` - idempotent teardown invoked by step 12 of `Sn.shutdown()`: it cancels all watch timers and releases the HttpClient. This owner's STATES entry is NOT touched here: step 13's `TenantRegistry.sweepOwner` sweeps it.

#### Internal logic

- Dedicated-repo mode: GET `https://api.github.com/repos/<repo>/releases/latest`. Shared-repo mode (`tagPrefix` non-null/non-empty): GET `https://api.github.com/repos/<repo>/releases?per_page=100` (the list endpoint) instead. Same headers either way: `Accept: application/vnd.github+json`, `X-GitHub-Api-Version: 2022-11-28`, `User-Agent: SnLib-UpdateChecker`; timeouts 5s connect / 10s request (the same values as DiscordWebhook), a lazy per-instance HttpClient with double-checked locking (the exact `DiscordWebhook.client()` pattern), synchronous `send` (it already runs on an async thread).
- **Optional token for PRIVATE repos**: `update-check.token` reads from the CONSUMER's main config on EVERY check (it picks up changes without restart) and is sent as `Authorization: Bearer <token>` only if non-empty. A read-only token recommended; it is NEVER logged.
- Failures (status != 200, IOException, InterruptedException with re-interruption, or in shared mode no tag matching the prefix): **warn-once per repo per enable** ("update check of '<repo>' failed: <detail>") and silence afterwards.
- Parsing without a JSON library: the package-private helper `static @Nullable String jsonString(String body, String field)` (a hand scanner: the first occurrence of quoted `"field"`, skips spaces and `:`, demands an opening `"` and reads up to the closing one un-escaping `\"`, `\\` and `\/`; an unexpected format returns null; documented assumption: in the releases/latest payload the first occurrence of `html_url` is the release's) - both `jsonString` and the new list scanner share the underlying `stringValueAt(body, index, field)` reader. For shared-repo mode, `static List<ReleaseTag> parseReleaseTags(String body)` scans a `releases` array and pairs every `tag_name` with the nearest PRECEDING `html_url` (safe because each release object emits its own `html_url` once, before its `tag_name`, and asset entries carry `url` but never `html_url`). And `static String stripTagPrefix(String tag)`: trim + strips the leading `v`/`V` ONLY if a digit follows (`v1.4.0` -> `1.4.0`, `vanilla` intact) - applied to a matched tag AFTER the plugin's `tagPrefix` is stripped off in shared mode.
- Shared-mode selection: among `ReleaseTag`s whose `tag()` starts with `tagPrefix`, the prefix is stripped, then `stripTagPrefix`, then the highest version wins via `SemverComparator.compareVersions`. No match -> warn-once "no release tag matching prefix '<prefix>'".
- Detection: `SemverComparator.compareVersions(latest, current) > 0` against `ctx.plugin().getPluginMeta().getVersion()`. On detection it stores a `Finding(latest, current, url)` in the owner's state, emits the INFO "Version <latest> available, installed <current>." (no URL in console) and sends the chat notice to admins already online holding `<plugin>.admin.update` - ONLY on the first detection or on a new release (no re-INFO every 6h); if there is no longer an update the entry is removed. Joining admins keep receiving the notice through the shared join listener while the finding exists.
- Multi-tenant state: `private static final TenantRegistry<UpdateState> STATES` (justified server-wide static: the shared listener reads it; keyed by owner with automatic sweep on disable). `UpdateState` holds the ctx, the `<plugin>.admin.update` permission (lowercased plugin name) and the repo -> `Finding` map. The state registers exactly ONCE per instance (compareAndSet) on the first `watch`/`checkNow`.
- Join notice: the `JoinListener` walks `STATES.forEachOwner`; for each state with pending findings and a player with `state.permission`, it schedules `syncLater(40 ticks)` which re-checks `player.isOnline()` and sends per finding `&e<Plugin> &7has a new version: &a<latest> &7(installed &c<current>&7) &f<url>` via `SnText.color`. A consumer wanting default-op must declare `<plugin>.admin.update` in ITS plugin.yml; without declaring it only those with the explicit permission receive it.

#### Notes and gotchas

- GitHub PATs contain no `%`: `SnYml.getString`'s placeholder pipeline is a no-op over the token.
- `checkNow` and `watch` share the warn-once: the `warnedRepos` set is per instance (per enable).
- ManticLib's Versionator (jar download + auto-swap) stands recorded as the anti-example: that capability is forbidden forever in this module.

### UpdateCheckerJsonTest
`src/test/java/com/sn/lib/update/UpdateCheckerJsonTest.java`
7 pure JUnit tests (no Bukkit init) over the package-private helpers `jsonString`, `parseReleaseTags` and `stripTagPrefix`.

- `void jsonStringExtractsTagName()` - a minimal release payload: extracts `v1.4.0` from `tag_name`.
- `void jsonStringFirstHtmlUrlWins()` - with the release's `html_url` and another inside `author`, it returns the first.
- `void jsonStringHandlesEscapedQuotes()` - un-escapes `\"`, `\\` and `\/` inside the value.
- `void jsonStringMissingFieldReturnsNull()` - a missing field, a non-string value and an unclosed string return null.
- `void stripTagPrefixStripsVOnlyBeforeDigit()` - `v1.2.3` -> `1.2.3`, `V2.0` -> `2.0`, `1.2.3` and `vanilla` intact.
- `void parseReleaseTagsPairsEachTagWithItsOwnHtmlUrl()` (v1.4, shared-repo mode) - a two-release array, each pairing its `tag_name` with the nearest preceding `html_url`; asserts neither the nested `author.html_url` nor an asset's `url` is picked up.
- `void parseReleaseTagsHandlesEmptyList()` (v1.4) - `"[]"` and `null` both return an empty list.
- Handoff consistency note: the handoff mentions "114 tests"; the last count verified in this documentation was 204 tests across 21 suites (see prior revisions for the full step-by-step history). Current verified count (surefire, `mvn test`), including the two `parseReleaseTags` tests added for the v1.4 shared-releases-repo feature, is 213 tests, all green.

## 18. Region: cuboid selection (v1.1)

New module `com.sn.lib.region` (a generalized port of SnGens' Admin Wand): HIGHLY visual cuboid selections for any consumer, with a PDC-tagged wand, particle edge rendering, configurable limits and callbacks. Always available via `sn.selections()` (no spec gate, like `actions()` or `cooldowns()`): it is 100% programmatic and its idle cost is an empty map plus one quit-cleanup registration. The `com.sn.lib.region.internal` package (listener + renderer) sits outside the semver contract per the `*.internal` package rule (and outside the japicmp analysis via the already-configured `com.sn.lib.**.internal.**` pattern).

A consumer's canonical flow:

```java
SelectionSpec spec = SelectionSpec.builder("arena")
    .permission("myplugin.wand")
    .wandItem(SnItem.builder(Material.GOLDEN_AXE).name("&6&lArena Wand").glow())
    .particle("DUST").dustColor("255, 140, 0").dustSize(1.2f)
    .step(0.5).refreshIntervalTicks(5).renderDistance(48)
    .maxVolume(100_000)
    .onSelect(cuboid -> arenas.saveRegion(cuboid))
    .build();

sn().selections().giveWand(player, spec);
// left click = pos1, right click = pos2, edges rendered live;
// on completion: SnSelectionCompleteEvent (cancelable) -> onSelect(Cuboid)
```

### Cuboid
`src/main/java/com/sn/lib/region/Cuboid.java`

An immutable BLOCK cuboid, thread-safe by construction: a `worldName` string plus 6 `int`s ALWAYS normalized (min <= max per axis) in the factory; inclusive edges in `contains`. The core (containment, iteration, size, serialization) is pure and touches no Bukkit statics; only the bridge methods (`of(Location, Location)`, `contains(Location)`, `world()`, `blocks()`, `center()`) do, so the core is testable in plain JUnit (`CuboidTest`).

- Factories: `of(String worldName, x1, y1, z1, x2, y2, z2)` (normalizes; a null/blank worldName throws `IllegalArgumentException`) and `of(Location a, Location b)` (block coords; null, an unloaded world or different worlds THROW: a programmer path, fail fast).
- Pure getters: `worldName()`, `minX()/minY()/minZ()/maxX()/maxY()/maxZ()`, `widthX()/heightY()/depthZ()` (inclusive count), `size()` in `long` (never int overflow).
- Containment: `contains(int, int, int)` (pure, ignores world), `contains(String, int, int, int)` (world-aware), `contains(@Nullable Location)` (null/worldless/different world -> false, never throws), `intersects(Cuboid)` (different worlds -> false; inclusive overlap: touching on an edge counts).
- Derivatives: `expand(dx, dy, dz)` returns a NEW instance that grows (or shrinks with negatives) in BOTH directions per axis; if min crosses max while shrinking, that axis collapses to 1 block at the midpoint (never throws).
- Iteration: `forEach(BlockConsumer)` (pure, visits every block once in x -> y -> z order) and `blocks()` (a LAZY Bukkit bridge that never materializes lists; an unloaded world -> an empty Iterable, never throws).
- Bridges: `world()` (null if not loaded) and `center()` (the geometric center +0.5 per axis, null without a world).
- Serialization: `serialize()` emits `world;minX;minY;minZ;maxX;maxY;maxZ` (normalized order) and `deserialize(String)` is the null-safe inverse that NEVER throws (null/blank, != 7 parts, a malformed number or a blank world -> null; each part trims; corners re-normalize; it does NOT require a loaded world, binding is lazy via `world()`). A SIBLING format of `LocationSerializer`'s (same `;` separator, per-part trim, lenient deserialize) but NOT parseable by it (that class expects 4 or 6 parts).
- `equals`/`hashCode` by worldName + the 6 coords; `toString()` is the serialized form.

Documented throw-vs-null policy: `of(Location, Location)` throws (the programmatic path) and `deserialize` returns null (the data/config path), consistent with the util package's philosophy.

### SelectionSpec
`src/main/java/com/sn/lib/region/SelectionSpec.java`

An immutable declaration of a selection class (wand + visuals + limits + callbacks), identified by a short `id` that travels in the wand's PDC. Builder pattern; ALL fields have defaults and clamps apply once in `build()`. The visual fields are stored PURE (the particle by NAME, the dust color as 3 ints): resolution to Bukkit types lives in the renderer.

- `builder(String id)` (a null/blank id -> "default") and `toBuilder()` (copies all fields to a new builder, for composing callbacks over a YML-loaded spec).
- `public static SelectionSpec fromConfig(Sn ctx, SnYml yml, String path, String id)` - reads the YML section of the golden spec `docs/selection-example.yml`: absent fields fall to the builder defaults; invalid values WARN once and fall to the default (SnYml's typed getters already resolve fallback + type WARN). The `item` section maps via `SnItem.fromConfig` ONLY if it exists (absent -> the manager's BLAZE_ROD fallback); an empty or absent `permission` -> null (no gate); `visibility` is a lenient enum (invalid -> OWNER_ONLY + WARN once). Canonical composition: `SelectionSpec.fromConfig(sn(), sn().yml().config(), "selection-wand", "arena").toBuilder().onSelect(...).build()`. No new SnSpec module: the YML integration is 100% consumer opt-in.
- Getters with defaults and clamps: `permission()` (null = no gate), `wandItem()` (null = fallback), `particleName()` (default "DUST"), `dustRed()/dustGreen()/dustBlue()` (defaults 255, 140, 0; clamp 0..255), `dustSize()` (default 1.2, clamp 0.1..4.0), `step()` (default 0.5, min 0.1), `refreshIntervalTicks()` (default 5, min 1), `renderDistance()` (default 64), `visibility()` (the nested enum `Visibility { OWNER_ONLY, WORLD }`, default OWNER_ONLY), `particleBudget()` (default 2000, per refresh PER viewer), `maxRenderVolume()` (default 250000), `maxVolume()` (default 0 = no cap), `timeoutTicks()` (default 0 = never expires), `completeEnds()` (default false), `silent()` (default false).
- Optional callbacks: `onSelect()` (`Consumer<Cuboid>`), `onUpdate()` (`Consumer<SelectionSession>`) and `onCancel()` (`Consumer<UUID>`: a UUID and not a Player because on quit/kick the Player may no longer be valid, and quit callbacks must be idempotent per the QuitCleanupListener contract).
- Fluent builder: one method per field, including `wandItem(SnItem)` / `wandItem(ItemStack)` (cloned template; each replaces the other), `particle(String)` / `particle(Particle)` (sugar storing `name()`), `dustColor(int, int, int)` / `dustColor(String)` (a lenient pure parse of `"R, G, B"` or `"#RRGGBB"`; malformed keeps the current one + WARN once per input).

### SelectionManager
`src/main/java/com/sn/lib/region/SelectionManager.java`

Selection module of an Sn context (`sn.selections()`): it registers specs by id, hands out tagged physical wands and owns one `SelectionSession` per selecting player. Session mutations are main-thread only.

Public constant: `WAND_TAG = "snlib_selection_wand"` (a PDC key, owner-namespaced via TagIo; the VALUE is the spec id, NOT a random UUID: identical wands stack without consequences and the id resolves the spec after a relog).

Justified server-wide static (the ItemPropertyListener.track pattern): `TenantRegistry<SelectionManager> MANAGERS` with the `shutdownQuietly` callback; its two only purposes are (a) letting the shared listener resolve a wand's owning manager by its PDC key namespace, (b) a double sweep net if the owner never shut down.

Public API:

- `begin(Player, SelectionSpec)` - registers the spec and opens a new session, first cancelling THIS context's previous one (renderer cut + the previous spec's onCancel; no event or message). It hands out no wand (compose with `giveWand`).
- `current(Player)` / `current(UUID)` - the active session or null.
- `cancel(Player)` / `cancel(UUID)` - cuts the renderer, removes the session and runs the spec's `onCancel` with the UUID (Throwable try/catch + WARN). Idempotent.
- `registerSpec(SelectionSpec)` - registers or replaces by id WITHOUT opening a session; it exists for the "register in onInnerEnable, give wands later by command" pattern: an old wand in an inventory works again as soon as its spec is registered (the listener auto-opens the session on the first click).
- `createWand(SelectionSpec)` - registers the spec and builds the physical wand (a rendered SnItem, a cloned template or the BLAZE_ROD "&6&lRegion Wand" fallback) with the PDC tag.
- `giveWand(Player, SelectionSpec)` - createWand + `InvUtil.giveItems` (overflow at the feet).
- `isWand(ItemStack)` / `wandSpecId(ItemStack)` - check/read of THIS context's tag (null/air/meta-less -> false/null).
- `shutdown()` - idempotent teardown invoked by step 4 of `Sn.shutdown()` and by the sweep as a double net: it cuts every render task (cancel with catch, the scheduler may be dying) and clears sessions and specs. It deliberately runs NO `onCancel` (running consumer callbacks during teardown is dangerous, same policy as the GUI close-actions).
- Public internal bridges outside the consumer contract: `forNamespace(String)` and `handleWandClick(...)` (for the shared listener), `handleRendererOffline(UUID)` and `handleRendererTimeout(UUID)` (for the renderer, which lives in `region.internal` and cannot reach the package-private members).

Completion pipeline (the contract's heart; it runs on every pos set by wand or setter): (1) stores the cloned pos and refreshes the renderer; (2) the `pos1-set`/`pos2-set` message (and `different-worlds` if both positions ended up in different worlds: an explicit notice instead of SnGens' confusing silence); (3) the spec's `onUpdate`; (4) if `hasBothPositions()`: builds the `Cuboid`; if `maxVolume > 0` and `size() > maxVolume` it sends `too-big` (placeholders `{volume}` `{max}`), the pos STAYS set and there is NO event or onSelect; (5) past the cap it fires `SnSelectionCompleteEvent` (binding cancelable); (6) if it survives it runs `onSelect(cuboid)` (try/catch + WARN); (7) the session does NOT close itself except with `completeEnds()` (cancelSilently: no onCancel, the selection ended well).

Messages: resolved via `ctx.lang()` if the module is declared (the `langOrNull()` pattern); without lang it sends the embedded English defaults (a mirror of `snlib-messages.yml`) through `SnText.color`. With `silent()` it sends nothing.

Reload policy: the reload does NOT touch selections (transient player state, not file-derived; a mid-selection reload does not steal the admin's selection); renderers keep running; if the consumer rebuilds specs from YML in its Reloadable, `registerSpec` replaces by id and live sessions keep pointing at the old spec (immutable, no inconsistent state) until the next begin/click. ReloadManager is not modified.

Complete cleanup: quit/kick via `QuitCleanupListener.register` (onQuit = cancel, idempotent because a kick fires kick and quit); `clearPositions` cuts the renderer with the session alive; every `refreshRenderer` re-arms; `shutdown()` from step 4 of the Sn teardown; a double net via MANAGERS' `shutdownQuietly` callback (step 13 of the shutdown and the TenantSweeper of an owner that never closed); SnLib's own disable falls into the existing `TenantSweeper.cascadeAll` cascade with no new code.

### SelectionSession
`src/main/java/com/sn/lib/region/SelectionSession.java`

Per-player mutable state, owned by the manager; main-thread only. It is NEVER persisted to disk (transient state); to persist the result use `Cuboid.serialize()`.

- `playerId()`, `spec()`, `createdAtMillis()` (creation instant; the spec's timeout counts from there).
- `pos1()` / `pos2()` - defensive copies (clone), null if not set.
- `setPos1(Location)` / `setPos2(Location)` - programmatic setters with the SAME consequences as a wand click (renderer refresh, messages, onUpdate and the completion pipeline); null clears that pos. They delegate to the manager.
- `hasBothPositions()` - both != null, with a loaded world and the SAME world by name.
- `cuboid()` - `Cuboid.of(pos1, pos2)` if `hasBothPositions()`, otherwise null.
- `clearPositions()` - clears both positions and cuts the renderer; the session stays alive.

### SnSelectionCompleteEvent
`src/main/java/com/sn/lib/event/SnSelectionCompleteEvent.java`

`public final class SnSelectionCompleteEvent extends SnPlayerEvent` (the getHandlers/getHandlerList pair, the SnArmourEquipEvent pattern). Synchronous, main thread, dispatched via `event.call()`.

- Constructor `(Player player, Plugin owner, String specId, Cuboid cuboid)`; accessors `owner()` (the owning consumer plugin), `specId()` and `cuboid()` (immutable, shareable without copying).
- BINDING cancellation: cancelled, `onSelect` does NOT run and the session stays alive (the player can re-click). It lets protection/staff plugins veto foreign selections (a real multi-tenant case).
- It is the module's ONLY event by design: there is NO SnSelectionUpdateEvent because every click would fire a server-wide event; the spec's `onUpdate` callback covers that case at zero global cost (addable additively later if the need appears).

### SelectionWandListener (internal)
`src/main/java/com/sn/lib/region/internal/SelectionWandListener.java`

Final shared listener, owned by SnLib, enrolled as listener 14 of the ListenerHub (`registerEvents` happens SOLELY in the SnLibPlugin bootstrap). A single handler `@EventHandler(priority = EventPriority.LOWEST, ignoreCancelled = false)` on `PlayerInteractEvent`.

Exact check order (layered quick exit, hot-path contract): (1) action LEFT_CLICK_BLOCK or RIGHT_CLICK_BLOCK (air and PHYSICAL are ignored; no raytrace, additively extendable); (2) `getHand() == EquipmentSlot.HAND` (discards the dual-fire's offhand echo); (3) null/air/meta-less item; (4) a PDC key scan for `snlib_selection_wand` and resolution of the owning manager by namespace (`SelectionManager.forNamespace`, the same pattern as ItemPropertyListener.match); (5) null clickedBlock; (6) `event.setCancelled(true)` BEFORE the permission (a preserved SnGens decision: a wand in hand never breaks/uses blocks, not even without permission; LOWEST + ignoreCancelled=false makes the wand win over land protections by being an administrative tool); (7) it delegates to `manager.handleWandClick` (an unregistered spec -> a debug note without spamming the player; the permission gate with the `no-permission` message; session auto-begin, which makes the wand work after a relog; LEFT = pos1, RIGHT = pos2 and the completion pipeline).

### SelectionRenderer (internal)
`src/main/java/com/sn/lib/region/internal/SelectionRenderer.java`

`public final class SelectionRenderer implements Runnable`: ONE instance per session with an active renderer, armed by `SelectionManager.refreshRenderer` via `ctx.scheduler().timer(1L, spec.refreshIntervalTicks(), renderer)` when AT LEAST ONE pos is set (an improvement over SnGens, which demanded both). It caches once per instance the resolved `Particle` and the `DustOptions` (`Color.fromRGB` + `dustSize`).

LENIENT particle resolution (the open-enums policy): `valueOf` of the uppercased name with the bidirectional REDSTONE <-> DUST alias; an unresolvable name -> FLAME fallback + WARN once per name; any particle other than DUST whose required dataType is not Void degrades to FLAME with WARN once (the rich dataType grammar belongs to the `[particle]` action, not here). Only DUST receives data on emission.

`run()` logic in order: (1) owner offline -> `handleRendererOffline` (silent cancel; it closes SnGens' slight leak: the task does not run in vain and covers the race the quit-cleanup missed); (2) timeout: `timeoutTicks > 0` and `now - createdAtMillis >= timeoutTicks * 50` -> `handleRendererTimeout` (cancel WITH onCancel + the `timeout` message unless silent); decision: the timeout lives in the renderer and not in a separate task, because the renderer exists whenever there is something to show and a session with no pos never expires; (3) geometry: ONE pos -> a 1-block marker box `[b, b+1)` (first-click feedback); both positions same world -> a bounding box `[min, max + 1.0)` in doubles; positions in DIFFERENT worlds -> the marker of whichever pos is in the OWNER's world (neither -> nothing drawn this tick, the task continues); (4) budget: `points = ceil(4 * (dx + dy + dz) / step)`; if it exceeds `particleBudget` the effective step recomputes (the WHOLE box shows, sparser; an edge is never cut mid-way); if `size() > maxRenderVolume` it draws NO edges: only the 8 corners as a mini-cross of 3 short segments (1 block per axis with the spec's step; < 200 points, more visible than SnGens' single dot); (5) viewers: OWNER_ONLY -> only the owner; WORLD -> `world.getPlayers()`; in both cases per-viewer culling via `LocationUtil.distanceToBoxSquared` against the box's CLOSEST point (per-axis clamp, not the center: correct on huge boxes); the budget applies PER viewer (cost scales viewers x points, documented); (6) emission `viewer.spawnParticle(particle, x, y, z, 1, 0, 0, 0, 0[, dustOptions])`.

Folia justification (documented in the Javadoc): it runs on the main thread / global region via SnScheduler; per-player `spawnParticle` only sends packets to the viewer and the renderer NEVER touches world logic (no blocks, no entities, no chunk loads); zero NMS. The same documented justification as SnGens.

### LocationUtil (in the util package)
`src/main/java/com/sn/lib/util/LocationUtil.java`

Static location math helpers (D11), a final class of the `com.sn.lib.util` package that NEVER throws: invalid input returns false or `Double.POSITIVE_INFINITY`.

- `inCuboid(point, cornerA, cornerB)` - world-aware containment from two loose corners (the pattern every plugin kept rewriting with edge bugs); null/absent or different worlds -> false; it DELEGATES to `Cuboid.of(...).contains(point)`: a single source of truth for inclusive edges and min/max normalization (the short-lived Cuboid is resolved by escape analysis; a convenience, not hot-path).
- `distance2dSquared(a, b)` / `distance2d(a, b)` - horizontal distance ignoring Y; null/different worlds -> infinity.
- `distanceToBoxSquared(box, point)` - distance to the CLOSEST point of the bounding box `[min, max + 1)` (per-axis clamp); a different/null world -> infinity. It IS the SelectionRenderer's culling helper, and public because it is exactly the "is it near the region" a zones plugin needs. It combines with SnChunkMoveEvent (section 10): a cuboid check on chunk crossing instead of on every move.

#### Module notes and gotchas

- Purely additive: zero existing signatures touched; the `selections()` accessor and listener 14 are permitted growths of the entrypoint.
- 1.20.4: particle resolution is by lenient NAME with the REDSTONE <-> DUST alias and the FLAME fallback, so no version/name combination crashes; a targeted `Particle.DUST` verification in the release's 1.20.4 smoke gate.
- Hot path: PlayerInteractEvent is frequent; the listener's quick exit (action -> hand -> null/air -> meta -> PDC scan) keeps the cost in nanoseconds for every normal item (the same budget as ItemInteractListener).
- Particle budget: the per-viewer budget + the effective step + corners-only above `max-render-volume` guarantee that no selection, however absurd, drops the client or saturates the main thread.
- Out of v1.1 scope (avoidable as future additive work): air-click/raytrace selection, WorldEdit-style expansion by commands, multiple named selections per player.

---
## 19. Velocity base: config, text, scheduler, commands (v1.3)

The SAME `SnLib.jar` is BOTH a Paper plugin and a Velocity plugin (dual descriptor:
`plugin.yml` and `velocity-plugin.json`, entry `SnLibVelocity`), so it drops into the proxy's
`plugins/` exactly as it does into each backend. On Velocity it is a SMALL base for homogeneity
across a developer's Paper and Velocity plugins - config, text, scheduler and commands - with NO
cross-server messaging. Only `com.sn.lib.velocity.*` and the platform-neutral text pipeline load on
Velocity; the Bukkit-bound classes never do (lazy per-platform class loading).

> This replaces the experimental SnBridge (cross-server messaging), removed in v1.3.0: it had grown
> into a full codec/handshake/verb framework, well beyond the library's "consistent base for my
> plugins" goal.

### 19.1 SnLibVelocity

The Velocity entry point named in `velocity-plugin.json` (`"main": "com.sn.lib.velocity.SnLibVelocity"`).
It is just the dependency anchor - consumers declare `"dependencies": [{ "id": "snlib" }]` and build
their own `Snv`. It holds no runtime state beyond logging that the base is enabled. The `@Plugin`
annotation is deliberately omitted so the velocity-api annotation processor does not emit a second,
unfiltered descriptor over the hand-written (Maven-filtered) `velocity-plugin.json`.

### 19.2 Snv (per-plugin context)

The small counterpart of the Paper `Sn`, created once in the consumer's `ProxyInitializeEvent`
handler:

```java
Snv snv = Snv.create(this, proxy, logger, dataDir); // loads + merges config.yml
```

- `proxy()`, `logger()`, `dataDir()` - the injected Velocity handles.
- `config()` -> `SnvConfig` - the managed `config.yml` (loaded on create, reloadable via `reloadConfig()`).
- `scheduler()` -> `SnvScheduler`.
- `command(name, command, aliases...)` - registers a Velocity command under the consumer plugin.
- `color(text)` -> Adventure `Component` through the SHARED `SnText` pipeline (`&`, `[rgb]`,
  `[small]`, `[center]`, MiniMessage), so text renders identically to the Paper side.

### 19.3 SnvConfig (managed YAML)

Minimal managed config backed by snakeyaml, which SnLib bundles and relocates to
`com.sn.lib.libs.snakeyaml` (Velocity, unlike Bukkit/Paper, does not put `org.yaml.snakeyaml` on the
plugin classpath). `SnvConfig.load(file, defaults, logger)` reads the user file, deep-merges any
keys missing versus the bundled defaults, rewrites the file when it changed or was absent, and
exposes dot-path getters:

- `getString/getInt/getLong/getDouble/getBoolean(path, default)`, `getStringList(path)`,
  `contains(path)`, `getSection(path)`, `keys()`.
- Deliberately small: no comment preservation on rewrite, common scalars/list/section only. For
  anything richer, read the tree with snakeyaml directly. Never throws: I/O and parse errors log and
  fall back to defaults. Covered by `SnvConfigTest`.

### 19.4 SnvScheduler

Thin wrapper over `proxy.getScheduler()`: `run(task)`, `later(task, delay)`, `repeat(task, interval)`
and `repeat(task, delay, interval)`, each returning the `ScheduledTask` so the caller can cancel it.
For anything richer use `snv.proxy().getScheduler()` directly.

### 19.5 Other known lib limitations

Unrelated to the Velocity base, tracked for a future release:

- `SnYml` has player-aware `getString`/`getStringList` only; numeric/boolean getters have no viewer
  overload.
- `DiscordWebhook` and `UpdateChecker` expose no completion future (fire-and-forget).

---
## 20. Warmup teleports (v1.6)

Package `com.sn.lib.teleport`: an OPT-IN warmup teleport module reached via `sn.teleports()` (declared with `SnSpec.builder().teleports()`). It solves the warmup teleport every `/home` `/warp` `/rally` re-implements: one pending teleport per player (dedup), a warmup message, cancel on movement and on damage, an optional cooldown category shared with `sn.cooldowns()`, and a Folia-safe completion through `Player#teleportAsync`. Cancellation is driven by two shared listeners (`internal/TeleportMoveListener`, `internal/TeleportDamageListener`) registered ONCE by the SnLib bootstrap via ListenerHub; they only ever act for owners that declared the module (no declared module, no manager, nothing runs). Main-thread only for `request`.

### Teleports
`src/main/java/com/sn/lib/teleport/Teleports.java`

Warmup teleport module of one context. A per-plugin instance registers in the static `TenantRegistry<Teleports> MANAGERS` (the two shared listeners resolve every manager with a pending teleport, and the sweep callback is the double safety net) and hooks `QuitCleanupListener`; per-plugin state (the `ConcurrentHashMap<UUID, PendingTeleport> pendings`) lives inside the instance. A static `DEFAULT_MESSAGES` map mirrors the three `snlib.teleport.*` keys for when the lang module is absent.

- `public Teleports(Sn ctx)` - wires the module: tenant registration plus quit cleanup.
- `public TeleportResult request(Player player, Location target)` - convenience overload with `TeleportOptions.instant()` (no warmup, no cooldown): a plain immediate teleport that still flows through the bookkeeping.
- `public TeleportResult request(Player player, Location target, TeleportOptions opts)` - main entry. Null player/target or an unloaded target world -> `FAILED`; a null `opts` becomes `instant()`. The target is `clone()`d. Computes `alreadyPending`, `onCooldown` (only when not already pending and the options' category still has `remainingMillis > 0`) and delegates the decision to `evaluate`, then acts: `TELEPORTED` -> immediate `performTeleport`, `WARMUP_STARTED` -> `startWarmup`, the three rejections have no side effect (the caller messages). Returns the `TeleportResult`.
- `static TeleportResult evaluate(boolean alreadyPending, boolean onCooldown, int warmupSeconds)` - pure state machine in priority order: dedup (`ALREADY_PENDING`) wins over cooldown (`ON_COOLDOWN`), and a zero warmup means `TELEPORTED`, else `WARMUP_STARTED`. Extracted for unit coverage (`TeleportStateMachineTest`).
- `public boolean isPending(Player)` / `isPending(UUID)` - whether the player has a pending (warming-up) teleport of THIS context.
- `public boolean cancel(Player)` / `cancel(UUID)` - cancels the pending teleport WITHOUT sending a message; returns whether one was pending. Idempotent (a no-op when nothing is pending).
- `public void shutdown()` - cancels every warmup task (each guarded, the scheduler may be dying) and clears the map; idempotent, runs no `onComplete`. Invoked by the context teardown (step 4 of `Sn.shutdown()`) and by the tenant sweep.
- `public static void dispatchMove(Player)` / `dispatchDamage(Player)` - internal bridges for the shared listeners: cancel the player's pending teleport in EVERY declared manager with the move/damage message. Not part of the consumer contract.

**Internal logic**
- `startWarmup` puts the `PendingTeleport` (main-thread only, so the `containsKey` check in `request` and this put form a stable dedup), schedules `complete(id)` at `opts.warmupTicks()` via `ctx.scheduler().syncLater`, and sends the warmup message with `{time}`.
- `complete` removes the pending entry, re-checks the player is online and calls `performTeleport`.
- `performTeleport` arms the cooldown category (when set and `cooldownSeconds > 0`) via `ctx.cooldowns().tryUseTicks`, then `player.teleportAsync(target)` and, on completion, runs the `onComplete` callback only through the `shouldRunOnComplete` gate.
- `static boolean shouldRunOnComplete(@Nullable Consumer<Player> onComplete, @Nullable Boolean success)` - pure gate: the callback runs only after a genuinely successful teleport (`success == Boolean.TRUE`), never a vetoed one (`false`) nor an exceptional one (`null`). Covered by `TeleportCompletionGateTest`. `runOnComplete` hops to the main thread (`ctx.scheduler().sync`), re-resolves the online player and swallows a throwing callback with a WARN.
- `cancelPending(id, move)` removes the entry, stops the task and sends the move or damage cancel message (unless silent). `onQuit` delegates to `cancel` (idempotent since a kick fires kick and quit).
- `PendingTeleport` (private): the cloned target, its options and a volatile `TaskHandle`; `stopTask` nulls and cancels it under a Throwable catch (the scheduler may be dying during teardown).

### TeleportOptions
`src/main/java/com/sn/lib/teleport/TeleportOptions.java`

Immutable per-request configuration, built via `builder()` (reusable: `build()` snapshots) or the shortcuts `instant()` (shared no-warmup singleton) and `warmup(int seconds)`. Message keys resolve against the requesting context's lang module when declared, else the embedded English defaults.

- Constants: `DEFAULT_WARMUP_KEY = "snlib.teleport.warmup"` (carries `{time}`), `DEFAULT_CANCELLED_MOVE_KEY = "snlib.teleport.cancelled-move"`, `DEFAULT_CANCELLED_DAMAGE_KEY = "snlib.teleport.cancelled-damage"`.
- `public int warmupSeconds()` (clamped `>= 0`; 0 = instant) / `public long warmupTicks()` (`warmupSeconds * 20`).
- `public @Nullable String cooldownCategory()` / `public int cooldownSeconds()` (clamped `>= 0`, ignored when the category is null).
- `public String warmupKey()` / `cancelledMoveKey()` / `cancelledDamageKey()` / `public boolean silent()` / `public @Nullable Consumer<Player> onComplete()`.
- Builder: `warmupSeconds(int)`, `cooldown(String category, int seconds)` (arms the category on completion AND rejects a request while it runs; shared with `sn.cooldowns()`), `warmupKey`/`cancelledMoveKey`/`cancelledDamageKey` (null-safe overrides), `silent(boolean)`, `onComplete(@Nullable Consumer<Player>)`, `build()`. Covered by `TeleportOptionsTest`.

### TeleportResult
`src/main/java/com/sn/lib/teleport/TeleportResult.java`

Enum outcome of `request`; the module never throws for a rejected request. Values: `WARMUP_STARTED` (warmup message sent, teleport pending), `TELEPORTED` (`warmupSeconds == 0`, dispatched immediately), `ALREADY_PENDING` (dedup: a second request while one is pending, never double-scheduled), `ON_COOLDOWN` (the options' category is still running; query `sn.cooldowns().remainingMillis(uuid, category)`), `FAILED` (null player/target or unloaded target world). `accepted()` is true for `WARMUP_STARTED`/`TELEPORTED`; `rejected()` is its complement.

### TeleportMoveListener / TeleportDamageListener (internal)
`src/main/java/com/sn/lib/teleport/internal/`

Single shared listeners owned by SnLib (inscribed in ListenerHub, never self-registering), both at `EventPriority.MONITOR` with `ignoreCancelled = true`.
- `TeleportMoveListener.onMove(PlayerMoveEvent)` - hot-path contract: `blockUnchanged(from, to)` quick-exits before any work when the destination is null or the move keeps the player in the same block (head rotation or sub-block movement, the overwhelming majority), so only an actual block-position change reaches `Teleports.dispatchMove`. `public static boolean sameBlock(int, int, int, int, int, int)` is the pure integer compare, covered by `TeleportMoveDeltaTest`.
- `TeleportDamageListener.onDamage(EntityDamageEvent)` - non-player entities quick-exit, then `Teleports.dispatchDamage(player)` cancels the damaged player's pending teleport across every declared manager.

The ListenerHub enumeration grows from 14 to 16 listeners (the move and damage teleport listeners), still registered once at bootstrap.
