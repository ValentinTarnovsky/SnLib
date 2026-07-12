# SnBridge - Operations runbook

> Companion to SNBRIDGE-SPEC.md. Written BEFORE implementing, on purpose: if anything in this
> runbook turns out to be unacceptable in practice, the design gets fixed before writing code.
> Audience: the OkiMC operator (Velocity proxy + the Gens, Gens-Old, Gens-Dev, Work,
> OkiPVP, Lobby, Worldbox backends) with manual deploys via SSH/Pterodactyl and manual restarts.

## 1. Version mental model

With the universal jar there is ONE SnLib version per server (proxy included) and one msgset per
plugin. Real axes:

```
SnLib on the proxy    1 version
SnLib per backend     7 versions (days of skew is the norm, not the exception)
msgset per plugin     1 per proxy/paper pair (travels with the plugin's jars)
```

The HELLO handshake negotiates the common minimum per (backend, namespace). A mixed fleet does
NOT break: it degrades with a visible typed result (`UNSUPPORTED_MSGSET`,
`UNSUPPORTED_AT_DESTINATION`).
What WOULD be broken: silence. If there is silence, something is wrong (see section 5).

## 2. Hard deploy rules (NEVER violate)

1. **Backends before proxy.** New SnLib gets installed on the backends first (on the next cycle
   of manual restarts), the proxy last.
2. **Never** release a proxy plugin that REQUIRES (hard-requires, without graceful degradation)
   a msgset or verb above the floor of the backend fleet it runs on. The transient mixed-msgset
   window that rule 4 produces with staggered restarts is EXPECTED and covered: HELLO negotiates
   and sends resolve as a typed `UNSUPPORTED_MSGSET` while it lasts.
3. **Never** roll back SnLib on a backend below the floor of verbs/msgsets that the proxy
   plugins already use (it silently strips capabilities from other plugins).
4. The proxy/paper pair of a plugin (e.g. SnCredits velocity + its paper consumer) deploys
   together, as today.
5. No automatic restarts: restarts are scheduled by the operator by hand, as always
   (sn-deploy restarts nothing; that does not change).

## 3. Concrete order of an SnLib rollout (e.g. 1.2.0 -> 1.2.1)

1. Upload `SnLib-1.2.1.jar` to each backend (replaces the old one). Do NOT restart yet if not
   needed.
2. On each game mode's natural restart cycle, the backend comes up with 1.2.1.
   Mixed fleet for days: OK by design.
3. When ALL relevant backends run 1.2.1, upload to the proxy and restart it during low-traffic
   hours.
4. Verify: `/snlibv status` on the proxy -> every backend with a READY handshake and the
   expected version.

## 4. Diagnostics: commands and what to look at

| Where | Command | What it shows |
|-------|---------|-------------|
| Backend | `/snlib bridge status` | handshake per namespace, negotiated versions, queue, drops/expired, NACKs, invalid HMAC frames |
| Proxy | `/snlibv status` | aggregated table per backend: frame, msgset, state, queue, drops |
| Proxy | `/snlibv allowlist-audit` | diff of the console verb's effective allowlists across backends |

NACKs (denied command, unsupported verb, old msgset) show up rate-limited in the proxy log:
a single place to look at, not 8 consoles.

## 5. "A message is not arriving" checklist

In order:

1. **Is there a handshake?** `/snlibv status`. If the backend shows no handshake:
   - Empty backend? The handshake needs a carrier player. No players, no channel. Period.
   - SnLib installed and started on that backend? (`/snlib version` on its console)
   - SnLib version too old for the frame? The status says it explicitly.
2. **WARMING state?** The backend just restarted and the first player has not joined yet, or
   joined moments ago and the resync is in progress. Wait for the first join; the plugin
   decides what to show meanwhile (that is the consumer's responsibility, not the transport's).
3. **Send result?** Every send returns a terminal result from the single `SnDeliveryResult`
   enum. Look in the proxy plugin's log for:
   `EXPIRED_TTL` (expired in queue: no carrier, no handshake, or carrier dropped mid-chunk),
   `UNSUPPORTED_MSGSET` (update SnLib or the consumer on that backend),
   `UNSUPPORTED_AT_DESTINATION` (verbs only: that backend's SnLib does not know the verb),
   `DENIED_BY_ALLOWLIST` (verbs only, see point 6), `UNKNOWN_SERVER` (typo in the name).
4. **HMAC drops?** `/snlib bridge status` on the backend. Invalid HMAC counter going up =
   the forwarding secret differs between `velocity.toml` and that backend's `paper-global.yml`
   (typically after rotating the secret on only one of the sides).
5. **detectLegacy warning?** "outdated counterpart detected" = one side of the plugin stayed
   on the old stack during a half-done migration. Update the missing side.
6. None of the above and still mute: check that the proxy plugin declares `dependencies: snlib`
   and that the claimed namespace is the same string on both sides.

## 6. Console verb allowlist

- Lives in `plugins/SnLib/config.yml` of EACH backend (backend-authoritative on purpose: a
  compromised proxy cannot widen its own permissions).
- Patterns anchored per argument: `crates key give <player> vote <int:1..64>`. Forbidden:
  `crates key give *`.
- After touching a backend's allowlist: run `/snlibv allowlist-audit` and verify that the diff
  across backends is the expected one. sn-deploy's yml merge PRESERVES local divergences:
  without the audit, a command allowed on Gens and forgotten on Work is a ghost incident.
- A rejection shows up as a NACK on the proxy with the pattern that failed. It is not a bridge
  bug: it is the allowlist working.

## 7. Forwarding secret rotation

The bridge HMAC uses Velocity's modern forwarding secret by default. Real locations:
on the proxy it is the `forwarding.secret` file (pointed to by `forwarding-secret-file` in
`velocity.toml`), on each backend `config/paper-global.yml` (`proxies.velocity.secret`).
When rotating it:

1. Update the proxy's `forwarding.secret` file and the `paper-global.yml` of ALL the backends
   in the same maintenance window (this is already the case today: without a coherent secret,
   players cannot join).
2. The bridge re-handshakes on its own: HELLO is per carrier connection and fires when the
   connection registers the channel (the proxy's minecraft:register, which arrives after the
   join), with automatic retries if registration takes long. The first join after each restart
   rebuilds the channel. Invalid HMAC counters during the window are expected; afterwards they
   must stay at zero.
3. If decoupling the bridge from that rotation is preferred: configure the dedicated secret
   (`bridge.hmac-secret` in `plugins/SnLib/config.yml` + the equivalent config on the proxy) on
   ALL servers. One more secret to keep coherent: the operator's call.

## 8. Limits that are not bugs

- **Empty backend = unreachable.** Plugin messaging travels over player connections. Nothing
  reaches (or leaves) a backend without players. Paid flows use DB persistence
  (SnCredits' `pending_commands`) and get delivered on the next join. A `console` verb to an
  empty backend expires with `EXPIRED_TTL`, visible and counted: correct behavior.
- **Resync = first join post-restart.** There is no synced state before that; WARMING makes it
  explicit.
- **At-most-once.** The bridge never retries on its own. Retries and deduplication belong to
  the consumer, with its DB, if the use case pays for it.

## 9. Quick compatibility matrix

| Situation | Behavior |
|-----------|----------------|
| Proxy 1.2.1, backend 1.2.0, compatible frames | Negotiates the minimum; new frame features are not used with that backend |
| Proxy requires msgset 3, backend consumer on msgset 2 | Typed `UNSUPPORTED_MSGSET` on every send + NACK; nothing explodes |
| New proxy verb, old SnLib on the backend | `UNSUPPORTED_AT_DESTINATION`; update that backend's SnLib |
| Old proxy plugin (legacy channel) + new consumer | Protocol silence; the NEW side logs "outdated counterpart" via `detectLegacy` (the old side is old code: it stays mute) |
| SnLib missing on a backend | No handshake; visible in `/snlibv status` |
| Hacked client sends frames to the channel | Invalid HMAC -> discard + counter |
| Authentic frame captured and reflected to the other direction | Receiver direction check in decode -> discard + its own counter |
