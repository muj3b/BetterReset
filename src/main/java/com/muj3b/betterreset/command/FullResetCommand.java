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
        if (!hasUsePermission(sender)) {
            sendNoPermission(sender);
            return true;
        }

        if (args.length < 1) {
            Messages.send(sender, "&cUsage: /" + label + " <world> [confirm|--confirm] [--seed <long>|--seed random] [--force]");
            return true;
        }

        String worldName = args[0];
        boolean confirmArg = false;
        boolean force = false;
        Optional<Long> seed = Optional.empty();

        for (int i = 1; i < args.length; i++) {
            String token = args[i].toLowerCase(Locale.ROOT);
            switch (token) {
                case "confirm", "--confirm" -> confirmArg = true;
                case "--force" -> force = true;
                case "--seed" -> {
                    if (i + 1 >= args.length) {
                        Messages.send(sender, "&cMissing value after --seed");
                        return true;
                    }
                    String seedArg = args[++i];
                    if (!seedArg.equalsIgnoreCase("random")) {
                        try {
                            seed = Optional.of(Long.parseLong(seedArg));
                        } catch (NumberFormatException ex) {
                            Messages.send(sender, "&cInvalid seed value: &e" + seedArg);
                            return true;
                        }
                    } else {
                        seed = Optional.empty();
                    }
                }
                default -> {
                    // ignore unknown tokens for forward compatibility
                }
            }
        }

        boolean requireConfirm = plugin.getConfig().getBoolean("confirmation.requireConfirm", true);
        String senderKey = ConfirmationManager.keyFor(sender);
        boolean consoleBypass = !(sender instanceof Player) && plugin.getConfig().getBoolean("confirmation.consoleBypasses", true);
        boolean permBypass = sender.hasPermission("betterreset.bypassconfirm");

        if (force) {
            if (!sender.hasPermission("betterreset.force")) {
                sendNoPermission(sender);
                return true;
            }
            confirmArg = true;
            requireConfirm = false;
        }

        if (!confirmArg && requireConfirm && !consoleBypass && !permBypass) {
            confirmationManager.createPending(senderKey, worldName, seed);
            sendConfirmationPrompt(sender, label, worldName);
            return true;
        }

        if (requireConfirm && !consoleBypass && !permBypass) {
            if (!(confirmArg && confirmationManager.consumeIfValid(senderKey, worldName))) {
                Messages.send(sender, "&cNo pending confirmation. Run the command once to see the warning then confirm.");
                return true;
            }
        }

        resetService.startResetWithCountdown(sender, worldName, seed);
        return true;
    }

    private boolean hasUsePermission(CommandSender sender) {
        return sender.hasPermission("betterreset.use") || sender.hasPermission("fullreset.use");
    }

    private void sendNoPermission(CommandSender sender) {
        String msg = plugin.getConfig().getString("messages.noPermission", "&cYou don't have permission to use this command.");
        Messages.send(sender, msg);
    }

    private void sendConfirmationPrompt(CommandSender sender, String label, String world) {
        long timeout = plugin.getConfig().getLong("confirmation.timeoutSeconds", 15L);
        String warning = plugin.getConfig().getString("messages.confirmationWarning",
                "&c[WARNING] This will DELETE and REGENERATE &e%world%&c!");
        warning = warning.replace("%world%", world);

        String howTo = plugin.getConfig().getString("messages.confirmationHowTo",
                "&7Type &e/%label% %world% confirm &7within &e%seconds%s &7to proceed.");
        howTo = howTo.replace("%label%", label)
                .replace("%world%", world)
                .replace("%seconds%", String.valueOf(timeout));

        Messages.send(sender, warning);
        if (howTo != null && !howTo.isBlank()) {
            Messages.send(sender, howTo);
        }
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
