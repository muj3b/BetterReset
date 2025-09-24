package com.muj3b.betterreset.command;

import com.muj3b.betterreset.FullResetPlugin;
import com.muj3b.betterreset.core.ConfirmationManager;
import com.muj3b.betterreset.core.ResetService;
import com.muj3b.betterreset.ui.SimpleGuiManager;
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

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.stream.Collectors;

public class BetterResetCommand implements CommandExecutor, TabCompleter {

    private final FullResetPlugin plugin;
    private final ResetService resetService;
    @SuppressWarnings("unused")
    private final ConfirmationManager confirmationManager;
    private final SimpleGuiManager guiManager;
    private final FullResetCommand delegate;
    private final org.bukkit.plugin.PluginDescriptionFile description;

    private static final DateTimeFormatter TIMESTAMP_FMT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss").withZone(ZoneId.systemDefault());

    @SuppressWarnings("deprecation")
    public BetterResetCommand(FullResetPlugin plugin, ResetService resetService, ConfirmationManager confirmationManager, SimpleGuiManager guiManager) {
        this.plugin = plugin;
        this.resetService = resetService;
        this.confirmationManager = confirmationManager;
        this.guiManager = guiManager;
        this.delegate = new FullResetCommand(plugin, resetService, confirmationManager);
        this.description = plugin.getDescription();
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        if (args.length == 0) {
            if (sender instanceof Player p) {
                if (!checkPermission(sender, "betterreset.gui")) return true;
                guiManager.openMainMenu(p);
                return true;
            }
            Messages.send(sender, "&eUsage: /" + label + " <fullreset|gui|reload|creator|status|cancel|fallback|seedsame|listworlds|about|prune|deleteallbackups|preload|testreset|seeds|stats>");
            return true;
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "fullreset":
                String[] shifted = Arrays.copyOfRange(args, 1, args.length);
                return delegate.onCommand(sender, command, label + " " + sub, shifted);
            case "gui":
                if (!checkPermission(sender, "betterreset.gui")) return true;
                if (!(sender instanceof Player p)) {
                    Messages.send(sender, "&cGUI can only be used by a player.");
                    return true;
                }
                guiManager.openMainMenu(p);
                return true;
            case "settings":
                if (!(sender instanceof Player p)) {
                    Messages.send(sender, "&cPlayer-only command.");
                    return true;
                }
                if (!checkPermission(sender, "betterreset.gui")) return true;
                guiManager.openSettingsMenu(p);
                return true;
            case "reload":
                if (!checkPermission(sender, "betterreset.reload")) return true;
                plugin.reloadConfig();
                Messages.send(sender, "&aBetterReset config reloaded.");
                return true;
            case "creator":
                if (!checkPermission(sender, "betterreset.creator")) return true;
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
                if (!checkPermission(sender, "betterreset.status")) return true;
                String status = resetService.getStatusLine();
                String extra = "&7Total resets: &e" + resetService.getTotalResets();
                Messages.send(sender, "&7Status: &e" + status + " &7| " + extra);
                return true;
            case "cancel":
                if (!checkPermission(sender, "betterreset.cancel")) return true;
                if (resetService.cancelCountdown()) {
                    Messages.send(sender, "&aCancelled the active countdown.");
                } else {
                    Messages.send(sender, "&cNo active countdown to cancel.");
                }
                return true;
            case "fallback":
                handleFallback(sender, args);
                return true;
            case "seedsame":
                handleSeedSame(sender, args);
                return true;
            case "listworlds":
                handleListWorlds(sender);
                return true;
            case "about":
                handleAbout(sender);
                return true;
            case "prune":
                handlePrune(sender, args);
                return true;
            case "deleteallbackups":
                handleDeleteAllBackups(sender, args);
                return true;
            case "preload":
                handlePreload(sender, args);
                return true;
            case "testreset":
                handleTestReset(sender, args);
                return true;
            case "seeds":
                handleSeeds(sender, args);
                return true;
            case "stats":
                handleStats(sender, args);
                return true;
            default:
                Messages.send(sender, "&cUnknown subcommand. Use &e/" + label + " help");
                return true;
        }
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        if (args.length == 1) {
            List<String> subs = Arrays.asList("fullreset","gui","settings","reload","creator","status","cancel","fallback","seedsame","listworlds","about","prune","deleteallbackups","preload","testreset","seeds","stats");
            return subs.stream().filter(s -> s.startsWith(args[0].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
        }

        String sub = args[0].toLowerCase(Locale.ROOT);
        if (args.length >= 2) {
            switch (sub) {
                case "settings" -> {
                    List<String> secs = Arrays.asList("confirmation","players","limits","countdown","preload","teleport","backups","seeds","deletion","debug","messages");
                    return secs.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                }
                case "fullreset" -> {
                    String[] shifted = Arrays.copyOfRange(args, 1, args.length);
                    return delegate.onTabComplete(sender, command, alias, shifted);
                }
                case "fallback" -> {
                    if (args.length == 2) {
                        Set<String> options = new TreeSet<>(allBaseWorlds());
                        options.add("none");
                        return options.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                    }
                }
                case "seedsame" -> {
                    if (args.length == 2) {
                        return Arrays.asList("true","false").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                    }
                }
                case "prune" -> {
                    if (args.length == 2) {
                        Set<String> bases = new TreeSet<>(allBaseWorlds());
                        bases.add("--force");
                        return bases.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                    }
                }
                case "deleteallbackups" -> {
                    if (args.length == 2) {
                        Set<String> bases = new TreeSet<>(allBaseWorlds());
                        bases.add("all");
                        return bases.stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                    }
                }
                case "preload" -> {
                    if (args.length == 2) {
                        return Arrays.asList("on","off","status").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                    }
                }
                case "testreset" -> {
                    if (args.length == 2) {
                        return new ArrayList<>(allBaseWorlds()).stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                    }
                    return Arrays.asList("--seed","--dry-run","--overworld","--nether","--end","--all").stream().filter(s -> s.startsWith(args[args.length - 1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                }
                case "seeds" -> {
                    if (args.length == 2) {
                        return Arrays.asList("list","use").stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                    }
                    if (args.length == 3 && args[1].equalsIgnoreCase("use")) {
                        return new ArrayList<>(allBaseWorlds()).stream().filter(s -> s.startsWith(args[2].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                    }
                }
                case "stats" -> {
                    if (args.length == 2) {
                        return new ArrayList<>(allBaseWorlds()).stream().filter(s -> s.startsWith(args[1].toLowerCase(Locale.ROOT))).collect(Collectors.toList());
                    }
                }
            }
        }
        return Collections.emptyList();
    }

    private void handleFallback(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "betterreset.fallback")) return;
        if (args.length < 2) {
            Messages.send(sender, "&cUsage: /betterreset fallback <world|none>");
            return;
        }
        String value = args[1];
        if (value.equalsIgnoreCase("none") || value.equalsIgnoreCase("null")) {
            plugin.getConfig().set("teleport.fallbackWorldName", "");
            plugin.saveConfig();
            Messages.send(sender, "&aCleared fallback world.");
            return;
        }
        plugin.getConfig().set("teleport.fallbackWorldName", value);
        plugin.saveConfig();
        Messages.send(sender, "&aSet fallback world to &e" + value + "&a.");
    }

    private void handleSeedSame(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "betterreset.seedsame")) return;
        if (args.length < 2) {
            Messages.send(sender, "&cUsage: /betterreset seedsame <true|false>");
            return;
        }
        String value = args[1].toLowerCase(Locale.ROOT);
        if (!value.equals("true") && !value.equals("false")) {
            Messages.send(sender, "&cValue must be true or false.");
            return;
        }
        boolean bool = Boolean.parseBoolean(value);
        plugin.getConfig().set("seeds.useSameSeedForAllDimensions", bool);
        plugin.saveConfig();
        Messages.send(sender, "&aUpdated seed policy: &e" + (bool ? "same seed" : "independent seeds"));
    }

    private void handleListWorlds(CommandSender sender) {
        if (!checkPermission(sender, "betterreset.listworlds")) return;
        Set<String> bases = allBaseWorlds();
        if (bases.isEmpty()) {
            Messages.send(sender, "&7No worlds are currently loaded.");
            return;
        }
        Messages.send(sender, "&7Loaded base worlds: &e" + String.join("&7, &e", bases));
    }

    private void handleAbout(CommandSender sender) {
        if (!checkPermission(sender, "betterreset.about")) return;
        String version = description.getVersion();
        String authors = String.join(", ", description.getAuthors());
        String website = description.getWebsite();
        Messages.send(sender, "&aBetterReset &ev" + version + " &7by &b" + authors);
        if (website != null && !website.isBlank()) {
            Messages.send(sender, "&7Website: &b" + website);
        }
    }

    private void handlePrune(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "betterreset.prune")) return;
        Optional<String> baseOpt = Optional.empty();
        boolean force = false;
        for (int i = 1; i < args.length; i++) {
            String token = args[i];
            if (token.equalsIgnoreCase("--force")) {
                force = true;
            } else {
                baseOpt = Optional.of(token);
            }
        }
        if (force) {
            resetService.pruneBackupsAsync(sender, baseOpt, true);
        } else {
            resetService.pruneBackupsAsync(sender, baseOpt);
        }
        if (baseOpt.isPresent()) {
            Messages.send(sender, "&7Pruning backups for &e" + baseOpt.get() + "&7...");
        } else {
            Messages.send(sender, "&7Pruning backups for all bases...");
        }
    }

    private void handleDeleteAllBackups(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "betterreset.deleteallbackups")) return;
        if (args.length >= 2 && !args[1].equalsIgnoreCase("all")) {
            String base = args[1];
            resetService.deleteAllBackupsForBaseAsync(sender, base);
            Messages.send(sender, "&7Deleting all backups for &e" + base + "&7...");
        } else {
            resetService.deleteAllBackupsAsync(sender);
            Messages.send(sender, "&7Deleting all backups for all bases...");
        }
    }

    private void handlePreload(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "betterreset.preload")) return;
        if (args.length < 2) {
            Messages.send(sender, "&cUsage: /betterreset preload <on|off|status>");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "on" -> {
                plugin.getConfig().set("preload.enabled", true);
                plugin.saveConfig();
                Messages.send(sender, "&aPreload enabled.");
            }
            case "off" -> {
                plugin.getConfig().set("preload.enabled", false);
                plugin.saveConfig();
                Messages.send(sender, "&aPreload disabled.");
            }
            case "status" -> {
                boolean enabled = plugin.getConfig().getBoolean("preload.enabled", true);
                Messages.send(sender, "&7Preload is currently &e" + (enabled ? "enabled" : "disabled"));
            }
            default -> Messages.send(sender, "&cUsage: /betterreset preload <on|off|status>");
        }
    }

    private void handleTestReset(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "betterreset.test")) return;
        if (args.length < 2) {
            Messages.send(sender, "&cUsage: /betterreset testreset <base> [--seed <long>] [--dry-run] [--overworld] [--nether] [--end] [--all]");
            return;
        }
        String base = args[1];
        Optional<Long> seedOpt = Optional.empty();
        boolean dryRun = false;
        EnumSet<ResetService.Dimension> dims = EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END);
        boolean customDims = false;
        for (int i = 2; i < args.length; i++) {
            String token = args[i];
            if (token.equalsIgnoreCase("--seed")) {
                if (i + 1 >= args.length) {
                    Messages.send(sender, "&cMissing value after --seed");
                    return;
                }
                String seedValue = args[++i];
                try {
                    seedOpt = Optional.of(Long.parseLong(seedValue));
                } catch (NumberFormatException ex) {
                    Messages.send(sender, "&cInvalid seed: &e" + seedValue);
                    return;
                }
                continue;
            }
            if (token.equalsIgnoreCase("--dry-run")) {
                dryRun = true;
                continue;
            }
            if (token.equalsIgnoreCase("--overworld")) {
                if (!customDims) {
                    dims = EnumSet.noneOf(ResetService.Dimension.class);
                    customDims = true;
                }
                dims.add(ResetService.Dimension.OVERWORLD);
                continue;
            }
            if (token.equalsIgnoreCase("--nether")) {
                if (!customDims) {
                    dims = EnumSet.noneOf(ResetService.Dimension.class);
                    customDims = true;
                }
                dims.add(ResetService.Dimension.NETHER);
                continue;
            }
            if (token.equalsIgnoreCase("--end")) {
                if (!customDims) {
                    dims = EnumSet.noneOf(ResetService.Dimension.class);
                    customDims = true;
                }
                dims.add(ResetService.Dimension.END);
                continue;
            }
            if (token.equalsIgnoreCase("--all")) {
                dims = EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END);
                customDims = true;
                continue;
            }
            Messages.send(sender, "&cUnknown flag: &e" + token);
            return;
        }
        if (dims.isEmpty()) {
            dims = EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END);
        }
        resetService.testResetAsync(sender, base, seedOpt, dims, dryRun);
    }

    private void handleSeeds(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "betterreset.seeds")) return;
        if (args.length < 2) {
            Messages.send(sender, "&cUsage: /betterreset seeds <list|use>");
            return;
        }
        String sub = args[1].toLowerCase(Locale.ROOT);
        switch (sub) {
            case "list" -> {
                List<Long> seeds = plugin.getSeedHistory().list();
                if (seeds.isEmpty()) {
                    Messages.send(sender, "&7No seeds recorded yet.");
                } else {
                    Messages.send(sender, "&7Recent seeds: &e" + seeds.stream().map(String::valueOf).collect(Collectors.joining("&7, &e")));
                }
                return;
            }
            case "use" -> {
                if (args.length < 4) {
                    Messages.send(sender, "&cUsage: /betterreset seeds use <base> <seed>");
                    return;
                }
                String targetBase = args[2];
                long parsedSeed;
                try {
                    parsedSeed = Long.parseLong(args[3]);
                } catch (NumberFormatException ex) {
                    Messages.send(sender, "&cInvalid seed value: &e" + args[3]);
                    return;
                }
                resetService.startResetWithCountdown(sender, targetBase, Optional.of(parsedSeed));
                return;
            }
            default -> {
                Messages.send(sender, "&cUsage: /betterreset seeds <list|use>");
            }
        }
    }

    private void handleStats(CommandSender sender, String[] args) {
        if (!checkPermission(sender, "betterreset.stats")) return;
        if (args.length >= 2) {
            String base = args[1];
            Optional<Long> ts = resetService.getLastResetTimestamp(base);
            if (ts.isPresent()) {
                Instant instant = Instant.ofEpochMilli(ts.get());
                Messages.send(sender, "&7Last reset for &e" + base + "&7: &e" + TIMESTAMP_FMT.format(instant) + "&7 (" + humanDuration(Duration.between(instant, Instant.now())) + " ago)");
            } else {
                Messages.send(sender, "&7No reset history for &e" + base + "&7 yet.");
            }
        } else {
            Messages.send(sender, "&7Total resets performed: &e" + resetService.getTotalResets());
            Messages.send(sender, "&7Countdown status: &e" + resetService.getStatusLine());
            Messages.send(sender, "&7Backups stored: &e" + resetService.listBackups().size());
        }
    }

    private boolean checkPermission(CommandSender sender, String permission) {
        if (sender.hasPermission(permission)) {
            return true;
        }
        sendNoPermission(sender);
        return false;
    }

    private void sendNoPermission(CommandSender sender) {
        String msg = plugin.getConfig().getString("messages.noPermission", "&cYou don't have permission to use this command.");
        Messages.send(sender, msg);
    }

    private Set<String> allBaseWorlds() {
        return Bukkit.getWorlds().stream()
                .map(World::getName)
                .map(this::baseName)
                .collect(Collectors.toCollection(TreeSet::new));
    }

    private String baseName(String worldName) {
        if (worldName.endsWith("_nether")) return worldName.substring(0, worldName.length() - 7);
        if (worldName.endsWith("_the_end")) return worldName.substring(0, worldName.length() - 8);
        return worldName;
    }

    private String humanDuration(Duration duration) {
        if (duration.isNegative()) {
            duration = duration.negated();
        }
        long days = duration.toDays();
        duration = duration.minusDays(days);
        long hours = duration.toHours();
        duration = duration.minusHours(hours);
        long minutes = duration.toMinutes();
        long seconds = duration.minusMinutes(minutes).getSeconds();
        List<String> parts = new ArrayList<>();
        if (days > 0) parts.add(days + "d");
        if (hours > 0) parts.add(hours + "h");
        if (minutes > 0) parts.add(minutes + "m");
        if (seconds > 0 && parts.size() < 2) parts.add(seconds + "s");
        if (parts.isEmpty()) return "just now";
        return String.join(" ", parts);
    }

}
