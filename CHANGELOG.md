## Version 1.1.0

### Added
- Added permanent chunkloaders with `/crfp add permanent [reason...]`. They never expire, are restored after server restarts, and stay until removed with `/crfp remove`. They cannot be extended and show as permanent in list, info and history. Admins can disable them with the new `allowPermanentLoaders` server config option
- Added custom names for chunkloaders with `/crfp add [name] <minutes|permanent> [reason...]`. Names must be 3 to 16 letters, digits or underscores, and cannot match an existing loader, a real player's name or the reserved `permanent` keyword. The name is used as the fake player's username and tab-list name
- Added a `showTabListPrefix` server config option to toggle the `[CRFP]` prefix in the tab list
