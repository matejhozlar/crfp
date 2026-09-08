## Unreleased

### Fixed
- Restart recovery is now resilient. Persisted loaders are read on startup and their fake players are placed on the first server tick, after every other mod has finished starting. If placement fails (for example because another mod's login handler throws), the loader is kept and retried every 5 seconds instead of being dropped and erased from the save file. Pending loaders show as `NOT PLACED` in `/crfp list` and their timer is paused until they are in the world.
- A login that failed halfway through no longer leaves an unmanaged fake player standing in the world.
- Loader state is autosaved every 30 seconds while loaders exist, and flushed after a crash via `ServerStoppedEvent`, so a crash or hard kill loses at most 30 seconds of countdown instead of everything since the last command.
- The `multiplayer.player.joined.renamed` join message is now suppressed for fake players too.

### Added
- `restore` events in the audit log (`/crfp history`) whenever a loader is placed back into the world after a restart.
- `Status` line in `/crfp info`.

## Version 1.0.0

### Added
- Spawn timer-bound fake players at your position with `/crfp add <minutes> [reason...]`. Fake players keep chunks loaded, trigger mob spawning, random ticks, and redstone exactly like a real AFK player.
- Fake players automatically despawn when their timer expires, with an action-bar warning sent to the creator shortly before expiry.
- In-game chat notification sent to the creator when a loader expires.
- Fake players mirror the skin of the admin who spawned them.
- Fake players appear in the server tab list labeled `[CRFP] Createrington#N`.
- `/crfp list` — show all active loaders with their remaining time.
- `/crfp info <name>` — detailed status for a single loader.
- `/crfp extend <name> <minutes>` — add time to an existing loader (capped at 24 hours total).
- `/crfp remove <name>` — despawn a loader early.
- `/crfp history [limit]` — tail of the append-only audit log, color-coded by event type.
- Loader state persists across server restarts in `<world>/crfp_loaders.json`; timers pause while the server is offline.
- Append-only audit log written to `<world>/crfp_history.jsonl`, recording every create, extend, remove, and expire event.
- Configurable via `serverconfig/crfp-server.toml`: maximum duration, default duration, expiry warning lead time, and required OP permission level.
