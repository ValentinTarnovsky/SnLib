# Language

The lang module gives your plugin per-language message files and a small API for sending
those messages as chat, action bars, titles, or raw Adventure `Component`s. Every message
flows through the shared [text](text.md) pipeline, so `&` colors, `[rgb]`, `[small]`,
`[center]` and MiniMessage all render the same way here as everywhere else.

Declare it in your spec and reach it through the context:

```java
// SnSpec:
SnSpec.builder().config("config.yml").lang().build();

// onInnerEnable:
SnLang lang = sn.lang();
```

{% hint style="info" %}
This page is the developer-facing view. The end-user side - how a server admin edits the
YAML, picks a language, and translates keys - is covered in
[Language files](../../admins/language-files.md). This page does not repeat it.
{% endhint %}

## Files, merging, and fallback

Messages live under `lang/messages_<code>.yml` in your data folder. The active language is
chosen by the `lang` key in your config (defaulting to `en`).

- **Always-merge.** On every load the module seeds `lang/messages_en.yml` from your jar,
  then structurally merges new keys into the file on disk - the same always-merge behavior
  as the general config system, and gated by the same `update-configs` master switch.
  There is no version marker; merging is structural and preserves user edits, extra keys,
  and comments. A non-English translation is merged against the disk `messages_en.yml` as
  its reference, so it picks up the `snlib.*` keys and any consumer key you add later.
- **Per-key fallback to English.** A key missing from the active language falls back to its
  English value, with **one** warning per key. A key missing from English too renders as
  `<missing:key>` (and warns once). You never get a silent blank or a crash.
- **Shared `snlib.*` keys.** The library's own message contract (`snlib.no-permission`,
  `snlib.usage`, `snlib.player-not-found`, the help header/entry/footer, and so on) is
  merged into every language automatically. These are exempt from the `update-configs`
  gate - they always merge, because the command module and others depend on them. This is
  what makes [command](commands.md) errors localized out of the box.

## Sending messages

The core call is `send`, which takes a target, a key, and any number of `Ph` placeholder
pairs. Placeholders are matched as both `%key%` and `{key}` and substituted before PAPI.

```java
sn.lang().send(player, "shop.bought", Ph.of("item", name), Ph.of("price", price));
```

```yaml
# lang/messages_en.yml
prefix: "&8[&aShop&8] "
shop:
  bought: "You bought &f{item}&7 for &6{price}&7 coins."
```

`send` accepts a `Player` or any `CommandSender` (console included); when the target is a
player, PAPI resolves per-viewer. Single-line messages get the optional top-level `prefix`
value prepended; list values are sent line by line, unprefixed.

`broadcast` sends to the whole server, resolving PAPI against the server:

```java
sn.lang().broadcast("event.started", Ph.of("event", eventName));
```

## Action bars and titles

```java
sn.lang().actionbar(player, "combat.tagged", Ph.of("seconds", 10));
```

`title` parses the message's first line as a format string,
`title;subtitle;fadeIn;stay;fadeOut`, with times in ticks. Omitted parts fall back to the
defaults `10;70;20`:

```yaml
arena:
  countdown: "&c&lGET READY;&7Starting in {seconds}s;10;40;10"
```

```java
sn.lang().title(player, "arena.countdown", Ph.of("seconds", 3));
```

### Persistent action bars

A plain action bar fades after two to three seconds. To keep a message on screen for a
duration, use the `Duration` overload of `actionbar`:

```java
sn.lang().actionbar(player, "combat.tagged", Duration.ofSeconds(10),
        Ph.of("seconds", 10));
```

Behavior:

- The line is rendered **once** at call time (PAPI and locals are frozen at that moment;
  re-call to refresh dynamic content), sent immediately, and **re-sent every 40 ticks**
  for the hold so the bar never fades out mid-duration.
- On expiry it is cleared with an empty component.
- A new held bar for the same player **replaces and cancels** the previous one instead of
  stacking - you cannot accidentally pile up two timers on one player.
- A plain (non-held) action bar sent during a hold is overwritten on the next 40-tick
  refresh.
- The timer is cancelled automatically when the player quits, and swept by the context
  shutdown.
- A null, zero, or negative duration falls back to the plain one-shot `actionbar`.

## Getting raw components

When you need the rendered message for custom use - a GUI item name, a book page, a
bossbar title - retrieve `Component`s directly:

```java
Component line = sn.lang().get("gui.title", Ph.of("page", 1));   // first line
List<Component> body = sn.lang().getList("help.body");           // every line
```

Both resolve and fall back exactly like `send`. A missing key yields a `<missing:key>`
marker component. There is also `getLegacy(key, phs)` returning a legacy section-code
string for the rare API that still requires legacy text.

Everything - `send`, `broadcast`, `actionbar`, `title`, `get`, `getList` - renders through
the shared [text](text.md) pipeline, so a message can freely mix `&a`, `&#RRGGBB`,
`[rgb]`, `[small]`, `[center]` and MiniMessage. Static lines (no placeholder token) are
pre-rendered to a `Component` once at load; dynamic lines render per call.

## Reloading

`sn.lang().reload()` re-runs the seed and merge and rebuilds every cache from disk. You
rarely call it directly: the default command `reload` sub and the context reload manager
already reload the lang module as part of a full reload.

## See also

- [Language files](../../admins/language-files.md) - the admin's guide to editing and
  translating.
- [Text](text.md) - the render pipeline behind every message.
- [PlaceholderAPI](papi.md) - how `%tokens%` in messages are resolved.
- [Commands](commands.md) - the `snlib.*` keys the command module renders.
- [Quickstart](../quickstart.md) and the [developer overview](../README.md).
