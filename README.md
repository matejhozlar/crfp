# CRFP — Createrington Fake Players

Server-side chunkloader mod for **NeoForge 1.21.1**. Admins spawn timer-bound fake players that behave exactly like a real AFK player for chunk loading, mob spawning, random ticks, and redstone. Loaders auto-despawn when their timer runs out.

Vanilla clients can connect — nothing needs to be installed client-side.

## Commands

All require OP level 2 by default (configurable).

| Command | Description |
|---|---|
| `/crfp add <minutes> [reason...]` | Spawn a fake player at your current position. |
| `/crfp list` | Show active loaders. |
| `/crfp info <name>` | Details for one loader. |
| `/crfp extend <name> <minutes>` | Add time (capped at 24h). |
| `/crfp remove <name>` | Despawn early. |
| `/crfp history [limit]` | Tail of the audit log, color-coded. |

## Persistence

- `<world>/crfp_loaders.json` — active loaders. Timers pause while the server is offline.
- `<world>/crfp_history.jsonl` — append-only audit log (create / extend / remove / expire).

## Config

`serverconfig/crfp-server.toml`:

- `maxDurationMinutes` — hard cap per loader. Default 1440 (24 h).
- `defaultDurationMinutes` — used when `<minutes>` is omitted. Default 60.
- `warnBeforeExpirySeconds` — action-bar warning lead time. Default 30.
- `permissionLevel` — OP level required for `/crfp`. Default 2.

