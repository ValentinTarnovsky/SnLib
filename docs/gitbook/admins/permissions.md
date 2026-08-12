# Permissions

Every Sn plugin names its admin permissions the same way. Learn the pattern once here and you can configure staff access for all of them.

## The shared naming convention

| Node | What it grants |
| --- | --- |
| `<plugin>.admin.<subcommand>` | One child node per admin subcommand. |
| `<plugin>.admin` | The parent node; grants every child at once. |

Every declared node defaults to `op`. Operators have them out of the box. Everyone else has nothing until you grant nodes through your permissions plugin. A node a plugin forgets to declare has no default at all; the update-notice node is the known case, see [Updates](updates.md).

> Grant the parent for full admin access, a single child for one subcommand. Every node defaults to op.

SnLib itself is the reference example. Its declared tree, straight from its plugin descriptor:

```yaml
permissions:
  snlib.admin:
    description: Grants every SnLib admin subcommand.
    default: op
    children:
      snlib.admin.version: true
      snlib.admin.plugins: true
      snlib.admin.integrations: true
      snlib.admin.iteminfo: true
      snlib.admin.reload: true
      snlib.admin.update: true
  snlib.admin.version:
    description: Allows /snlib version.
    default: op
  # ...one declared node per subcommand, each default: op
```

Every Sn plugin repeats this structure (`sngens.admin`, `snclans.admin`, and so on). Grant `<plugin>.admin` to a trusted staff member and they can run everything that plugin exposes. Grant one child, for example `<plugin>.admin.reload`, and they can run only that.

{% hint style="info" %}
Subcommands are permission-gated in tab-completion and in help output too. A subcommand a player cannot run does not tab-complete and is not listed in the plugin's help.
{% endhint %}

## SnLib's own nodes

| Node | Gates |
| --- | --- |
| `snlib.admin` | Every declared child below. |
| `snlib.admin.version` | `/snlib version` |
| `snlib.admin.plugins` | `/snlib plugins` |
| `snlib.admin.integrations` | `/snlib integrations` |
| `snlib.admin.iteminfo` | `/snlib iteminfo` |
| `snlib.admin.reload` | `/snlib reload` |
| `snlib.admin.update` | `/snlib update`, plus SnLib's own update notices in chat. |

[The /snlib Command](snlib-command.md) explains what each subcommand does.

{% hint style="warning" %}
`/snlib debug` is gated by `snlib.admin.debug`, which the descriptor does not declare. Granting `snlib.admin` therefore does not include it. Grant the node explicitly to staff who need the debug controls.
{% endhint %}

## Update notices

Holders of `<plugin>.admin.update` receive a chat notice when a watched plugin has a newer release. Whether that node defaults to op depends on the plugin declaring it: [Updates](updates.md) owns that trap.

## Related pages

* [Updates](updates.md): who receives update notices, and the `admin.update` declaration trap.
* [The /snlib Command](snlib-command.md): what each of the gated subcommands does.
* [Customizing Commands](customizing-commands.md): renaming, aliasing and translating the gated commands.
* [Troubleshooting](troubleshooting.md): first stop when an update notice never arrives.
