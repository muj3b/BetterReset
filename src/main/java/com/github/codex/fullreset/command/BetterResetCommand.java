package com.github.codex.fullreset.command;

import com.github.codex.fullreset.FullResetPlugin;
import com.github.codex.fullreset.core.ConfirmationManager;
import com.github.codex.fullreset.core.ResetService;
import com.github.codex.fullreset.ui.GuiManager;
import com.github.codex.fullreset.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.command.Command;
import org.bukkit.command.CommandExecutor;
import org.bukkit.command.CommandSender;
import org.bukkit.command.TabCompleter;
import org.bukkit.entity.Player;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Root command: /betterreset <fullreset|gui|reload>
 */
public class BetterResetCommand implements CommandExecutor, TabCompleter {

    private final FullResetPlugin plugin;
    private final ResetService resetService;
    private final ConfirmationManager confirmationManager;
    private final GuiManager guiManager;
    private final FullResetCommand delegate;

    public BetterResetCommand(FullResetPlugin plugin, ResetService resetService, ConfirmationManager confirmationManager, GuiManager guiManager) {
        this.plugin = plugin;
        this.resetService = resetService;
        this.confirmationManager = confirmationManager;
        this.guiManager = guiManager;
        this.delegate = new FullResetCommand(plugin, resetService, confirmationManager);
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            Messages.send(sender, "&eUsage: /" + label + " <fullreset|gui|reload> ...");
            return true;
        }
        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "fullreset":
                // Reuse existing implementation; shift args
                String[] shifted = Arrays.copyOfRange(args, 1, args.length);
                return delegate.onCommand(sender, command, label + " " + sub, shifted);
            case "gui":
                if (!sender.hasPermission("betterreset.gui")) {
                    Messages.send(sender, plugin.getConfig().getString("messages.noPermission"));
                    return true;
                }
                if (!(sender instanceof Player p)) {
                    Messages.send(sender, "&cGUI can only be used by a player.");
                    return true;
                }
                guiManager.openWorldSelect(p);
                return true;
            case "reload":
                if (!sender.hasPermission("betterreset.reload")) {
                    Messages.send(sender, plugin.getConfig().getString("messages.noPermission"));
                    return true;
                }
                plugin.reloadConfig();
                Messages.send(sender, "&aBetterReset config reloaded.");
                return true;
            default:
                Messages.send(sender, "&cUnknown subcommand. Use &e/" + label + " <fullreset|gui|reload>");
                return true;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            return Arrays.asList("fullreset", "gui", "reload").stream()
                    .filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT)))
                    .collect(Collectors.toList());
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("fullreset")) {
            // Delegate to underlying completer for world names and flags
            String[] shifted = Arrays.copyOfRange(args, 1, args.length);
            return delegate.onTabComplete(sender, command, alias, shifted);
        }
        return Collections.emptyList();
    }
}

