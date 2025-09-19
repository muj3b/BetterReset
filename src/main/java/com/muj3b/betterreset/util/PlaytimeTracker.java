package com.muj3b.betterreset.util;

import com.muj3b.betterreset.FullResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerChangedWorldEvent;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.event.player.PlayerQuitEvent;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.util.*;

/**
 * Tracks rough playtime per base world to surface in backup tooltips.
 * Granularity is session-based and not perfect but good enough.
 */
public class PlaytimeTracker implements Listener {

    private final FullResetPlugin plugin;
    private final Map<UUID, Session> sessions = new HashMap<>();
    private final Map<String, Long> accumulatedSeconds = new HashMap<>(); // base -> seconds

    public PlaytimeTracker(FullResetPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        load();
    }

    public long getSecondsForBase(String base) {
        return accumulatedSeconds.getOrDefault(base.toLowerCase(), 0L);
    }

    private static String baseName(World w) {
        String n = w.getName();
        if (n.endsWith("_nether")) return n.substring(0, n.length()-7);
        if (n.endsWith("_the_end")) return n.substring(0, n.length()-8);
        return n;
    }

    @EventHandler
    public void onJoin(PlayerJoinEvent e) {
        Player p = e.getPlayer();
        sessions.put(p.getUniqueId(), new Session(System.currentTimeMillis(), baseName(p.getWorld())));
    }

    @EventHandler
    public void onQuit(PlayerQuitEvent e) {
        endSession(e.getPlayer());
        save();
    }

    @EventHandler
    public void onWorldChange(PlayerChangedWorldEvent e) {
        endSession(e.getPlayer());
        sessions.put(e.getPlayer().getUniqueId(), new Session(System.currentTimeMillis(), baseName(e.getPlayer().getWorld())));
    }

    private void endSession(Player p) {
        Session s = sessions.remove(p.getUniqueId());
        if (s == null) return;
        long seconds = Math.max(0, (System.currentTimeMillis() - s.startedMs) / 1000);
        String key = s.base.toLowerCase();
        accumulatedSeconds.put(key, accumulatedSeconds.getOrDefault(key, 0L) + seconds);
    }

    public void save() {
        try {
            Properties props = new Properties();
            for (Map.Entry<String, Long> e : accumulatedSeconds.entrySet()) props.put(e.getKey(), String.valueOf(e.getValue()));
            File f = new File(plugin.getDataFolder(), "playtime.properties");
            f.getParentFile().mkdirs();
            try (FileOutputStream fos = new FileOutputStream(f)) { props.store(fos, "BetterReset playtime per base (seconds)"); }
        } catch (Exception ignored) {}
    }

    private void load() {
        try {
            File f = new File(plugin.getDataFolder(), "playtime.properties");
            if (!f.exists()) return;
            Properties props = new Properties();
            try (FileInputStream fis = new FileInputStream(f)) { props.load(fis); }
            for (String k : props.stringPropertyNames()) accumulatedSeconds.put(k, Long.parseLong(props.getProperty(k, "0")));
        } catch (Exception ignored) {}
    }

    private record Session(long startedMs, String base) {}
}

