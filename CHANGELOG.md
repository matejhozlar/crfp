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
