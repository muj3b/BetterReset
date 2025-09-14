package com.github.codex.fullreset;

import com.github.codex.fullreset.command.BetterResetCommand;
import com.github.codex.fullreset.command.FullResetCommand;
import com.github.codex.fullreset.command.LegacyFullResetCommand;
import com.github.codex.fullreset.core.ConfirmationManager;
import com.github.codex.fullreset.core.ResetService;
import com.github.codex.fullreset.ui.GuiManager;
import com.github.codex.fullreset.util.CountdownManager;
import com.github.codex.fullreset.util.MultiverseCompat;
import org.bukkit.plugin.java.JavaPlugin;

/**
 * FullReset plugin entry.
 *
 * Features:
 * - /fullreset <world> [confirm] [--seed <long>] to reset overworld, nether, and end.
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

    @Override
    public void onEnable() {
        // Save default config (config.yml)
        saveDefaultConfig();

        this.confirmationManager = new ConfirmationManager(this);
        this.countdownManager = new CountdownManager(this);
        this.multiverseCompat = new MultiverseCompat(this);
        this.resetService = new ResetService(this, confirmationManager, countdownManager, multiverseCompat);
        this.guiManager = new GuiManager(this, resetService);

        // Register commands
        BetterResetCommand root = new BetterResetCommand(this, resetService, confirmationManager, guiManager);
        if (getCommand("betterreset") != null) {
            getCommand("betterreset").setExecutor(root);
            getCommand("betterreset").setTabCompleter(root);
        } else {
            getLogger().severe("Command 'betterreset' not found in plugin.yml");
        }
        // Legacy alias for /fullreset – shows hint and delegates
        FullResetCommand inner = new FullResetCommand(this, resetService, confirmationManager);
        LegacyFullResetCommand legacy = new LegacyFullResetCommand(this, inner);
        if (getCommand("fullreset") != null) {
            getCommand("fullreset").setExecutor(legacy);
            getCommand("fullreset").setTabCompleter(inner);
        }

        getLogger().info("BetterReset enabled.");
    }

    @Override
    public void onDisable() {
        getLogger().info("BetterReset disabled.");
    }
}
