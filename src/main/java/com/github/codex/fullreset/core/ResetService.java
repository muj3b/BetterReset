package com.github.codex.fullreset.core;

import com.github.codex.fullreset.FullResetPlugin;
import com.github.codex.fullreset.util.CountdownManager;
import com.github.codex.fullreset.util.Messages;
import com.github.codex.fullreset.util.MultiverseCompat;
import com.github.codex.fullreset.util.ResetAuditLogger;
import com.github.codex.fullreset.util.BackupManager;
import com.github.codex.fullreset.util.PreloadManager;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.io.File;
import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Orchestrates the safe reset flow:
 * 1) Teleport players out of target worlds to a safe fallback.
 * 2) Unload worlds on main thread.
 * 3) Asynchronously delete world directories.
 * 4) Recreate worlds with new or provided seeds.
 * 5) Optionally return players to new world spawn.
 */
public class ResetService {

    private final FullResetPlugin plugin;
    private final ConfirmationManager confirmationManager;
    private final CountdownManager countdownManager;
    private final MultiverseCompat multiverseCompat;
    private final BackupManager backupManager;
    private final PreloadManager preloadManager;

    private final ResetAuditLogger auditLogger = new ResetAuditLogger();

    public ResetService(FullResetPlugin plugin, ConfirmationManager confirmationManager, CountdownManager countdownManager, MultiverseCompat multiverseCompat, PreloadManager preloadManager) {
        this.plugin = plugin;
        this.confirmationManager = confirmationManager;
        this.countdownManager = countdownManager;
        this.multiverseCompat = multiverseCompat;
        this.backupManager = new BackupManager(plugin);
        this.preloadManager = preloadManager;
    }

    private volatile boolean resetInProgress = false;
    private volatile String currentTarget = null;
    private volatile String phase = "IDLE"; // IDLE, COUNTDOWN, RUNNING

    public void startResetWithCountdown(CommandSender initiator, String baseWorldName, Optional<Long> seedOpt) {
        startResetWithCountdown(initiator, baseWorldName, seedOpt, EnumSet.of(Dimension.OVERWORLD, Dimension.NETHER, Dimension.END));
    }

    public enum Dimension { OVERWORLD, NETHER, END }

    public void startResetWithCountdown(CommandSender initiator, String baseWorldName, Optional<Long> seedOpt, EnumSet<Dimension> dims) {
        if (resetInProgress) {
            Messages.send(initiator, "&cA reset is already in progress. Please wait.");
            return;
        }
        // Gate by online players threshold if configured
        int maxOnline = plugin.getConfig().getInt("limits.maxOnlineForReset", -1);
        if (maxOnline >= 0 && Bukkit.getOnlinePlayers().size() > maxOnline) {
            Messages.send(initiator, "&cToo many players online to reset now (&e" + Bukkit.getOnlinePlayers().size() + "&c > &e" + maxOnline + "&c).");
            return;
        }
        int seconds = plugin.getConfig().getInt("countdown.seconds", 10);
        Messages.send(initiator, "&eStarting reset countdown for &6" + baseWorldName + "&e...");
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(initiator)) continue;
            if (online.hasPermission("betterreset.notify")) {
                Messages.send(online, "&e[BetterReset]&7 Countdown started for '&e" + baseWorldName + "&7'.");
            }
        }
        resetInProgress = true;
        currentTarget = baseWorldName;
        phase = "COUNTDOWN";

        // Decide audience
        boolean broadcast = plugin.getConfig().getBoolean("countdown.broadcastToAll", true);
        Set<Player> audience;
        if (broadcast) {
            audience = new java.util.HashSet<>(Bukkit.getOnlinePlayers());
        } else {
            // Only players in affected worlds
            java.util.List<String> dimsList = dimensionNames(baseWorldName, dims);
            audience = new java.util.HashSet<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (dimsList.contains(p.getWorld().getName())) audience.add(p);
            }
        }

        long chosenSeed = seedOpt.orElseGet(() -> new Random().nextLong());
        auditLogger.log(plugin, "Countdown started by " + initiator.getName() + " for '" + baseWorldName + "' (seconds=" + seconds + ", seed=" + chosenSeed + ", dims=" + dims + ")");
        boolean preloadEnabled = plugin.getConfig().getBoolean("preload.enabled", true);
        boolean auto = plugin.getConfig().getBoolean("preload.autoDisableHighLag", true);
        double threshold = plugin.getConfig().getDouble("preload.tpsThreshold", 18.0);
        if (preloadEnabled) {
            boolean shouldPreload = true;
            if (auto) {
                try {
                    double[] tps = (double[]) Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer());
                    if (tps != null && tps.length > 0 && tps[0] < threshold) shouldPreload = false;
                } catch (Throwable ignored) { /* Not Paper or method missing */ }
            }
            if (shouldPreload) {
                EnumSet<com.github.codex.fullreset.util.PreloadManager.Dimension> preloadDims = EnumSet.noneOf(com.github.codex.fullreset.util.PreloadManager.Dimension.class);
                if (dims.contains(Dimension.OVERWORLD)) preloadDims.add(com.github.codex.fullreset.util.PreloadManager.Dimension.OVERWORLD);
                if (dims.contains(Dimension.NETHER)) preloadDims.add(com.github.codex.fullreset.util.PreloadManager.Dimension.NETHER);
                if (dims.contains(Dimension.END)) preloadDims.add(com.github.codex.fullreset.util.PreloadManager.Dimension.END);
                preloadManager.preload(baseWorldName, chosenSeed, preloadDims);
            }
        }
        countdownManager.runCountdown(baseWorldName, seconds, audience, () -> resetWorldAsync(initiator, baseWorldName, Optional.of(chosenSeed), dims));
    }

    public void resetWorldAsync(CommandSender initiator, String baseWorldName, Optional<Long> seedOpt) {
        resetWorldAsync(initiator, baseWorldName, seedOpt, EnumSet.of(Dimension.OVERWORLD, Dimension.NETHER, Dimension.END));
    }

    public void resetWorldAsync(CommandSender initiator, String baseWorldName, Optional<Long> seedOpt, EnumSet<Dimension> dims) {
        final String worldBase = baseWorldName;
        final List<String> worldNames = dimensionNames(worldBase, dims);

        // Capture current players in these worlds
        Set<UUID> affectedPlayers = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (worldNames.contains(p.getWorld().getName())) {
                affectedPlayers.add(p.getUniqueId());
            }
        }

        // Ensure we run Bukkit ops on main thread
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                phase = "RUNNING";
                auditLogger.log(plugin, "Reset started for '" + worldBase + "'");
                // Pick or create fallback world
                World fallback = findOrCreateFallbackWorld(worldNames);
                if (fallback == null) {
                    Messages.send(initiator, "&cFailed to find or create a fallback world; aborting.");
                    resetInProgress = false;
                    phase = "IDLE";
                    return;
                }

                // Teleport affected players out
                for (UUID id : affectedPlayers) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        safeTeleport(p, fallback.getSpawnLocation());
                    }
                }
                // Double-check any players still in target worlds and teleport again
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (worldNames.contains(online.getWorld().getName())) {
                        safeTeleport(online, fallback.getSpawnLocation());
                    }
                }

                // Resolve world folders before unloading, in case some are not loaded
                Map<String, Path> worldFolders = resolveWorldFolders(worldNames);

                // Unload worlds (retry if necessary)
                if (!unloadWorldsReliably(worldNames, fallback, initiator)) {
                    resetInProgress = false;
                    phase = "IDLE";
                    return;
                }

                boolean backupsEnabled = plugin.getConfig().getBoolean("backups.enabled", true);
                Messages.send(initiator, backupsEnabled ? "&7Worlds unloaded. Archiving backups and recreating..." : "&7Worlds unloaded. Deleting old folders and recreating...");

                // Async snapshot (move) or delete, then recreate
                CompletableFuture.runAsync(() -> {
                    try {
                        if (backupsEnabled) {
                            backupManager.snapshot(worldBase, worldFolders);
                        } else {
                            // Move to trash quickly; delete later
                            Path trashRoot = plugin.getDataFolder().toPath().resolve("trash").resolve(String.valueOf(System.currentTimeMillis()));
                            Files.createDirectories(trashRoot);
                            for (Map.Entry<String, Path> e : worldFolders.entrySet()) {
                                Path path = e.getValue();
                                if (path == null || !Files.exists(path)) continue;
                                try {
                                    Files.move(path, trashRoot.resolve(path.getFileName()), StandardCopyOption.REPLACE_EXISTING);
                                } catch (Exception ex) {
                                    try { deletePath(path); } catch (IOException ignored) {}
                                }
                            }
                            CompletableFuture.runAsync(() -> { try { deletePath(trashRoot); } catch (IOException ignored) {} });
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            swapPreloadedIfAny(worldBase, dims);
                            recreateWorlds(initiator, worldBase, seedOpt, affectedPlayers, dims);
                        });
                    } catch (Exception ex) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Messages.send(initiator, "&cUnexpected error while deleting worlds: " + ex.getMessage());
                            resetInProgress = false;
                            phase = "IDLE";
                            auditLogger.log(plugin, "Reset failed for '" + worldBase + "' (exception during delete): " + ex.getMessage());
                        });
                    }
                });
            } catch (Exception ex) {
                Messages.send(initiator, "&cError during reset: " + ex.getMessage());
                resetInProgress = false;
                phase = "IDLE";
                auditLogger.log(plugin, "Reset failed for '" + worldBase + "' (exception): " + ex.getMessage());
            }
        });
    }

    private void recreateWorlds(CommandSender initiator, String base, Optional<Long> seedOpt, Set<UUID> previouslyAffected, EnumSet<Dimension> dims) {
        try {
            boolean sameSeedForAll = plugin.getConfig().getBoolean("seeds.useSameSeedForAllDimensions", true);
            long baseSeed = seedOpt.orElseGet(() -> new Random().nextLong());
            World overworld = null;
            if (dims.contains(Dimension.OVERWORLD)) {
                overworld = new WorldCreator(base)
                        .seed(baseSeed)
                        .environment(World.Environment.NORMAL)
                        .type(WorldType.NORMAL)
                        .createWorld();
                if (overworld == null) {
                    Messages.send(initiator, "&cFailed to create overworld: " + base);
                    resetInProgress = false;
                    phase = "IDLE";
                    auditLogger.log(plugin, "Reset failed creating overworld for '" + base + "'");
                    return;
                }
                try { overworld.getChunkAt(overworld.getSpawnLocation()).load(true); } catch (Exception ignored) {}
                multiverseCompat.ensureRegistered(base, World.Environment.NORMAL, baseSeed);
            }

            // nether
            long netherSeed = sameSeedForAll ? baseSeed : new Random().nextLong();
            if (dims.contains(Dimension.NETHER)) {
                World nether = new WorldCreator(base + "_nether")
                        .seed(netherSeed)
                        .environment(World.Environment.NETHER)
                        .type(WorldType.NORMAL)
                        .createWorld();
                if (nether == null) {
                    Messages.send(initiator, "&cFailed to create nether: " + base + "_nether");
                    resetInProgress = false;
                    phase = "IDLE";
                    auditLogger.log(plugin, "Reset failed creating nether for '" + base + "'");
                    return;
                }
                try { nether.getChunkAt(nether.getSpawnLocation()).load(true); } catch (Exception ignored) {}
                multiverseCompat.ensureRegistered(base + "_nether", World.Environment.NETHER, netherSeed);
            }

            // the end
            long endSeed = sameSeedForAll ? baseSeed : new Random().nextLong();
            if (dims.contains(Dimension.END)) {
                World theEnd = new WorldCreator(base + "_the_end")
                        .seed(endSeed)
                        .environment(World.Environment.THE_END)
                        .type(WorldType.NORMAL)
                        .createWorld();
                if (theEnd == null) {
                    Messages.send(initiator, "&cFailed to create the_end: " + base + "_the_end");
                    resetInProgress = false;
                    phase = "IDLE";
                    auditLogger.log(plugin, "Reset failed creating the_end for '" + base + "'");
                    return;
                }
                try { theEnd.getChunkAt(theEnd.getSpawnLocation()).load(true); } catch (Exception ignored) {}
                multiverseCompat.ensureRegistered(base + "_the_end", World.Environment.THE_END, endSeed);
            }

            // Optional: Multiverse compatibility – if present, it will typically detect loads via events.
            // We avoid a hard dependency to keep the plugin simple and portable.

            Messages.send(initiator, "&aRecreated worlds for '&e" + base + "&a' successfully.");

            boolean returnPlayers = plugin.getConfig().getBoolean("players.returnToNewSpawnAfterReset", true);
            if (returnPlayers && overworld != null) {
                Location spawn = overworld.getSpawnLocation();
                for (UUID id : previouslyAffected) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        safeTeleport(p, spawn);
                        applyFreshStartIfEnabled(p);
                    }
                }
            }
            Messages.send(initiator, "&aReset complete for '&e" + base + "&a'.");
            // Notify others with notify permission
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.equals(initiator)) continue;
                if (online.hasPermission("betterreset.notify")) {
                    Messages.send(online, "&a[BetterReset]&7 World '&e" + base + "&7' has been reset.");
                }
            }
            auditLogger.log(plugin, "Reset completed for '" + base + "'");
            resetInProgress = false;
            phase = "IDLE";
            try { ((com.github.codex.fullreset.FullResetPlugin) plugin).getRespawnManager().markReset(base); } catch (Exception ignored) {}
        } catch (Exception ex) {
            Messages.send(initiator, "&cError recreating worlds: " + ex.getMessage());
            resetInProgress = false;
            phase = "IDLE";
            auditLogger.log(plugin, "Reset failed (exception during create) for '" + base + "': " + ex.getMessage());
        }
    }

    private static List<String> dimensionNames(String base, EnumSet<Dimension> dims) {
        List<String> list = new ArrayList<>();
        if (dims.contains(Dimension.OVERWORLD)) list.add(base);
        if (dims.contains(Dimension.NETHER)) list.add(base + "_nether");
        if (dims.contains(Dimension.END)) list.add(base + "_the_end");
        return list;
    }

    private Map<String, Path> resolveWorldFolders(List<String> worldNames) {
        Map<String, Path> map = new LinkedHashMap<>();
        File container = Bukkit.getWorldContainer();
        Path containerPath = container.toPath().toAbsolutePath().normalize();
        for (String name : worldNames) {
            World w = Bukkit.getWorld(name);
            File f = (w != null) ? w.getWorldFolder() : new File(container, name);
            Path p = f.toPath().toAbsolutePath().normalize();
            if (!p.startsWith(containerPath)) {
                // Safety check; skip anything outside container
                map.put(name, null);
            } else if (Files.exists(p)) {
                map.put(name, p);
            } else {
                map.put(name, null);
            }
        }
        return map;
    }

    private World findOrCreateFallbackWorld(List<String> toAvoid) {
        // If a configured fallback world exists, prefer it when it's not targeted
        String configured = plugin.getConfig().getString("teleport.fallbackWorldName", "").trim();
        if (!configured.isEmpty()) {
            World cw = Bukkit.getWorld(configured);
            if (cw != null && !toAvoid.contains(cw.getName())) {
                return cw;
            }
        }
        for (World w : Bukkit.getWorlds()) {
            if (!toAvoid.contains(w.getName())) {
                return w;
            }
        }
        // If all loaded worlds are being reset, create a temporary fallback world
        String tmpName = "betterreset_safe_" + Instant.now().getEpochSecond();
        return new WorldCreator(tmpName)
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .createWorld();
    }

    public boolean cancelCountdown() {
        boolean canceled = countdownManager.cancel();
        if (canceled) {
            resetInProgress = false;
            phase = "IDLE";
            auditLogger.log(plugin, "Countdown canceled for '" + currentTarget + "'");
            currentTarget = null;
        }
        return canceled;
    }

    public String getStatusLine() {
        if ("IDLE".equals(phase)) return "IDLE";
        if ("COUNTDOWN".equals(phase)) {
            return "COUNTDOWN '" + currentTarget + "' (" + countdownManager.secondsLeft() + "/" + countdownManager.totalSeconds() + ")";
        }
        return "RUNNING '" + currentTarget + "'";
    }

    public List<com.github.codex.fullreset.util.BackupManager.BackupRef> listBackups() {
        return backupManager.listBackups();
    }

    public void restoreBackupAsync(CommandSender initiator, String base, String timestamp) {
        if (resetInProgress) {
            Messages.send(initiator, "&cA reset/restore is already in progress. Please wait.");
            return;
        }
        resetInProgress = true;
        phase = "RUNNING";
        currentTarget = base;
        List<String> worldNames = Arrays.asList(base, base + "_nether", base + "_the_end");
        // Teleport any players in these worlds out
        Set<UUID> affected = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (worldNames.contains(p.getWorld().getName())) affected.add(p.getUniqueId());
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            World fallback = findOrCreateFallbackWorld(worldNames);
            if (fallback == null) {
                Messages.send(initiator, "&cFailed to create fallback world; aborting restore.");
                resetInProgress = false;
                phase = "IDLE";
                return;
            }
            for (UUID id : affected) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) safeTeleport(p, fallback.getSpawnLocation());
            }
            // Unload if loaded
            for (String name : worldNames) {
                World w = Bukkit.getWorld(name);
                if (w != null) {
                    try { w.save(); } catch (Exception ignored) {}
                    Bukkit.unloadWorld(w, true);
                }
            }
            CompletableFuture.runAsync(() -> {
                try {
                    backupManager.restore(base, timestamp);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        // Load worlds if their folders exist
                        for (String name : worldNames) {
                            File f = new File(Bukkit.getWorldContainer(), name);
                            if (f.exists()) {
                                World.Environment env = name.endsWith("_nether") ? World.Environment.NETHER : name.endsWith("_the_end") ? World.Environment.THE_END : World.Environment.NORMAL;
                                new WorldCreator(name).environment(env).type(WorldType.NORMAL).createWorld();
                            }
                        }
                        Messages.send(initiator, "&aRestored backup '&e" + base + " @ " + timestamp + "&a'.");
                        // Teleport initiator to overworld spawn if player
                        if (initiator instanceof Player ip) {
                            World w = Bukkit.getWorld(base);
                            if (w != null) safeTeleport(ip, w.getSpawnLocation());
                        }
                        resetInProgress = false;
                        phase = "IDLE";
                    });
                } catch (Exception ex) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Messages.send(initiator, "&cRestore failed: " + ex.getMessage());
                        resetInProgress = false;
                        phase = "IDLE";
                    });
                }
            });
        });
    }

    public void restoreBackupAsync(CommandSender initiator, String base, String timestamp, EnumSet<Dimension> dims) {
        if (resetInProgress) {
            Messages.send(initiator, "&cA reset/restore is already in progress. Please wait.");
            return;
        }
        resetInProgress = true;
        phase = "RUNNING";
        currentTarget = base;
        List<String> worldNames = dimensionNames(base, dims);
        // Teleport players in selected dimensions
        Set<UUID> affected = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (worldNames.contains(p.getWorld().getName())) affected.add(p.getUniqueId());
        }
        Bukkit.getScheduler().runTask(plugin, () -> {
            World fallback = findOrCreateFallbackWorld(worldNames);
            if (fallback == null) {
                Messages.send(initiator, "&cFailed to create fallback world; aborting restore.");
                resetInProgress = false;
                phase = "IDLE";
                return;
            }
            for (UUID id : affected) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) safeTeleport(p, fallback.getSpawnLocation());
            }
            for (String name : worldNames) {
                World w = Bukkit.getWorld(name);
                if (w != null) {
                    try { w.save(); } catch (Exception ignored) {}
                    Bukkit.unloadWorld(w, true);
                }
            }
            CompletableFuture.runAsync(() -> {
                try {
                    backupManager.restore(base, timestamp, dims);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        for (String name : worldNames) {
                            File f = new File(Bukkit.getWorldContainer(), name);
                            if (f.exists()) {
                                World.Environment env = name.endsWith("_nether") ? World.Environment.NETHER : name.endsWith("_the_end") ? World.Environment.THE_END : World.Environment.NORMAL;
                                new WorldCreator(name).environment(env).type(WorldType.NORMAL).createWorld();
                            }
                        }
                        Messages.send(initiator, "&aRestored backup '&e" + base + " @ " + timestamp + "&a' for " + dims + ".");
                        if (initiator instanceof Player ip) {
                            World w = Bukkit.getWorld(base);
                            if (w != null) safeTeleport(ip, w.getSpawnLocation());
                        }
                        resetInProgress = false;
                        phase = "IDLE";
                    });
                } catch (Exception ex) {
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        Messages.send(initiator, "&cRestore failed: " + ex.getMessage());
                        resetInProgress = false;
                        phase = "IDLE";
                    });
                }
            });
        });
    }

    public void deleteBackupAsync(CommandSender initiator, String base, String timestamp) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                backupManager.deleteBackup(base, timestamp);
                Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aDeleted backup '&e" + base + " @ " + timestamp + "&a'."));
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cDelete failed: " + ex.getMessage()));
            }
        });
    }

    public void pruneBackupsAsync(CommandSender initiator, Optional<String> baseOpt) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (baseOpt.isPresent()) {
                    backupManager.prune(baseOpt.get());
                } else {
                    backupManager.pruneAll();
                }
                Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aPrune complete."));
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cPrune failed: " + ex.getMessage()));
            }
        });
    }

    public void pruneBackupsAsync(CommandSender initiator, Optional<String> baseOpt, boolean forceKeepPolicy) {
        if (!forceKeepPolicy) { pruneBackupsAsync(initiator, baseOpt); return; }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int keep = plugin.getConfig().getInt("backups.pruneNowKeepPerBase", 2);
                if (baseOpt.isPresent()) backupManager.pruneKeepPerBase(baseOpt.get(), keep);
                else backupManager.pruneKeepAllBases(keep);
                Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aPrune complete. Kept at most " + keep + " per base."));
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cPrune failed: " + ex.getMessage()));
            }
        });
    }

    public void deleteAllBackupsForBaseAsync(CommandSender initiator, String base) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                backupManager.deleteAllForBase(base);
                Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aDeleted all backups for '&e" + base + "&a'."));
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cDelete all failed: " + ex.getMessage()));
            }
        });
    }

    public void deleteAllBackupsAsync(CommandSender initiator) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                backupManager.deleteAllBases();
                Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aDeleted ALL backups for ALL bases."));
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cDelete all failed: " + ex.getMessage()));
            }
        });
    }

    public void testResetAsync(CommandSender initiator, String base, Optional<Long> seedOpt, EnumSet<Dimension> dims) {
        String testBase = ("brtest_" + base + "_" + System.currentTimeMillis());
        long seed = seedOpt.orElseGet(() -> new Random().nextLong());
        Messages.send(initiator, "&7Starting test reset for '&e" + base + "&7' → temp '&e" + testBase + "&7'.");
        long t0 = System.nanoTime();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                // Create temp worlds
                for (String name : dimensionNames(testBase, dims)) {
                    World.Environment env = name.endsWith("_nether") ? World.Environment.NETHER : name.endsWith("_the_end") ? World.Environment.THE_END : World.Environment.NORMAL;
                    World w = new WorldCreator(name).environment(env).seed(seed).type(WorldType.NORMAL).createWorld();
                    if (w != null) try { w.getChunkAt(w.getSpawnLocation()).load(true);} catch (Exception ignored) {}
                }
                long tCreate = System.nanoTime();
                Messages.send(initiator, "&aCreated temp worlds in &e" + ((tCreate - t0)/1_000_000) + "ms.");
                // Unload and delete temp worlds
                for (String name : dimensionNames(testBase, dims)) {
                    World w = Bukkit.getWorld(name);
                    if (w != null) Bukkit.unloadWorld(w, true);
                }
                CompletableFuture.runAsync(() -> {
                    try {
                        for (String name : dimensionNames(testBase, dims)) {
                            File f = new File(Bukkit.getWorldContainer(), name);
                            if (f.exists()) deletePath(f.toPath());
                        }
                        long tEnd = System.nanoTime();
                        Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aCleanup complete. Total test time: &e" + ((tEnd - t0)/1_000_000) + "ms"));
                    } catch (Exception ex) {
                        Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cTest cleanup failed: " + ex.getMessage()));
                    }
                });
            } catch (Exception ex) {
                Messages.send(initiator, "&cTest reset failed: " + ex.getMessage());
            }
        });
    }

    private void safeTeleport(Player p, Location to) {
        try {
            // Use sync teleport for Spigot compatibility; Paper may optimize internally
            p.teleport(to);
        } catch (Exception ignored) {
        }
    }

    private void applyFreshStartIfEnabled(Player p) {
        if (!plugin.getConfig().getBoolean("players.freshStartOnReset", true)) return;
        try {
            p.getInventory().clear();
            p.getEnderChest().clear();
            p.setFireTicks(0);
            p.setHealth(Math.min(p.getMaxHealth(), 20.0));
            p.setFoodLevel(20);
            p.setSaturation(5f);
            p.setExhaustion(0f);
            p.setLevel(0);
            p.setExp(0f);
            p.setTotalExperience(0);
            p.setFallDistance(0f);
            p.setRemainingAir(p.getMaximumAir());
        } catch (Exception ignored) {}
    }

    private void deletePath(Path path) throws IOException {
        if (!Files.exists(path)) return;
        // Delete contents recursively
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private boolean unloadWorldsReliably(List<String> worldNames, World fallback, CommandSender initiator) {
        List<String> remaining = new ArrayList<>();
        for (String name : worldNames) {
            World w = Bukkit.getWorld(name);
            if (w == null) continue;
            for (Player pl : new ArrayList<>(w.getPlayers())) safeTeleport(pl, fallback.getSpawnLocation());
            try { w.save(); } catch (Exception ignored) {}
            if (!Bukkit.unloadWorld(w, true)) remaining.add(name);
        }
        if (remaining.isEmpty()) return true;
        final int max = 5;
        for (int attempt = 1; attempt <= max && !remaining.isEmpty(); attempt++) {
            try { Thread.sleep(50); } catch (InterruptedException ignored) {}
            Iterator<String> it = remaining.iterator();
            while (it.hasNext()) {
                String name = it.next();
                World w = Bukkit.getWorld(name);
                if (w == null) { it.remove(); continue; }
                for (Player pl : new ArrayList<>(w.getPlayers())) safeTeleport(pl, fallback.getSpawnLocation());
                try { w.save(); } catch (Exception ignored) {}
                if (Bukkit.unloadWorld(w, true)) it.remove();
            }
        }
        if (!remaining.isEmpty()) {
            Messages.send(initiator, "&cFailed to unload: &e" + String.join(", ", remaining));
        }
        return remaining.isEmpty();
    }

    private void swapPreloadedIfAny(String base, EnumSet<Dimension> dims) {
        for (String target : dimensionNames(base, dims)) {
            String prep = "brprep_" + target;
            World prepWorld = Bukkit.getWorld(prep);
            if (prepWorld != null) Bukkit.unloadWorld(prepWorld, true);
            File container = Bukkit.getWorldContainer();
            File prepFolder = new File(container, prep);
            File targetFolder = new File(container, target);
            if (prepFolder.exists()) {
                if (targetFolder.exists()) {
                    try { deletePath(targetFolder.toPath()); } catch (Exception ignored) {}
                }
                try { Files.move(prepFolder.toPath(), targetFolder.toPath(), StandardCopyOption.REPLACE_EXISTING); } catch (Exception ignored) {}
            }
        }
    }
}
