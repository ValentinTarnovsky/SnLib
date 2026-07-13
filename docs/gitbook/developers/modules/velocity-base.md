# Velocity Base

Since v1.3, the exact same `SnLib.jar` is also a Velocity plugin. It declares a `velocity-plugin.json` with the entry class `SnLibVelocity`, so you install the identical jar in the proxy's `plugins/` folder, just as you install it on every Paper backend. No separate download, no separate artifact.

{% hint style="info" %}
The Velocity side is a **small base for homogeneity** between your Paper and Velocity plugins, not a full port of the Paper feature set. It gives you config, text rendering, a scheduler wrapper, and command registration. It has explicitly **no** cross-server messaging capability - it holds no cross-server state.
{% endhint %}

## One jar, two platforms

`SnLib.jar` carries both a `plugin.yml` (Paper) and a `velocity-plugin.json` (Velocity). On a Velocity proxy the entry class is `SnLibVelocity`, which is just a dependency anchor: it logs a line on proxy init and holds no state. All the consumer-facing work happens in `Snv`, which your own proxy plugin creates.

### Lazy per-platform class loading

On a Velocity proxy, only `com.sn.lib.velocity.*` and the platform-neutral text pipeline classes ever load. The Bukkit-bound classes are never touched there. This is deliberate: it means a proxy never risks a `NoClassDefFoundError` from a Bukkit-only type, because those classes are simply never referenced on the Velocity code path. The same jar therefore runs cleanly on a proxy that has no Bukkit API on its classpath at all.

## Consumer setup

Declare a dependency on SnLib in your `velocity-plugin.json` (or via the `@Plugin` annotation's dependencies), then build an `Snv` context from your plugin's injected values.

```java
@Plugin(id = "myplugin", name = "MyPlugin", version = "1.0.0",
        dependencies = {@Dependency(id = "snlib")})
public final class MyPlugin {

    private final Snv snv;

    @Inject
    public MyPlugin(ProxyServer proxy, Logger logger, @DataDirectory Path dataDirectory) {
        // Creates the context and loads + merges config.yml from the data directory:
        this.snv = Snv.create(this, proxy, logger, dataDirectory);
    }

    @Subscribe
    public void onInit(ProxyInitializeEvent event) {
        proxy().getConsoleCommandSource()
                .sendMessage(snv.color(snv.config().getString("motd", "&aHello")));
        snv.command("mycmd", new MyCommand(snv), "mc");
        snv.scheduler().repeat(this::tick, Duration.ofSeconds(1));
    }
}
```

If your `velocity-plugin.json` is hand-written rather than annotation-generated, the dependency looks like:

```json
{
  "id": "myplugin",
  "dependencies": [ { "id": "snlib" } ]
}
```

`Snv.create(plugin, proxy, logger, dataDirectory)` takes your plugin main instance (used for scheduler and command ownership, and to resolve the bundled defaults), the `ProxyServer`, the SLF4J `Logger`, and the `@DataDirectory Path`. It loads `config.yml` from the data directory, merging any missing keys from the `config.yml` bundled in your jar, and returns a per-plugin context.

## The `Snv` context

`Snv` is the small counterpart of the Paper `Sn`. It groups the proxy, logger, data directory, and the four capabilities:

| Accessor | Returns |
|----------|---------|
| `snv.proxy()` | the `ProxyServer` |
| `snv.logger()` | the SLF4J `Logger` |
| `snv.dataDir()` | the plugin data directory `Path` |
| `snv.config()` | the managed `SnvConfig` |
| `snv.scheduler()` | the `SnvScheduler` |
| `snv.color(text)` | renders text through the shared `SnText` pipeline |
| `snv.command(name, cmd, ...aliases)` | registers a Velocity command |
| `snv.reloadConfig()` | re-reads `config.yml` and re-merges the bundled defaults |

## Managed config (`SnvConfig`)

`snv.config()` returns a managed YAML config with the same philosophy as the Paper `SnYml`: it loads the user file, deep-merges any keys missing versus the bundled defaults, writes the merged file back when it changed, and exposes dot-path getters. It is backed by the snakeyaml that SnLib bundles (Velocity, unlike Paper, does not put `org.yaml.snakeyaml` on the plugin classpath, so SnLib provides it).

```java
SnvConfig cfg = snv.config();

String motd   = cfg.getString("motd", "&aHello");
int    max    = cfg.getInt("limits.max-players", 100);   // dot-path into a section
long   window = cfg.getLong("cooldown-ms", 5000L);
double factor = cfg.getDouble("scale", 1.0);
boolean debug = cfg.getBoolean("debug", false);
List<String> hosts = cfg.getStringList("allowed-hosts");
Map<String, Object> section = cfg.getSection("limits");
boolean has   = cfg.contains("motd");
Set<String> keys = cfg.keys();
```

Every getter takes a default and returns it when the path is absent or the value is the wrong type; nothing ever throws. To reload after an admin edits the file on disk, call `snv.reloadConfig()`.

{% hint style="info" %}
`SnvConfig` is deliberately small: no comment preservation on rewrite, and only the common scalar/list/section getters. For anything richer, read the tree with snakeyaml directly. I/O and parse errors are logged and fall back to defaults rather than throwing.
{% endhint %}

## Scheduler (`SnvScheduler`)

A thin wrapper over Velocity's scheduler that removes the `buildTask(...).schedule()` boilerplate. Every method returns the `ScheduledTask` so you can `cancel()` it.

```java
SnvScheduler s = snv.scheduler();

ScheduledTask a = s.run(() -> log.info("now"));
ScheduledTask b = s.later(() -> log.info("in 5s"), Duration.ofSeconds(5));
ScheduledTask c = s.repeat(this::tick, Duration.ofSeconds(1));
ScheduledTask d = s.repeat(this::tick, Duration.ofSeconds(10), Duration.ofSeconds(1)); // delay, then interval

c.cancel();
```

For anything the wrapper does not cover, reach the underlying scheduler with `snv.proxy().getScheduler()`.

## Commands

`snv.command(name, command, ...aliases)` registers a Velocity `Command` under your plugin with optional aliases:

```java
snv.command("mycmd", new MyCommand(snv), "mc", "mine");
```

The `Command` is any Velocity command implementation (for example a `SimpleCommand` or a Brigadier command). SnLib only builds the `CommandMeta` and registers it against your plugin instance.

## Shared text rendering

`snv.color(text)` delegates to the **exact same** `SnText` pipeline used on Paper. This is the point of the Velocity base: `&` legacy codes, `[rgb]` gradients, and MiniMessage all render identically on both platforms, with zero duplicated logic. A message string that renders one way on your Paper backend renders the same way through your Velocity proxy.

```java
Component motd = snv.color("[rgb]&lWelcome to the network");
proxy.getAllPlayers().forEach(p -> p.sendMessage(motd));
```

Because the text pipeline is platform-neutral, it is one of the classes that loads on both Paper and Velocity - the rendering rules documented in [Text rendering](text.md) apply unchanged on the proxy.

## What it is not

- **No cross-server messaging.** SnLib on Velocity does not move players between servers, forward messages, or hold any cross-server state. If you need that, use Velocity's own APIs.
- **Not the Paper module set.** GUIs, items, database, PAPI, lang, holograms, and the rest are Paper-only. Only config, text, scheduler, and commands exist on the proxy.
- **Outside the Paper API-level handshake.** The Velocity surface (`com.sn.lib.velocity.*`) is a separate, Velocity-only API kept outside the Paper `SnApi.LEVEL` handshake and outside the japicmp gate while it settles.

## See also

- [Text rendering](text.md) - the shared `SnText` pipeline `snv.color()` delegates to.
- [Managed config (SnYml)](yml.md) - the Paper counterpart of `SnvConfig`.
- Back to the [developer guide](../README.md).
