package com.muj3b.betterreset;

import com.muj3b.betterreset.command.BetterResetCommand;
import com.muj3b.betterreset.command.FullResetCommand;
import com.muj3b.betterreset.command.LegacyFullResetCommand;
import com.muj3b.betterreset.core.ConfirmationManager;
import com.muj3b.betterreset.core.ResetService;
import com.muj3b.betterreset.ui.SimpleGuiManager;
import com.muj3b.betterreset.util.CountdownManager;
import com.muj3b.betterreset.util.MultiverseCompat;
import com.muj3b.betterreset.util.PlaytimeTracker;
import com.muj3b.betterreset.util.PreloadManager;
import com.muj3b.betterreset.util.RespawnManager;
import com.muj3b.betterreset.util.SeedHistory;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;

/**
 * Plugin entry point for BetterReset. Consolidated implementation.
 */
public final class FullResetPlugin extends JavaPlugin {

    private ConfirmationManager confirmationManager;
    private ResetService resetService;
    private SimpleGuiManager guiManager;
    private CountdownManager countdownManager;
    private MultiverseCompat multiverseCompat;
    private RespawnManager respawnManager;
    private PreloadManager preloadManager;
    private PlaytimeTracker playtimeTracker;
    private ExecutorService backgroundExecutor;
    private SeedHistory seedHistory;

    @Override
    public void onEnable() {
        saveDefaultConfig();

        this.confirmationManager = new ConfirmationManager(this);
        this.countdownManager = new CountdownManager(this);
        this.multiverseCompat = new MultiverseCompat(this);
        this.preloadManager = new PreloadManager(this);

        int historySize = Math.max(1, getConfig().getInt("seeds.historyCapacity", 10));
        this.seedHistory = new SeedHistory(historySize);

        this.resetService = new ResetService(this, confirmationManager, countdownManager, multiverseCompat, preloadManager);
        this.guiManager = new SimpleGuiManager(this, resetService);
        this.respawnManager = new RespawnManager(this);
        this.playtimeTracker = new PlaytimeTracker(this);

        int parallel = Math.max(1, getConfig().getInt("deletion.parallelism", 2));
        ThreadFactory tf = r -> {
            Thread t = new Thread(r, "betterreset-bg");
            t.setDaemon(true);
            return t;
        };
        this.backgroundExecutor = Executors.newFixedThreadPool(parallel, tf);

        // Register commands
        BetterResetCommand root = new BetterResetCommand(this, resetService, confirmationManager, guiManager);
        if (getCommand("betterreset") != null) {
            getCommand("betterreset").setExecutor(root);
            getCommand("betterreset").setTabCompleter(root);
        } else {
            getLogger().severe("Command 'betterreset' not found in plugin.yml");
        }

        // Legacy alias
        if (getCommand("fullreset") != null) {
            getCommand("fullreset").setExecutor(new LegacyFullResetCommand(this, new FullResetCommand(this, resetService, confirmationManager)));
        }

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
    public SeedHistory getSeedHistory() { return seedHistory; }
    public PlaytimeTracker getPlaytimeTracker() { return playtimeTracker; }
    public RespawnManager getRespawnManager() { return respawnManager; }
    public ResetService getResetService() { return resetService; }
}
