package com.github.codex.fullreset.core;

import com.github.codex.fullreset.FullResetPlugin;
import com.github.codex.fullreset.util.CountdownManager;
import com.github.codex.fullreset.util.Messages;
import com.github.codex.fullreset.util.MultiverseCompat;
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

    public ResetService(FullResetPlugin plugin, ConfirmationManager confirmationManager, CountdownManager countdownManager, MultiverseCompat multiverseCompat) {
        this.plugin = plugin;
        this.confirmationManager = confirmationManager;
        this.countdownManager = countdownManager;
        this.multiverseCompat = multiverseCompat;
    }

    private volatile boolean resetInProgress = false;

    public void startResetWithCountdown(CommandSender initiator, String baseWorldName, Optional<Long> seedOpt) {
        if (resetInProgress) {
            Messages.send(initiator, "&cA reset is already in progress. Please wait.");
            return;
        }
        int seconds = plugin.getConfig().getInt("countdown.seconds", 10);
        Messages.send(initiator, "&eStarting reset countdown for &6" + baseWorldName + "&e...");
        resetInProgress = true;
        countdownManager.runCountdown(baseWorldName, seconds, () -> resetWorldAsync(initiator, baseWorldName, seedOpt));
    }

    public void resetWorldAsync(CommandSender initiator, String baseWorldName, Optional<Long> seedOpt) {
        final String worldBase = baseWorldName;
        final List<String> worldNames = dimensionNames(worldBase);

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
                // Pick or create fallback world
                World fallback = findOrCreateFallbackWorld(worldNames);
                if (fallback == null) {
                    Messages.send(initiator, "&cFailed to find or create a fallback world; aborting.");
                    resetInProgress = false;
                    return;
                }

                // Teleport affected players out
                for (UUID id : affectedPlayers) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        safeTeleport(p, fallback.getSpawnLocation());
                    }
                }

                // Resolve world folders before unloading, in case some are not loaded
                Map<String, Path> worldFolders = resolveWorldFolders(worldNames);

                // Unload worlds
                for (String name : worldNames) {
                    World w = Bukkit.getWorld(name);
                    if (w != null) {
                        boolean ok = Bukkit.unloadWorld(w, false);
                        if (!ok) {
                            Messages.send(initiator, "&cFailed to unload world '&e" + name + "&c' — aborting.");
                            resetInProgress = false;
                            return;
                        }
                    }
                }

                Messages.send(initiator, "&7Worlds unloaded. Deleting on disk asynchronously...");

                // Async delete
                CompletableFuture.runAsync(() -> {
                    try {
                        boolean deletedAll = true;
                        for (Map.Entry<String, Path> e : worldFolders.entrySet()) {
                            String name = e.getKey();
                            Path path = e.getValue();
                            if (path == null) continue; // nothing to delete
                            try {
                                deletePath(path);
                            } catch (IOException ex) {
                                plugin.getLogger().warning("Failed to delete world folder '" + path + "': " + ex.getMessage());
                                deletedAll = false;
                            }
                        }
                        boolean finalDeletedAll = deletedAll;
                        // Back to main thread to recreate worlds
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            if (!finalDeletedAll) {
                                Messages.send(initiator, "&cSome world folders could not be deleted. Aborting recreation.");
                                resetInProgress = false;
                                return;
                            }
                            recreateWorlds(initiator, worldBase, seedOpt, affectedPlayers);
                        });
                    } catch (Exception ex) {
                        Bukkit.getScheduler().runTask(plugin, () -> {
                            Messages.send(initiator, "&cUnexpected error while deleting worlds: " + ex.getMessage());
                            resetInProgress = false;
                        });
                    }
                });
            } catch (Exception ex) {
                Messages.send(initiator, "&cError during reset: " + ex.getMessage());
                resetInProgress = false;
            }
        });
    }

    private void recreateWorlds(CommandSender initiator, String base, Optional<Long> seedOpt, Set<UUID> previouslyAffected) {
        try {
            boolean sameSeedForAll = plugin.getConfig().getBoolean("seeds.useSameSeedForAllDimensions", true);
            long baseSeed = seedOpt.orElseGet(() -> new Random().nextLong());

            // Create overworld
            World overworld = new WorldCreator(base)
                    .seed(baseSeed)
                    .environment(World.Environment.NORMAL)
                    .type(WorldType.NORMAL)
                    .createWorld();
            if (overworld == null) {
                Messages.send(initiator, "&cFailed to create overworld: " + base);
                resetInProgress = false;
                return;
            }
            multiverseCompat.ensureRegistered(base, World.Environment.NORMAL, baseSeed);

            // nether
            long netherSeed = sameSeedForAll ? baseSeed : new Random().nextLong();
            World nether = new WorldCreator(base + "_nether")
                    .seed(netherSeed)
                    .environment(World.Environment.NETHER)
                    .type(WorldType.NORMAL)
                    .createWorld();
            if (nether == null) {
                Messages.send(initiator, "&cFailed to create nether: " + base + "_nether");
                resetInProgress = false;
                return;
            }
            multiverseCompat.ensureRegistered(base + "_nether", World.Environment.NETHER, netherSeed);

            // the end
            long endSeed = sameSeedForAll ? baseSeed : new Random().nextLong();
            World theEnd = new WorldCreator(base + "_the_end")
                    .seed(endSeed)
                    .environment(World.Environment.THE_END)
                    .type(WorldType.NORMAL)
                    .createWorld();
            if (theEnd == null) {
                Messages.send(initiator, "&cFailed to create the_end: " + base + "_the_end");
                resetInProgress = false;
                return;
            }
            multiverseCompat.ensureRegistered(base + "_the_end", World.Environment.THE_END, endSeed);

            // Optional: Multiverse compatibility – if present, it will typically detect loads via events.
            // We avoid a hard dependency to keep the plugin simple and portable.

            Messages.send(initiator, "&aRecreated worlds for '&e" + base + "&a' successfully.");

            boolean returnPlayers = plugin.getConfig().getBoolean("players.returnToNewSpawnAfterReset", true);
            if (returnPlayers) {
                Location spawn = overworld.getSpawnLocation();
                for (UUID id : previouslyAffected) {
                    Player p = Bukkit.getPlayer(id);
                    if (p != null && p.isOnline()) {
                        safeTeleport(p, spawn);
                    }
                }
            }
            resetInProgress = false;
        } catch (Exception ex) {
            Messages.send(initiator, "&cError recreating worlds: " + ex.getMessage());
            resetInProgress = false;
        }
    }

    private static List<String> dimensionNames(String base) {
        return Arrays.asList(base, base + "_nether", base + "_the_end");
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
        for (World w : Bukkit.getWorlds()) {
            if (!toAvoid.contains(w.getName())) {
                return w;
            }
        }
        // If all loaded worlds are being reset, create a temporary fallback world
        String tmpName = "fullreset_safe_" + Instant.now().getEpochSecond();
        return new WorldCreator(tmpName)
                .environment(World.Environment.NORMAL)
                .type(WorldType.NORMAL)
                .createWorld();
    }

    private void safeTeleport(Player p, Location to) {
        try {
            // Use sync teleport for Spigot compatibility; Paper may optimize internally
            p.teleport(to);
        } catch (Exception ignored) {
        }
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
}
