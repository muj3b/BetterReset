package com.muj3b.betterreset.core;

import com.muj3b.betterreset.FullResetPlugin;
import com.muj3b.betterreset.util.BackupManager;
import com.muj3b.betterreset.util.CountdownManager;
import com.muj3b.betterreset.util.Messages;
import com.muj3b.betterreset.util.MultiverseCompat;
import com.muj3b.betterreset.util.PreloadManager;
import com.muj3b.betterreset.util.OfflinePlayerResetUtil;
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

    public enum Dimension {
        OVERWORLD, NETHER, END
    }

    private final FullResetPlugin plugin;
    @SuppressWarnings("unused")
    private final ConfirmationManager confirmationManager;
    private final CountdownManager countdownManager;
    private final MultiverseCompat multiverseCompat;
    private final BackupManager backupManager;
    private final PreloadManager preloadManager;
    private final OfflinePlayerResetUtil offlinePlayerResetUtil;

    private final ResetAuditLogger auditLogger = new ResetAuditLogger();
    private final Map<UUID, ResetTask> activeTasks = new HashMap<>();
    private final Random rng = new Random();
    private final SeedHistory seedHistory;
    private final Map<String, Long> lastResetAt = new ConcurrentHashMap<>();
    private final Map<String, Long> lastResetTimestamp = new ConcurrentHashMap<>();
    private long totalResets = 0;

    public ResetService(FullResetPlugin plugin, ConfirmationManager confirmationManager,
            CountdownManager countdownManager, MultiverseCompat multiverseCompat, PreloadManager preloadManager) {
        this.plugin = plugin;
        this.confirmationManager = confirmationManager;
        this.countdownManager = countdownManager;
        this.multiverseCompat = multiverseCompat;
        this.backupManager = new BackupManager(plugin);
        this.preloadManager = preloadManager;
        this.offlinePlayerResetUtil = new OfflinePlayerResetUtil(plugin.getLogger(), plugin.getBackgroundExecutor());
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
                Messages.send(player,
                        "&cA reset for &6" + baseWorld + "&c was performed recently. Please wait before retrying.");
                return;
            }
        }
        if (resetInProgress) {
            Messages.send(player, "&cA reset is already in progress. Please wait.");
            return;
        }
        int maxOnline = plugin.getConfig().getInt("limits.maxOnlineForReset", -1);
        if (maxOnline >= 0 && Bukkit.getOnlinePlayers().size() > maxOnline) {
            Messages.send(player, "&cToo many players online to reset now (&e" + Bukkit.getOnlinePlayers().size()
                    + "&c > &e" + maxOnline + "&c).");
            return;
        }

        List<World> affectedWorlds = dimensions.stream().flatMap(dim -> getAffectedWorld(baseWorld, dim).stream())
                .toList();
        Optional<Long> effectiveSeed = Optional.of(new Random().nextLong());
        try {
            maybePreload(baseWorld, effectiveSeed.get(), dimensions);
        } catch (Throwable ignored) {
        }
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
        resetInProgress = true;
        currentTarget = baseWorld;
        phase = "COUNTDOWN";
    }

    public void startResetWithCountdown(Player player, String baseWorld, Optional<Long> seedOpt,
            EnumSet<Dimension> dimensions) {
        if (resetInProgress) {
            Messages.send(player, "&cA reset is already in progress. Please wait.");
            return;
        }
        List<World> affectedWorlds = dimensions.stream().flatMap(dim -> getAffectedWorld(baseWorld, dim).stream())
                .toList();
        Optional<Long> effectiveSeed = seedOpt.isPresent() ? seedOpt : Optional.of(rng.nextLong());
        try {
            maybePreload(baseWorld, effectiveSeed.get(), dimensions);
        } catch (Throwable ignored) {
        }
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
        resetInProgress = true;
        currentTarget = baseWorld;
        phase = "COUNTDOWN";
    }

    public void startResetWithCountdown(CommandSender sender, String baseWorld, Optional<Long> seed) {
        Player player = null;
        if (sender instanceof Player p)
            player = p;
        EnumSet<Dimension> dims = EnumSet.of(Dimension.OVERWORLD, Dimension.NETHER, Dimension.END);
        if (player != null)
            startResetWithCountdown(player, baseWorld, seed, dims);
        else {
            Messages.send(Bukkit.getConsoleSender(), "&eConsole initiated reset for " + baseWorld);
            resetWorldAsync(Bukkit.getConsoleSender(), baseWorld, seed);
        }
    }

    private Optional<World> getAffectedWorld(String baseWorld, Dimension dim) {
        String worldName = switch (dim) {
            case OVERWORLD -> baseWorld;
            case NETHER -> baseWorld + "_nether";
            case END -> baseWorld + "_the_end";
        };
        return Optional.ofNullable(Bukkit.getWorld(worldName));
    }

    public void resetWorldAsync(CommandSender initiator, String baseWorldName, Optional<Long> seedOpt) {
        resetWorldAsync(initiator, baseWorldName, seedOpt,
                EnumSet.of(Dimension.OVERWORLD, Dimension.NETHER, Dimension.END));
    }

    public void resetWorldAsync(CommandSender initiator, String baseWorldName, Optional<Long> seedOpt,
            EnumSet<Dimension> dims) {
        final String worldBase = baseWorldName;
        final List<String> worldNames = dimensionNames(worldBase, dims);
        Set<UUID> affectedPlayers = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (worldNames.contains(p.getWorld().getName()))
                affectedPlayers.add(p.getUniqueId());

        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                phase = "RUNNING";
                auditLogger.log(plugin, "Reset started for '" + worldBase + "'");
                World fallback = findOrCreateFallbackWorld(worldNames);
                if (fallback == null) {
                    Messages.send(initiator, "&cFailed to find or create a fallback world; aborting.");
                    resetInProgress = false;
                    phase = "IDLE";
                    return;
                }

                for (UUID id : affectedPlayers) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline())
                        safeTeleport(p, fallback.getSpawnLocation());
                }
                for (Player online : Bukkit.getOnlinePlayers())
                    if (worldNames.contains(online.getWorld().getName()))
                        safeTeleport(online, fallback.getSpawnLocation());
                // Apply fresh-start immediately for affected players (pre-unload) to ensure
                // visible reset
                if (plugin.getConfig().getBoolean("players.freshStartOnReset", true)) {
                    for (UUID id : affectedPlayers) {
                        Player p = Bukkit.getPlayer(id);
                        if (p != null && p.isOnline())
                            applyFreshStartIfEnabled(p);
                    }
                }

                Map<String, Path> worldFolders = resolveWorldFolders(worldNames);
                Set<String> failedToUnload = unloadWorldsReliably(worldNames, fallback, initiator);

                // For worlds that couldn't be unloaded (like the default world), use fallback
                // reset
                final long seedForFallback = seedOpt.orElse(System.currentTimeMillis());
                for (String failedWorld : failedToUnload) {
                    forceResetLoadedWorld(failedWorld, seedForFallback, initiator);
                    // Remove from worldFolders since it's already handled
                    worldFolders.remove(failedWorld);
                }

                // Mark reset for RespawnManager protection even for fallback-reset worlds
                if (!failedToUnload.isEmpty()) {
                    try {
                        plugin.getRespawnManager().markReset(worldBase);
                    } catch (Exception ignored) {
                    }
                }

                boolean backupsEnabled = plugin.getConfig().getBoolean("backups.enabled", true);

                // Only process worlds that were successfully unloaded
                final Map<String, Path> unloadedWorldFolders = new HashMap<>();
                for (String name : worldNames) {
                    if (!failedToUnload.contains(name) && worldFolders.containsKey(name)) {
                        unloadedWorldFolders.put(name, worldFolders.get(name));
                    }
                }

                if (unloadedWorldFolders.isEmpty() && failedToUnload.isEmpty()) {
                    Messages.send(initiator, "§7No worlds needed processing.");
                    resetInProgress = false;
                    phase = "IDLE";
                    return;
                }

                Messages.send(initiator, backupsEnabled ? "§7Worlds processed. Archiving backups and recreating..."
                        : "§7Worlds processed. Deleting old folders and recreating...");

                final Set<String> finalFailedToUnload = failedToUnload;
                plugin.getBackgroundExecutor().submit(() -> {
                    try {
                        if (backupsEnabled && !unloadedWorldFolders.isEmpty())
                            backupManager.snapshot(worldBase, unloadedWorldFolders);
                        else if (!unloadedWorldFolders.isEmpty()) {
                            Path trashRoot = plugin.getDataFolder().toPath().resolve("trash")
                                    .resolve(String.valueOf(System.currentTimeMillis()));
                            Files.createDirectories(trashRoot);
                            for (Map.Entry<String, Path> e : unloadedWorldFolders.entrySet()) {
                                Path path = e.getValue();
                                if (path == null || !Files.exists(path))
                                    continue;
                                try {
                                    moveWithFallback(path, trashRoot.resolve(path.getFileName()));
                                } catch (Exception ex) {
                                    try {
                                        deletePath(path);
                                    } catch (IOException ignored) {
                                    }
                                }
                            }
                            plugin.getBackgroundExecutor().submit(() -> {
                                try {
                                    deletePath(trashRoot);
                                } catch (IOException ignored) {
                                }
                            });
                        }
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            // Only swap preloaded for worlds that were unloaded, skip fallback-reset ones
                            EnumSet<Dimension> dimsToSwap = EnumSet.noneOf(Dimension.class);
                            for (Dimension dim : dims) {
                                String dimWorldName = switch (dim) {
                                    case OVERWORLD -> worldBase;
                                    case NETHER -> worldBase + "_nether";
                                    case END -> worldBase + "_the_end";
                                };
                                if (!finalFailedToUnload.contains(dimWorldName)) {
                                    dimsToSwap.add(dim);
                                }
                            }
                            if (!dimsToSwap.isEmpty()) {
                                swapPreloadedIfAny(worldBase, dimsToSwap);
                            }
                            // Recreate only the worlds that weren't force-reset
                            recreateWorlds(initiator, worldBase, seedOpt, affectedPlayers, dimsToSwap);
                        });
                    } catch (Exception ex) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Messages.send(initiator, "&cUnexpected error while deleting worlds: " + ex.getMessage());
                            resetInProgress = false;
                            phase = "IDLE";
                            auditLogger.log(plugin, "Reset failed for '" + worldBase + "' (exception during delete): "
                                    + ex.getMessage());
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

    private void recreateWorlds(CommandSender initiator, String base, Optional<Long> seedOpt,
            Set<UUID> previouslyAffected, EnumSet<Dimension> dims) {
        try {
            boolean sameSeedForAll = plugin.getConfig().getBoolean("seeds.useSameSeedForAllDimensions", true);
            long baseSeed = seedOpt.orElseGet(() -> rng.nextLong());
            World overworld = null;
            if (dims.contains(Dimension.OVERWORLD)) {
                overworld = new WorldCreator(base).seed(baseSeed).environment(World.Environment.NORMAL)
                        .type(WorldType.NORMAL).createWorld();
                if (overworld == null) {
                    Messages.send(initiator, "&cFailed to create overworld: " + base);
                    resetInProgress = false;
                    phase = "IDLE";
                    auditLogger.log(plugin, "Reset failed creating overworld for '" + base + "'");
                    return;
                }
                try {
                    overworld.getChunkAt(overworld.getSpawnLocation()).load(true);
                } catch (Exception ignored) {
                }
                multiverseCompat.ensureRegistered(base, World.Environment.NORMAL, baseSeed);
            }

            long netherSeed = sameSeedForAll ? baseSeed : rng.nextLong();
            if (dims.contains(Dimension.NETHER)) {
                World nether = new WorldCreator(base + "_nether").seed(netherSeed).environment(World.Environment.NETHER)
                        .type(WorldType.NORMAL).createWorld();
                if (nether == null) {
                    Messages.send(initiator, "&cFailed to create nether: " + base + "_nether");
                    resetInProgress = false;
                    phase = "IDLE";
                    auditLogger.log(plugin, "Reset failed creating nether for '" + base + "'");
                    return;
                }
                try {
                    nether.getChunkAt(nether.getSpawnLocation()).load(true);
                } catch (Exception ignored) {
                }
                multiverseCompat.ensureRegistered(base + "_nether", World.Environment.NETHER, netherSeed);
            }

            long endSeed = sameSeedForAll ? baseSeed : rng.nextLong();
            if (dims.contains(Dimension.END)) {
                World theEnd = new WorldCreator(base + "_the_end").seed(endSeed).environment(World.Environment.THE_END)
                        .type(WorldType.NORMAL).createWorld();
                if (theEnd == null) {
                    Messages.send(initiator, "&cFailed to create the_end: " + base + "_the_end");
                    resetInProgress = false;
                    phase = "IDLE";
                    auditLogger.log(plugin, "Reset failed creating the_end for '" + base + "'");
                    return;
                }
                try {
                    theEnd.getChunkAt(theEnd.getSpawnLocation()).load(true);
                } catch (Exception ignored) {
                }
                multiverseCompat.ensureRegistered(base + "_the_end", World.Environment.THE_END, endSeed);
            }

            try {
                seedHistory.add(baseSeed);
                if (!sameSeedForAll) {
                    seedHistory.add(netherSeed);
                    seedHistory.add(endSeed);
                }
            } catch (Exception ignored) {
            }
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
                            plugin.getLogger().warning(
                                    "Failed to clear bed spawn for player " + p.getName() + ": " + ex.getMessage());
                        }
                    }
                }
            }

            // Apply fresh-start resets to players as configured
            if (plugin.getConfig().getBoolean("players.freshStartOnReset", true)) {
                Set<UUID> alreadyReset = new HashSet<>();
                for (UUID id : previouslyAffected) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        applyFreshStartIfEnabled(p);
                        alreadyReset.add(id);
                        try {
                            showShortTitle(p, "Fresh start applied");
                            p.sendActionBar(net.kyori.adventure.text.Component.text("Done"));
                        } catch (Throwable ignored) {
                        }
                    }
                }
                if (plugin.getConfig().getBoolean("players.resetAllOnlineAfterReset", true)) {
                    for (Player online : Bukkit.getOnlinePlayers()) {
                        if (!alreadyReset.contains(online.getUniqueId())) {
                            applyFreshStartIfEnabled(online);
                            try {
                                showShortTitle(online, "Fresh start applied");
                                online.sendActionBar(net.kyori.adventure.text.Component.text("Done"));
                            } catch (Throwable ignored) {
                            }
                        }
                    }
                }
            }

            Messages.send(initiator, "&aRecreated worlds for '&e" + base + "&a' successfully.");
            for (Player online : Bukkit.getOnlinePlayers()) {
                if (online.equals(initiator))
                    continue;
                if (online.hasPermission("betterreset.notify"))
                    Messages.send(online, "&a[BetterReset]&7 World '&e" + base + "&7' has been reset.");
            }

            // Reset offline players if enabled
            if (plugin.getConfig().getBoolean("players.resetOfflinePlayers", false)) {
                offlinePlayerResetUtil.resetOfflinePlayers(base).thenAccept(count -> {
                    if (count > 0) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Messages.send(initiator, "&7Reset &e" + count + "&7 offline players' data.");
                            auditLogger.log(plugin, "Reset " + count + " offline players for '" + base + "'");
                        });
                    }
                });
            }

            totalResets++;
            lastResetTimestamp.put(base, System.currentTimeMillis());
            auditLogger.log(plugin, "Reset completed for '" + base + "'");
            resetInProgress = false;
            phase = "IDLE";
            try {
                plugin.getRespawnManager().markReset(base);
            } catch (Exception ignored) {
            }
        } catch (Exception ex) {
            Messages.send(initiator, "&cError recreating worlds: " + ex.getMessage());
            resetInProgress = false;
            phase = "IDLE";
            auditLogger.log(plugin, "Reset failed (exception during create) for '" + base + "': " + ex.getMessage());
        }
    }

    private static List<String> dimensionNames(String base, EnumSet<Dimension> dims) {
        List<String> list = new ArrayList<>();
        if (dims.contains(Dimension.OVERWORLD))
            list.add(base);
        if (dims.contains(Dimension.NETHER))
            list.add(base + "_nether");
        if (dims.contains(Dimension.END))
            list.add(base + "_the_end");
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
            if (!p.startsWith(containerPath))
                map.put(name, null);
            else if (Files.exists(p))
                map.put(name, p);
            else
                map.put(name, null);
        }
        return map;
    }

    private World findOrCreateFallbackWorld(List<String> toAvoid) {
        String configured = plugin.getConfig().getString("teleport.fallbackWorldName", "").trim();
        if (!configured.isEmpty()) {
            World cw = Bukkit.getWorld(configured);
            if (cw != null && !toAvoid.contains(cw.getName()))
                return cw;
        }
        for (World w : Bukkit.getWorlds())
            if (!toAvoid.contains(w.getName()))
                return w;
        String tmpName = "betterreset_safe_" + Instant.now().getEpochSecond();
        return new WorldCreator(tmpName).environment(World.Environment.NORMAL).type(WorldType.NORMAL).createWorld();
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
        if ("IDLE".equals(phase))
            return "IDLE";
        if ("COUNTDOWN".equals(phase))
            return "COUNTDOWN '" + currentTarget + "' (" + countdownManager.secondsLeft() + "/"
                    + countdownManager.totalSeconds() + ")";
        return "RUNNING '" + currentTarget + "'";
    }

    public List<BackupManager.BackupRef> listBackups() {
        return backupManager.listBackups();
    }

    public long getTotalResets() {
        return totalResets;
    }

    public Optional<Long> getLastResetTimestamp(String base) {
        return Optional.ofNullable(lastResetTimestamp.get(base));
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
        Set<UUID> affected = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (worldNames.contains(p.getWorld().getName()))
                affected.add(p.getUniqueId());
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
                if (p != null && p.isOnline())
                    safeTeleport(p, fallback.getSpawnLocation());
            }
            for (String name : worldNames) {
                World w = Bukkit.getWorld(name);
                if (w != null) {
                    try {
                        w.save();
                    } catch (Exception ignored) {
                    }
                    Bukkit.unloadWorld(w, true);
                }
            }
            // Apply fresh-start immediately to affected players prior to restore
            if (plugin.getConfig().getBoolean("players.freshStartOnReset", true)) {
                for (UUID id : affected) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline())
                        applyFreshStartIfEnabled(p);
                }
            }
            CompletableFuture.runAsync(() -> {
                try {
                    backupManager.restore(base, timestamp);
                    Bukkit.getScheduler().runTask(plugin, () -> {
                        for (String name : worldNames) {
                            File f = new File(Bukkit.getWorldContainer(), name);
                            if (f.exists()) {
                                World.Environment env = name.endsWith("_nether") ? World.Environment.NETHER
                                        : name.endsWith("_the_end") ? World.Environment.THE_END
                                                : World.Environment.NORMAL;
                                new WorldCreator(name).environment(env).type(WorldType.NORMAL).createWorld();
                            }
                        }
                        // Optionally return affected players and fresh-start
                        boolean returnPlayers = plugin.getConfig().getBoolean("players.returnToNewSpawnAfterReset",
                                true);
                        World baseWorld = Bukkit.getWorld(base);
                        if (returnPlayers && baseWorld != null) {
                            Location spawn = baseWorld.getSpawnLocation();
                            for (UUID id : affected) {
                                Player p = Bukkit.getPlayer(id);
                                if (p != null && p.isOnline())
                                    safeTeleport(p, spawn);
                            }
                        }
                        if (plugin.getConfig().getBoolean("players.freshStartOnReset", true)) {
                            Set<UUID> already = new HashSet<>();
                            for (UUID id : affected) {
                                Player p = Bukkit.getPlayer(id);
                                if (p != null && p.isOnline()) {
                                    applyFreshStartIfEnabled(p);
                                    already.add(id);
                                    try {
                                        showShortTitle(p, "Fresh start applied");
                                        p.sendActionBar(net.kyori.adventure.text.Component.text("Done"));
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                            if (plugin.getConfig().getBoolean("players.resetAllOnlineAfterReset", true)) {
                                for (Player op : Bukkit.getOnlinePlayers())
                                    if (!already.contains(op.getUniqueId())) {
                                        applyFreshStartIfEnabled(op);
                                        try {
                                            showShortTitle(op, "Fresh start applied");
                                            op.sendActionBar(net.kyori.adventure.text.Component.text("Done"));
                                        } catch (Throwable ignored) {
                                        }
                                    }
                            }
                        }
                        Messages.send(initiator, "&aRestored backup '&e" + base + " @ " + timestamp + "&a'.");
                        if (initiator instanceof Player ip) {
                            World w = Bukkit.getWorld(base);
                            if (w != null)
                                safeTeleport(ip, w.getSpawnLocation());
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
        Set<UUID> affected = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers())
            if (worldNames.contains(p.getWorld().getName()))
                affected.add(p.getUniqueId());
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
                if (p != null && p.isOnline())
                    safeTeleport(p, fallback.getSpawnLocation());
            }
            for (String name : worldNames) {
                World w = Bukkit.getWorld(name);
                if (w != null) {
                    try {
                        w.save();
                    } catch (Exception ignored) {
                    }
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
                                World.Environment env = name.endsWith("_nether") ? World.Environment.NETHER
                                        : name.endsWith("_the_end") ? World.Environment.THE_END
                                                : World.Environment.NORMAL;
                                new WorldCreator(name).environment(env).type(WorldType.NORMAL).createWorld();
                            }
                        }
                        // Optionally return affected players and fresh-start
                        boolean returnPlayers = plugin.getConfig().getBoolean("players.returnToNewSpawnAfterReset",
                                true);
                        World baseWorld = Bukkit.getWorld(base);
                        if (returnPlayers && baseWorld != null) {
                            Location spawn = baseWorld.getSpawnLocation();
                            for (UUID id : affected) {
                                Player p = Bukkit.getPlayer(id);
                                if (p != null && p.isOnline())
                                    safeTeleport(p, spawn);
                            }
                        }
                        if (plugin.getConfig().getBoolean("players.freshStartOnReset", true)) {
                            Set<UUID> already = new HashSet<>();
                            for (UUID id : affected) {
                                Player p = Bukkit.getPlayer(id);
                                if (p != null && p.isOnline()) {
                                    applyFreshStartIfEnabled(p);
                                    already.add(id);
                                    try {
                                        showShortTitle(p, "Fresh start applied");
                                        p.sendActionBar(net.kyori.adventure.text.Component.text("Done"));
                                    } catch (Throwable ignored) {
                                    }
                                }
                            }
                            if (plugin.getConfig().getBoolean("players.resetAllOnlineAfterReset", true)) {
                                for (Player op : Bukkit.getOnlinePlayers())
                                    if (!already.contains(op.getUniqueId())) {
                                        applyFreshStartIfEnabled(op);
                                        try {
                                            showShortTitle(op, "Fresh start applied");
                                            op.sendActionBar(net.kyori.adventure.text.Component.text("Done"));
                                        } catch (Throwable ignored) {
                                        }
                                    }
                            }
                        }
                        Messages.send(initiator,
                                "&aRestored backup '&e" + base + " @ " + timestamp + "&a' for " + dims + ".");
                        if (initiator instanceof Player ip) {
                            World w = Bukkit.getWorld(base);
                            if (w != null)
                                safeTeleport(ip, w.getSpawnLocation());
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
                Bukkit.getScheduler().runTask(plugin,
                        () -> Messages.send(initiator, "&aDeleted backup '&e" + base + " @ " + timestamp + "&a'."));
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> Messages.send(initiator, "&cDelete failed: " + ex.getMessage()));
            }
        });
    }

    public void pruneBackupsAsync(CommandSender initiator, Optional<String> baseOpt) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                if (baseOpt.isPresent())
                    backupManager.prune(baseOpt.get());
                else
                    backupManager.pruneAll();
                Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator, "&aPrune complete."));
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> Messages.send(initiator, "&cPrune failed: " + ex.getMessage()));
            }
        });
    }

    public void pruneBackupsAsync(CommandSender initiator, Optional<String> baseOpt, boolean forceKeepPolicy) {
        if (!forceKeepPolicy) {
            pruneBackupsAsync(initiator, baseOpt);
            return;
        }
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                int keep = plugin.getConfig().getInt("backups.pruneNowKeepPerBase", 2);
                if (baseOpt.isPresent())
                    backupManager.pruneKeepPerBase(baseOpt.get(), keep);
                else
                    backupManager.pruneKeepAllBases(keep);
                Bukkit.getScheduler().runTask(plugin,
                        () -> Messages.send(initiator, "&aPrune complete. Kept at most " + keep + " per base."));
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> Messages.send(initiator, "&cPrune failed: " + ex.getMessage()));
            }
        });
    }

    public void deleteAllBackupsForBaseAsync(CommandSender initiator, String base) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                backupManager.deleteAllForBase(base);
                Bukkit.getScheduler().runTask(plugin,
                        () -> Messages.send(initiator, "&aDeleted all backups for '&e" + base + "&a'."));
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> Messages.send(initiator, "&cDelete all failed: " + ex.getMessage()));
            }
        });
    }

    public void deleteAllBackupsAsync(CommandSender initiator) {
        Bukkit.getScheduler().runTaskAsynchronously(plugin, () -> {
            try {
                backupManager.deleteAllBases();
                Bukkit.getScheduler().runTask(plugin,
                        () -> Messages.send(initiator, "&aDeleted ALL backups for ALL bases."));
            } catch (Exception ex) {
                Bukkit.getScheduler().runTask(plugin,
                        () -> Messages.send(initiator, "&cDelete all failed: " + ex.getMessage()));
            }
        });
    }

    public void testResetAsync(CommandSender initiator, String base, Optional<Long> seedOpt, EnumSet<Dimension> dims) {
        testResetAsync(initiator, base, seedOpt, dims, false);
    }

    public void testResetAsync(CommandSender initiator, String base, Optional<Long> seedOpt, EnumSet<Dimension> dims,
            boolean dryRun) {
        String testBase = ("brtest_" + base + "_" + System.currentTimeMillis());
        long seed = seedOpt.orElseGet(() -> rng.nextLong());
        Messages.send(initiator, "&7Starting test reset for '&e" + base + "&7' → temp '&e" + testBase + "&7'.");
        long t0 = System.nanoTime();
        Bukkit.getScheduler().runTask(plugin, () -> {
            try {
                for (String name : dimensionNames(testBase, dims)) {
                    World.Environment env = name.endsWith("_nether") ? World.Environment.NETHER
                            : name.endsWith("_the_end") ? World.Environment.THE_END : World.Environment.NORMAL;
                    World w = new WorldCreator(name).environment(env).seed(seed).type(WorldType.NORMAL).createWorld();
                    if (w != null)
                        try {
                            w.getChunkAt(w.getSpawnLocation()).load(true);
                        } catch (Exception ignored) {
                        }
                }
                long tCreate = System.nanoTime();
                Messages.send(initiator, "&aCreated temp worlds in &e" + ((tCreate - t0) / 1_000_000) + "ms.");
                for (String name : dimensionNames(testBase, dims)) {
                    World w = Bukkit.getWorld(name);
                    if (w != null)
                        Bukkit.unloadWorld(w, true);
                }
                if (dryRun) {
                    long tEnd = System.nanoTime();
                    Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator,
                            "&aDry-run complete. Total test time: &e" + ((tEnd - t0) / 1_000_000) + "ms"));
                } else {
                    CompletableFuture.runAsync(() -> {
                        try {
                            for (String name : dimensionNames(testBase, dims)) {
                                File f = new File(Bukkit.getWorldContainer(), name);
                                if (f.exists())
                                    deletePath(f.toPath());
                            }
                            long tEnd = System.nanoTime();
                            Bukkit.getScheduler().runTask(plugin, () -> Messages.send(initiator,
                                    "&aCleanup complete. Total test time: &e" + ((tEnd - t0) / 1_000_000) + "ms"));
                        } catch (Exception ex) {
                            Bukkit.getScheduler().runTask(plugin,
                                    () -> Messages.send(initiator, "&cTest cleanup failed: " + ex.getMessage()));
                        }
                    });
                }
            } catch (Exception ex) {
                Messages.send(initiator, "&cTest reset failed: " + ex.getMessage());
            }
        });
    }

    private void safeTeleport(Player p, Location to) {
        try {
            p.teleport(to);
        } catch (Exception ignored) {
        }
    }

    private void showShortTitle(Player p, String message) {
        try {
            net.kyori.adventure.title.Title.Times times = net.kyori.adventure.title.Title.Times.times(
                    java.time.Duration.ofMillis(300), java.time.Duration.ofMillis(1500),
                    java.time.Duration.ofMillis(300));
            net.kyori.adventure.title.Title title = net.kyori.adventure.title.Title.title(
                    net.kyori.adventure.text.Component.empty(),
                    net.kyori.adventure.text.Component.text(message),
                    times);
            p.showTitle(title);
        } catch (Throwable ignored) {
        }
    }

    private void applyFreshStartIfEnabled(Player p) {
        if (!plugin.getConfig().getBoolean("players.freshStartOnReset", true))
            return;
        try {
            // Clear inventory and ender chest
            p.getInventory().clear();
            p.getEnderChest().clear();

            // Reset health and status effects
            p.setFireTicks(0);
            double maxHealth = 20.0;
            try {
                // Try new attribute name first (1.21.3+): MAX_HEALTH
                org.bukkit.attribute.Attribute maxHealthAttr = null;
                try {
                    maxHealthAttr = org.bukkit.attribute.Attribute.valueOf("MAX_HEALTH");
                } catch (IllegalArgumentException e1) {
                    // Fall back to old name (pre-1.21.3): GENERIC_MAX_HEALTH
                    try {
                        maxHealthAttr = org.bukkit.attribute.Attribute.valueOf("GENERIC_MAX_HEALTH");
                    } catch (IllegalArgumentException e2) {
                        // Neither exists, use default
                    }
                }
                if (maxHealthAttr != null) {
                    var attr = p.getAttribute(maxHealthAttr);
                    if (attr != null) {
                        maxHealth = attr.getValue();
                    }
                }
            } catch (Exception e) {
                // Fall back to default max health
            }
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
                plugin.getLogger().warning(
                        "Failed to clear potion effects for player " + p.getName() + ": " + effectEx.getMessage());
            }

        } catch (Exception ignored) {
            plugin.getLogger().warning("Failed to apply fresh start for player " + p.getName());
        }
    }

    private void deletePath(Path path) throws IOException {
        if (!Files.exists(path))
            return;
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

    /**
     * Attempt to unload worlds. Returns set of world names that failed to unload.
     * For worlds that fail (like the default world), the caller should use
     * forceResetLoadedWorld.
     */
    private Set<String> unloadWorldsReliably(List<String> worldNames, World fallback, CommandSender initiator) {
        Set<String> failed = new HashSet<>();
        List<String> remaining = new ArrayList<>();

        for (String name : worldNames) {
            World w = Bukkit.getWorld(name);
            if (w == null)
                continue;
            for (Player pl : new ArrayList<>(w.getPlayers()))
                safeTeleport(pl, fallback.getSpawnLocation());
            try {
                w.save();
            } catch (Exception ignored) {
            }
            if (!Bukkit.unloadWorld(w, true))
                remaining.add(name);
        }

        if (remaining.isEmpty())
            return failed;

        final int max = 5;
        for (int attempt = 1; attempt <= max && !remaining.isEmpty(); attempt++) {
            try {
                Thread.sleep(50);
            } catch (InterruptedException ignored) {
            }
            Iterator<String> it = remaining.iterator();
            while (it.hasNext()) {
                String name = it.next();
                World w = Bukkit.getWorld(name);
                if (w == null) {
                    it.remove();
                    continue;
                }
                for (Player pl : new ArrayList<>(w.getPlayers()))
                    safeTeleport(pl, fallback.getSpawnLocation());
                try {
                    w.save();
                } catch (Exception ignored) {
                }
                if (Bukkit.unloadWorld(w, true))
                    it.remove();
            }
        }

        // Any remaining worlds couldn't be unloaded - add them to failed set
        for (String name : remaining) {
            failed.add(name);
            plugin.getLogger().warning(
                    "Could not unload world '" + name + "' (likely the default world). Using fallback reset strategy.");
        }

        return failed;
    }

    /**
     * Force reset a world that couldn't be unloaded (like the default world).
     * This unloads all chunks, deletes region files, and triggers regeneration.
     */
    private void forceResetLoadedWorld(String worldName, long newSeed, CommandSender initiator) {
        World world = Bukkit.getWorld(worldName);
        if (world == null) {
            plugin.getLogger().warning("forceResetLoadedWorld: World '" + worldName + "' not found");
            return;
        }

        plugin.getLogger().info("Force-resetting loaded world: " + worldName);
        Messages.send(initiator, "§e[BetterReset] Using fallback reset for '" + worldName + "' (default world)...");

        // Step 1: Force unload all chunks
        int unloadedChunks = 0;
        for (org.bukkit.Chunk chunk : world.getLoadedChunks()) {
            try {
                chunk.unload(true);
                unloadedChunks++;
            } catch (Exception ignored) {
            }
        }
        plugin.getLogger().info("Unloaded " + unloadedChunks + " chunks from " + worldName);

        // Step 2: Delete region files (the world folder's region, entities, poi
        // subdirectories)
        File worldFolder = world.getWorldFolder();
        String[] dataDirs = { "region", "entities", "poi", "DIM-1", "DIM1", "playerdata", "advancements", "stats",
                "datapacks" };

        for (String dir : dataDirs) {
            File dataDir = new File(worldFolder, dir);
            if (dataDir.exists() && dataDir.isDirectory()) {
                try {
                    org.apache.commons.io.FileUtils.deleteDirectory(dataDir);
                    plugin.getLogger().info("Deleted " + dir + " folder for " + worldName);
                } catch (Exception e) {
                    plugin.getLogger().warning("Failed to delete " + dir + " for " + worldName + ": " + e.getMessage());
                }
            }
        }

        // Also delete level.dat to reset seed and spawn
        File levelDat = new File(worldFolder, "level.dat");
        File levelDatOld = new File(worldFolder, "level.dat_old");
        try {
            if (levelDat.exists())
                levelDat.delete();
            if (levelDatOld.exists())
                levelDatOld.delete();
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to delete level.dat for " + worldName + ": " + e.getMessage());
        }

        // Step 3: The world will regenerate with fresh chunks when players enter
        // Set spawn to 0,0 initially - it will be recalculated
        try {
            Location newSpawn = new Location(world, 0, 64, 0);
            // Find safe spawn Y
            int highY = world.getHighestBlockYAt(0, 0);
            if (highY > 0) {
                newSpawn.setY(highY + 1);
            }
            world.setSpawnLocation(newSpawn);
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to set spawn for " + worldName + ": " + e.getMessage());
        }

        plugin.getLogger().info("Force reset completed for " + worldName + ". Chunks will regenerate on demand.");
    }

    private void swapPreloadedIfAny(String base, EnumSet<Dimension> dims) {
        for (String target : dimensionNames(base, dims)) {
            String prep = "brprep_" + target;
            World prepWorld = Bukkit.getWorld(prep);
            if (prepWorld != null)
                Bukkit.unloadWorld(prepWorld, true);
            File container = Bukkit.getWorldContainer();
            File prepFolder = new File(container, prep);
            File targetFolder = new File(container, target);
            if (prepFolder.exists()) {
                if (targetFolder.exists()) {
                    try {
                        deletePath(targetFolder.toPath());
                    } catch (Exception ignored) {
                    }
                }
                try {
                    moveWithFallback(prepFolder.toPath(), targetFolder.toPath());
                } catch (Exception ignored) {
                }
            }
        }
    }

    private void maybePreload(String baseWorld, long seed, EnumSet<Dimension> dims) {
        try {
            if (!plugin.getConfig().getBoolean("preload.enabled", true))
                return;
            if (plugin.getConfig().getBoolean("preload.autoDisableHighLag", true)) {
                try {
                    double[] tps = (double[]) Bukkit.getServer().getClass().getMethod("getTPS")
                            .invoke(Bukkit.getServer());
                    double minTps = plugin.getConfig().getDouble("preload.tpsThreshold", 18.0);
                    if (tps != null && tps.length > 0 && tps[0] < minTps)
                        return;
                } catch (Throwable ignored) {
                }
            }
            EnumSet<PreloadManager.Dimension> pdims = EnumSet.noneOf(PreloadManager.Dimension.class);
            if (dims.contains(Dimension.OVERWORLD))
                pdims.add(PreloadManager.Dimension.OVERWORLD);
            if (dims.contains(Dimension.NETHER))
                pdims.add(PreloadManager.Dimension.NETHER);
            if (dims.contains(Dimension.END))
                pdims.add(PreloadManager.Dimension.END);
            preloadManager.preload(baseWorld, seed, pdims);
        } catch (Throwable ignored) {
        }
    }

    private void moveWithFallback(Path src, Path dst) throws IOException, InterruptedException {
        int attempts = 3;
        for (int i = 0; i < attempts; i++) {
            try {
                Files.createDirectories(dst.getParent());
                Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
                return;
            } catch (AtomicMoveNotSupportedException amnse) {
                try {
                    Files.move(src, dst, StandardCopyOption.REPLACE_EXISTING);
                    return;
                } catch (Exception ignored) {
                }
            } catch (IOException ex) {
                try {
                    Thread.sleep(50);
                } catch (InterruptedException ignored) {
                }
            }
        }
        try {
            src.toFile().renameTo(dst.toFile());
            if (!Files.exists(dst))
                throw new IOException("renameTo failed");
            return;
        } catch (Exception ignored) {
        }
        copyAndDelete(src, dst);
    }

    private void copyAndDelete(Path src, Path dst) throws IOException {
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(dir);
                Path target = dst.resolve(rel);
                Files.createDirectories(target);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(file);
                Files.copy(file, dst.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
        deletePath(src);
    }

    // --- Teleport Mode (soft reset of overworld) ---
    public void startTeleportWithCountdown(Player player, String baseWorld, Optional<Long> seedOpt,
            EnumSet<Dimension> dimensions) {
        if (resetInProgress) {
            Messages.send(player, "&cA reset is already in progress. Please wait.");
            return;
        }

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
                    if (dims.contains(Dimension.NETHER))
                        ne.add(Dimension.NETHER);
                    if (dims.contains(Dimension.END))
                        ne.add(Dimension.END);
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
        if (overworld == null) {
            Messages.send(initiator, "&cBase world not found: &e" + baseWorld);
            return;
        }

        // Use same distance for everyone (15000 blocks by default)
        int teleportDistance = plugin.getConfig().getInt("teleportMode.playerDistance", 15000);
        boolean fresh = plugin.getConfig().getBoolean("players.freshStartOnReset", true);
        boolean setWorldSpawn = plugin.getConfig().getBoolean("teleportMode.setWorldSpawn", true);

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
            if (fresh)
                applyFreshStartIfEnabled(p);
            try {
                showShortTitle(p, "Teleported to new location");
                p.sendActionBar(net.kyori.adventure.text.Component.text("Done"));
            } catch (Throwable ignored) {
            }
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
                        (int) sharedTeleportLocation.getX() + ", " +
                        (int) sharedTeleportLocation.getY() + ", " +
                        (int) sharedTeleportLocation.getZ());
            } catch (Exception ex) {
                plugin.getLogger().warning("Failed to set world spawn: " + ex.getMessage());
                Messages.send(initiator, "&cWarning: Failed to set world spawn location.");
            }
        }

        Messages.send(initiator, "&aTeleport-mode complete. All players moved to the same location &e"
                + teleportDistance + " blocks away in '&6" + baseWorld + "&a'.");
    }

    private Location findSafeSurfaceLocation(World world, int radius, java.util.Random rng) {
        if (world == null)
            return null;
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
                if (y == -1)
                    continue; // Skip if we can't find a safe surface Y

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
        } catch (Exception ignored) {
        }
        return spawn;
    }

    private int findSurfaceY(World world, int x, int z) {
        try {
            // Start from a high altitude and work down to find the first solid block
            // (surface)
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
