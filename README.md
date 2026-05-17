# TabCompleter

A Minecraft server plugin that restricts tab completion and command execution based on player permissions. Players without access cannot see or run commands they are not allowed to use.

Supports **Spigot/Paper**, **BungeeCord**, and **Velocity**.

---

## Features

- Hides commands from tab completion for unauthorized players
- Blocks execution of hidden commands
- Permission-based group system with inheritance (Spigot/Paper only)
- Allowlist or blocklist filtering modes (Spigot/Paper only; proxies use blocklist)
- Namespace-aware command matching (`essentials` covers all `essentials:*` commands)
- Custom server brand displayed on the F3 debug screen
- Hot-reload configuration without restarting the server
- Bypass permission for staff who should see all commands

---

## Commands

### Spigot / Paper

| Command | Permission | Description |
|---------|-----------|-------------|
| `/tabcompleter reload` | `tabcompleter.reload` | Reloads the configuration from disk |

### BungeeCord

| Command | Aliases | Permission | Description |
|---------|---------|-----------|-------------|
| `/bungeetabcompleter reload` | `/btc reload` | `tabcompleter.reload` | Reloads the configuration from disk |

### Velocity

| Command | Aliases | Permission | Description |
|---------|---------|-----------|-------------|
| `/velocitytabcompleter reload` | `/vtc reload` | `tabcompleter.reload` | Reloads the configuration from disk |

---

## Permissions

| Permission | Default | Description |
|-----------|---------|-------------|
| `tabcompleter.admin` | op | Access to the plugin management command |
| `tabcompleter.reload` | op | Use the `reload` subcommand |
| `tabcompleter.bypass` | op | Bypass all filtering — sees and can run all commands |
| `tabcompleter.group.default` | false | Access to commands in the `default` group |
| `tabcompleter.group.mod` | false | Access to commands in the `mod` group |
| `tabcompleter.group.admin` | false | Access to commands in the `admin` group |

Custom group permissions are defined in `config.yml` and follow the pattern `tabcompleter.group.<name>` by default.

Players with the `*` wildcard permission are treated the same as `tabcompleter.bypass`.

---

## Configuration

The config file is located at `plugins/TabCompleter/config.yml` (Velocity: `plugins/tabcompleter/config.yml`).

### Spigot / Paper

```yaml
# Message prefix for plugin messages (supports & color codes)
prefix: ""

# Permission node used for bypassing all restrictions
bypass-permission: "tabcompleter.bypass"

# Permission node used for the reload command
reload-permission: "tabcompleter.reload"

# Message sent when a player tries to run a hidden command
no-permission-message: "&cThis command does not exist."

# Custom brand shown on the F3 debug screen (supports & color codes)
server-brand: "&cMy Server"

# Filtering mode:
#   allowlist — only listed commands are visible (default)
#   blocklist — all commands are visible except those listed
mode: "allowlist"

# Commands visible to all players (or blocked in blocklist mode)
commands:
  - help
  - spawn

# Permission groups with their own command lists
groups:
  mod:
    permission: tabcompleter.group.mod
    commands:
      - kick
      - mute
  admin:
    permission: tabcompleter.group.admin
    inherits:
      - mod
    commands:
      - ban
      - op
      - stop
```

### BungeeCord / Velocity

```yaml
# Message prefix for plugin messages (supports & color codes)
prefix: ""

# Permission node used for bypassing all restrictions
bypass-permission: "tabcompleter.bypass"

# Permission node used for the reload command
reload-permission: "tabcompleter.reload"

# Message sent when a player tries to run a blocked command
no-permission-message: "&cThis command does not exist."

# Custom brand shown on the F3 debug screen (supports & color codes)
server-brand: "&cMy Network"

# Commands blocked for all players without the bypass permission
blocked-commands:
  - bungeetabcompleter
  - velocity
```

---

## Filtering Modes (Spigot / Paper only)

### Allowlist (default)

Only commands listed under `commands` are visible to players without a group permission. Players assigned to a group can also see that group's commands, plus any inherited group commands.

An empty `commands` list means ungrouped players see no commands at all.

### Blocklist

All commands are visible to players except those listed under `commands`. Players with `tabcompleter.bypass` always see everything regardless of mode.

---

## Groups (Spigot / Paper only)

Groups let you give sets of players access to specific commands by assigning them a permission.

Each group supports:
- **`permission`** — the permission node that grants access to this group's commands (defaults to `tabcompleter.group.<name>`)
- **`commands`** — list of commands accessible to members of this group
- **`inherits`** — list of other groups whose commands are also granted to members of this group

Inheritance is recursive: a group can inherit from a group that itself inherits from another.

```yaml
groups:
  default:
    commands:
      - help
      - spawn
  mod:
    permission: tabcompleter.group.mod
    inherits:
      - default
    commands:
      - kick
      - mute
  admin:
    permission: tabcompleter.group.admin
    inherits:
      - mod
    commands:
      - ban
      - op
```

In this example, an `admin` group member can see and run commands from `admin`, `mod`, and `default`.

---

## Namespace Matching

Listing a command without a namespace (e.g. `essentials`) covers all namespace-prefixed variants of that plugin (e.g. `essentials:friend`, `essentials:home`).

Listing a short name (e.g. `friend`) does **not** cover `essentials:friend` — only the exact `friend` command.

---

## Server Brand

The `server-brand` option sets the text shown on the F3 debug screen under "Server brand". Supports `&` color codes (e.g. `&cRed &fWhite`).
