# 🔁 BetterReset — Live Overworld/Nether/End Reset

<!-- Badges -->
![Minecraft](https://img.shields.io/badge/Minecraft-1.21.x%2B-brightgreen?style=for-the-badge&logo=minecraft) ![API](https://img.shields.io/badge/API-Paper%2FSpigot_1.21%2B-blue?style=for-the-badge) ![Java](https://img.shields.io/badge/Java-17%2B-orange?style=for-the-badge&logo=openjdk) ![Status](https://img.shields.io/badge/Status-Production_Ready-success?style=for-the-badge) ![MV](https://img.shields.io/badge/Multiverse-Soft_Depend-9cf?style=for-the-badge)

---

> Safely reset a world's Overworld, Nether, and End while the server is running. Player-safe teleports, clean unload, async deletion, and full regeneration — all in one command or via a sleek GUI.

---

## ✨ Highlights

- ⚡ One command to reset all three dimensions for a base world.
- 🧍 Player safety first: teleport to a fallback world before unload.
- 🧹 Async deletion of world folders for smooth performance.
- 🌱 New random seeds or a custom seed you choose.
- 🖱️ GUI world selector with Confirm and Custom Seed prompt.
- ⏱️ Big title + bossbar countdown with credit “plugin made by muj3b”.
- 🧭 Multiverse-Core friendly (softdepend, no hard link).
- 🧰 Admin tools: status, cancel, force flag, notify permission, audit logs.
- 🔊 Broadcast control (all players or just affected dimensions).
- 🧷 Configurable fallback world and online-player threshold gate.

---

## 📥 Installation

1. Build with `mvn package` (Java 17+).
2. Place `target/betterreset-1.0.0.jar` into your server’s `plugins/` folder.
3. Start the server to generate `plugins/BetterReset/config.yml`.
4. Tweak messages and behavior in `config.yml` as needed.

Requirements: PaperMC or Spigot 1.21+ and Java 17+.

---

## 🕹️ Commands

Root: `/betterreset <fullreset|gui|reload|creator|status|cancel|fallback|seedsame|listworlds|about>`

| Command | Description | Permission | Default |
|:--|:--|:--|:--|
| `/betterreset fullreset &lt;world&gt; [confirm\|--confirm] [--seed &lt;long&gt;\|--seed random] [--force]` | Reset Overworld+Nether+End for `&lt;world&gt;` | `betterreset.use` | OP |
| `/betterreset gui` | Open GUI world selector + confirm | `betterreset.gui` | OP |
| `/betterreset reload` | Reload config/messages | `betterreset.reload` | OP |
| `/betterreset creator` | Show clickable donation link | `betterreset.creator` | Everyone |
| `/betterreset status` | Show current state (IDLE/COUNTDOWN/RUNNING) | `betterreset.status` | Everyone |
| `/betterreset cancel` | Cancel active countdown | `betterreset.cancel` | OP |
| `/betterreset fallback &lt;world&gt;\|none` | Set fallback world | `betterreset.fallback` | OP |
| `/betterreset seedsame &lt;true\|false&gt;` | Toggle same-seed policy | `betterreset.seedsame` | OP |
| `/betterreset listworlds` | List loaded base worlds | `betterreset.listworlds` | Everyone |
| `/betterreset about` | Show plugin version/author | `betterreset.about` | Everyone |

Examples:

```text
/betterreset fullreset world         # show warning, then confirm
/betterreset fullreset world confirm # proceed with random seed
/betterreset fullreset world --seed 12345 confirm
/betterreset fullreset world --force # skip confirm (requires permission)
/betterreset gui                     # GUI flow with Random or Custom Seed
```

---

## ⚙️ Configuration (essentials)

Edit `plugins/BetterReset/config.yml` after first run. Key options:

```yaml
confirmation:
  requireConfirm: true
  timeoutSeconds: 15
  consoleBypasses: true

seeds:
  useSameSeedForAllDimensions: true

players:
  returnToNewSpawnAfterReset: true
  freshStartOnReset: true
  resetOfflinePlayers: true  # Also reset offline players' data (inventory, XP, etc.)

countdown:
  seconds: 10
  broadcastToAll: true

teleport:
  fallbackWorldName: ""

limits:

  maxOnlineForReset: -1  # -1 disables the check

messages:
  noPermission: "&cYou don't have permission to use this command."
  countdownTitle: "&cReset in %s..."
  countdownSubtitle: "&7plugin made by muj3b"
```

---

## 🔍 How It Works

1) Detect affected players and safely teleport them to a fallback world.  
2) Unload Overworld/Nether/End for the target base world on the main thread.  
3) Delete world folders asynchronously off the main thread.  
4) Recreate Overworld/Nether/End with new seeds.  
5) Optionally return affected players to the new spawn.

Best practices followed: Bukkit calls are main-thread; disk IO deletion is async; folder deletes are restricted to the world container.

---

## 🔌 Multiverse-Core

BetterReset doesn’t require Multiverse-Core. If present, the plugin tries (via reflection) to register/import/load recreated worlds so MV stays in sync. World names remain consistent (`<world>`, `<world>_nether`, `<world>_the_end`).

---

## 🙌 Support the Creator

Open in-game with `/betterreset creator` or click below:

[![Donate](https://img.shields.io/badge/%F0%9F%92%96_Donate-Support_Development-ff69b4?style=for-the-badge)](https://donate.stripe.com/8x29AT0H58K03judnR0Ba01)

---

## 🧩 Notes & Tips

- Use `/betterreset status` and `/betterreset cancel` to manage countdowns.  
- `--force` requires the `betterreset.force` permission.  
- Admin notifications go to players with `betterreset.notify`.  
- A fallback world can be set via config or `/betterreset fallback <world>`.

---

Developed by muj3b • Paper/Spigot 1.21+ • Java 17+
