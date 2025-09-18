package com.github.codex.fullreset;

import com.github.codex.fullreset.command.BetterResetCommand;
import com.github.codex.fullreset.core.ConfirmationManager;
import com.github.codex.fullreset.core.ResetService;
import com.github.codex.fullreset.ui.GuiManager;
import com.github.codex.fullreset.util.CountdownManager;
import com.github.codex.fullreset.util.MultiverseCompat;
import org.bukkit.plugin.java.JavaPlugin;
import com.github.codex.fullreset.util.RespawnManager;
import com.github.codex.fullreset.util.PreloadManager;
import com.github.codex.fullreset.util.PlaytimeTracker;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/**
 * BetterReset plugin entry.
 *
 * Features:
 * - /betterreset fullreset <world> [confirm] [--seed <long>] to reset overworld, nether, and end.
 * - Teleports players to a safe world before unload and optionally returns them after recreation.
 * - Async deletion of world folders; all Bukkit world calls run on the main thread.
 * - Optional seed specification; otherwise uses random seeds (configurable behavior for all dims).
 * - Soft-works with Multiverse-Core if present (no hard dependency required).
 *
 * Build: Java 17+, Paper/Spigot 1.21+
 */
public final class FullResetPlugin extends JavaPlugin {

    private ConfirmationManager confirmationManager;
    private ResetService resetService;
    private GuiManager guiManager;
    private CountdownManager countdownManager;
    private MultiverseCompat multiverseCompat;
    private RespawnManager respawnManager;
    private PreloadManager preloadManager;
    private PlaytimeTracker playtimeTracker;
    private ExecutorService backgroundExecutor;
    private com.github.codex.fullreset.util.SeedHistory seedHistory;

    @Override
    public void onEnable() {
        // Save default config (config.yml)
        saveDefaultConfig();

        this.confirmationManager = new ConfirmationManager(this);
    this.countdownManager = new CountdownManager(this);
        this.multiverseCompat = new MultiverseCompat(this);
        this.preloadManager = new PreloadManager(this);
        this.resetService = new ResetService(this, confirmationManager, countdownManager, multiverseCompat, preloadManager);
        this.guiManager = new GuiManager(this, resetService);
        this.respawnManager = new RespawnManager(this);
        this.playtimeTracker = new PlaytimeTracker(this);

    // Seed history shared store
    int historySize = Math.max(1, getConfig().getInt("seeds.historyCapacity", 10));
    this.seedHistory = new com.github.codex.fullreset.util.SeedHistory(historySize);

    // Create background executor for heavy IO tasks. Size is configurable.
    int parallel = getConfig().getInt("deletion.parallelism", 2);
    this.backgroundExecutor = Executors.newFixedThreadPool(Math.max(1, parallel));

        // Register commands
        BetterResetCommand root = new BetterResetCommand(this, resetService, confirmationManager, guiManager);
        if (getCommand("betterreset") != null) {
            getCommand("betterreset").setExecutor(root);
            getCommand("betterreset").setTabCompleter(root);
        } else {
            getLogger().severe("Command 'betterreset' not found in plugin.yml");
        }
        // No top-level /fullreset command; use /betterreset fullreset

        getLogger().info("BetterReset enabled.");
    }

    @Override
    public void onDisable() {
        if (backgroundExecutor != null) {
            try { backgroundExecutor.shutdownNow(); } catch (Exception ignored) {}
        }
        getLogger().info("BetterReset disabled.");
    }

    public ExecutorService getBackgroundExecutor() { return backgroundExecutor; }

    public com.github.codex.fullreset.util.SeedHistory getSeedHistory() { return seedHistory; }

    public RespawnManager getRespawnManager() {
        return respawnManager;
    }

    public PlaytimeTracker getPlaytimeTracker() { return playtimeTracker; }
}
