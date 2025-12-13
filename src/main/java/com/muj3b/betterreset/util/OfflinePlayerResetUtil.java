package com.muj3b.betterreset.util;

import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.io.File;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.logging.Logger;

/**
 * Utility class for resetting offline player data.
 * Deletes offline player .dat files so they start fresh when rejoining.
 */
public class OfflinePlayerResetUtil {

    private final Logger logger;
    private final Executor executor;

    public OfflinePlayerResetUtil(Logger logger, Executor executor) {
        this.logger = logger;
        this.executor = executor;
    }

    /**
     * Resets all offline players for the given base world by deleting their .dat
     * files.
     * Online players are excluded from deletion.
     *
     * @param baseWorld The base world name (e.g., "world")
     * @return CompletableFuture containing the count of reset players
     */
    public CompletableFuture<Integer> resetOfflinePlayers(String baseWorld) {
        File worldFolder = resolveWorldFolder(baseWorld);
        if (worldFolder == null) {
            logger.warning("World folder not found for: " + baseWorld);
            return CompletableFuture.completedFuture(0);
        }

        Set<UUID> onlineSnapshot = captureOnlinePlayers();

        return CompletableFuture.supplyAsync(() -> {
            try {
                return performOfflineReset(worldFolder, onlineSnapshot);
            } catch (Exception e) {
                logger.severe("Error resetting offline players: " + e.getMessage());
                return 0;
            }
        }, executor);
    }

    /**
     * Counts how many offline players would be affected by a reset.
     *
     * @param baseWorld The base world name
     * @return Count of offline players with data files
     */
    public int countOfflinePlayers(String baseWorld) {
        File worldFolder = resolveWorldFolder(baseWorld);
        if (worldFolder == null) {
            return 0;
        }

        Set<UUID> onlineUUIDs = captureOnlinePlayers();
        File playerDataDir = new File(worldFolder, "playerdata");
        if (!playerDataDir.exists()) {
            return 0;
        }

        File[] datFiles = playerDataDir.listFiles((dir, name) -> name.endsWith(".dat"));
        if (datFiles == null) {
            return 0;
        }

        int count = 0;
        for (File datFile : datFiles) {
            try {
                String fileName = datFile.getName();
                String uuidStr = fileName.substring(0, fileName.length() - 4);
                UUID playerUUID = UUID.fromString(uuidStr);
                if (!onlineUUIDs.contains(playerUUID)) {
                    count++;
                }
            } catch (IllegalArgumentException e) {
                // Not a valid UUID filename, skip
            }
        }
        return count;
    }

    private int performOfflineReset(File worldFolder, Set<UUID> onlineUUIDs) {
        File playerDataDir = new File(worldFolder, "playerdata");
        if (!playerDataDir.exists()) {
            logger.warning("Player data directory not found for world: " + worldFolder.getName());
            return 0;
        }

        int resetCount = 0;
        File[] datFiles = playerDataDir.listFiles((dir, name) -> name.endsWith(".dat"));
        if (datFiles == null) {
            return 0;
        }

        for (File datFile : datFiles) {
            try {
                String fileName = datFile.getName();
                String uuidStr = fileName.substring(0, fileName.length() - 4);
                UUID playerUUID = UUID.fromString(uuidStr);

                if (onlineUUIDs.contains(playerUUID)) {
                    continue;
                }

                if (datFile.delete()) {
                    resetCount++;
                    logger.info("Reset offline player data: " + uuidStr);
                } else {
                    logger.warning("Failed to delete player data file: " + datFile.getAbsolutePath());
                }
            } catch (IllegalArgumentException e) {
                logger.fine("Skipping non-UUID file: " + datFile.getName());
            }
        }

        resetPlayerStats(worldFolder, onlineUUIDs);
        resetPlayerAdvancements(worldFolder, onlineUUIDs);

        return resetCount;
    }

    private Set<UUID> captureOnlinePlayers() {
        Set<UUID> onlineUUIDs = new HashSet<>();
        for (Player p : Bukkit.getOnlinePlayers()) {
            onlineUUIDs.add(p.getUniqueId());
        }
        return onlineUUIDs;
    }

    /**
     * Resolve the world folder containing player data.
     */
    private File resolveWorldFolder(String baseWorld) {
        World world = Bukkit.getWorld(baseWorld);
        if (world != null) {
            return world.getWorldFolder();
        }
        File folder = new File(Bukkit.getWorldContainer(), baseWorld);
        return folder.exists() ? folder : null;
    }

    /**
     * Resets player statistics files for offline players.
     */
    private void resetPlayerStats(File worldFolder, Set<UUID> onlineUUIDs) {
        File statsDir = new File(worldFolder, "stats");

        if (!statsDir.exists()) {
            return;
        }

        File[] statsFiles = statsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (statsFiles == null) {
            return;
        }

        for (File statsFile : statsFiles) {
            try {
                String fileName = statsFile.getName();
                String uuidStr = fileName.substring(0, fileName.length() - 5); // Remove .json
                UUID playerUUID = UUID.fromString(uuidStr);

                if (!onlineUUIDs.contains(playerUUID)) {
                    if (statsFile.delete()) {
                        logger.fine("Reset offline player stats: " + uuidStr);
                    }
                }
            } catch (IllegalArgumentException e) {
                // Not a valid UUID filename, skip
            }
        }
    }

    /**
     * Resets player advancements files for offline players.
     */
    private void resetPlayerAdvancements(File worldFolder, Set<UUID> onlineUUIDs) {
        File advancementsDir = new File(worldFolder, "advancements");

        if (!advancementsDir.exists()) {
            return;
        }

        File[] advFiles = advancementsDir.listFiles((dir, name) -> name.endsWith(".json"));
        if (advFiles == null) {
            return;
        }

        for (File advFile : advFiles) {
            try {
                String fileName = advFile.getName();
                String uuidStr = fileName.substring(0, fileName.length() - 5); // Remove .json
                UUID playerUUID = UUID.fromString(uuidStr);

                if (!onlineUUIDs.contains(playerUUID)) {
                    if (advFile.delete()) {
                        logger.fine("Reset offline player advancements: " + uuidStr);
                    }
                }
            } catch (IllegalArgumentException e) {
                // Not a valid UUID filename, skip
            }
        }
    }
}
