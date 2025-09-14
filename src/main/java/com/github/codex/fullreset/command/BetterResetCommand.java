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
            if (sender instanceof Player p) {
                if (!sender.hasPermission("betterreset.gui")) {
                    Messages.send(sender, plugin.getConfig().getString("messages.noPermission"));
                    return true;
                }
                guiManager.openMain(p);
                return true;
            }
            Messages.send(sender, "&eUsage: /" + label + " <fullreset|gui|reload|creator|status|cancel|fallback|seedsame|listworlds|about>");
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
                guiManager.openMain(p);
                return true;
            case "reload":
                if (!sender.hasPermission("betterreset.reload")) {
                    Messages.send(sender, plugin.getConfig().getString("messages.noPermission"));
                    return true;
                }
                plugin.reloadConfig();
                Messages.send(sender, "&aBetterReset config reloaded.");
                return true;
            case "creator":
                if (!sender.hasPermission("betterreset.creator")) {
                    Messages.send(sender, plugin.getConfig().getString("messages.noPermission"));
                    return true;
                }
                // Red message + clickable URL using Adventure formatting
                Messages.send(sender, "&cSupport the creator ");
                if (sender instanceof Player) {
                    net.kyori.adventure.text.Component click = net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer.legacyAmpersand().deserialize("&n&bDonate via Stripe");
                    click = click.clickEvent(net.kyori.adventure.text.event.ClickEvent.openUrl("https://donate.stripe.com/8x29AT0H58K03judnR0Ba01"));
                    ((Player) sender).sendMessage(click);
                } else {
                    Messages.send(sender, "&bhttps://donate.stripe.com/8x29AT0H58K03judnR0Ba01");
                }
                return true;
            case "status":
                if (!sender.hasPermission("betterreset.status")) {
                    Messages.send(sender, plugin.getConfig().getString("messages.noPermission"));
                    return true;
                }
                Messages.send(sender, "&7Status: &e" + resetService.getStatusLine());
                return true;
            case "cancel":
                if (!sender.hasPermission("betterreset.cancel")) {
                    Messages.send(sender, plugin.getConfig().getString("messages.noPermission"));
                    return true;
                }
                boolean canceled = resetService.cancelCountdown();
                if (canceled) Messages.send(sender, "&aCountdown canceled.");
                else Messages.send(sender, "&cNo countdown to cancel.");
                return true;
            case "fallback":
                if (!sender.hasPermission("betterreset.fallback")) {
                    Messages.send(sender, plugin.getConfig().getString("messages.noPermission"));
                    return true;
                }
                if (args.length < 2) {
                    Messages.send(sender, "&eUsage: /" + label + " fallback <world|none>");
                    return true;
                }
                String target = args[1].equalsIgnoreCase("none") ? "" : args[1];
                plugin.getConfig().set("teleport.fallbackWorldName", target);
                plugin.saveConfig();
                Messages.send(sender, "&aFallback world set to '&e" + (target.isEmpty()?"<auto>":target) + "&a'.");
                return true;
            case "seedsame":
                if (!sender.hasPermission("betterreset.seedsame")) {
                    Messages.send(sender, plugin.getConfig().getString("messages.noPermission"));
                    return true;
                }
                if (args.length < 2 || !(args[1].equalsIgnoreCase("true") || args[1].equalsIgnoreCase("false"))) {
                    Messages.send(sender, "&eUsage: /" + label + " seedsame <true|false>");
                    return true;
                }
                boolean val = Boolean.parseBoolean(args[1]);
                plugin.getConfig().set("seeds.useSameSeedForAllDimensions", val);
                plugin.saveConfig();
                Messages.send(sender, "&aSeed policy updated: useSameSeedForAllDimensions=&e" + val);
                return true;
            case "listworlds":
                if (!sender.hasPermission("betterreset.listworlds")) {
                    Messages.send(sender, plugin.getConfig().getString("messages.noPermission"));
                    return true;
                }
                Set<String> bases = Bukkit.getWorlds().stream().map(World::getName).map(BetterResetCommand::base).collect(java.util.stream.Collectors.toCollection(java.util.LinkedHashSet::new));
                Messages.send(sender, "&7Loaded base worlds (&e" + bases.size() + "&7): &f" + String.join(", ", bases));
                return true;
            case "about":
                if (!sender.hasPermission("betterreset.about")) {
                    Messages.send(sender, plugin.getConfig().getString("messages.noPermission"));
                    return true;
                }
                Messages.send(sender, "&bBetterReset &7v" + plugin.getDescription().getVersion() + " &7by &emuj3b&7. Type &e/" + label + " creator &7to support.");
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
        if (args.length == 1) return java.util.Arrays.asList("fullreset","gui","reload","creator","status","cancel","fallback","seedsame","listworlds","about");
        if (args.length == 2 && args[0].equalsIgnoreCase("fallback")) {
            String prefix = args[1].toLowerCase(java.util.Locale.ROOT);
            return Bukkit.getWorlds().stream().map(World::getName).filter(n -> n.toLowerCase(java.util.Locale.ROOT).startsWith(prefix)).collect(java.util.stream.Collectors.toList());
        }
        return Collections.emptyList();
    }

    private static String base(String input) {
        if (input.endsWith("_nether")) return input.substring(0, input.length() - 7);
        if (input.endsWith("_the_end")) return input.substring(0, input.length() - 8);
        return input;
    }
}
