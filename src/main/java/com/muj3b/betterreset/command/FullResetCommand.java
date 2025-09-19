package com.muj3b.betterreset.command;

import com.muj3b.betterreset.FullResetPlugin;
import com.muj3b.betterreset.core.ConfirmationManager;
import com.muj3b.betterreset.core.ResetService;
import com.muj3b.betterreset.util.Messages;
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

public class FullResetCommand implements CommandExecutor, TabCompleter {

    private final FullResetPlugin plugin;
    private final ResetService resetService;
    private final ConfirmationManager confirmationManager;

    public FullResetCommand(FullResetPlugin plugin, ResetService resetService, ConfirmationManager confirmationManager) {
        this.plugin = plugin;
        this.resetService = resetService;
        this.confirmationManager = confirmationManager;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (!(sender.hasPermission("betterreset.use") || sender.hasPermission("fullreset.use"))) {
            Messages.send(sender, plugin.getConfig().getString("messages.noPermission"));
            return true;
        }

        if (args.length < 1) {
            Messages.send(sender, "&cUsage: /" + label + " <world> [confirm|--confirm] [--seed <long>|--seed random]");
            return true;
        }

        // simplified argument parsing for brevity
        String worldName = args[0];
        boolean confirm = Arrays.asList(args).contains("confirm") || Arrays.asList(args).contains("--confirm");
        Optional<Long> seed = Optional.empty();

        boolean requireConfirm = plugin.getConfig().getBoolean("confirmation.requireConfirm", true);
        String senderKey = ConfirmationManager.keyFor(sender);
        boolean consoleBypass = !(sender instanceof Player) && plugin.getConfig().getBoolean("confirmation.consoleBypasses", true);
        boolean permBypass = sender.hasPermission("betterreset.bypassconfirm");

        if (!confirm && requireConfirm && !consoleBypass && !permBypass) {
            confirmationManager.createPending(senderKey, worldName, seed);
            Messages.send(sender, plugin.getConfig().getString("messages.confirmationWarning"));
            return true;
        }

        if (requireConfirm && !consoleBypass && !permBypass) {
            if (!confirmationManager.consumeIfValid(senderKey, worldName)) {
                Messages.send(sender, "&cNo pending confirmation. Run the command once to see the warning then confirm.");
                return true;
            }
        }

        resetService.startResetWithCountdown(sender, worldName, seed);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!(sender.hasPermission("betterreset.use") || sender.hasPermission("fullreset.use"))) return Collections.emptyList();
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getWorlds().stream().map(World::getName).distinct().filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix)).sorted().collect(Collectors.toList());
        }
        return Collections.emptyList();
    }
}
