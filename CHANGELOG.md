## Version 1.0.1

### Added
- Added `restore` events to the audit log (`/crfp history`) recorded whenever a loader is placed back into the world after a server restart.
- Added a `Status` line to `/crfp info` showing whether a loader's fake player is active or pending, and the reason for any failed placement attempts.

### Fixed
- Fixed loaders being permanently deleted after a restart if placement failed (for example because another mod's login handler threw an error). They are now kept in the save file and retried with increasing delay (5 s up to 60 s); affected loaders show as `NOT PLACED` in `/crfp list` and their countdown is paused until they are in the world.
- Fixed fake players that were removed from the player list by another mod being counted as actively loading chunks. The affected loader now falls back to the pending state and is re-placed automatically.
- Fixed crashes losing all countdown progress since the last command. Loader state is now autosaved every 30 seconds and flushed on server stop via `ServerStoppedEvent`.
- Fixed a partial login failure leaving an unmanaged fake player standing in the world.
- Fixed the renamed join announcement not being suppressed for fake players.
- Fixed a throwing `PlayerLoggedOutEvent` handler or a version-mismatched mod being able to crash the server during chunk-loader tick, expiry, or shutdown.
