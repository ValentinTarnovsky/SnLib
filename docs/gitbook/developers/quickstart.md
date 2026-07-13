# Quickstart

This walkthrough builds a minimal SnLib consumer plugin from an empty Maven
project to a working `SnPlugin` subclass. By the end you will have a plugin that
depends on `SnLib.jar`, compiles against the `com.sn:snlib` artifact, declares a
few modules, and reaches them at runtime through its `Sn` context.

## Prerequisites

- Java 21. SnLib's classfiles are compiled at release 21 and refuse to load on
  an older JVM (see [Compatibility and versioning](compatibility-and-versioning.md)).
- A local build of SnLib installed in your Maven repository (next section).
- A Paper server between 1.20.4 and 1.21.8 with `SnLib.jar` in its `plugins/`.

## Step 1: resolve `com.sn:snlib`

SnLib is distributed through your local Maven repository (`.m2`). Publishing to
a public resolver such as JitPack is not set up yet: the repository was private
until now, and JitPack does not build private repositories. Clone the SnLib
repository and install it once:

```bash
mvn install -f path/to/SnLib/pom.xml
```

That publishes `com.sn:snlib:1.3.0` into your local `.m2`, where your consumer's
build can resolve it. Re-run it whenever you pull a new SnLib version.

Now set up the consumer `pom.xml`. The critical points are that `com.sn:snlib`
is `provided` (it comes from the installed `SnLib.jar` at runtime, never from
your jar) and that you do not shade it. A ready-to-copy template lives at
`docs/consumer-pom-template.xml` in the SnLib repository; the minimal form is:

```xml
<project xmlns="http://maven.apache.org/POM/4.0.0"
         xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
         xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 http://maven.apache.org/xsd/maven-4.0.0.xsd">
    <modelVersion>4.0.0</modelVersion>

    <groupId>com.sn</groupId>
    <artifactId>myplugin</artifactId>
    <version>1.0.0</version>
    <packaging>jar</packaging>

    <properties>
        <maven.compiler.release>21</maven.compiler.release>
        <project.build.sourceEncoding>UTF-8</project.build.sourceEncoding>
    </properties>

    <repositories>
        <repository>
            <id>papermc</id>
            <url>https://repo.papermc.io/repository/maven-public/</url>
        </repository>
    </repositories>

    <dependencies>
        <!-- SnLib: ALWAYS provided, resolved from your local .m2. Never shaded. -->
        <dependency>
            <groupId>com.sn</groupId>
            <artifactId>snlib</artifactId>
            <version>1.3.0</version>
            <scope>provided</scope>
        </dependency>

        <dependency>
            <groupId>io.papermc.paper</groupId>
            <artifactId>paper-api</artifactId>
            <version>1.21.1-R0.1-SNAPSHOT</version>
            <scope>provided</scope>
        </dependency>
    </dependencies>

    <build>
        <plugins>
            <plugin>
                <groupId>org.apache.maven.plugins</groupId>
                <artifactId>maven-compiler-plugin</artifactId>
                <version>3.13.0</version>
            </plugin>
            <!-- NO maven-shade-plugin for the lib: SnLib is never shaded into the consumer. -->
        </plugins>
    </build>
</project>
```

{% hint style="warning" %}
If your consumer shades other dependencies of its own, never include
`com.sn:snlib` in the shade. Shading SnLib into your jar would put a second copy
of its classes on the server, defeating the single-classloader model.
{% endhint %}

## Step 2: declare the dependency in `plugin.yml`

`depend: [SnLib]` makes the server load `SnLib.jar` first and refuse to enable
your plugin if it is missing. Point `main` at the class you write in Step 3.

```yaml
name: MyPlugin
main: com.sn.myplugin.MyPlugin
version: '1.0.0'
api-version: '1.20'
depend: [SnLib]
commands:
  myplugin:
    description: Main command of MyPlugin
permissions:
  myplugin.admin:
    description: Full administrative access of MyPlugin
    default: op
```

## Step 3: extend `SnPlugin`

`com.sn.lib.SnPlugin` is the only initialization path into the library. The
`SnLib.init` call is package-private, so extending this class is the single
public way to obtain a context. `SnPlugin` makes `onEnable` and `onDisable`
final and drives the lifecycle for you; you implement four members:

- `requiredApiLevel()` - the compile-time API level your code needs.
- `buildSpec()` - which modules to mount.
- `onInnerEnable()` - your enable logic, run after the context is built.
- `onInnerDisable()` - optional; your disable logic, run before teardown.

### `requiredApiLevel()` and why it exists

Implement it exactly as `return SnApi.LEVEL;`, nothing else:

```java
@Override
protected int requiredApiLevel() {
    return SnApi.LEVEL;
}
```

`SnApi.LEVEL` is a `public static final int` compile-time constant. When javac
compiles your plugin, it inlines the literal value into your class file: your
`requiredApiLevel()` is frozen with the level you compiled against, not with the
level of whatever `SnLib.jar` is installed at runtime.

At enable time, `SnPlugin` reads the API level baked into the actually-installed
`SnLib.jar` and compares it to the level inlined in your bytecode. If the
installed jar is older than what you compiled against, your plugin disables
itself cleanly with a clear log message and a download URL, instead of failing
later with a `NoSuchMethodError` or `NoClassDefFoundError` the first time it
calls a method that the old jar does not have. The relevant part of the base
class:

```java
int installed = SnLibPlugin.get().apiLevel();
int required = requiredApiLevel();
if (installed < required) {
    getLogger().severe("Requires SnLib API level " + required + " (installed: " + installed
            + "). Update SnLib.jar (restart required): ...");
    getServer().getPluginManager().disablePlugin(this);
    return;
}
```

This is the whole reason the method must be `return SnApi.LEVEL;` verbatim: any
other expression would defeat the compile-time inlining that makes the check
meaningful. See [Compatibility and versioning](compatibility-and-versioning.md)
for how `SnApi.LEVEL` is incremented over releases.

### `buildSpec()` and the module declaration

`buildSpec()` returns an immutable `SnSpec` describing which modules this plugin
uses. Every builder method is opt-in; anything you do not call stays disabled.
The complete builder surface is:

| Builder call | Declares |
|--------------|----------|
| `.config("config.yml")` | The managed main config file (seeded, merged, `update-configs` gated). |
| `.lang()` | The lang module (`lang/messages_<code>.yml`). |
| `.guis()` | The menus module (the `guis/` folder, one GUI per file). |
| `.items("items.yml")` | The items module backed by a YML file. |
| `.db()` | The database module. |
| `.debugCommand()` | The runtime `debug` subcommand on the plugin's own command roots. |
| `.updates("owner/repo")` | The notify-only update check against a GitHub repository. |

A full working example plugin that uses config, lang and menus:

```java
package com.sn.myplugin;

import com.sn.lib.Sn;
import com.sn.lib.SnApi;
import com.sn.lib.SnPlugin;
import com.sn.lib.SnSpec;
import com.sn.lib.gui.Gui;
import org.bukkit.entity.Player;

public final class MyPlugin extends SnPlugin {

    @Override
    protected int requiredApiLevel() {
        return SnApi.LEVEL;
    }

    @Override
    protected SnSpec buildSpec() {
        return SnSpec.builder()
                .config("config.yml")   // managed yml + auto-update
                .lang()                 // lang/messages_<code>.yml
                .guis()                 // guis/ folder, one gui per file
                .build();
    }

    @Override
    protected void onInnerEnable() {
        Sn sn = sn();
        String prefix = sn.yml().config().getString("prefix", "&7[MyPlugin]");
        getLogger().info("Loaded prefix: " + prefix);

        sn.commands().root("myplugin")
                .permission("myplugin.admin")
                .sub("menu")
                    .executes(ctx -> {
                        Player player = ctx.player();
                        Gui shop = sn.guis().get("shop");   // guis/shop.yml
                        shop.open(player);
                    })
                .and()
                .register();
    }

    @Override
    protected void onInnerDisable() {
        // Optional. The library's ordered teardown runs automatically afterwards.
    }
}
```

### Reaching modules through `sn()`

Inside `onInnerEnable()` (and from then on) call `sn()` to get your plugin's
`Sn` context, then reach each module through it: `sn.yml()`, `sn.guis()`,
`sn.items()`, `sn.db()`, `sn.commands()`, `sn.lang()`, and so on. The context is
private to your plugin; another consumer's context never sees your state.

`onInnerEnable()` runs after the API-level handshake passed and the context was
built. If it throws, `SnPlugin` logs the error and disables your plugin cleanly
rather than leaving it half-initialized. `onInnerDisable()` is optional and runs
before the library's own ordered teardown, which cleans up everything you
registered (see [Multi-tenant contract](multi-tenant-contract.md)).

## Step 4: declare only what you use

The module accessors on `Sn` are gated by your `SnSpec`. Calling an accessor for
a module you did not declare throws `UnsupportedOperationException`, and the
message names the exact builder call you are missing. For example, calling
`sn.db()` without `.db()` in your spec tells you to add `.db()`. This keeps the
declaration honest: declare a module and it is mounted at enable and torn down at
disable; leave it out and it costs nothing.

{% hint style="info" %}
A few surfaces are always available with no spec gate, because they are pure or
server-wide rather than per-plugin state: the utility classes and the region
[selection](modules/region-selection.md) wands (`sn.selections()`). Everything
else must be declared.
{% endhint %}

## Step 5: build and install

```bash
mvn clean package
```

Copy the resulting jar into your server's `plugins/` next to `SnLib.jar`, and
start the server on Java 21. On enable you should see your plugin come up after
`SnLib`; if the installed `SnLib.jar` is too old for your `requiredApiLevel()`,
you will instead see the clean disable message telling you to update it.

## Where to go next

- [Compatibility and versioning](compatibility-and-versioning.md) - the runtime
  floor, the API-level handshake and the semver contract.
- [Threading model](threading-model.md) - the async rules a consumer must
  follow on a shared server.
- The [module pages](README.md#module-catalogue) - the full API of each module
  you declare.
