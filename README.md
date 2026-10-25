# CRFP — Createrington Fake Players

Server-side chunkloader mod for **NeoForge 1.21.1**. Admins spawn timer-bound fake players that behave exactly like a real AFK player for chunk loading, mob spawning, random ticks, and redstone. Loaders auto-despawn when their timer runs out, or can be made permanent so they stay until removed.

Vanilla clients can connect — nothing needs to be installed client-side.

## Commands

All require OP level 2 by default (configurable).

| Command | Description |
|---|---|
| `/crfp add <minutes> [reason...]` | Spawn a fake player at your current position. |
| `/crfp add permanent [reason...]` | Spawn a fake player with no timer. It stays, across restarts, until `/crfp remove`. |
| `/crfp add <name> <minutes\|permanent> [reason...]` | Same, but with a name of your choice instead of the automatic `Createrington_N`. The name is the fake player's username and tab-list name. |
| `/crfp list` | Show active loaders. |
| `/crfp info <name>` | Details for one loader. |
| `/crfp extend <name> <minutes>` | Add time (capped at 24h). Not applicable to permanent loaders. |
| `/crfp remove <name>` | Despawn early. |
| `/crfp history [limit]` | Tail of the audit log, color-coded. |

Names must be 3 to 16 letters, digits or underscores, must not be in use by another loader, and must not belong to a player who is online. A name made only of digits is read as the minutes argument instead, and `permanent` is a keyword. Do not use the name of a real player on your server: in offline mode the fake player would take over their UUID.

## Persistence

- `<world>/crfp_loaders.json` — active loaders. Written on every change, every 30 seconds while loaders exist, and on shutdown (including after a crash). Timers pause while the server is offline.
- `<world>/crfp_history.jsonl` — append-only audit log (create / extend / remove / expire / restore).

On startup the persisted loaders are placed back into the world on the first server tick. If a loader cannot be placed (unknown dimension, another mod's login handler failing, ...) or its fake player is removed by something else later, it is kept in the file and retried with increasing delay (5 s up to 60 s); it shows as `NOT PLACED` in `/crfp list`, `/crfp info` shows the reason, and its timer does not run until it is in the world. Use `/crfp remove <name>` to discard one that will never place.

## Config

`serverconfig/crfp-server.toml`:

- `maxDurationMinutes` — hard cap per loader. Default 1440 (24 h).
- `defaultDurationMinutes` — used when `<minutes>` is omitted. Default 60.
- `allowPermanentLoaders` — whether `/crfp add permanent` is allowed. Default true. `maxDurationMinutes` does not apply to permanent loaders.
- `showTabListPrefix` — show `[CRFP]` in front of fake players in the tab list. Default true. Off, a loader appears under its bare name (`Createrington#3` or the custom name).
- `warnBeforeExpirySeconds` — action-bar warning lead time. Default 30.
- `permissionLevel` — OP level required for `/crfp`. Default 2.

