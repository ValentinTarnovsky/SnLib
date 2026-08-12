# Database Connection

One `database:` section in a plugin's own config chooses its storage backend, and every key has a working default. SnLib itself creates no database; a plugin that stores data reads the section from its own `config.yml`. With no section at all, data goes to a local SQLite file.

## Key reference

| Key | Default | Backend | Meaning |
| --- | --- | --- | --- |
| `type` | `sqlite` | both | `sqlite` or `mysql`; anything else logs one WARN and falls back to SQLite |
| `file` | `database.db` | SQLite | database path, relative to the plugin's data folder; an absolute path is honored |
| `host` | `localhost` | MySQL | server host |
| `port` | `3306` | MySQL | server port |
| `database` | plugin name in lowercase | MySQL | schema name |
| `username` | `root` | MySQL | login user |
| `password` | empty | MySQL | login password |
| `pool-size` | `4` | MySQL | connection pool size, minimum 1; SQLite always uses a single connection |
| `ssl` | `false` | MySQL | whether the connection uses SSL |
| `connect-timeout-seconds` | `10` | both | cap on opening a connection; clamped to 1-3600 |
| `socket-timeout-seconds` | `30` | MySQL | cap on a read over an open connection; 0-3600, where 0 means unlimited |

> The key is `username`, not `user`. A misspelled key is not an error: it is silently ignored and the default takes over.

{% hint style="info" %}
With a `user:` line the plugin logs in as `root`, the default. If MySQL rejects credentials you know are right, check the key spelling first.
{% endhint %}

## SQLite (the default)

```yaml
database:
  type: sqlite
  file: database.db
```

This is also what you get with no `database:` section at all. The file lives inside `plugins/<Plugin>/`, needs no server and needs no credentials. The pool is pinned to one connection, so `pool-size` has no effect here.

## MySQL

```yaml
database:
  type: mysql
  host: db.example.com
  port: 3306
  database: myplugin        # defaults to the plugin name in lowercase
  username: minecraft
  password: "change-me"
  pool-size: 4
  ssl: false
  connect-timeout-seconds: 10
  socket-timeout-seconds: 30
```

## Timeouts

Two keys bound how long the driver may block, so a dead database cannot freeze the plugin's queries forever.

- `connect-timeout-seconds` caps opening a new connection. It can never be zero; values clamp to 1 through 3600.
- `socket-timeout-seconds` caps a read on an already-open MySQL connection. Values clamp to 0 through 3600. SQLite ignores it.

{% hint style="warning" %}
`socket-timeout-seconds: 0` removes the read cap. On a black-holed link a query then hangs forever. Raise the limit for genuinely long queries instead of disabling it.
{% endhint %}

## What the section does not have

{% hint style="info" %}
There is no `table-prefix` key and no free-form driver properties map. Table names are up to the plugin, and driver tuning beyond the keys above is not exposed.
{% endhint %}

## Related pages

- [Configuration Files](configuration-files.md) - how the config holding this section is merged and backed up.
- [Troubleshooting](troubleshooting.md) - symptoms of a failed connection and of a hanging query.
- [The /snlib Command](snlib-command.md) - what `/snlib reload [plugin]` covers when you change a config.
