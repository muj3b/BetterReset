package com.github.codex.fullreset.command;

import com.github.codex.fullreset.FullResetPlugin;
import com.github.codex.fullreset.core.ConfirmationManager;
import com.github.codex.fullreset.core.ResetService;
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
 * Implements the /fullreset command.
 * Usage:
 *   /fullreset <world> [confirm|--confirm] [--seed <long>|--seed random]
 *
 * Permission: betterreset.use (default: OP)
 */
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

        String worldName = normalizeBase(args[0]);
        boolean confirm = false;
        Optional<Long> seed = Optional.empty();

        // Parse the rest of the arguments in any order
        for (int i = 1; i < args.length; i++) {
            String a = args[i].toLowerCase(Locale.ROOT);
            if (a.equals("confirm") || a.equals("--confirm")) {
                confirm = true;
            } else if (a.equals("--seed")) {
                if (i + 1 >= args.length) {
                    Messages.send(sender, "&cMissing value for --seed. Use a long or 'random'.");
                    return true;
                }
                String seedStr = args[++i];
                if (seedStr.equalsIgnoreCase("random")) {
                    seed = Optional.empty(); // handled as random later
                } else {
                    try {
                        seed = Optional.of(Long.parseLong(seedStr));
                    } catch (NumberFormatException e) {
                        Messages.send(sender, "&cInvalid seed: " + seedStr);
                        return true;
                    }
                }
            } else {
                Messages.send(sender, "&cUnknown option: " + a);
                return true;
            }
        }

        // Confirmation flow
        boolean requireConfirm = plugin.getConfig().getBoolean("confirmation.requireConfirm", true);
        long confirmWindowSec = plugin.getConfig().getLong("confirmation.timeoutSeconds", 15);

        String senderKey = ConfirmationManager.keyFor(sender);
        if (!confirm && requireConfirm) {
            confirmationManager.createPending(senderKey, worldName, seed);
            String warn = plugin.getConfig().getString("messages.confirmationWarning",
                    "&c[WARNING] This will DELETE and REGENERATE &e%world% &coverworld, nether, and end while the server is running!");
            String howTo = plugin.getConfig().getString("messages.confirmationHowTo",
                    "&7Type &e/%label% %world% confirm &7within &e%seconds%s &7to proceed.");
            warn = warn.replace("%world%", worldName);
            howTo = howTo.replace("%world%", worldName)
                         .replace("%seconds%", String.valueOf(confirmWindowSec))
                         .replace("%label%", label);
            Messages.send(sender, warn);
            Messages.send(sender, howTo);
            return true;
        }

        if (requireConfirm) {
            // Check/consume pending
            if (!confirmationManager.consumeIfValid(senderKey, worldName)) {
                Messages.send(sender, "&cNo pending confirmation. Run the command once to see the warning then confirm.");
                return true;
            }
        }

        // Start reset
        // Use countdown + reset flow for consistency
        resetService.startResetWithCountdown(sender, worldName, seed);
        return true;
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (!(sender.hasPermission("betterreset.use") || sender.hasPermission("fullreset.use"))) return Collections.emptyList();
        
        if (args.length == 1) {
            String prefix = args[0].toLowerCase(Locale.ROOT);
            return Bukkit.getWorlds().stream()
                    .map(World::getName)
                    .distinct()
                    .filter(n -> n.toLowerCase(Locale.ROOT).startsWith(prefix))
                    .sorted()
                    .collect(Collectors.toList());
        }
        if (args.length >= 2) {
            String last = args[args.length - 1].toLowerCase(Locale.ROOT);
            List<String> opts = new ArrayList<>();
            opts.add("confirm");
            opts.add("--confirm");
            opts.add("--seed");
            if (last.equals("--seed") && args.length >= 3) {
                return Arrays.asList("random", String.valueOf(new Random().nextLong()));
            }
            return opts.stream().filter(o -> o.startsWith(last)).collect(Collectors.toList());
        }
        return Collections.emptyList();
    }

    private static String normalizeBase(String input) {
        String s = input;
        if (s.endsWith("_nether")) {
            return s.substring(0, s.length() - "_nether".length());
        }
        if (s.endsWith("_the_end")) {
            return s.substring(0, s.length() - "_the_end".length());
        }
        return s;
    }
}
