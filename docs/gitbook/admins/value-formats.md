# Shared Value Formats

Every Sn plugin reads a few small text formats the same way: sounds, durations, schedules and typed numbers. Learn each format once on this page. Every page that uses one of them links back here.

## Sounds

A sound is one string: `"SOUND_ID [volume] [pitch]"`. Volume and pitch are optional and both default to `1.0`.

```yaml
open-sound: "ENTITY_PLAYER_LEVELUP 0.8 1.2"
close-sound: "none"
click-actions:
  - "[sound] minecraft:block.chest.close 1 0.9"
```

Both id styles work, in any letter case:

| Style | Example |
| --- | --- |
| Enum name | `ENTITY_PLAYER_LEVELUP` |
| Namespaced key | `minecraft:entity.player.levelup` |
| Custom pack sound | `okimc:click-2` |

Ids resolve against the running server's registry, so sounds added by newer Minecraft versions keep working. A volume or pitch that is not a number logs one WARN and plays with `1.0`.

### Custom sounds from a resource pack

A sound that only exists inside your resource pack (Nexo, Oraxen, ItemsAdder or a hand-made pack) is not in the server registry, so it can only be played by name. Write it **with its namespace** and it is sent to the client as-is:

```yaml
click-actions:
  - "[sound] okimc:click-2 1 1"
```

The namespace is the opt-in. That is why an id with no namespace that resolves nowhere (`ENTITY_PLAYER_LEVELUPP`) is still treated as a typo: it logs one WARN and plays nothing, while `okimc:click-2` is played without complaint. If a namespaced sound stays silent, the id is missing from the pack the player has loaded (check `sounds.json`); the server cannot tell the difference and will not warn.

> `none` and `""` mean silence on purpose. Deleting the key does not: a managed file restores it on the next restart.

## Durations

A duration is a compact string such as `"1d 2h 30m 15s"`.

| Unit | Meaning |
| --- | --- |
| `d` | days |
| `h` | hours |
| `m` | minutes |
| `s` | seconds |
| `t` | server ticks (20 per second) |
| `ms` | milliseconds |

Decimals work (`1.5h`), and so do spelled-out units (`1 day 2 hours`). A bare number counts as seconds. Unknown units are skipped, and a fully unreadable value reads as zero. Commands that take a duration reject a zero result as invalid.

{% hint style="info" %}
When a plugin prints a duration back, the unit labels are English (`1d 2h`, `2 hours`). There is no language-file key to translate them yet.
{% endhint %}

## Cron schedules

Configs that schedule work accept two shortcuts plus standard 5-field cron:

| Form | Example | Meaning |
| --- | --- | --- |
| `daily HH:mm` | `daily 04:30` | every day at 04:30; the time is optional and defaults to 00:00 |
| `hourly :mm` | `hourly :15` | every hour at minute 15; the minute is optional and defaults to :00 |
| 5-field cron | `0 4 * * 1` | fields in order: minute, hour, day of month, month, day of week |

Inside a field you combine `*`, lists (`1,15`), ranges (`1-5`) and steps (`*/10`, `10-30/5`). Day of week runs 0 to 7, and both 0 and 7 mean Sunday. Schedules follow the server timezone; a time erased by a DST jump skips to the next matching day. A malformed expression schedules nothing and logs one WARN naming the job; the message reports the out-of-range field value, the wrong field count, or the bad shortcut time.

> Restricting day of month AND day of week runs on days matching EITHER one. That is standard cron OR semantics, not AND.

## Typed number shorthand

Wherever a command asks for a number, you can type an abbreviated one: `2k` means `2000`, `1.5b` means `1500000000`.

| Suffix | Multiplier |
| --- | --- |
| `k` | thousand |
| `m` | million |
| `b` | billion |
| `t` | trillion |
| `qa` | quadrillion |
| `qi` | quintillion |

Suffixes are case-insensitive. Separators normalize too: `1,500` groups to `1500`, while `1,5` reads as `1.5`.

> Suffixes shorten what you type; number hints shorten what players read. They are separate systems and never mix.

Display-side number formatting lives in [Text, Colors and Numbers](text-formatting.md).

## Related pages

- [Actions and Requirements](actions-and-requirements.md) - the `[sound]` action tag takes the sound format above.
- [Menus](menus.md) - `open-sound` and `close-sound` take the sound format.
- [Text, Colors and Numbers](text-formatting.md) - number hints, the display counterpart of typed suffixes.
- [Configuration Files](configuration-files.md) - why a deleted key returns in a managed file.
- [Troubleshooting](troubleshooting.md) - what to check when a sound or schedule is ignored.
