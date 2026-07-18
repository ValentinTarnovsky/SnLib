# Commands

The command module builds root/sub command trees fluently and registers them against
Bukkit for you. Every node is permission-gated, every argument is typed and
tab-completable, and the whole tree is reload-safe: when your plugin disables, its
commands are torn down and each client's command list is refreshed so no ghost entries
remain.

Reach the module through the context:

```java
SnCommands commands = sn.commands();
```

## Anatomy of a tree

A tree has one **root** (`/shop`) and any number of **subcommands** (`/shop give`,
`/shop reload`, ...). You start a root with `root(name)`, declare subs with `sub(name)`,
close each sub with `and()`, and finish with `register()`.

```java
sn.commands().root("shop")
        .permission("shop.use")
        .description("Shop commands")
        .sub("give")
            .permission("shop.admin.give")
            .description("Gives a shop item to a player")
            .arg("player", Args.onlinePlayer())
            .arg("amount", Args.intRange(1, 64))
            .executes(ctx -> {
                Player target = ctx.player("player");
                int amount = ctx.getInt("amount");
                // ... business logic ...
            })
        .and()
        .register();
```

`register()` returns the built `RootCommand`; you rarely need it, since the context
teardown already unregisters everything your plugin owns.

{% hint style="info" %}
Declare the root name in your `plugin.yml` `commands:` section. The module wires the
executor and tab completer through `plugin.getCommand(name)`. If the name is not declared
there, the module still registers it dynamically through Paper's public command map, but
it logs one warning asking you to declare it. Aliases added in code that are not in the
`plugin.yml` declaration also register dynamically with a warning.
{% endhint %}

## Permission-gated visibility

Permissions do more than block execution. A subcommand the sender lacks permission for is
**invisible**: it never appears in tab completion and never appears in the generated help.
The sender cannot even discover that it exists.

- A root can carry a `permission(...)`. Without one the root is public.
- A subcommand can carry its own `permission(...)`. Without one it **inherits** the root
  permission.
- The *effective* permission of a node is its own, or the root's when it has none. Tab
  completion and help both filter on the effective permission.

```java
sn.commands().root("arena")
        .permission("arena.use")          // needed to see or run anything under /arena
        .sub("join")                      // inherits arena.use
            .arg("name", Args.oneOf(arenas::names))
            .executes(ctx -> join(ctx.player(), ctx.get("name")))
        .and()
        .sub("delete")
            .permission("arena.admin")    // only admins see /arena delete at all
            .arg("name", Args.oneOf(arenas::names))
            .executes(ctx -> delete(ctx.get("name")))
        .and()
        .register();
```

A player with `arena.use` but not `arena.admin` sees only `join` in tab completion and in
`/arena help`. `delete` is hidden entirely, not merely rejected on execution.

You can also hide a subcommand regardless of permission with `visible(false)` (useful for
internal or deprecated aliases that should still run but never advertise themselves).

## Nested subcommands (groups)

A subcommand can own children, which turns it into a **group** that dispatches on the next
token instead of running an executor. Declare a child with the `sub(name, spec)` overload on
the subcommand builder; `spec` configures the child through the same builder API (arguments,
permission, `executes`, or further nested children):

```java
sn.commands().root("clan")
        .permission("clan.use")
        .sub("admin")                          // a group: it dispatches, it never runs
            .permission("clan.admin")          // gates every child under it
            .sub("disband", s -> s
                .description("Disband a clan")
                .arg("clan", Args.oneOf(clans::names))
                .executes(ctx -> disband(ctx.get("clan"))))
            .sub("setleader", s -> s
                .description("Reassign a clan leader")
                .arg("clan", Args.oneOf(clans::names))
                .arg("player", Args.onlinePlayer())
                .executes(ctx -> setLeader(ctx.get("clan"), ctx.player("player"))))
        .and()
        .register();
```

That yields `/clan admin disband <clan>` and `/clan admin setleader <clan> <player>`.
Groups nest arbitrarily: a child can declare its own children and become a group in turn.

A few rules follow from a node being a group:

- **A group dispatches, it never runs.** Its own `arg`, `when` and `executes` are unused at
  runtime, so declare those on the leaf children. A group invoked with no next token replies
  with its full-path usage listing the child names the sender may use
  (`/clan admin <disband|setleader>`); an unknown next token replies with the full-path
  `snlib.unknown-subcommand`.
- **`and()` closes only top-level subcommands.** A child declared through `sub(name, spec)`
  is closed by its spec lambda, not by `and()`. Calling `and()` on a nested child throws.
- **Help flattens the tree.** The generated help lists one entry per reachable *leaf*, each
  rendered with its full path (`/clan admin disband <clan>`), never one entry per group.

### Permission chains

Each node - the root, every group, every leaf - may carry its own `permission(...)`, and a
node without one **inherits the nearest ancestor's** (a group's, or ultimately the root's).
The *effective* check to run, or even to see, a leaf is **every** permission on the path from
the root down to it. In the tree above a sender needs both `clan.use` (root) and `clan.admin`
(the `admin` group) to reach `/clan admin disband`. Tab completion and the generated help
descend the same chain: a group whose permission the sender lacks hides its whole subtree.

## Aliases

A root can take its aliases from three sources, in priority order:

- **A config binding** with `aliasesFromConfig()` reads a string list from the conventional
  config key `command.aliases`. When that key is set (even to an empty list) it is
  **authoritative**: it fully decides the aliases, and it is re-sourced on every reload, so
  an admin can add or remove aliases by editing the config and reloading. Use
  `aliasesFromConfig(key)` to read a custom key. The binding needs the config module; when
  the module is not declared, or the key is absent, it contributes nothing and the fallback
  applies.
- **A dynamic supplier** with `aliases(Supplier<Collection<String>>)` computes the aliases in
  code, re-evaluated on every register pass. A non-null result is authoritative (an empty
  list clears the aliases); a null result falls back.
- **Static aliases** with `aliases(String...)`, plus any aliases declared in your
  `plugin.yml`. These are the **fallback**, used only while no authoritative source has an
  opinion.

```java
sn.commands().root("warp")
        .aliasesFromConfig()        // reads command.aliases; authoritative when set
        .aliases("w", "warps")      // fallback used only when command.aliases is absent
        .permission("warp.use")
        // ... subs ...
        .register();
```

```yaml
# config.yml
command:
  aliases: [w, warps, wp]
```

Aliases are reconciled against the server command map on every register pass: aliases that
appeared since the last pass are added (with a warning when they are not declared in your
`plugin.yml`), aliases that disappeared are removed, and an alias already owned by another
command is left in place with a warning. A reload that changed `command.aliases` therefore
takes effect immediately without leaving ghost aliases behind.

## Arguments

Declare positional arguments in order with `arg(name, Args.xxx())`. Declaration order is
parse order, and the name you give is the key you read back in the executor.

Two flavors:

- `arg(name, arg)` - a **required** argument. Its absence rejects the invocation with the
  generated usage message.
- `argOptional(name, arg)` - an **optional** trailing argument. It suggests and parses
  when present, but its absence never rejects. Optionals must come last; declaring a
  required `arg` after an optional one is a programming error and throws immediately.

Inside `executes`, read parsed values off the `CommandContext`:

```java
.executes(ctx -> {
    CommandSender sender = ctx.sender();     // player or console
    Player who = ctx.player();               // throws if sender is not a player
    Player target = ctx.player("player");    // a parsed onlinePlayer() arg
    int amount = ctx.getInt("amount");       // a parsed intRange() arg
    double factor = ctx.getDouble("factor"); // a parsed doubleRange() arg
    String note = ctx.get("note");           // any arg, typed by inference
    String rawFirst = ctx.raw(0);            // raw token, or null when absent
})
```

The executor only runs after permission, argument count, declarative conditions and typed
parsing all pass. Any exception it throws is caught and logged at `SEVERE` against your
plugin; it never leaks a stack trace to the player.

### The `Args` helper family

`Args` is a factory of typed, self-completing arguments. Each one knows how to parse a raw
token into a value, how to reject a bad token (with a shared `snlib.*` message key), and
what to suggest in tab completion.

| Factory | Parses to | Rejection key | Suggestions |
|---------|-----------|---------------|-------------|
| `Args.onlinePlayer()` | `Player` (exact online name) | `snlib.player-not-found` | up to 100 online names |
| `Args.offlinePlayerUuid()` | `UUID` (online, then local offline cache; never a blocking lookup) | `snlib.player-not-found` | online names |
| `Args.oneOf(Supplier<Collection<String>>)` | `String` (canonical option, case-insensitive) | `snlib.invalid-value` | up to 100 current options |
| `Args.oneOf(Function<CommandSender, Collection<String>>)` | `String` (canonical option, scoped to the sender) | `snlib.invalid-value` | up to 100 sender-scoped options |
| `Args.intRange(min, max)` | `Integer` in range | `snlib.invalid-number` / `snlib.out-of-range` | both bounds as examples |
| `Args.doubleRange(min, max)` | `Double` in range | `snlib.invalid-number` / `snlib.out-of-range` | both bounds as examples |
| `Args.duration()` | `Long` millis (e.g. `1d 2h 30m`) | `snlib.invalid-value` | `30s`, `5m`, `1h`, `1d` |
| `Args.bool()` | `Boolean` (`true/yes/on`, `false/no/off`) | `snlib.invalid-value` | `true`, `false` |
| `Args.string()` | `String` (one token, as-is) | never | the arg-name hint `<argName>` |
| `Args.string(hint)` | `String` (one token, as-is) | never | the explicit hint `<hint>` |
| `Args.greedy()` | `String` (every remaining token, space-joined) | never | the arg-name hint `<argName>` |
| `Args.greedy(hint)` | `String` (every remaining token, space-joined) | never | the explicit hint `<hint>` |
| `Args.suggesting(options)` | `String` (one token, as-is; you validate) | never | up to 100 current options |

`Args.greedy()` only makes sense as the **last** argument of a subcommand: it consumes all
remaining tokens into a single space-joined value, so `/mail send Steve hello there world`
gives `hello there world` as one argument.

Both `oneOf` and `suggesting` also have a sender-aware overload that takes a
`Function<CommandSender, Collection<String>>`, so the option set (and, for `oneOf`, the
parse-time validation) is computed per invoking sender - the caller's own clan members, the
warps they may use, and so on.

{% hint style="info" %}
`Args.offlinePlayerUuid()` deliberately never calls `Bukkit.getOfflinePlayer(String)`,
which can perform a blocking profile lookup on the main thread. It resolves only against
online players and the local offline cache. For remote name resolution, do the lookup
yourself off-thread (see the utilities module's `PlayerLookup`).
{% endhint %}

### Which one to reach for

| Your argument is... | Use | Why |
|---|---|---|
| an online player | `Args.onlinePlayer()` | completes online names and rejects unknowns |
| one of a known set | `Args.oneOf(...)` | completes and validates against the set (sender-aware overload available) |
| a number in a range | `Args.intRange(min, max)` / `Args.doubleRange(min, max)` | parses and range-checks, and suggests the bounds |
| free-form text | `Args.string()` / `Args.greedy()` (add a hint) | no fixed set; suggests an `<argName>` hint |
| a dynamic set you validate yourself | `Args.suggesting(...)` | suggests the set but accepts any token as-is |

### Free-form arguments and hints

`Args.string()` and `Args.greedy()` carry no fixed set of values, so there is nothing
concrete to suggest. Instead of a placeholder like the old literal `text`, their lone
suggestion is an **angle-bracket hint** that tells the player what to type. By default the
hint is the declared argument name, so `.arg("target", Args.string())` suggests `<target>`.

Give an explicit hint with `Args.string(hint)` / `Args.greedy(hint)`. The value is wrapped
in angle brackets unless you already bracketed it yourself, so `Args.string("amount")` and
`Args.string("<amount>")` both suggest `<amount>`:

```java
.arg("reason", Args.greedy("<ban reason>"))   // suggests "<ban reason>"
```

The hint is only offered while the token is still empty (or the player is typing a `<`);
once they type a real value the free-form argument suggests nothing and accepts whatever
they typed. Because a hint is never a valid value, it is a cue in the completion list, not
something the player is meant to select.

{% hint style="info" %}
Before 1.5.0 an un-hinted `string()` / `greedy()` suggested the literal word `text`, which
looked like a real value. That default is gone: un-hinted free-form arguments now suggest
`<argName>`, and you can override it with the hint overloads above.
{% endhint %}

### Showing the current value in tab completion

Every `Args` factory returns an `SnArg<T>` that accepts a `suggestCurrent(Supplier)`
decorator. It prepends the current actual value to the suggestion list, ahead of the
examples, so admins editing a setting see what it is set to right now:

```java
.arg("radius", Args.intRange(1, 64)
        .suggestCurrent(() -> String.valueOf(config.radius())))
```

With an empty or prefix-matching partial the actual value comes first; a non-matching
partial filters it out. A supplier that throws is ignored silently.

### Declarative conditions

`when(index, predicate)` guards a raw token before any typed parsing runs. A failing token
rejects the invocation with the usage message. Use it for cheap structural checks that do
not warrant a full custom `Arg`:

```java
.sub("color")
    .arg("hex", Args.string())
    .when(0, token -> token.matches("#[0-9A-Fa-f]{6}"))
    .executes(ctx -> applyColor(ctx.get("hex")))
.and()
```

## Default subcommands

Every root gets two subcommands injected automatically, unless you opt out:

- **`reload`** - permission `<plugin>.admin.reload`. Delegates to the shared reload
  manager through the context (`Sn.reloadAll()`), which re-reads configs, lang files and
  the rest of your declared modules, then confirms with the `snlib.reload-done` message.
- **`help`** - generated from the visible, permitted subcommands, one entry per reachable
  leaf rendered with its full path. Paginated at 10 entries per page; the footer with the
  page indicator only appears when there is more than one page. Invoke a specific page with
  `/<root> help <n>`. Each entry renders through the `snlib.help.entry` key, whose default
  is `&e{usage} &7{description}` (`{permission}` is also available if you want to show it).

A subcommand you declare with the name `reload` or `help` **replaces** the default. To
drop both defaults entirely, call `withoutDefaults()` on the root builder - but then you
are responsible for providing your own reload and help subs.

When your spec declared `debugCommand()` on the `SnSpec`, a **`debug`** subcommand is also
added (permission `<plugin>.admin.debug`) that toggles the runtime debug service on and
off without a restart. This one is gated by the spec, not by the defaults opt-out.

```java
// SnSpec:
SnSpec.builder().config("config.yml").debugCommand().build();

// yields /<root> debug on every root your plugin registers
```

### The `<plugin>.admin` permission convention

The injected defaults follow one convention worth adopting for your own admin subcommands:
every administrative node lives under `<plugin>.admin.<sub>`, derived from your plugin's
lowercased name. `reload` is `<plugin>.admin.reload` and `debug` is `<plugin>.admin.debug`.
Declare a parent `<plugin>.admin` permission in your `plugin.yml` with each
`<plugin>.admin.<sub>` node as a `children:` entry (all `default: op`), and a single grant of
`<plugin>.admin` unlocks the whole admin fleet at once - the same shape SnLib uses for its
own `snlib.admin` parent over `snlib.admin.reload`, `snlib.admin.version` and the rest. Put
your admin subcommands under an `admin` group (see [nested subcommands](#nested-subcommands-groups))
and the `.admin.<sub>` permission nodes line up with the command tree one to one.

## Bare-root behavior (`onEmpty`)

Running the root with no arguments (`/kit`) prints the generated help by default. Override
that with `onEmpty(handler)` on the root builder to run your own logic instead - a banner, a
status line, opening a menu:

```java
sn.commands().root("kit")
        .permission("kits.use")
        .onEmpty(ctx -> {
            ctx.sender().sendMessage(SnText.color("&6Kits &7- &f/kit help"));
            ctx.help();                 // still fall through to the generated help
        })
        // ... subs ...
        .register();
```

The handler receives a `RootContext`: the invoking `sender()`, plus `help()` / `help(page)`
to render the standard generated help (so a banner can still fall through to it). The hook
fires only on the truly empty invocation; a wrong subcommand or a bad argument still goes
through normal resolution and its `snlib.*` message. A handler that throws is caught and
logged at `SEVERE` against your plugin, exactly like a subcommand executor.

## Reload safety and ghost commands

Registration is keyed by the owning plugin and is reload-safe end to end:

- Re-registering a root with the same name **replaces** the previous tree in place.
- When your plugin disables (cleanly, through a reload, or even force-removed by a manager
  like PlugMan), the tenant sweeper detaches every root your plugin owns.
- After **every** register and unregister, the module calls `player.updateCommands()` on
  each online player, so their client-side command tree is refreshed. Clients never see a
  command that no longer exists, and never miss one that was just added.

This is why you never have to unregister commands by hand: the ordered context teardown
does it, and the client trees are always brought back in sync.

## A complete example

```java
@Override
protected void onInnerEnable() {
    Sn sn = sn();

    sn.commands().root("kit")
            .aliases("kits")
            .permission("kits.use")
            .description("Claim and manage kits")
            .sub("claim")
                .description("Claim a kit")
                .arg("kit", Args.oneOf(kitService::names))
                .executes(ctx -> kitService.claim(ctx.player(), ctx.get("kit")))
            .and()
            .sub("give")
                .permission("kits.admin")
                .description("Force-give a kit to a player")
                .arg("player", Args.onlinePlayer())
                .arg("kit", Args.oneOf(kitService::names))
                .argOptional("silent", Args.bool())
                .executes(ctx -> {
                    Player target = ctx.player("player");
                    boolean silent = ctx.raw(2) == null ? false : ctx.get("silent");
                    kitService.give(target, ctx.get("kit"), silent);
                })
            .and()
            .sub("cooldown")
                .permission("kits.admin")
                .description("Set a kit cooldown")
                .arg("kit", Args.oneOf(kitService::names))
                .arg("duration", Args.duration())
                .executes(ctx -> {
                    long millis = ctx.get("duration");
                    kitService.setCooldown(ctx.get("kit"), Duration.ofMillis(millis));
                })
            .and()
            .register();
}
```

A player with only `kits.use` sees `/kit claim` (and the generated `help`) in tab
completion. An admin with `kits.admin` additionally sees `give` and `cooldown`. Neither
ever sees a raw stack trace: bad input yields a localized `snlib.*` message, and executor
failures are logged, not surfaced.

## See also

- [Language](lang.md) - the `snlib.*` message keys the command module renders, and how to
  override them.
- [Database](database.md) - run command logic that reads or writes data off the main
  thread.
- [Quickstart](../quickstart.md) and the [developer overview](../README.md).
