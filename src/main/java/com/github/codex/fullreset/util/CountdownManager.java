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

    public CountdownManager(FullResetPlugin plugin) {
        this.plugin = plugin;
    }

    public void runCountdown(String world, int seconds, Runnable onFinish) {
        Set<Player> audience = new HashSet<>(Bukkit.getOnlinePlayers());
        // BossBar via adventure
        BossBar bar = BossBar.bossBar(Component.text("Reset " + world), 1.0f, BossBar.Color.RED, BossBar.Overlay.PROGRESS);
        audience.forEach(p -> p.showBossBar(bar));

        final int total = Math.max(1, seconds);
        new BukkitRunnable() {
            int left = total + 1; // first tick decrements to total
            @Override
            public void run() {
                left--;
                float progress = Math.max(0f, (float) left / (float) total);
                bar.progress(progress);
                String line1 = plugin.getConfig().getString("messages.countdownTitle", "Reset in %s...").replace("%s", String.valueOf(Math.max(0, left)));
                String line2 = plugin.getConfig().getString("messages.countdownSubtitle", "plugin made by muj3b");
                Title title = Title.title(Component.text(line1), Component.text(line2), Title.Times.times(Duration.ofMillis(100), Duration.ofMillis(900), Duration.ofMillis(100)));
                for (Player p : Bukkit.getOnlinePlayers()) {
                    p.showTitle(title);
                }
                if (left <= 0) {
                    cancel();
                    audience.forEach(p -> p.hideBossBar(bar));
                    onFinish.run();
                }
            }
        }.runTaskTimer(plugin, 0L, 20L);
    }
}
