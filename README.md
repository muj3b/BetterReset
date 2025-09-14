BetterReset (Paper/Spigot 1.21+)

Summary
- Reset a world's overworld, nether, and end while the server is running, using one command.
- Teleports players out first, unloads worlds, deletes folders asynchronously, and recreates dimensions with new seeds.
- Optional seed parameter, confirmation flow to prevent mistakes, and Multiverse-Core friendly (softdepend, no hard link).
 - GUI to choose world and confirm (with custom seed prompt).
 - Big on-screen countdown (title + bossbar) with credit: "plugin made by muj3b".

Requirements
- Java 17+
- PaperMC or Spigot 1.21+

Install
1. Build with `mvn package` and place `target/betterreset-1.0.0.jar` into your server's `plugins/` folder.
2. Start the server to generate `plugins/BetterReset/config.yml`.
3. Adjust messages and behavior in `config.yml` as desired.

Commands
- Root: `/betterreset <fullreset|gui|reload>`
  - `/betterreset fullreset <world> [confirm|--confirm] [--seed <long>|--seed random]`
    - Permission: `betterreset.use` (default: OP)
  - Examples:
    - `/betterreset fullreset world` → shows warning; you must confirm
    - `/betterreset fullreset world confirm` → proceed with random seeds
    - `/betterreset fullreset world --seed 12345 confirm` → use seed 12345 for all dimensions (configurable)
  - `/betterreset gui` → opens world selection and confirmation GUI (permission: `betterreset.gui`, default OP)
  - `/betterreset reload` → reloads config/messages (permission: `betterreset.reload`, default OP)
 - Legacy alias: `/fullreset ...` still works but prints a hint to use `/betterreset`.

Behavior
- Players in the target world's overworld, nether, or end are teleported to a safe fallback world.
- The three dimensions are unloaded, then their folders are deleted asynchronously.
- The overworld, nether, and end are recreated with new seeds.
- Optionally, affected players are returned to the new overworld spawn (configurable).

Multiverse-Core
- The plugin does not require Multiverse-Core, but has a softdepend and avoids conflicts.
- Multiverse typically detects world loads/unloads via events when worlds are recreated with the same names.

Notes
- The plugin follows Bukkit best practices: all Bukkit calls on the main thread; heavy disk IO deletion is async.
- Folder deletion is constrained to the server's world container for safety.
