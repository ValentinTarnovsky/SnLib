# Actions and Requirements

The `ActionEngine` and `RequirementEngine` power the `[tag] argument` mini-language that admins write in menu, item and config YAML. This page covers the Java-side API: registering your own action tags, understanding how the requirement parser evaluates expressions, and how your custom tags compose with the built-in click guards.

{% hint style="info" %}
For the YAML tag syntax itself - the full catalog of `[player]`, `[message]`, `[sound]`, requirement operators and so on that admins can already use without any code - see [Actions and Requirements for admins](../../admins/actions-and-requirements.md). This page is about extending and driving the engines from Java.
{% endhint %}

## The action engine (ActionEngine)

`sn.actions()` returns the action engine for your context. It executes lists of `[tag] argument` lines: menu click actions, item interact actions, close actions, anything a consumer wires to an action list.

### Registering a custom action tag

`register(String tag, ActionHandler handler)` makes a plugin-specific behavior usable inside any YAML action list, exactly like a built-in tag. Once registered, admins write `[my-tag] argument` in a menu or item and it just works.

```java
Sn sn = sn();

sn.actions().register("give-token", (player, arg, context) -> {
    int amount = Integer.parseInt(arg.trim());
    tokenService.give(player.getUniqueId(), amount);
    player.sendMessage("You received " + amount + " tokens");
});
```

```yaml
# now usable in any menu/item YAML this plugin loads
click-actions:
  - "[give-token] 5"
  - "[message] &aEnjoy!"
```

The tag may be written with or without brackets and is case-insensitive; `register("give-token", ...)`, `register("[Give-Token]", ...)` and so on all key the same handler. A registration **may override a built-in** tag if you deliberately reuse its name.

The `ActionHandler` is a functional interface:

```java
@FunctionalInterface
public interface ActionHandler {
    void run(Player player, String arg, ActionContext context);
}
```

Key facts about what your handler receives:

- **`arg` is already resolved.** By the time your handler runs, the argument has passed through the context's local placeholders and PlaceholderAPI (viewer-aware). You get the final string; you do not re-resolve placeholders.
- **You always run on the main thread.** The engine dispatches every run on the main thread. If `run(...)` is called from another thread, the engine hops through the context scheduler first. Your handler body is free to touch Bukkit directly.
- **`context` carries the click and page state.** `ActionContext` exposes `player()`, `ctx()`, `pageTarget()`, `clickType()`, `clickSurface()` and `phs()`. Use it if your action needs to know how it was triggered.

{% hint style="warning" %}
An unknown tag WARNs once (per tag) and the line is ignored; a handler that throws is caught and logged as a WARN naming the offending line. The engine never lets one bad line abort the rest of the list. Keep your handler resilient, but you do not need to wrap it in a catch-all yourself.
{% endhint %}

### Running an action list yourself

You can also drive the engine directly, for example from your own listener:

```java
List<String> lines = sn.yml().config().getStringList("on-join-actions", List.of());
sn.actions().run(player, lines, Ph.of("player", player.getName()));
```

The `run(Player, List<String>, Ph...)` overload runs with local placeholders and no click or page data. The `run(Player, List<String>, ActionContext)` overload lets you supply a full context (click type, click surface, pagination target) when the behavior needs it.

### Gating an untrusted action list

If you ever execute an action list from an untrusted source, `effectiveTags(List<String>)` resolves the terminal tag of each line (after stripping leading guard tags) *without* running anything, so you can check the list against a safe allowlist before calling `run`:

```java
List<String> tags = sn.actions().effectiveTags(untrustedLines);
boolean safe = SAFE_PRESENTATION_TAGS.containsAll(tags);
if (safe) {
    sn.actions().run(player, untrustedLines);
}
```

A line with no leading `[tag]` resolves to `"message"` (bare text is sent as a chat message).

## Click guards, and how your tag composes with them

Any action line may carry one or more leading **guard** prefixes before its terminal tag. Guards decide whether the line runs at all, based on the `ClickType` and click surface in the context. Your custom tag composes with them for free: put a guard in front of your `[my-tag]` line and the engine evaluates the guard first, then dispatches to your handler only if it passes.

```yaml
click-actions:
  - "[right-click-only] [give-token] 5"   # only a plain right click
  - "[left-click] [message] &7Left!"       # inclusive left-click semantics
```

### Guard families

- **Inclusive click guards** (historical v1.0.0 semantics, intact): `[right-click]` passes for RIGHT and SHIFT_RIGHT (`ClickType.isRightClick()`); `[left-click]` passes for LEFT, SHIFT_LEFT, DOUBLE_CLICK and CREATIVE (`ClickType.isLeftClick()`).
- **Exact click guards** (each matches exactly one `ClickType`): `[right-click-only]` (RIGHT, excludes shift/double/creative), `[left-click-only]` (LEFT), `[shift-right-click]`, `[shift-left-click]`, `[middle-click]`, `[double-click]`, `[drop-click]`, `[number-key]`, `[swap-offhand]`.
- **Generic set guard**: `[click=TYPE,...]` takes comma-separated `ClickType` names (case-insensitive, dashes accepted for underscores); the line runs when the context click is in the set.
- **Positional guards**: `[click-block]` / `[click-air]` match exactly against the click surface. Only world item interactions carry a surface; GUI clicks and clickless runs leave it null.
- **Chance guard**: `[chance=N]` rolls a 0-100 chance (doubles allowed).

### Fail-open vs fail-closed, precisely

The engine's policy differs deliberately by guard type, and it matters when you compose your own tags:

- **`[click=TYPE,...]` is FAIL-CLOSED.** An invalid `ClickType` name (a typo) WARNs once and the line is **skipped**. The reasoning: a typo must never accidentally fire actions on unwanted clicks.
- **`[chance=N]` is FAIL-OPEN.** A malformed number WARNs once and the line **runs anyway**.
- **A guard with no click in the context is skipped** with a debug note. Every click guard requires a `ClickType`; positional guards additionally require a click surface. Outside a click (a clickless run), guarded lines simply do not fire.

{% hint style="info" %}
Guards are stripped left to right until the terminal tag is reached, so you can stack them: `[chance=50] [right-click-only] [my-tag] arg` runs your tag only on a plain right click and only half the time. Your handler never sees the guards; it only runs if all of them passed.
{% endhint %}

## The requirement engine (RequirementEngine)

Requirements are boolean expressions over placeholders, used for `view-requirements`, `click-requirements`, `interact-requirements` and their `deny-actions` companions. Since v1.1 the parser is a real **recursive-descent parser**, not a naive split.

### The grammar

```
expr    := and ('||' and)*
and     := primary ('&&' primary)*
primary := '(' expr ')' | comparison
```

- **Comparison operators**: `>`, `<`, `>=`, `<=`, `=`, `==`, `!=`.
- **Boolean connectors**: `&&` and `||`, where `&&` binds tighter than `||`.
- **Parentheses** group sub-expressions.
- **Quoting**: an operand may be wrapped in `'...'` or `"..."`. Inside a quoted region the connectors, parentheses and operator symbols stay literal; the surrounding quotes are stripped from the final operand. This lets a value legitimately contain a space, a `>` or an `&&`.
- Multiple lines in a list are joined with an implicit `AND`.

```yaml
view-requirements:
  - "%vault_eco_balance% >= 1000 && (%player_level% > 10 || %myplugin_vip% = true)"
  - "%myplugin_rank% != 'trial mod'"
```

### How it evaluates

Parsing happens **once at load**. Placeholders are kept as raw tokens in an immutable requirement tree; they are resolved on *every* evaluation through the caller's resolver (PlaceholderAPI, or SnLib's own local placeholders). Coercion at evaluation time:

- When both sides parse as numbers, the comparison is **numeric**.
- Otherwise `=` / `==` / `!=` compare **lexicographically, case-insensitive**.
- The relational operators (`>`, `<`, `>=`, `<=`) on non-numeric operands evaluate to **false** with a debug WARN.

### Fail-open policy

The requirement engine is fail-open by design, and this is unchanged since before the recursive-descent rewrite:

- A **malformed line** (unbalanced parentheses, a dangling connector, empty parentheses, a text run that is not a comparison, leftover tokens) WARNs once quoting the line and turns that whole line into an **always-true** requirement.
- **Null, empty or blank** input is an always-pass requirement.

The rationale is that a broken config should never permanently lock players out of a menu or an item. A typo makes a gate *open*, not *stuck closed*, and it is loud about it in the console.

{% hint style="warning" %}
Fail-open means you should not rely on a requirement expression as your only security boundary for a sensitive action. A malformed requirement passes. For anything that must never be bypassed, gate it in Java (a permission check, a server-side balance check) in addition to the YAML requirement.
{% endhint %}

## See also

- [Actions and Requirements (admin YAML reference)](../../admins/actions-and-requirements.md) - the tag catalog and requirement syntax admins use directly.
- [Debug and Scheduler](debug-and-scheduler.md) - action dispatch hops to the main thread through the scheduler.
- [Developer overview](../README.md) and [the threading model](../threading-model.md).
