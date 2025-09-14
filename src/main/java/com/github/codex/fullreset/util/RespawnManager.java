package com.github.codex.fullreset.util;

import com.github.codex.fullreset.FullResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerRespawnEvent;

import java.time.Instant;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Ensures players respawn in the new overworld after a reset.
 * Tracks recently reset bases and overrides respawn location when appropriate.
 */
public class RespawnManager implements Listener {

    private final FullResetPlugin plugin;
    private final Map<String, Long> recentlyResetBases = new ConcurrentHashMap<>();

    public RespawnManager(FullResetPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void markReset(String base) {
        recentlyResetBases.put(base.toLowerCase(), Instant.now().getEpochSecond());
    }

    private boolean isRecent(String base) {
        long ttl = plugin.getConfig().getLong("players.respawnRecentWindowSeconds", 600L); // 10m
        Long t = recentlyResetBases.get(base.toLowerCase());
        if (t == null) return false;
        if (Instant.now().getEpochSecond() - t > ttl) {
            recentlyResetBases.remove(base.toLowerCase());
            return false;
        }
        return true;
    }

    @EventHandler
    public void onRespawn(PlayerRespawnEvent e) {
        if (!plugin.getConfig().getBoolean("players.forceRespawnToNewOverworld", true)) return;
        Player p = e.getPlayer();
        String currentBase = baseName(p.getWorld().getName());
        if (!isRecent(currentBase)) return;
        World w = Bukkit.getWorld(currentBase);
        if (w != null) {
            e.setRespawnLocation(w.getSpawnLocation());
        }
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        if (!plugin.getConfig().getBoolean("players.forceRespawnToNewOverworld", true)) return;
        Player p = e.getPlayer();
        String currentBase = baseName(p.getWorld().getName());
        if (isRecent(currentBase)) {
            World w = Bukkit.getWorld(currentBase);
            if (w != null) {
                Bukkit.getScheduler().runTask(plugin, () -> p.teleport(w.getSpawnLocation()));
            }
        }
    }

    private static String baseName(String input) {
        if (input.endsWith("_nether")) return input.substring(0, input.length() - 7);
        if (input.endsWith("_the_end")) return input.substring(0, input.length() - 8);
        return input;
    }
}

