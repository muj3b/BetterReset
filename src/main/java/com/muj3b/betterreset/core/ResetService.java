package com.muj3b.betterreset.core;

import com.muj3b.betterreset.FullResetPlugin;
import com.muj3b.betterreset.util.BackupManager;
import com.muj3b.betterreset.util.CountdownManager;
import com.muj3b.betterreset.util.Messages;
import com.muj3b.betterreset.util.MultiverseCompat;
import com.muj3b.betterreset.util.PreloadManager;
import com.muj3b.betterreset.util.ResetAuditLogger;
import com.muj3b.betterreset.util.SeedHistory;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.Material;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Orchestrates the safe reset flow for worlds.
 */
public class ResetService {

    public enum Dimension { OVERWORLD, NETHER, END }

    private final FullResetPlugin plugin;
    @SuppressWarnings("unused")
    private final ConfirmationManager confirmationManager;
    private final CountdownManager countdownManager;
    private final MultiverseCompat multiverseCompat;
    private final BackupManager backupManager;
    private final PreloadManager preloadManager;

    private final ResetAuditLogger auditLogger = new ResetAuditLogger();
    private final Map<UUID, ResetTask> activeTasks = new HashMap<>();
    private final Random rng = new Random();
    private final SeedHistory seedHistory;
    private final Map<String, Long> lastResetAt = new ConcurrentHashMap<>();
    private final Map<String, Long> lastResetTimestamp = new ConcurrentHashMap<>();
    private long totalResets = 0;

    public ResetService(FullResetPlugin plugin, ConfirmationManager confirmationManager, CountdownManager countdownManager, MultiverseCompat multiverseCompat, PreloadManager preloadManager) {
        this.plugin = plugin;
        this.confirmationManager = confirmationManager;
        this.countdownManager = countdownManager;
        this.multiverseCompat = multiverseCompat;
        this.backupManager = new BackupManager(plugin);
        this.preloadManager = preloadManager;
        this.seedHistory = plugin.getSeedHistory();
    }

    private volatile boolean resetInProgress = false;
    private volatile String currentTarget = null;
    private volatile String phase = "IDLE";

    public void startReset(Player player, String baseWorld, EnumSet<Dimension> dimensions) {
        int cooldownSeconds = plugin.getConfig().getInt("limits.resetCooldownSeconds", 0);
        if (cooldownSeconds > 0) {
            Long last = lastResetAt.get(baseWorld);
            if (last != null && (System.currentTimeMillis() - last) < (cooldownSeconds * 1000L)) {
                Messages.send(player, "&cA reset for &6" + baseWorld + "&c was performed recently. Please wait before retrying.");
                return;
            }
        }
        if (resetInProgress) { Messages.send(player, "&cA reset is already in progress. Please wait."); return; }
        int maxOnline = plugin.getConfig().getInt("limits.maxOnlineForReset", -1);
        if (maxOnline >= 0 && Bukkit.getOnlinePlayers().size() > maxOnline) { Messages.send(player, "&cToo many players online to reset now (&e" + Bukkit.getOnlinePlayers().size() + "&c > &e" + maxOnline + "&c)."); return; }

        List<World> affectedWorlds = dimensions.stream().flatMap(dim -> getAffectedWorld(baseWorld, dim).stream()).toList();
        Optional<Long> effectiveSeed = Optional.of(new Random().nextLong());
        try { maybePreload(baseWorld, effectiveSeed.get(), dimensions); } catch (Throwable ignored) {}
        ResetTask task = new ResetTask(baseWorld, dimensions, player, effectiveSeed.orElse(null), affectedWorlds);
        activeTasks.put(player.getUniqueId(), task);

        int seconds = plugin.getConfig().getInt("countdown.seconds", 10);
        Messages.send(player, "&eStarting reset countdown for &6" + baseWorld + "&e...");
        countdownManager.startCountdown(task.getInitiator(), task.getAffectedWorlds(), seconds, () -> {
            if (!task.isCancelled()) {
                resetWorldAsync(task.getInitiator(), baseWorld, effectiveSeed, dimensions);
                lastResetAt.put(baseWorld, System.currentTimeMillis());
            }
        });
        resetInProgress = true; currentTarget = baseWorld; phase = "COUNTDOWN";
    }

    public void startResetWithCountdown(Player player, String baseWorld, Optional<Long> seedOpt, EnumSet<Dimension> dimensions) {
        if (resetInProgress) { Messages.send(player, "&cA reset is already in progress. Please wait."); return; }
        List<World> affectedWorlds = dimensions.stream().flatMap(dim -> getAffectedWorld(baseWorld, dim).stream()).toList();
        Optional<Long> effectiveSeed = seedOpt.isPresent() ? seedOpt : Optional.of(rng.nextLong());
        try { maybePreload(baseWorld, effectiveSeed.get(), dimensions); } catch (Throwable ignored) {}
        ResetTask task = new ResetTask(baseWorld, dimensions, player, effectiveSeed.orElse(null), affectedWorlds);
        activeTasks.put(player.getUniqueId(), task);
        int seconds = plugin.getConfig().getInt("countdown.seconds", 10);
        Messages.send(player, "&eStarting reset countdown for &6" + baseWorld + "&e...");
        countdownManager.startCountdown(task.getInitiator(), task.getAffectedWorlds(), seconds, () -> {
            if (!task.isCancelled()) {
                resetWorldAsync(task.getInitiator(), baseWorld, effectiveSeed, dimensions);
                lastResetAt.put(baseWorld, System.currentTimeMillis());
            }
        });
        resetInProgress = true; currentTarget = baseWorld; phase = "COUNTDOWN";
    }

    public void startResetWithCountdown(CommandSender sender, String baseWorld, Optional<Long> seed) {
        Player player = null; if (sender instanceof Player p) player = p;
        EnumSet<Dimension> dims = EnumSet.of(Dimension.OVERWORLD, Dimension.NETHER, Dimension.END);
        if (player != null) startResetWithCountdown(player, baseWorld, seed, dims);
        else { Messages.send(Bukkit.getConsoleSender(), "&eConsole initiated reset for " + baseWorld); resetWorldAsync(Bukkit.getConsoleSender(), baseWorld, seed); }
    }

    private Optional<World> getAffectedWorld(String baseWorld, Dimension dim) {
        String worldName = switch (dim) { case OVERWORLD -> baseWorld; case NETHER -> baseWorld + "_nether"; case END -> baseWorld + "_the_end"; };
        return Optional.ofNullable(Bukkit.getWorld(worldName));
    }

    public void resetWorldAsync(CommandSender initiator, String baseWorldName, Optional<Long> seedOpt) {
        resetWorldAsync(initiator, baseWorldName, seedOpt, EnumSet.of(Dimension.OVERWORLD, Dimension.NETHER, Dimension.END));
    }

    public void resetWorldAsync(CommandSender initiator, String baseWorldName, Optional<Long> seedOpt, EnumSet<Dimension> dims) {
        final String worldBase = baseWorldName;
        final List<String> worldNames = dimensionNames(worldBase, dims);
        Set<UUID> affectedPlayers = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) if (worldNames.contains(p.getWorld().getName())) affectedPlayers.add(p.getUniqueId());

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                phase = "RUNNING";
                auditLogger.log(plugin, "Reset started for '" + worldBase + "'");
                World fallback = findOrCreateFallbackWorld(worldNames);
                if (fallback == null) { Messages.send(initiator, "&cFailed to find or create a fallback world; aborting."); resetInProgress = false; phase = "IDLE"; return; }

                for (UUID id : affectedPlayers) { Player p = Bukkit.getPlayer(id); if (p != null && p.isOnline()) safeTeleport(p, fallback.getSpawnLocation()); }
                for (Player online : Bukkit.getOnlinePlayers()) if (worldNames.contains(online.getWorld().getName())) safeTeleport(online, fallback.getSpawnLocation());
                // Apply fresh-start immediately for affected players (pre-unload) to ensure visible reset
                if (plugin.getConfig().getBoolean("players.freshStartOnReset", true)) {
                    for (UUID id : affectedPlayers) { Player p = Bukkit.getPlayer(id); if (p != null && p.isOnline()) applyFreshStartIfEnabled(p); }
                }

                Map<String, Path> worldFolders = resolveWorldFolders(worldNames);
                if (!unloadWorldsReliably(worldNames, fallback, initiator)) { resetInProgress = false; phase = "IDLE"; return; }

                boolean backupsEnabled = plugin.getConfig().getBoolean("backups.enabled", true);
                Messages.send(initiator, backupsEnabled ? "&7Worlds unloaded. Archiving backups and recreating..." : "&7Worlds unloaded. Deleting old folders and recreating...");

                plugin.getBackgroundExecutor().submit(() -> {
                    try {
                        if (backupsEnabled) backupManager.snapshot(worldBase, worldFolders);
                        else {
                            Path trashRoot = plugin.getDataFolder().toPath().resolve("trash").resolve(String.valueOf(System.currentTimeMillis()));
                            Files.createDirectories(trashRoot);
                            for (Map.Entry<String, Path> e : worldFolders.entrySet()) {
                                Path path = e.getValue();
                                if (path == null || !Files.exists(path)) continue;
                                try { moveWithFallback(path, trashRoot.resolve(path.getFileName())); } catch (Exception ex) { try { deletePath(path); } catch (IOException ignored) {} }
                            }
                            plugin.getBackgroundExecutor().submit(() -> { try { deletePath(trashRoot); } catch (IOException ignored) {} });
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> { swapPreloadedIfAny(worldBase, dims); recreateWorlds(initiator, worldBase, seedOpt, affectedPlayers, dims); });
                    } catch (Exception ex) {
                        Bukkit.getScheduler().runTask(plugin, () -> { Messages.send(initiator, "&cUnexpected error while deleting worlds: " + ex.getMessage()); resetInProgress = false; phase = "IDLE"; auditLogger.log(plugin, "Reset failed for '" + worldBase + "' (exception during delete): " + ex.getMessage()); });
                    }
                });
            } catch (Exception ex) { Messages.send(initiator, "&cError during reset: " + ex.getMessage()); resetInProgress = false; phase = "IDLE"; auditLogger.log(plugin, "Reset failed for '" + worldBase + "' (exception): " + ex.getMessage()); }
        });
    }

    private void recreateWorlds(CommandSender initiator, String base, Optional<Long> seedOpt, Set<UUID> previouslyAffected, EnumSet<Dimension> dims) {
        try {
            boolean sameSeedForAll = plugin.getConfig().getBoolean("seeds.useSameSeedForAllDimensions", true);
            long baseSeed = seedOpt.orElseGet(() -> rng.nextLong());
            World overworld = null;
            if (dims.contains(Dimension.OVERWORLD)) {
                overworld = new WorldCreator(base).seed(baseSeed).environment(World.Environment.NORMAL).type(WorldType.NORMAL).createWorld();
                if (overworld == null) { Messages.send(initiator, "&cFailed to create overworld: " + base); resetInProgress = false; phase = "IDLE"; auditLogger.log(plugin, "Reset failed creating overworld for '" + base + "'"); return; }
                try { overworld.getChunkAt(overworld.getSpawnLocation()).load(true); } catch (Exception ignored) {}
                multiverseCompat.ensureRegistered(base, World.Environment.NORMAL, baseSeed);
            }

            long netherSeed = sameSeedForAll ? baseSeed : rng.nextLong();
            if (dims.contains(Dimension.NETHER)) {
                World nether = new WorldCreator(base + "_nether").seed(netherSeed).environment(World.Environment.NETHER).type(WorldType.NORMAL).createWorld();
                if (nether == null) { Messages.send(initiator, "&cFailed to create nether: " + base + "_nether"); resetInProgress = false; phase = "IDLE"; auditLogger.log(plugin, "Reset failed creating nether for '" + base + "'"); return; }
                try { nether.getChunkAt(nether.getSpawnLocation()).load(true); } catch (Exception ignored) {}
                multiverseCompat.ensureRegistered(base + "_nether", World.Environment.NETHER, netherSeed);
            }

            long endSeed = sameSeedForAll ? baseSeed : rng.nextLong();
            if (dims.contains(Dimension.END)) {
                World theEnd = new WorldCreator(base + "_the_end").seed(endSeed).environment(World.Environment.THE_END).type(WorldType.NORMAL).createWorld();
                if (theEnd == null) { Messages.send(initiator, "&cFailed to create the_end: " + base + "_the_end"); resetInProgress = false; phase = "IDLE"; auditLogger.log(plugin, "Reset failed creating the_end for '" + base + "'"); return; }
                try { theEnd.getChunkAt(theEnd.getSpawnLocation()).load(true); } catch (Exception ignored) {}
                multiverseCompat.ensureRegistered(base + "_the_end", World.Environment.THE_END, endSeed);
            }

            try { seedHistory.add(baseSeed); if (!sameSeedForAll) { seedHistory.add(netherSeed); seedHistory.add(endSeed); } } catch (Exception ignored) {}
            Messages.send(initiator, "&aRecreated worlds for '&e" + base + "&a' successfully.");

            boolean returnPlayers = plugin.getConfig().getBoolean("players.returnToNewSpawnAfterReset", true);
            if (returnPlayers && overworld != null) {
                Location spawn = overworld.getSpawnLocation();
                // Set world spawn to ensure respawning works correctly
                try {
                    overworld.setSpawnLocation(spawn);
                } catch (Exception ex) {
                    plugin.getLogger().warning("Failed to set world spawn after reset: " + ex.getMessage());
                }
                
                for (UUID id : previouslyAffected) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        safeTeleport(p, spawn);
                        // Clear bed spawn location so they use world spawn
                        try {
                            p.setRespawnLocation(null);
                        } catch (Exception ex) {
                            plugin.getLogger().warning("Failed to clear bed spawn for player " + p.getName() + ": " + ex.getMessage());
                        }
                    }
                }
            }

            // Apply fresh-start resets to players as configured
            if (plugin.getConfig().getBoolean("players.freshStartOnReset", true)) {
                Set<UUID> alreadyReset = new HashSet<>();
                for (UUID id : previouslyAffected) {
                    Player p = Bukkit.getPlayer(id);
if (p != null && p.isOnline()) { applyFreshStartIfEnabled(p); alreadyReset.add(id); try { showShortTitle(p, "Fresh start applied"); p.sendActionBar(net.kyori.adventure.text.Component.text("Done")); } catch (Throwable ignored) {} }
                }
                if (plugin.getConfig().getBoolean("players.resetAllOnlineAfterReset", true)) {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (!alreadyReset.contains(online.getUniqueId())) {
                            applyFreshStartIfEnabled(online);
try { showShortTitle(online, "Fresh start applied"); online.sendActionBar(net.kyori.adventure.text.Component.text("Done")); } catch (Throwable ignored) {}
                        }
                    }
                }
            }

            Messages.send(initiator, "&aRecreated worlds for '&e" + base + "&a' successfully.");
            for (Player online : Bukkit.getOnlinePlayers()) { if (online.equals(initiator)) continue; if (online.hasPermission("betterreset.notify")) Messages.send(online, "&a[BetterReset]&7 World '&e" + base + "&7' has been reset."); }

            totalResets++; lastResetTimestamp.put(base, System.currentTimeMillis()); auditLogger.log(plugin, "Reset completed for '" + base + "'"); resetInProgress = false; phase = "IDLE";
            try { plugin.getRespawnManager().markReset(base); } catch (Exception ignored) {}
        } catch (Exception ex) { Messages.send(initiator, "&cError recreating worlds: " + ex.getMessage()); resetInProgress = false; phase = "IDLE"; auditLogger.log(plugin, "Reset failed (exception during create) for '" + base + "': " + ex.getMessage()); }
    }

    private static List<String> dimensionNames(String base, EnumSet<Dimension> dims) {
        List<String> list = new ArrayList<>(); if (dims.contains(Dimension.OVERWORLD)) list.add(base); if (dims.contains(Dimension.NETHER)) list.add(base + "_nether"); if (dims.contains(Dimension.END)) list.add(base + "_the_end"); return list;
    }

    private Map<String, Path> resolveWorldFolders(List<String> worldNames) {
        Map<String, Path> map = new LinkedHashMap<>(); File container = Bukkit.getWorldContainer(); Path containerPath = container.toPath().toAbsolutePath().normalize();
        for (String name : worldNames) {
            World w = Bukkit.getWorld(name);
            File f = (w != null) ? w.getWorldFolder() : new File(container, name);
            Path p = f.toPath().toAbsolutePath().normalize();
            if (!p.startsWith(containerPath)) map.put(name, null);
            else if (Files.exists(p)) map.put(name, p);
            else map.put(name, null);
        }
        return map;
    }

    private World findOrCreateFallbackWorld(List<String> toAvoid) {
        String configured = plugin.getConfig().getString("teleport.fallbackWorldName", "").trim();
        if (!configured.isEmpty()) { World cw = Bukkit.getWorld(configured); if (cw != null && !toAvoid.contains(cw.getName())) return cw; }
        for (World w : Bukkit.getWorlds()) if (!toAvoid.contains(w.getName())) return w;
        String tmpName = "betterreset_safe_" + Instant.now().getEpochSecond();
        return new WorldCreator(tmpName).environment(World.Environment.NORMAL).type(WorldType.NORMAL).createWorld();
    }

    public boolean cancelCountdown() { boolean canceled = countdownManager.cancel(); if (canceled) { resetInProgress = false; phase = "IDLE"; auditLogger.log(plugin, "Countdown canceled for '" + currentTarget + "'"); currentTarget = null; } return canceled; }

    public String getStatusLine() { if ("IDLE".equals(phase)) return "IDLE"; if ("COUNTDOWN".equals(phase)) return "COUNTDOWN '" + currentTarget + "' (" + countdownManager.secondsLeft() + "/" + countdownManager.totalSeconds() + ")"; return "RUNNING '" + currentTarget + "'"; }

    public List<BackupManager.BackupRef> listBackups() { return backupManager.listBackups(); }

    public long getTotalResets() { return totalResets; }

    public Optional<Long> getLastResetTimestamp(String base) { return Optional.ofNullable(lastResetTimestamp.get(base)); }

    public void restoreBackupAsync(CommandSender initiator, String base, String timestamp) {
        if (resetInProgress) { Messages.send(initiator, "&cA reset/restore is already in progress. Please wait."); return; }
        resetInProgress = true; phase = "RUNNING"; currentTarget = base; List<String> worldNames = Arrays.asList(base, base + "_nether", base + "_the_end"); Set<UUID> affected = new HashSet<>(); for (Player p : Bukkit.getOnlinePlayers()) if (worldNames.contains(p.getWorld().getName())) affected.add(p.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            World fallback = findOrCreateFallbackWorld(worldNames); if (fallback == null) { Messages.send(initiator, "&cFailed to create fallback world; aborting restore."); resetInProgress = false; phase = "IDLE"; return; }
            for (UUID id : affected) { Player p = Bukkit.getPlayer(id); if (p != null && p.isOnline()) safeTeleport(p, fallback.getSpawnLocation()); }
            for (String name : worldNames) { World w = Bukkit.getWorld(name); if (w != null) { try { w.save(); } catch (Exception ignored) {} Bukkit.unloadWorld(w, true); } }
            // Apply fresh-start immediately to affected players prior to restore
            if (plugin.getConfig().getBoolean("players.freshStartOnReset", true)) {
                for (UUID id : affected) { Player p = Bukkit.getPlayer(id); if (p != null && p.isOnline()) applyFreshStartIfEnabled(p); }
            }
            CompletableFuture.runAsync(() -> {
                try {
                    backupManager.restore(base, timestamp);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        for (String name : worldNames) { File f = new File(Bukkit.getWorldContainer(), name); if (f.exists()) { World.Environment env = name.endsWith("_nether") ? World.Environment.NETHER : name.endsWith("_the_end") ? World.Environment.THE_END : World.Environment.NORMAL; new WorldCreator(name).environment(env).type(WorldType.NORMAL).createWorld(); } }
                        // Optionally return affected players and fresh-start
                        boolean returnPlayers = plugin.getConfig().getBoolean("players.returnToNewSpawnAfterReset", true);
                        World baseWorld = Bukkit.getWorld(base);
                        if (returnPlayers && baseWorld != null) {
                            Location spawn = baseWorld.getSpawnLocation();
                            for (UUID id : affected) { Player p = Bukkit.getPlayer(id); if (p != null && p.isOnline()) safeTeleport(p, spawn); }
                        }
                        if (plugin.getConfig().getBoolean("players.freshStartOnReset", true)) {
                            Set<UUID> already = new HashSet<>();
for (UUID id : affected) { Player p = Bukkit.getPlayer(id); if (p != null && p.isOnline()) { applyFreshStartIfEnabled(p); already.add(id); try { showShortTitle(p, "Fresh start applied"); p.sendActionBar(net.kyori.adventure.text.Component.text("Done")); } catch (Throwable ignored) {} } }
                            if (plugin.getConfig().getBoolean("players.resetAllOnlineAfterReset", true)) {
for (Player op : Bukkit.getOnlinePlayers()) if (!already.contains(op.getUniqueId())) { applyFreshStartIfEnabled(op); try { showShortTitle(op, "Fresh start applied"); op.sendActionBar(net.kyori.adventure.text.Component.text("Done")); } catch (Throwable ignored) {} }
                            }
                        }
                        Messages.send(initiator, "&aRestored backup '&e" + base + " @ " + timestamp + "&a'."); if (initiator instanceof Player ip) { World w = Bukkit.getWorld(base); if (w != null) safeTeleport(ip, w.getSpawnLocation()); } resetInProgress = false; phase = "IDLE";
                    });
                } catch (Exception ex) { Bukkit.getScheduler().runTask(plugin, () -> { Messages.send(initiator, "&cRestore failed: " + ex.getMessage()); resetInProgress = false; phase = "IDLE"; }); }
            });
        });
    }

    public void restoreBackupAsync(CommandSender initiator, String base, String timestamp, EnumSet<Dimension> dims) {
        if (resetInProgress) { Messages.send(initiator, "&cA reset/restore is already in progress. Please wait."); return; }
        resetInProgress = true; phase = "RUNNING"; currentTarget = base; List<String> worldNames = dimensionNames(base, dims); Set<UUID> affected = new HashSet<>(); for (Player p : Bukkit.getOnlinePlayers()) if (worldNames.contains(p.getWorld().getName())) affected.add(p.getUniqueId());
        Bukkit.getScheduler().runTask(plugin, () -> {
            World fallback = findOrCreateFallbackWorld(worldNames); if (fallback == null) { Messages.send(initiator, "&cFailed to create fallback world; aborting restore."); resetInProgress = false; phase = "IDLE"; return; }
            for (UUID id : affected) { Player p = Bukkit.getPlayer(id); if (p != null && p.isOnline()) safeTeleport(p, fallback.getSpawnLocation()); }
            for (String name : worldNames) { World w = Bukkit.getWorld(name); if (w != null) { try { w.save(); } catch (Exception ignored) {} Bukkit.unloadWorld(w, true); } }
            CompletableFuture.runAsync(() -> {
                try {
                    backupManager.restore(base, timestamp, dims);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        for (String name : worldNames) { File f = new File(Bukkit.getWorldContainer(), name); if (f.exists()) { World.Environment env = name.endsWith("_nether") ? World.Environment.NETHER : name.endsWith("_the_end") ? World.Environment.THE_END : World.Environment.NORMAL; new WorldCreator(name).environment(env).type(WorldType.NORMAL).createWorld(); } }
                        // Optionally return affected players and fresh-start
                        boolean returnPlayers = plugin.getConfig().getBoolean("players.returnToNewSpawnAfterReset", true);
                        World baseWorld = Bukkit.getWorld(base);
                        if (returnPlayers && baseWorld != null) {
                            Location spawn = baseWorld.getSpawnLocation();
                            for (UUID id : affected) { Player p = Bukkit.getPlayer(id); if (p != null && p.isOnline()) safeTeleport(p, spawn); }
                        }
                        if (plugin.getConfig().getBoolean("players.freshStartOnReset", true)) {
                            Set<UUID> already = new HashSet<>();
for (UUID id : affected) { Player p = Bukkit.getPlayer(id); if (p != null && p.isOnline()) { applyFreshStartIfEnabled(p); already.add(id); try { showShortTitle(p, "Fresh start applied"); p.sendActionBar(net.kyori.adventure.text.Component.text("Done")); } catch (Throwable ignored) {} } }
                            if (plugin.getConfig().getBoolean("players.resetAllOnlineAfterReset", true)) {
for (Player op : Bukkit.getOnlinePlayers()) if (!already.contains(op.getUniqueId())) { applyFreshStartIfEnabled(op); try { showShortTitle(op, "Fresh start applied"); op.sendActionBar(net.kyori.adventure.text.Component.text("Done")); } catch (Throwable ignored) {} }
                            }
                        }
                        Messages.send(initiator, "&aRestored backup '&e" + base + " @ " + timestamp + "&a' for " + dims + "."); if (initiator instanceof Player ip) { World w = Bukkit.getWorld(base); if (w != null) safeTeleport(ip, w.getSpawnLocation()); } resetInProgress = false; phase = "IDLE";
                    });
                } catch (Exception ex) { Bukkit.getScheduler().runTask(plugin, () -> { Messages.send(initiator, "&cRestore failed: " + ex.getMessage()); resetInProgress = false; phase = "IDLE"; }); }
            });
        });
    }

    public void deleteBackupAsync(CommandSender initiator, String base, String timestamp) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> { try { backupManager.deleteBackup(base, timestamp); Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aDeleted backup '&e" + base + " @ " + timestamp + "&a'.")); } catch (Exception ex) { Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cDelete failed: " + ex.getMessage())); } });
    }

    public void pruneBackupsAsync(CommandSender initiator, Optional<String> baseOpt) { Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> { try { if (baseOpt.isPresent()) backupManager.prune(baseOpt.get()); else backupManager.pruneAll(); Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aPrune complete.")); } catch (Exception ex) { Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cPrune failed: " + ex.getMessage())); } }); }

    public void pruneBackupsAsync(CommandSender initiator, Optional<String> baseOpt, boolean forceKeepPolicy) { if (!forceKeepPolicy) { pruneBackupsAsync(initiator, baseOpt); return; } Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> { try { int keep = plugin.getConfig().getInt("backups.pruneNowKeepPerBase", 2); if (baseOpt.isPresent()) backupManager.pruneKeepPerBase(baseOpt.get(), keep); else backupManager.pruneKeepAllBases(keep); Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aPrune complete. Kept at most " + keep + " per base.")); } catch (Exception ex) { Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cPrune failed: " + ex.getMessage())); } }); }

    public void deleteAllBackupsForBaseAsync(CommandSender initiator, String base) { Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> { try { backupManager.deleteAllForBase(base); Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aDeleted all backups for '&e" + base + "&a'.")); } catch (Exception ex) { Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cDelete all failed: " + ex.getMessage())); } }); }

    public void deleteAllBackupsAsync(CommandSender initiator) { Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> { try { backupManager.deleteAllBases(); Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aDeleted ALL backups for ALL bases.")); } catch (Exception ex) { Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cDelete all failed: " + ex.getMessage())); } }); }

    public void testResetAsync(CommandSender initiator, String base, Optional<Long> seedOpt, EnumSet<Dimension> dims) { testResetAsync(initiator, base, seedOpt, dims, false); }

    public void testResetAsync(CommandSender initiator, String base, Optional<Long> seedOpt, EnumSet<Dimension> dims, boolean dryRun) {
        String testBase = ("brtest_" + base + "_" + System.currentTimeMillis());
        long seed = seedOpt.orElseGet(() -> rng.nextLong());
        Messages.send(initiator, "&7Starting test reset for '&e" + base + "&7' → temp '&e" + testBase + "&7'.");
        long t0 = System.nanoTime();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                for (String name : dimensionNames(testBase, dims)) {
                    World.Environment env = name.endsWith("_nether") ? World.Environment.NETHER : name.endsWith("_the_end") ? World.Environment.THE_END : World.Environment.NORMAL;
                    World w = new WorldCreator(name).environment(env).seed(seed).type(WorldType.NORMAL).createWorld();
                    if (w != null) try { w.getChunkAt(w.getSpawnLocation()).load(true);} catch (Exception ignored) {}
                }
                long tCreate = System.nanoTime();
                Messages.send(initiator, "&aCreated temp worlds in &e" + ((tCreate - t0)/1_000_000) + "ms.");
                for (String name : dimensionNames(testBase, dims)) { World w = Bukkit.getWorld(name); if (w != null) Bukkit.unloadWorld(w, true); }
                if (dryRun) { long tEnd = System.nanoTime(); Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aDry-run complete. Total test time: &e" + ((tEnd - t0)/1_000_000) + "ms")); }
                else {
                    CompletableFuture.runAsync(() -> {
                        try {
                            for (String name : dimensionNames(testBase, dims)) { File f = new File(Bukkit.getWorldContainer(), name); if (f.exists()) deletePath(f.toPath()); }
                            long tEnd = System.nanoTime(); Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aCleanup complete. Total test time: &e" + ((tEnd - t0)/1_000_000) + "ms"));
                        } catch (Exception ex) { Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cTest cleanup failed: " + ex.getMessage())); }
                    });
                }
            } catch (Exception ex) { Messages.send(initiator, "&cTest reset failed: " + ex.getMessage()); }
        });
    }

    private void safeTeleport(Player p, Location to) { try { p.teleport(to); } catch (Exception ignored) {} }

    private void showShortTitle(Player p, String message) {
        try {
            net.kyori.adventure.title.Title.Times times = net.kyori.adventure.title.Title.Times.times(java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(1500), java.time.Duration.ofMillis(300));
            net.kyori.adventure.title.Title title = net.kyori.adventure.title.Title.title(
                net.kyori.adventure.text.Component.empty(),
                net.kyori.adventure.text.Component.text(message),
                times
            );
            p.showTitle(title);
        } catch (Throwable ignored) {}
    }

    private void applyFreshStartIfEnabled(Player p) {
        if (!plugin.getConfig().getBoolean("players.freshStartOnReset", true)) return;
        try {
            // Clear inventory and ender chest
            p.getInventory().clear();
            p.getEnderChest().clear();
            
            // Reset health and status effects
            p.setFireTicks(0);
            var attr = p.getAttribute(org.bukkit.attribute.Attribute.GENERIC_MAX_HEALTH);
            double maxHealth = (attr != null) ? attr.getValue() : 20.0;
            p.setHealth(Math.min(maxHealth, 20.0));
            
            // Reset hunger and saturation
            p.setFoodLevel(20);
            p.setSaturation(5f);
            p.setExhaustion(0f);
            
            // Reset XP more thoroughly
            try {
                p.setLevel(0);
                p.setExp(0f);
                p.setTotalExperience(0);
            } catch (Exception xpEx) {
                // Log XP reset failure for debugging
                plugin.getLogger().warning("Failed to reset XP for player " + p.getName() + ": " + xpEx.getMessage());
            }
            
            // Reset other player state
            p.setFallDistance(0f);
            p.setRemainingAir(p.getMaximumAir());
            
            // Clear potion effects
            try {
                p.getActivePotionEffects().forEach(effect -> p.removePotionEffect(effect.getType()));
            } catch (Exception effectEx) {
                plugin.getLogger().warning("Failed to clear potion effects for player " + p.getName() + ": " + effectEx.getMessage());
            }
            
        } catch (Exception ignored) {
            plugin.getLogger().warning("Failed to apply fresh start for player " + p.getName());
        }
    }

    private void deletePath(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Files.deleteIfExists(file); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException { Files.deleteIfExists(dir); return FileVisitResult.CONTINUE; }
        });
    }

    private boolean unloadWorldsReliably(List<String> worldNames, World fallback, CommandSender initiator) {
        List<String> remaining = new ArrayList<>();
        for (String name : worldNames) {
            World w = Bukkit.getWorld(name); if (w == null) continue; for (Player pl : new ArrayList<>(w.getPlayers())) safeTeleport(pl, fallback.getSpawnLocation()); try { w.save(); } catch (Exception ignored) {} if (!Bukkit.unloadWorld(w, true)) remaining.add(name);
        }
        if (remaining.isEmpty()) return true;
        final int max = 5;
        for (int attempt = 1; attempt <= max && !remaining.isEmpty(); attempt++) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            Iterator<String> it = remaining.iterator();
            while (it.hasNext()) {
                String name = it.next(); World w = Bukkit.getWorld(name); if (w == null) { it.remove(); continue; } for (Player pl : new ArrayList<>(w.getPlayers())) safeTeleport(pl, fallback.getSpawnLocation()); try { w.save(); } catch (Exception ignored) {} if (Bukkit.unloadWorld(w, true)) it.remove();
            }
        }
        if (!remaining.isEmpty()) Messages.send(initiator, "&cFailed to unload: &e" + String.join(", ", remaining));
        return remaining.isEmpty();
    }

    private void swapPreloadedIfAny(String base, EnumSet<Dimension> dims) {
        for (String target : dimensionNames(base, dims)) {
            String prep = "brprep_" + target; World prepWorld = Bukkit.getWorld(prep); if (prepWorld != null) Bukkit.unloadWorld(prepWorld, true);
            File container = Bukkit.getWorldContainer(); File prepFolder = new File(container, prep); File targetFolder = new File(container, target);
            if (prepFolder.exists()) {
                if (targetFolder.exists()) { try { deletePath(targetFolder.toPath()); } catch (Exception ignored) {} }
                try { moveWithFallback(prepFolder.toPath(), targetFolder.toPath()); } catch (Exception ignored) {}
            }
        }
    }

    private void maybePreload(String baseWorld, long seed, EnumSet<Dimension> dims) {
        try {
            if (!plugin.getConfig().getBoolean("preload.enabled", true)) return;
            if (plugin.getConfig().getBoolean("preload.autoDisableHighLag", true)) {
                try { double[] tps = (double[]) Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer()); double minTps = plugin.getConfig().getDouble("preload.tpsThreshold", 18.0); if (tps != null && tps.length > 0 && tps[0] < minTps) return; } catch (Throwable ignored) {}
            }
            EnumSet<PreloadManager.Dimension> pdims = EnumSet.noneOf(PreloadManager.Dimension.class);
            if (dims.contains(Dimension.OVERWORLD)) pdims.add(PreloadManager.Dimension.OVERWORLD);
            if (dims.contains(Dimension.NETHER)) pdims.add(PreloadManager.Dimension.NETHER);
            if (dims.contains(Dimension.END)) pdims.add(PreloadManager.Dimension.END);
            preloadManager.preload(baseWorld, seed, pdims);
        } catch (Throwable ignored) {}
    }

    private void moveWithFallback(Path src, Path dst) throws IOException, InterruptedException {
        int attempts = 3;
        for (int i = 0; i < attempts; i++) {
            try { Files.createDirectories(dst.getParent()); Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE); return; }
            catch (AtomicMoveNotSupportedException amnse) { try { Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING); return; } catch (Exception ignored) {} }
            catch (IOException ex) { try { Thread.sleep(50); } catch (InterruptedException ignored) {} }
        }
        try { src.toFile().renameTo(dst.toFile()); if (!Files.exists(dst)) throw new IOException("renameTo failed"); return; } catch (Exception ignored) {}
        copyAndDelete(src, dst);
    }

    private void copyAndDelete(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException { Path rel = src.relativize(dir); Path target = dst.resolve(rel); Files.createDirectories(target); return FileVisitResult.CONTINUE; }
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { Path rel = src.relativize(file); Files.copy(file, dst.resolve(rel), StandardCopyOption.REPLACE_EXISTING); return FileVisitResult.CONTINUE; }
        });
        deletePath(src);
    }

    // --- Teleport Mode (soft reset of overworld) ---
    public void startTeleportWithCountdown(Player player, String baseWorld, Optional<Long> seedOpt, EnumSet<Dimension> dimensions) {
        if (resetInProgress) { Messages.send(player, "&cA reset is already in progress. Please wait."); return; }
        
        // Ensure nether and end are included by default for teleport mode
        EnumSet<Dimension> dims = EnumSet.copyOf(dimensions);
        boolean resetNetherEnd = plugin.getConfig().getBoolean("teleportMode.resetNetherEnd", true);
        if (resetNetherEnd) {
            dims.add(Dimension.NETHER);
            dims.add(Dimension.END);
        }
        
        // We'll teleport in overworld and optionally reset Nether/End
        List<World> affectedWorlds = dims.stream().flatMap(dim -> getAffectedWorld(baseWorld, dim).stream()).toList();
        Optional<Long> effectiveSeed = seedOpt.isPresent() ? seedOpt : Optional.of(rng.nextLong());
        ResetTask task = new ResetTask(baseWorld, dims, player, effectiveSeed.orElse(null), affectedWorlds);
        activeTasks.put(player.getUniqueId(), task);
        int seconds = plugin.getConfig().getInt("countdown.seconds", 10);
        Messages.send(player, "&eStarting teleport-mode countdown for &6" + baseWorld + "&e...");
        countdownManager.startCountdown(task.getInitiator(), task.getAffectedWorlds(), seconds, () -> {
            if (!task.isCancelled()) {
                // Do the teleport + fresh start immediately on main thread
                Bukkit.getScheduler().runTask(plugin, () -> doTeleportMode(task.getInitiator(), baseWorld));
                // Reset Nether/End if configured to do so
                EnumSet<Dimension> ne = EnumSet.noneOf(Dimension.class);
                if (resetNetherEnd) {
                    if (dims.contains(Dimension.NETHER)) ne.add(Dimension.NETHER);
                    if (dims.contains(Dimension.END)) ne.add(Dimension.END);
                }
                if (!ne.isEmpty()) {
                    Messages.send(task.getInitiator(), "&7Also resetting nether and end dimensions...");
                    resetWorldAsync(task.getInitiator(), baseWorld, effectiveSeed, ne);
                }
                lastResetAt.put(baseWorld, System.currentTimeMillis());
            }
        });
    }

    private void doTeleportMode(CommandSender initiator, String baseWorld) {
        World overworld = Bukkit.getWorld(baseWorld);
        if (overworld == null) { Messages.send(initiator, "&cBase world not found: &e" + baseWorld); return; }
        int distSelf = plugin.getConfig().getInt("teleportMode.playerDistance", 15000);
        int distOthers = plugin.getConfig().getInt("teleportMode.othersDistance", 50000);
        boolean fresh = plugin.getConfig().getBoolean("players.freshStartOnReset", true);
        boolean setWorldSpawn = plugin.getConfig().getBoolean("teleportMode.setWorldSpawn", true);
        
        // Choose random angles
        java.util.Random r = new java.util.Random();
        Set<UUID> affected = new HashSet<>();
        Location newSpawnLocation = null;
        
        if (initiator instanceof Player ip && ip.isOnline()) {
            Location selfTarget = findSafeLocation(overworld, distSelf, r);
            if (selfTarget != null) {
                ip.teleport(selfTarget);
                affected.add(ip.getUniqueId());
                if (fresh) applyFreshStartIfEnabled(ip);
                
                // Set the new spawn location to where the initiator teleported
                if (setWorldSpawn) {
                    newSpawnLocation = selfTarget.clone();
                }
                
try { showShortTitle(ip, "Teleported ~" + distSelf + " blocks"); ip.sendActionBar(net.kyori.adventure.text.Component.text("Done")); } catch (Throwable ignored) {}
            }
        }
        
        // If no initiator location was set, find a new spawn location
        if (setWorldSpawn && newSpawnLocation == null) {
            newSpawnLocation = findSafeLocation(overworld, distSelf, r);
        }
        
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (initiator instanceof Player ip && p.getUniqueId().equals(ip.getUniqueId())) continue;
            Location t = findSafeLocation(overworld, distOthers, r);
            if (t != null) {
                p.teleport(t);
                affected.add(p.getUniqueId());
                if (fresh) applyFreshStartIfEnabled(p);
try { showShortTitle(p, "Teleported ~" + distOthers + " blocks"); p.sendActionBar(net.kyori.adventure.text.Component.text("Done")); } catch (Throwable ignored) {}
            }
        }
        
        // Set world spawn to the new location
        if (setWorldSpawn && newSpawnLocation != null) {
            try {
                overworld.setSpawnLocation(newSpawnLocation);
                // Update player spawn beds to prevent respawning at old beds
                for (UUID playerId : affected) {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p != null && p.isOnline()) {
                        // Clear bed spawn location so they use world spawn
                        p.setRespawnLocation(null);
                    }
                }
                Messages.send(initiator, "&aWorld spawn set to new location: &e" + 
                    (int)newSpawnLocation.getX() + ", " + 
                    (int)newSpawnLocation.getY() + ", " + 
                    (int)newSpawnLocation.getZ());
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to set world spawn: " + ex.getMessage());
                Messages.send(initiator, "&cWarning: Failed to set world spawn location.");
            }
        }
        
        Messages.send(initiator, "&aTeleport-mode complete. Players moved far away in '&e" + baseWorld + "&a'.");
    }

    private Location findSafeLocation(World world, int radius, java.util.Random rng) {
        if (world == null) return null;
        Location center = world.getSpawnLocation();
        int attempts = 64;
        for (int i = 0; i < attempts; i++) {
            double angle = rng.nextDouble() * Math.PI * 2;
            int x = center.getBlockX() + (int) Math.round(Math.cos(angle) * radius);
            int z = center.getBlockZ() + (int) Math.round(Math.sin(angle) * radius);
            
            try {
                // Check if chunk is loaded, if not try to load it synchronously for safety
                org.bukkit.Chunk chunk = world.getChunkAt(x >> 4, z >> 4);
                if (!chunk.isLoaded()) {
                    // Try to load chunk safely
                    boolean loaded = chunk.load(false);
                    if (!loaded) {
                        continue; // Skip this location if chunk can't be loaded
                    }
                }
                
                // Safer way to get highest block Y without causing crashes
                int y = findSafeY(world, x, z);
                if (y == -1) continue; // Skip if we can't find a safe Y
                
                Location loc = new Location(world, x + 0.5, y + 1.0, z + 0.5);
                
                org.bukkit.block.Block feet = world.getBlockAt(x, y, z);
                org.bukkit.block.Block head = world.getBlockAt(x, y + 1, z);
                org.bukkit.block.Block ground = world.getBlockAt(x, y - 1, z);
                
                if (feet.isEmpty() && head.isEmpty()) {
                    Material gm = ground.getType();
                    if (!gm.isAir() && gm != Material.LAVA && gm != Material.WATER) {
                        return loc;
                    }
                }
            } catch (Throwable ignored) {
                // Continue to next attempt if anything goes wrong
            }
        }
        // fallback to spawn if no good spot found
        return center;
    }
    
    private int findSafeY(World world, int x, int z) {
        try {
            // Use a safer approach to find the highest block
            int maxY = world.getMaxHeight() - 1;
            int minY = Math.max(world.getMinHeight() + 1, -60);
            
            // Start from a reasonable height and work our way down to find solid ground
            for (int y = Math.min(maxY, 320); y >= minY; y--) {
                org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                if (!block.getType().isAir() && block.getType().isSolid()) {
                    // Found solid ground, check if there's space above
                    org.bukkit.block.Block above1 = world.getBlockAt(x, y + 1, z);
                    org.bukkit.block.Block above2 = world.getBlockAt(x, y + 2, z);
                    if (above1.getType().isAir() && above2.getType().isAir()) {
                        return y + 1; // Return the Y position where player's feet would be
                    }
                }
            }
            
            // If no solid ground found, try the world's spawn location Y
            return world.getSpawnLocation().getBlockY();
        } catch (Throwable e) {
            // Return a reasonable default if everything fails
            return Math.max(world.getMinHeight() + 10, 70);
        }
    }
}

