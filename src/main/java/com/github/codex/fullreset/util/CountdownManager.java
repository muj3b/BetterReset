package com.github.codex.fullreset.util;

import com.github.codex.fullreset.FullResetPlugin;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

public class CountdownManager {

    private final FullResetPlugin plugin;
    private volatile BukkitRunnable currentTask;
    private volatile String currentLabel = null;
    private volatile int totalSeconds = 0;
    private volatile int secondsLeft = 0;

    public CountdownManager(FullResetPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void startCountdown(Player initiator, java.util.List<org.bukkit.World> affectedWorlds, int seconds, Runnable onComplete) {
        cancel();
        String label = (affectedWorlds != null && !affectedWorlds.isEmpty()) ? affectedWorlds.get(0).getName() : "";
        Set<Player> audience = new HashSet<>();
        if (plugin.getConfig().getBoolean("countdown.broadcastToAll", true)) {
            audience.addAll(Bukkit.getOnlinePlayers());
        } else if (affectedWorlds != null) {
            for (org.bukkit.World world : affectedWorlds) {
                audience.addAll(world.getPlayers());
            }
        }
        startCountdownInternal(label, seconds, audience, onComplete);
    }

    private synchronized void startCountdownInternal(String world, int seconds, Set<Player> audience, Runnable onFinish) {
        cancel();
        this.currentLabel = world;
        this.totalSeconds = Math.max(1, seconds);
        this.secondsLeft = this.totalSeconds;
        if (audience == null || audience.isEmpty()) {
            audience = new HashSet<>(Bukkit.getOnlinePlayers());
        }

        this.currentTask = new BukkitRunnable() {
            @Override
            public void run() {
                secondsLeft--;
                String raw1 = plugin.getConfig().getString("messages.countdownTitle", "&cReset in %s...").replace("%s", String.valueOf(Math.max(0, secondsLeft)));
                String raw2 = plugin.getConfig().getString("messages.countdownSubtitle", "&7plugin made by muj3b");
                Component line1 = LegacyComponentSerializer.legacyAmpersand().deserialize(raw1);
                Component line2 = LegacyComponentSerializer.legacyAmpersand().deserialize(raw2);
                Title title = Title.title(line1, line2, Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(900), Duration.ofMillis(100)));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(title);
                }
                if (secondsLeft <= 0) {
                    cancel();
                    clearState();
                    onFinish.run();
                }
            }
        };
        this.currentTask.runTaskTimer(plugin, 0L, 20L);
    }

    public synchronized boolean cancel() {
        if (currentTask != null) {
            try { currentTask.cancel(); } catch (Exception ignored) {}
        }
        boolean wasActive = currentLabel != null;
        clearState();
        return wasActive;
    }

    private void clearState() {
        currentTask = null;
        currentLabel = null;
        totalSeconds = 0;
        secondsLeft = 0;
    }

    public boolean isActive() { return currentLabel != null; }
    public String currentLabel() { return currentLabel; }
    public int secondsLeft() { return secondsLeft; }
    public int totalSeconds() { return totalSeconds; }
}
