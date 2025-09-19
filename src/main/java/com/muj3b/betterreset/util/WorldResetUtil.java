package com.muj3b.betterreset.util;

import com.muj3b.betterreset.FullResetPlugin;
import net.kyori.adventure.text.Component;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.io.File;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Handles world reset operations with safe fallbacks for all versions
 */
public final class WorldResetUtil {

    private final FullResetPlugin plugin;

    public WorldResetUtil(@NotNull FullResetPlugin plugin) {
        this.plugin = plugin;
    }

    /**
     * Reset a world with the specified settings
     *
     * @param world The world to reset
     * @param seed The seed to use, or null for random
     * @param callback Callback for progress updates
     * @return true if reset was successful
     */
    public boolean resetWorld(@NotNull World world, @Nullable Long seed, @NotNull Consumer<Component> callback) {
        try {
            // Step 1: Send messages
            callback.accept(TextComponents.gray("Starting reset of world: " + world.getName()));
            
            // Step 2: Save configuration and data
            world.save();
            
            // Step 3: Move players to safe location
            world.getPlayers().forEach(player -> {
                World fallback = getFallbackWorld(world);
                if (fallback != null) {
                    player.teleport(fallback.getSpawnLocation());
                }
            });
            
            // Step 4: Unload world
            plugin.getServer().unloadWorld(world, true);
            
            // Step 5: Delete world files
            boolean deleted = deleteWorldFolder(world);
            if (!deleted) {
                callback.accept(TextComponents.red("Failed to delete world files for: " + world.getName()));
                return false;
            }
            
            // Step 6: Recreate world
            World newWorld = recreateWorld(world.getName(), seed);
            if (newWorld == null) {
                callback.accept(TextComponents.red("Failed to recreate world: " + world.getName()));
                return false;
            }
            
            callback.accept(TextComponents.green("Successfully reset world: " + world.getName()));
            return true;
            
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Error resetting world: " + world.getName(), e);
            callback.accept(TextComponents.red("Error resetting world: " + e.getMessage()));
            return false;
        }
    }

    private boolean deleteWorldFolder(@NotNull World world) {
        File worldFolder = world.getWorldFolder();
        if (!worldFolder.exists()) return true;
        
        try {
            org.apache.commons.io.FileUtils.deleteDirectory(worldFolder);
            return true;
        } catch (Exception e) {
            plugin.getLogger().log(Level.WARNING, "Failed to delete world folder: " + worldFolder, e);
            return false;
        }
    }

    @Nullable
    private World recreateWorld(@NotNull String name, @Nullable Long seed) {
        try {
            World.Environment env = World.Environment.NORMAL;
            if (name.endsWith("_nether")) {
                env = World.Environment.NETHER;
            } else if (name.endsWith("_the_end")) {
                env = World.Environment.THE_END;  
            }
            
            return plugin.getServer().createWorld(
                new org.bukkit.WorldCreator(name)
                    .environment(env)
                    .seed(seed != null ? seed : System.currentTimeMillis())
            );
        } catch (Exception e) {
            plugin.getLogger().log(Level.SEVERE, "Failed to recreate world: " + name, e);
            return null;
        }
    }

    @Nullable
    private World getFallbackWorld(@NotNull World current) {
        if (current.getName().equals("world")) {
            return plugin.getServer().getWorld("world_nether");
        } else {
            return plugin.getServer().getWorld("world");
        }
    }

    /**
     * Check if a player can be safely teleported to a world
     *
     * @param player The player to check
     * @param target The target world
     * @return true if teleport is safe
     */
    public boolean canTeleportSafely(@NotNull Player player, @NotNull World target) {
        if (!target.isChunkLoaded(target.getSpawnLocation().getChunk())) {
            target.getChunkAtAsync(target.getSpawnLocation()).thenAccept(chunk -> {
                if (chunk != null && chunk.isLoaded()) {
                    player.teleportAsync(target.getSpawnLocation());
                }
            });
            return false;
        }
        return true;
    }
}