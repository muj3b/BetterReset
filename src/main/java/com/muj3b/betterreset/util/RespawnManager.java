package com.muj3b.betterreset.util;

import com.muj3b.betterreset.FullResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.configuration.file.YamlConfiguration;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.io.File;
import java.io.IOException;
import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ensures players respawn in the new overworld after a reset.
 * Tracks recently reset bases and overrides respawn location when appropriate.
 * Data is persisted to disk to survive server restarts.
 */
public class RespawnManager implements Listener {

    private final FullResetPlugin plugin;
    private final Map<String, Long> recentlyResetBases = new ConcurrentHashMap<>();
    private final File dataFile;

    public RespawnManager(FullResetPlugin plugin) {
        this.plugin = plugin;
        this.dataFile = new File(plugin.getDataFolder(), "respawn_data.yml");
        loadData();
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    /**
     * Load persisted reset timestamps from disk.
     */
    private void loadData() {
        if (!dataFile.exists()) {
            return;
        }
        try {
            YamlConfiguration config = YamlConfiguration.loadConfiguration(dataFile);
            long ttl = plugin.getConfig().getLong("players.respawnRecentWindowSeconds", 600L);
            long now = Instant.now().getEpochSecond();

            for (String key : config.getKeys(false)) {
                long timestamp = config.getLong(key, 0L);
                // Only load if still within TTL window
                if (now - timestamp <= ttl) {
                    recentlyResetBases.put(key.toLowerCase(), timestamp);
                    plugin.getLogger().info("Loaded recent reset marker for world: " + key);
                }
            }
        } catch (Exception e) {
            plugin.getLogger().warning("Failed to load respawn data: " + e.getMessage());
        }
    }

    /**
     * Save reset timestamps to disk for persistence across restarts.
     */
    private void saveData() {
        try {
            YamlConfiguration config = new YamlConfiguration();
            long ttl = plugin.getConfig().getLong("players.respawnRecentWindowSeconds", 600L);
            long now = Instant.now().getEpochSecond();

            for (Map.Entry<String, Long> entry : recentlyResetBases.entrySet()) {
                // Only save entries that are still valid
                if (now - entry.getValue() <= ttl) {
                    config.set(entry.getKey(), entry.getValue());
                }
            }
            config.save(dataFile);
        } catch (IOException e) {
            plugin.getLogger().warning("Failed to save respawn data: " + e.getMessage());
        }
    }

    public void markReset(String base) {
        recentlyResetBases.put(base.toLowerCase(), Instant.now().getEpochSecond());
        // Persist immediately so data survives unexpected shutdowns
        saveData();
        plugin.getLogger().info(
                "Marked world '" + base + "' as recently reset (protection active for respawnRecentWindowSeconds)");
    }

    private boolean isRecent(String base) {
        long ttl = plugin.getConfig().getLong("players.respawnRecentWindowSeconds", 600L); // 10m
        Long t = recentlyResetBases.get(base.toLowerCase());
        if (t == null)
            return false;
        if (Instant.now().getEpochSecond() - t > ttl) {
            recentlyResetBases.remove(base.toLowerCase());
            saveData(); // Clean up expired entry from disk
            return false;
        }
        return true;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onRespawn(PlayerRespawnEvent e) {
        if (!plugin.getConfig().getBoolean("players.forceRespawnToNewOverworld", true))
            return;
        Player p = e.getPlayer();
        String currentBase = baseName(p.getWorld().getName());
        if (!isRecent(currentBase))
            return;
        World w = Bukkit.getWorld(currentBase);
        if (w != null) {
            Location safeSpawn = findSafeSpawnLocation(w);
            e.setRespawnLocation(safeSpawn);
            plugin.getLogger().info("Forcing respawn to new world spawn for player: " + p.getName());
        }
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("players.forceRespawnToNewOverworld", true))
            return;
        Player p = e.getPlayer();
        String currentBase = baseName(p.getWorld().getName());
        if (isRecent(currentBase)) {
            World w = Bukkit.getWorld(currentBase);
            if (w != null) {
                Location safeSpawn = findSafeSpawnLocation(w);
                Bukkit.getScheduler().runTask(plugin, () -> {
                    p.teleport(safeSpawn);
                    plugin.getLogger().info(
                            "Teleported joining player " + p.getName() + " to safe spawn (recent reset protection)");
                });
            }
        }
    }

    /**
     * Find a safe surface spawn near the current world spawn.
     */
    private Location findSafeSpawnLocation(World world) {
        Location spawn = world.getSpawnLocation();
        return SafeSpawnFinder.findNear(world, spawn, 64, 8);
    }

    /**
     * Called when plugin is disabled - save any pending data.
     */
    public void shutdown() {
        saveData();
    }

    private static String baseName(String input) {
        if (input.endsWith("_nether"))
            return input.substring(0, input.length() - 7);
        if (input.endsWith("_the_end"))
            return input.substring(0, input.length() - 8);
        return input;
    }
}
