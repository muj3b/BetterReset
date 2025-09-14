package com.github.codex.fullreset.util;

import com.github.codex.fullreset.FullResetPlugin;
import net.kyori.adventure.bossbar.BossBar;
import net.kyori.adventure.text.Component;
import net.kyori.adventure.title.Title;
import org.bukkit.Bukkit;
import org.bukkit.entity.Player;
import org.bukkit.scheduler.BukkitRunnable;

import java.time.Duration;
import java.util.HashSet;
import java.util.Set;

public class CountdownManager {

    private final FullResetPlugin plugin;
    private volatile BukkitRunnable currentTask;
    private volatile BossBar currentBar;
    private volatile String currentLabel = null;
    private volatile int totalSeconds = 0;
    private volatile int secondsLeft = 0;

    public CountdownManager(FullResetPlugin plugin) {
        this.plugin = plugin;
    }

    public synchronized void runCountdown(String world, int seconds, Set<Player> audience, Runnable onFinish) {
        cancel();
        this.currentLabel = world;
        this.totalSeconds = Math.max(1, seconds);
        this.secondsLeft = this.totalSeconds;

        BossBar bar = BossBar.bossBar(Component.text("Reset " + world), 1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        this.currentBar = bar;
        if (audience == null || audience.isEmpty()) {
            audience = new HashSet<>(Bukkit.getOnlinePlayers());
        }
        Set<Player> finalAudience = audience;
        finalAudience.forEach(p -> p.showBossBar(bar));

        this.currentTask = new BukkitRunnable() {
            @Override
            public void run() {
                secondsLeft--;
                float progress = Math.max(0f, (float) secondsLeft / (float) totalSeconds);
                bar.progress(progress);
                String line1 = plugin.getConfig().getString("messages.countdownTitle", "Reset in %s...").replace("%s", String.valueOf(Math.max(0, secondsLeft)));
                String line2 = plugin.getConfig().getString("messages.countdownSubtitle", "plugin made by muj3b");
                Title title = Title.title(Component.text(line1), Component.text(line2), Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(900), Duration.ofMillis(100)));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(title);
                }
                if (secondsLeft <= 0) {
                    cancel();
                    finalAudience.forEach(p -> p.hideBossBar(bar));
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
        if (currentBar != null) {
            for (Player p : Bukkit.getOnlinePlayers()) {
                try { p.hideBossBar(currentBar); } catch (Exception ignored) {}
            }
        }
        boolean wasActive = currentLabel != null;
        clearState();
        return wasActive;
    }

    private void clearState() {
        currentTask = null;
        currentBar = null;
        currentLabel = null;
        totalSeconds = 0;
        secondsLeft = 0;
    }

    public boolean isActive() { return currentLabel != null; }
    public String currentLabel() { return currentLabel; }
    public int secondsLeft() { return secondsLeft; }
    public int totalSeconds() { return totalSeconds; }
}
