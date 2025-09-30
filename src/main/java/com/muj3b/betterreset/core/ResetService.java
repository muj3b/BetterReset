package com.muj3b.betterreset.core;

import com.muj3b.betterreset.FullResetPlugin;
import com.muj3b.betterreset.util.BackupManager;
import com.muj3b.betterreset.util.CountdownManager;
import com.muj3b.betterreset.util.Messages;
import com.muj3b.betterreset.util.ConfigKeys;
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
import org.apache.commons.io.FileUtils;

import java.io.File;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Random;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Level;

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
    private final Map<UUID, ResetTask> activeTasks = new ConcurrentHashMap<>();
    private final Random rng = new Random();
    private final SeedHistory seedHistory;
    private final Map<String, Long> lastResetAt = new ConcurrentHashMap<>();
    private final Map<String, Long> lastResetTimestamp = new ConcurrentHashMap<>();
    private long totalResets = 0;
    private final AtomicBoolean resetInProgress = new AtomicBoolean(false);


    public ResetService(FullResetPlugin plugin, ConfirmationManager confirmationManager, CountdownManager countdownManager, MultiverseCompat multiverseCompat, PreloadManager preloadManager) {
        this.plugin = plugin;
        this.confirmationManager = confirmationManager;
        this.countdownManager = countdownManager;
        this.multiverseCompat = multiverseCompat;
        this.backupManager = new BackupManager(plugin);
        this.preloadManager = preloadManager;
        this.seedHistory = plugin.getSeedHistory();
    }

    private volatile String currentTarget = null;
    private volatile String phase = "IDLE";

    public void startReset(Player player, String baseWorld, EnumSet<Dimension> dimensions) {
        startResetWithCountdown(player, baseWorld, Optional.empty(), dimensions);
    }

    public void startResetWithCountdown(Player player, String baseWorld, Optional<Long> seedOpt, EnumSet<Dimension> dimensions) {
        if (!resetInProgress.compareAndSet(false, true)) {
            Messages.send(player, "&cA reset is already in progress. Please wait.");
            return;
        }

        try {
            int cooldownSeconds = plugin.getConfig().getInt(ConfigKeys.LIMITS_RESET_COOLDOWN_SECONDS, 0);
            if (cooldownSeconds > 0) {
                Long last = lastResetAt.get(baseWorld);
                if (last != null && (System.currentTimeMillis() - last) < (cooldownSeconds * 1000L)) {
                    Messages.send(player, "&cA reset for &6" + baseWorld + "&c was performed recently. Please wait before retrying.");
                    resetInProgress.set(false);
                    return;
                }
            }
            int maxOnline = plugin.getConfig().getInt(ConfigKeys.LIMITS_MAX_ONLINE_FOR_RESET, -1);
            if (maxOnline >= 0 && Bukkit.getOnlinePlayers().size() > maxOnline) {
                Messages.send(player, "&cToo many players online to reset now (&e" + Bukkit.getOnlinePlayers().size() + "&c > &e" + maxOnline + "&c).");
                resetInProgress.set(false);
                return;
            }

            phase = "COUNTDOWN";
            currentTarget = baseWorld;

            List<World> affectedWorlds = dimensions.stream().flatMap(dim -> getAffectedWorld(baseWorld, dim).stream()).toList();
            Optional<Long> effectiveSeed = seedOpt.isPresent() ? seedOpt : Optional.of(rng.nextLong());
            if (effectiveSeed.isPresent()) {
                try {
                    maybePreload(baseWorld, effectiveSeed.get(), dimensions);
                } catch (Throwable e) {
                    plugin.getLogger().log(Level.WARNING, "Error during preloading", e);
                }
            }

            ResetTask task = new ResetTask(baseWorld, dimensions, player, effectiveSeed.orElse(null), affectedWorlds);
            activeTasks.put(player.getUniqueId(), task);
            int seconds = plugin.getConfig().getInt(ConfigKeys.COUNTDOWN_SECONDS, 10);
            Messages.send(player, "&eStarting reset countdown for &6" + baseWorld + "&e...");

            countdownManager.startCountdown(task.getInitiator(), task.getAffectedWorlds(), seconds, () -> {
                if (!task.isCancelled()) {
                    resetWorldAsync(task.getInitiator(), baseWorld, effectiveSeed, dimensions);
                    lastResetAt.put(baseWorld, System.currentTimeMillis());
                } else {
                    resetInProgress.set(false);
                    phase = "IDLE";
                    currentTarget = null;
                }
            });

        } catch (Exception e) {
            Messages.send(player, "&cFailed to start reset countdown: " + e.getMessage());
            plugin.getLogger().log(Level.SEVERE, "Failed to start reset countdown for " + baseWorld, e);
            resetInProgress.set(false);
            phase = "IDLE";
            currentTarget = null;
        }
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
        for (Player p : Bukkit.getOnlinePlayers()) {
            if (p.getWorld() != null && worldNames.contains(p.getWorld().getName())) {
                affectedPlayers.add(p.getUniqueId());
            }
        }

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                phase = "RUNNING";
                auditLogger.log(plugin, "Reset started for '" + worldBase + "'");
                World fallback = findOrCreateFallbackWorld(worldNames);
                if (fallback == null) {
                    Messages.send(initiator, "&cFailed to find or create a fallback world; aborting.");
                    resetInProgress.set(false);
                    return;
                }

                preparePlayersForReset(affectedPlayers, worldNames, fallback.getSpawnLocation());

                Map<String, Path> worldFolders = resolveWorldFolders(worldNames);
                if (!unloadWorldsReliably(worldNames, fallback, initiator)) {
                    resetInProgress.set(false);
                    return;
                }

                boolean backupsEnabled = plugin.getConfig().getBoolean(ConfigKeys.BACKUPS_ENABLED, true);
                Messages.send(initiator, backupsEnabled ? "&7Worlds unloaded. Archiving backups and recreating..." : "&7Worlds unloaded. Deleting old folders and recreating...");

                plugin.getBackgroundExecutor().submit(() -> {
                    handleOldWorldFiles(initiator, worldBase, worldFolders, seedOpt, affectedPlayers, dims, backupsEnabled);
                });
            } catch (Exception ex) {
                Messages.send(initiator, "&cError during reset: " + ex.getMessage());
                auditLogger.log(plugin, "Reset failed for '" + worldBase + "' (exception): " + ex.getMessage());
                plugin.getLogger().log(Level.SEVERE, "Reset failed for '" + worldBase + "'", ex);
                resetInProgress.set(false);
                phase = "IDLE";
            }
        });
    }

    private void recreateWorlds(CommandSender initiator, String base, Optional<Long> seedOpt, Set<UUID> previouslyAffected, EnumSet<Dimension> dims) {
        try { // The lock is acquired in resetWorldAsync and must be released here.
            boolean sameSeedForAll = plugin.getConfig().getBoolean(ConfigKeys.SEEDS_USE_SAME_SEED_FOR_ALL_DIMENSIONS, true);
            long baseSeed = seedOpt.orElseGet(rng::nextLong);
            World overworld = null;

            if (dims.contains(Dimension.OVERWORLD)) {
                overworld = createAndRegisterWorld(initiator, base, baseSeed, World.Environment.NORMAL);
                if (overworld == null) return;
            }

            long netherSeed = sameSeedForAll ? baseSeed : rng.nextLong();
            if (dims.contains(Dimension.NETHER)) {
                if (createAndRegisterWorld(initiator, base + "_nether", netherSeed, World.Environment.NETHER) == null)
                    return;
            }

            long endSeed = sameSeedForAll ? baseSeed : rng.nextLong();
            if (dims.contains(Dimension.END)) {
                if (createAndRegisterWorld(initiator, base + "_the_end", endSeed, World.Environment.THE_END) == null)
                    return;
            }

            try {
                seedHistory.add(baseSeed);
                if (!sameSeedForAll) {
                    seedHistory.add(netherSeed);
                    seedHistory.add(endSeed);
                }
            } catch (Exception e) {
                plugin.getLogger().log(Level.WARNING, "Failed to update seed history", e);
            }
            Messages.send(initiator, "&aRecreated worlds for '&e" + base + "&a' successfully.");

            finalizeReset(initiator, base, overworld, previouslyAffected);
        } catch (Exception ex) {
            Messages.send(initiator, "&cError recreating worlds: " + ex.getMessage());
            auditLogger.log(plugin, "Reset failed (exception during create) for '" + base + "': " + ex.getMessage());
            plugin.getLogger().log(Level.SEVERE, "Failed to recreate worlds for " + base, ex);
        } finally {
            resetInProgress.set(false);
            phase = "IDLE";
            currentTarget = null;
        }
    }

    private void finalizeReset(CommandSender initiator, String base, World overworld, Set<UUID> previouslyAffected) {
        boolean returnPlayers = plugin.getConfig().getBoolean(ConfigKeys.PLAYERS_RETURN_TO_NEW_SPAWN_AFTER_RESET, true);
        if (returnPlayers && overworld != null) {
            Location spawn = overworld.getSpawnLocation();
            try {
                overworld.setSpawnLocation(spawn);
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to set world spawn after reset: " + ex.getMessage());
            }

            for (UUID id : previouslyAffected) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    safeTeleport(p, spawn);
                    try {
                        p.setRespawnLocation(null);
                    } catch (Exception ex) {
                        plugin.getLogger().warning("Failed to clear bed spawn for player " + p.getName() + ": " + ex.getMessage());
                    }
                }
            }
        }

        if (plugin.getConfig().getBoolean(ConfigKeys.PLAYERS_FRESH_START_ON_RESET, true)) {
            Set<UUID> alreadyReset = new HashSet<>();
            for (UUID id : previouslyAffected) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    applyFreshStartIfEnabled(p);
                    alreadyReset.add(id);
                    try {
                        showShortTitle(p, "Fresh start applied");
                        p.sendActionBar(net.kyori.adventure.text.Component.text("Done"));
                    } catch (Throwable t) {
                        plugin.getLogger().log(Level.WARNING, "Failed to show title on fresh start", t);
                    }
                }
            }
            if (plugin.getConfig().getBoolean(ConfigKeys.PLAYERS_RESET_ALL_ONLINE_AFTER_RESET, true)) {
                for (Player online : Bukkit.getOnlinePlayers()) {
                    if (!alreadyReset.contains(online.getUniqueId())) {
                        applyFreshStartIfEnabled(online);
                        try {
                            showShortTitle(online, "Fresh start applied");
                            online.sendActionBar(net.kyori.adventure.text.Component.text("Done"));
                        } catch (Throwable t) {
                            plugin.getLogger().log(Level.WARNING, "Failed to show title on fresh start", t);
                        }
                    }
                }
            }
        }

        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.equals(initiator)) continue;
            if (online.hasPermission("betterreset.notify"))
                Messages.send(online, "&a[BetterReset]&7 World '&e" + base + "&7' has been reset.");
        }

        totalResets++;
        lastResetTimestamp.put(base, System.currentTimeMillis());
        auditLogger.log(plugin, "Reset completed for '" + base + "'");
        try {
            plugin.getRespawnManager().markReset(base);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to mark reset for respawn manager", e);
        }
    }

    private World createAndRegisterWorld(CommandSender initiator, String name, long seed, World.Environment environment) {
        World world = new WorldCreator(name).seed(seed).environment(environment).type(WorldType.NORMAL).createWorld();
        if (world == null) {
            Messages.send(initiator, "&cFailed to create world: " + name);
            auditLogger.log(plugin, "Reset failed creating world '" + name + "'");
            return null;
        }
        try {
            world.getChunkAt(world.getSpawnLocation()).load(true);
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to load spawn chunk for " + world.getName(), e);
        }
        multiverseCompat.ensureRegistered(name, environment, seed);
        return world;
    }

    private void handleOldWorldFiles(CommandSender initiator, String worldBase, Map<String, Path> worldFolders, Optional<Long> seedOpt, Set<UUID> affectedPlayers, EnumSet<Dimension> dims, boolean backupsEnabled) {
        try {
            if (backupsEnabled) {
                backupManager.snapshot(worldBase, worldFolders);
            } else {
                Path trashRoot = plugin.getDataFolder().toPath().resolve("trash").resolve(String.valueOf(System.currentTimeMillis()));
                Files.createDirectories(trashRoot);
                for (Map.Entry<String, Path> e : worldFolders.entrySet()) {
                    Path path = e.getValue();
                    if (path == null || !Files.exists(path)) continue;
                    try {
                        FileUtils.moveDirectoryToDirectory(path.toFile(), trashRoot.toFile(), true);
                    } catch (Exception ex) {
                        plugin.getLogger().log(Level.SEVERE, "Failed to move world folder to trash", ex);
                        try {
                            FileUtils.deleteDirectory(path.toFile());
                        } catch (IOException ioEx) {
                            plugin.getLogger().log(Level.SEVERE, "Failed to delete world folder after move failed", ioEx);
                        }
                    }
                }
                plugin.getBackgroundExecutor().submit(() -> {
                    try {
                        FileUtils.deleteDirectory(trashRoot.toFile());
                    } catch (IOException ioEx) {
                        plugin.getLogger().log(Level.WARNING, "Failed to delete trash folder", ioEx);
                    }
                });
            }
            Bukkit.getScheduler().runTask(plugin, () -> {
                swapPreloadedIfAny(worldBase, dims);
                recreateWorlds(initiator, worldBase, seedOpt, affectedPlayers, dims);
            });
        } catch (Exception ex) {
            Bukkit.getScheduler().runTask(plugin, () -> {
                Messages.send(initiator, "&cUnexpected error while deleting worlds: " + ex.getMessage());
                auditLogger.log(plugin, "Reset failed for '" + worldBase + "' (exception during delete): " + ex.getMessage());
                plugin.getLogger().log(Level.SEVERE, "Reset failed for '" + worldBase + "'", ex);
                resetInProgress.set(false);
                phase = "IDLE";
            });
        }
    }

    private void preparePlayersForReset(Set<UUID> affectedPlayers, List<String> worldNames, Location fallbackLocation) {
        // Teleport affected players
        for (UUID id : affectedPlayers) {
            Player p = Bukkit.getPlayer(id);
            if (p != null && p.isOnline()) {
                safeTeleport(p, fallbackLocation);
            }
        }
        // Teleport any other players in the worlds being reset
        for (Player online : Bukkit.getOnlinePlayers()) {
            if (online.getWorld() != null && worldNames.contains(online.getWorld().getName())) {
                safeTeleport(online, fallbackLocation);
            }
        }
        // Apply fresh start to affected players
        if (plugin.getConfig().getBoolean(ConfigKeys.PLAYERS_FRESH_START_ON_RESET, true)) {
            for (UUID id : affectedPlayers) {
                Player p = Bukkit.getPlayer(id);
                if (p != null && p.isOnline()) {
                    applyFreshStartIfEnabled(p);
                }
            }
        }
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
        String configured = plugin.getConfig().getString(ConfigKeys.TELEPORT_FALLBACK_WORLD_NAME, "").trim();
        if (!configured.isEmpty()) { World cw = Bukkit.getWorld(configured); if (cw != null && !toAvoid.contains(cw.getName())) return cw; }
        for (World w : Bukkit.getWorlds()) if (!toAvoid.contains(w.getName())) return w;
        String tmpName = "betterreset_safe_" + Instant.now().getEpochSecond();
        return new WorldCreator(tmpName).environment(World.Environment.NORMAL).type(WorldType.NORMAL).createWorld();
    }

    public boolean cancelCountdown() { boolean canceled = countdownManager.cancel(); if (canceled) { resetInProgress.set(false); phase = "IDLE"; auditLogger.log(plugin, "Countdown canceled for '" + currentTarget + "'"); currentTarget = null; } return canceled; }

    public String getStatusLine() { if ("IDLE".equals(phase)) return "IDLE"; if ("COUNTDOWN".equals(phase)) return "COUNTDOWN '" + currentTarget + "' (" + countdownManager.secondsLeft() + "/" + countdownManager.totalSeconds() + ")"; return "RUNNING '" + currentTarget + "'"; }

    public List<BackupManager.BackupRef> listBackups() { return backupManager.listBackups(); }

    public long getTotalResets() { return totalResets; }

    public Optional<Long> getLastResetTimestamp(String base) { return Optional.ofNullable(lastResetTimestamp.get(base)); }

    public void restoreBackupAsync(CommandSender initiator, String base, String timestamp) {
        restoreBackupAsync(initiator, base, timestamp, EnumSet.allOf(Dimension.class));
    }

    public void restoreBackupAsync(CommandSender initiator, String base, String timestamp, EnumSet<Dimension> dims) {
        if (!resetInProgress.compareAndSet(false, true)) {
            Messages.send(initiator, "&cA reset/restore is already in progress. Please wait.");
            return;
        }
        try {
            phase = "RUNNING";
            currentTarget = base;
            final List<String> worldNames = dimensionNames(base, dims);
            final Set<UUID> affectedPlayers = new HashSet<>();
            for (Player p : Bukkit.getOnlinePlayers()) {
                if (p.getWorld() != null && worldNames.contains(p.getWorld().getName())) {
                    affectedPlayers.add(p.getUniqueId());
                }
            }

            Bukkit.getScheduler().runTask(plugin, () -> {
                try {
                    World fallback = findOrCreateFallbackWorld(worldNames);
                    if (fallback == null) {
                        Messages.send(initiator, "&cFailed to create fallback world; aborting restore.");
                        resetInProgress.set(false);
                        phase = "IDLE";
                        return;
                    }
                    preparePlayersForReset(affectedPlayers, worldNames, fallback.getSpawnLocation());
                    if (!unloadWorldsReliably(worldNames, fallback, initiator)) {
                        resetInProgress.set(false);
                        phase = "IDLE";
                        return;
                    }

                    CompletableFuture.runAsync(() -> {
                        try {
                            backupManager.restore(base, timestamp, dims);

                            Bukkit.getScheduler().runTask(plugin, () -> {
                                try {
                                    World overworld = null;
                                    for (String name : worldNames) {
                                        File f = new File(Bukkit.getWorldContainer(), name);
                                        if (f.exists()) {
                                            World.Environment env = name.endsWith("_nether") ? World.Environment.NETHER : name.endsWith("_the_end") ? World.Environment.THE_END : World.Environment.NORMAL;
                                            World w = createAndRegisterWorld(initiator, name, 0, env);
                                            if (name.equals(base)) {
                                                overworld = w;
                                            }
                                        }
                                    }
                                    Messages.send(initiator, "&aRestored backup '&e" + base + " @ " + timestamp + "&a' for " + dims + ".");
                                    finalizeReset(initiator, base, overworld, affectedPlayers);

                                    if (initiator instanceof Player ip) {
                                        World w = Bukkit.getWorld(base);
                                        if (w != null) safeTeleport(ip, w.getSpawnLocation());
                                    }
                                } catch (Exception ex) {
                                    Messages.send(initiator, "&cRestore failed during finalization: " + ex.getMessage());
                                    plugin.getLogger().log(Level.SEVERE, "Restore finalization failed for " + base, ex);
                                } finally {
                                    resetInProgress.set(false);
                                    phase = "IDLE";
                                    currentTarget = null;
                                }
                            });
                        } catch (Exception ex) {
                            Bukkit.getScheduler().runTask(plugin, () -> {
                                Messages.send(initiator, "&cRestore failed during file operations: " + ex.getMessage());
                                plugin.getLogger().log(Level.SEVERE, "Restore failed for " + base, ex);
                                resetInProgress.set(false);
                                phase = "IDLE";
                                currentTarget = null;
                            });
                        }
                    }, plugin.getBackgroundExecutor());
                } catch (Exception ex) {
                    Messages.send(initiator, "&cRestore failed during preparation: " + ex.getMessage());
                    plugin.getLogger().log(Level.SEVERE, "Restore preparation failed for " + base, ex);
                    resetInProgress.set(false);
                    phase = "IDLE";
                    currentTarget = null;
                }
            });
        } catch (Exception ex) {
            Messages.send(initiator, "&cFailed to start restore: " + ex.getMessage());
            plugin.getLogger().log(Level.SEVERE, "Failed to start restore for " + base, ex);
            resetInProgress.set(false);
            phase = "IDLE";
            currentTarget = null;
        }
    }

    public void deleteBackupAsync(CommandSender initiator, String base, String timestamp) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> { try { backupManager.deleteBackup(base, timestamp); Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aDeleted backup '&e" + base + " @ " + timestamp + "&a'.")); } catch (Exception ex) { Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cDelete failed: " + ex.getMessage())); } });
    }

    public void pruneBackupsAsync(CommandSender initiator, Optional<String> baseOpt) { Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> { try { if (baseOpt.isPresent()) backupManager.prune(baseOpt.get()); else backupManager.pruneAll(); Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aPrune complete.")); } catch (Exception ex) { Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cPrune failed: " + ex.getMessage())); } }); }

    public void pruneBackupsAsync(CommandSender initiator, Optional<String> baseOpt, boolean forceKeepPolicy) { if (!forceKeepPolicy) { pruneBackupsAsync(initiator, baseOpt); return; } Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> { try { int keep = plugin.getConfig().getInt(ConfigKeys.BACKUPS_PRUNE_NOW_KEEP_PER_BASE, 2); if (baseOpt.isPresent()) backupManager.pruneKeepPerBase(baseOpt.get(), keep); else backupManager.pruneKeepAllBases(keep); Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aPrune complete. Kept at most " + keep + " per base.")); } catch (Exception ex) { Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cPrune failed: " + ex.getMessage())); } }); }

    public void deleteAllBackupsForBaseAsync(CommandSender initiator, String base) { Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> { try { backupManager.deleteAllForBase(base); Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aDeleted all backups for '&e" + base + "&a'.")); } catch (Exception ex) { Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cDelete all failed: " + ex.getMessage())); } }); }

    public void deleteAllBackupsAsync(CommandSender initiator) { Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> { try { backupManager.deleteAllBases(); Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aDeleted ALL backups for ALL bases.")); } catch (Exception ex) { Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cDelete all failed: " + ex.getMessage())); } }); }

    public void testResetAsync(CommandSender initiator, String base, Optional<Long> seedOpt, EnumSet<Dimension> dims) { testResetAsync(initiator, base, seedOpt, dims, false); }

    public void testResetAsync(CommandSender initiator, String base, Optional<Long> seedOpt, EnumSet<Dimension> dims, boolean dryRun) {
        String testBase = ("brtest_" + base + "_" + System.currentTimeMillis());
        long seed = seedOpt.orElseGet(rng::nextLong);
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
                            for (String name : dimensionNames(testBase, dims)) {
                                File f = new File(Bukkit.getWorldContainer(), name);
                                if (f.exists()) FileUtils.deleteDirectory(f);
                            }
                            long tEnd = System.nanoTime();
                            Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aCleanup complete. Total test time: &e" + ((tEnd - t0)/1_000_000) + "ms"));
                        } catch (Exception ex) {
                            Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&cTest cleanup failed: " + ex.getMessage()));
                        }
                    }, plugin.getBackgroundExecutor());
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
        if (!plugin.getConfig().getBoolean(ConfigKeys.PLAYERS_FRESH_START_ON_RESET, true)) return;
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
            String prep = "brprep_" + target;
            World prepWorld = Bukkit.getWorld(prep);
            if (prepWorld != null) {
                Bukkit.unloadWorld(prepWorld, true);
            }
            File container = Bukkit.getWorldContainer();
            File prepFolder = new File(container, prep);
            File targetFolder = new File(container, target);
            if (prepFolder.exists()) {
                try {
                    if (targetFolder.exists()) {
                        FileUtils.deleteDirectory(targetFolder);
                    }
                    FileUtils.moveDirectory(prepFolder, targetFolder);
                } catch (IOException e) {
                    plugin.getLogger().log(Level.SEVERE, "Failed to swap preloaded world " + prep, e);
                }
            }
        }
    }

    private void maybePreload(String baseWorld, long seed, EnumSet<Dimension> dims) {
        try {
            if (!plugin.getConfig().getBoolean(ConfigKeys.PRELOAD_ENABLED, true)) return;
            if (plugin.getConfig().getBoolean(ConfigKeys.PRELOAD_AUTO_DISABLE_HIGH_LAG, true)) {
                try { double[] tps = (double[]) Bukkit.getServer().getClass().getMethod("getTPS").invoke(Bukkit.getServer()); double minTps = plugin.getConfig().getDouble(ConfigKeys.PRELOAD_TPS_THRESHOLD, 18.0); if (tps != null && tps.length > 0 && tps[0] < minTps) return; } catch (Throwable ignored) {}
            }
            EnumSet<PreloadManager.Dimension> pdims = EnumSet.noneOf(PreloadManager.Dimension.class);
            if (dims.contains(Dimension.OVERWORLD)) pdims.add(PreloadManager.Dimension.OVERWORLD);
            if (dims.contains(Dimension.NETHER)) pdims.add(PreloadManager.Dimension.NETHER);
            if (dims.contains(Dimension.END)) pdims.add(PreloadManager.Dimension.END);
            preloadManager.preload(baseWorld, seed, pdims);
        } catch (Throwable ignored) {}
    }


    // --- Teleport Mode (soft reset of overworld) ---
    public void startTeleportWithCountdown(Player player, String baseWorld, Optional<Long> seedOpt, EnumSet<Dimension> dimensions) {
        if (resetInProgress.get()) { Messages.send(player, "&cA reset is already in progress. Please wait."); return; }
        
        // Ensure nether and end are included by default for teleport mode
        EnumSet<Dimension> dims = EnumSet.copyOf(dimensions);
        boolean resetNetherEnd = plugin.getConfig().getBoolean(ConfigKeys.TELEPORT_MODE_RESET_NETHER_END, true);
        if (resetNetherEnd) {
            dims.add(Dimension.NETHER);
            dims.add(Dimension.END);
        }
        
        // We'll teleport in overworld and optionally reset Nether/End
        List<World> affectedWorlds = dims.stream().flatMap(dim -> getAffectedWorld(baseWorld, dim).stream()).toList();
        Optional<Long> effectiveSeed = seedOpt.isPresent() ? seedOpt : Optional.of(rng.nextLong());
        ResetTask task = new ResetTask(baseWorld, dims, player, effectiveSeed.orElse(null), affectedWorlds);
        activeTasks.put(player.getUniqueId(), task);
        int seconds = plugin.getConfig().getInt(ConfigKeys.COUNTDOWN_SECONDS, 10);
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
        
        // Use same distance for everyone (15000 blocks by default)
        int teleportDistance = plugin.getConfig().getInt(ConfigKeys.TELEPORT_MODE_PLAYER_DISTANCE, 15000);
        boolean fresh = plugin.getConfig().getBoolean(ConfigKeys.PLAYERS_FRESH_START_ON_RESET, true);
        boolean setWorldSpawn = plugin.getConfig().getBoolean(ConfigKeys.TELEPORT_MODE_SET_WORLD_SPAWN, true);
        
        // Find ONE safe location that everyone will teleport to
        java.util.Random r = new java.util.Random();
        Location sharedTeleportLocation = findSafeSurfaceLocation(overworld, teleportDistance, r);
        
        if (sharedTeleportLocation == null) {
            Messages.send(initiator, "&cFailed to find a safe teleport location!");
            return;
        }
        
        Set<UUID> affected = new HashSet<>();
        
        // Teleport ALL online players to the same location
        for (Player p : Bukkit.getOnlinePlayers()) {
            p.teleport(sharedTeleportLocation);
            affected.add(p.getUniqueId());
            if (fresh) applyFreshStartIfEnabled(p);
try { showShortTitle(p, "Teleported to new location"); p.sendActionBar(net.kyori.adventure.text.Component.text("Done")); } catch (Throwable ignored) {}
        }
        
        // Set world spawn to the new shared location
        if (setWorldSpawn) {
            try {
                overworld.setSpawnLocation(sharedTeleportLocation);
                // Update player spawn beds to prevent respawning at old beds
                for (UUID playerId : affected) {
                    Player p = Bukkit.getPlayer(playerId);
                    if (p != null && p.isOnline()) {
                        // Clear bed spawn location so they use world spawn
                        p.setRespawnLocation(null);
                    }
                }
                Messages.send(initiator, "&aWorld spawn set to new location: &e" + 
                    (int)sharedTeleportLocation.getX() + ", " + 
                    (int)sharedTeleportLocation.getY() + ", " + 
                    (int)sharedTeleportLocation.getZ());
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to set world spawn: " + ex.getMessage());
                Messages.send(initiator, "&cWarning: Failed to set world spawn location.");
            }
        }
        
        Messages.send(initiator, "&aTeleport-mode complete. All players moved to the same location &e" + teleportDistance + " blocks away in '&6" + baseWorld + "&a'.");
    }

    private Location findSafeSurfaceLocation(World world, int radius, java.util.Random rng) {
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
                    boolean loaded = chunk.load(false);
                    if (!loaded) {
                        continue; // Skip this location if chunk can't be loaded
                    }
                }
                
                // Find the highest solid block at this X,Z coordinate (surface level)
                int y = findSurfaceY(world, x, z);
                if (y == -1) continue; // Skip if we can't find a safe surface Y
                
                Location loc = new Location(world, x + 0.5, y + 1.0, z + 0.5);
                
                // Verify this is actually a safe surface location
                org.bukkit.block.Block ground = world.getBlockAt(x, y, z);
                org.bukkit.block.Block feet = world.getBlockAt(x, y + 1, z);
                org.bukkit.block.Block head = world.getBlockAt(x, y + 2, z);
                
                if (ground.getType().isSolid() && 
                    feet.getType().isAir() && 
                    head.getType().isAir() &&
                    ground.getType() != Material.LAVA &&
                    ground.getType() != Material.WATER) {
                    return loc;
                }
            } catch (Throwable ignored) {
                // Continue to next attempt if anything goes wrong
            }
        }
        
        // Fallback: return spawn location but elevated to surface
        Location spawn = center.clone();
        try {
            int surfaceY = findSurfaceY(world, spawn.getBlockX(), spawn.getBlockZ());
            if (surfaceY != -1) {
                spawn.setY(surfaceY + 1.0);
            }
        } catch (Exception ignored) {}
        return spawn;
    }
    
    private int findSurfaceY(World world, int x, int z) {
        try {
            // Start from a high altitude and work down to find the first solid block (surface)
            int maxY = Math.min(world.getMaxHeight() - 1, 320);
            int minY = Math.max(world.getMinHeight() + 1, 0);
            
            for (int y = maxY; y >= minY; y--) {
                org.bukkit.block.Block block = world.getBlockAt(x, y, z);
                Material type = block.getType();
                
                // Found a solid block that's suitable for standing on
                if (type.isSolid() && 
                    type != Material.LAVA && 
                    type != Material.WATER &&
                    type != Material.BEDROCK) {
                    
                    // Make sure there's air space above for player to stand
                    org.bukkit.block.Block above1 = world.getBlockAt(x, y + 1, z);
                    org.bukkit.block.Block above2 = world.getBlockAt(x, y + 2, z);
                    
                    if (above1.getType().isAir() && above2.getType().isAir()) {
                        return y; // Return the Y position of the solid block
                    }
                }
            }
            
            // If no suitable surface found, return a reasonable default
            return Math.max(world.getSeaLevel() + 1, 65);
        } catch (Throwable e) {
            // Return a safe default if everything fails
            return Math.max(world.getSeaLevel() + 1, 65);
        }
    }
}

