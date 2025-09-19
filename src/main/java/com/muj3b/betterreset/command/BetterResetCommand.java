package com.muj3b.betterreset.command;

import com.muj3b.betterreset.FullResetPlugin;
import com.muj3b.betterreset.core.ConfirmationManager;
import com.muj3b.betterreset.core.ResetService;
import com.muj3b.betterreset.ui.GuiManager;
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

public class BetterResetCommand implements CommandExecutor, TabCompleter {

    private final FullResetPlugin plugin;
    private final ResetService resetService;
    @SuppressWarnings("unused")
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
                String status = resetService.getStatusLine();
                String extra = "&7Total resets: &e" + resetService.getTotalResets();
                Messages.send(sender, "&7Status: &e" + status + " &7| " + extra);
                return true;
            default:
                Messages.send(sender, "&cUnknown subcommand. Use &e/" + label + " <fullreset|gui|reload>");
                return true;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = Arrays.asList("fullreset","gui","reload","creator","status","cancel","fallback","seedsame","listworlds","about","prune","deleteallbackups","preload","testreset","seeds");
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }
        if (args.length >= 2 && args[0].equalsIgnoreCase("fullreset")) {
            String[] shifted = Arrays.copyOfRange(args, 1, args.length);
            return delegate.onTabComplete(sender, command, alias, shifted);
        }
        return Collections.emptyList();
    }

}
