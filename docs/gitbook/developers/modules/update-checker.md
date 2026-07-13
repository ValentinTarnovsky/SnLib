# Update Checker

A strictly notify-only update checker, reached through `sn.updates()`. Point it at a GitHub repository and it tells you (and your admins) when there is a release newer than the installed version. It **never** downloads a jar, **never** touches the running plugin, and **never** auto-updates anything. The only outputs are one INFO line in the console and a join-notice to players holding a permission.

{% hint style="danger" %}
This guarantee is permanent and by design. There is no flag, no config key, and no code path that makes this module fetch or swap a jar. If you want the newer version installed, a human installs it. Nothing here ever mutates the running server.
{% endhint %}

## Fully opt-in

Declaring nothing generates **zero traffic and zero state**. A consumer that never declares `updates(...)` in its spec and never calls `sn.updates()` produces no HTTP request, arms no timer, and registers no per-plugin state. You pay for the module only when you ask for it.

## Two ways to activate it

### Declarative (recommended)

Declare the repo in your `SnSpec`. This auto-arms a recurring watch when the plugin enables.

```java
@Override
protected SnSpec buildSpec() {
    return SnSpec.builder()
            .config("config.yml")
            .updates("owner/repo")   // arms a recurring watch on enable
            .build();
}
```

### Imperative

If you would rather drive it from code, `sn.updates()` gives you two entry points:

```java
Sn sn = sn();

// One immediate check, no recurring timer:
sn.updates().checkNow("owner/repo");

// Arm (or re-arm) a recurring timer for this repo:
sn.updates().watch("owner/repo");
```

`checkNow` runs a single check off the main thread and arms nothing. `watch` arms a recurring timer, and re-watching the same repo **replaces** (and cancels) the previous timer for that repo. In both cases an invalid `owner/repo` format WARNs and does nothing; the accepted format is a single `owner/repo` matching `^[A-Za-z0-9_.-]+/[A-Za-z0-9_.-]+$`.

## Shared releases repo (one public repo for many plugins)

Both `updates(...)` and `watch`/`checkNow` also accept a **tag prefix**. Without it, the repo is assumed dedicated to this one plugin and is polled against `releases/latest`. With it, the repo is treated as **shared by several plugins**: the checker instead lists the repo's releases and keeps only the tags starting with your prefix, then picks the highest matching version.

```java
.updates("owner/Sn-Releases", "myplugin-")   // only tags like myplugin-v1.4.0 are considered
```

```java
sn.updates().checkNow("owner/Sn-Releases", "myplugin-");
sn.updates().watch("owner/Sn-Releases", "myplugin-");
```

This exists so a whole family of plugins can publish to **one** public repo instead of maintaining a dedicated public releases repo per plugin. The convention is to tag each release `<pluginId>-vX.Y.Z` (for example `myplugin-v1.4.0`) on the shared repo, so the prefix (`myplugin-`) unambiguously picks out this plugin's releases among everyone else's tags. Passing `null` (or using the single-argument overloads) keeps the old dedicated-repo behavior.

## Timing

- First check: **60 seconds** after enable (1200 ticks).
- Then: **every 6 hours** (432000 ticks).
- Always **off the main thread**, through the JDK `HttpClient` with a **5-second connect** timeout and a **10-second request** timeout.

The watch lives for the enable. A consumer reload neither re-arms nor duplicates it, and the timer is cancelled cleanly on disable.

## What it checks and how it compares

Each watched repo is polled against the GitHub `releases/latest` endpoint:

```
GET https://api.github.com/repos/<owner>/<repo>/releases/latest
```

The response `tag_name` is read, a leading `v`/`V` is stripped only when a digit follows (so `v1.4.0` becomes `1.4.0`, while a tag like `vanilla` stays intact), and the result is compared against the installed plugin version with `SemverComparator`. This means a tag like `v1.4.0` is correctly compared against an installed `1.3.2`. When the latest is strictly greater, it is recorded as a finding and an INFO line is logged **once** per new version:

```
[MyPlugin] Version 1.4.0 available, installed 1.3.2: https://github.com/owner/repo/releases/tag/v1.4.0
```

If the latest release is not newer, any prior finding for that repo is cleared.

## What admins see

When a finding exists, players who **join** and hold the permission `<plugin>.admin.update` receive a chat notice (sent a short moment after join) naming the new and installed versions plus the release URL.

To make this permission default to op, declare it in **your own** `plugin.yml`:

```yaml
permissions:
  myplugin.admin.update:
    description: Receive update notices for MyPlugin
    default: op
```

If you do not declare it, only players who have been **explicitly** granted `myplugin.admin.update` are notified. The permission name is always your plugin's (lowercased) name plus `.admin.update`.

{% hint style="info" %}
For the full receiving-end view - what the console line and the join notice look like from an admin's chair, and how to grant the permission - see [Permissions and updates](../../admins/permissions-and-updates.md) in the admin guide.
{% endhint %}

## Failure handling

A non-200 response, a network error, or a response missing `tag_name` triggers **exactly one** WARN per repo per enable, then stays silent for the rest of that run. This keeps a repo that has no releases yet, a rate-limited API, or a flaky network from spamming the console.

```
[MyPlugin] update check of 'owner/repo' failed: HTTP 404
```

In shared-repo mode, a repo with no release tag matching your prefix WARNs the same way once and stays silent:

```
[MyPlugin] update check of 'owner/Sn-Releases' failed: no release tag matching prefix 'myplugin-'
```

## Private repositories

To watch a private repo, provide a read-only token under `update-check.token` in your **own** config:

```yaml
# config.yml
update-check:
  token: "ghp_your_read_only_token"
```

The token is read from your config on **every single check** (not cached at enable), so you can rotate it without a restart. It is sent as a `Bearer` header and is **never logged**. Leave the key empty or absent for public repos.

## Real-world example: SnLib watches itself

Now that the SnLib repository is public, SnLib uses this exact module **on itself**. Its internal `buildSelfSpec()` wires in the same declarative call every consumer uses:

```java
private static SnSpec buildSelfSpec() {
    return SnSpec.builder()
            .config("config.yml")
            .debugCommand()
            .updates("ValentinTarnovsky/SnLib")
            .build();
}
```

There is no separate, special-cased self-update mechanism to maintain: the library dogfoods the very module it hands to every consumer. When a newer SnLib release is published, the console logs it and admins holding `snlib.admin.update` are notified on join, exactly as they would be for any other plugin. And, like every consumer, it still only notifies: updating `SnLib.jar` remains a manual, restart-required action.

## See also

- [Permissions and updates](../../admins/permissions-and-updates.md) - the admin-facing side of the join notice.
- [Bossbars, Holograms, Cron, Leaderboards, Discord](bossbars-holograms-cron-leaderboards-discord.md) - the Discord module shares the same `HttpClient` and warn-once discipline.
- Back to the [developer guide](../README.md).
