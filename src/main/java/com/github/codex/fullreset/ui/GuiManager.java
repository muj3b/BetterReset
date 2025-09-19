package com.github.codex.fullreset.ui;

import com.github.codex.fullreset.FullResetPlugin;
import com.github.codex.fullreset.core.ResetService;
import com.github.codex.fullreset.util.BackupManager;
import com.github.codex.fullreset.util.Messages;
import com.github.codex.fullreset.util.TextComponents;
import com.github.codex.fullreset.util.VersionCompat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Clean, single-definition GUI manager. The previous file had duplicated blocks and
 * broken braces which caused cascading syntax errors and "duplicate method" reports.
 * This class provides a stable, minimal GUI that wires to ResetService correctly.
 */
public class GuiManager implements Listener {

    private static final Component TITLE_MAIN = TextComponents.darkAqua("BetterReset | Manager");
    private static final Component TITLE_SELECT = TextComponents.darkGreen("Reset | Select World");
    private static final Component TITLE_SETTINGS = TextComponents.blue("Settings");
    private static final Component TITLE_RESET_FOR = TextComponents.darkRed("Reset | ");
    private static final Component TITLE_BACKUPS = TextComponents.gold("Backups");
    private static final Component TITLE_BACKUP_OPTIONS = TextComponents.gold("Backup | ");
    private static final Component TITLE_DELETE_BACKUP = TextComponents.darkRed("Delete Backup | ");

    private final FullResetPlugin plugin;
    private final ResetService resetService;

    // Chat workflows and per-player selections
    private final Map<UUID, String> awaitingSeedForWorld = new HashMap<>();
    private final Map<UUID, String> awaitingConfigPath = new HashMap<>();
    private final Map<UUID, String> selectedBase = new HashMap<>();
    private final Map<UUID, EnumSet<ResetService.Dimension>> selectedDims = new HashMap<>();

    public GuiManager(FullResetPlugin plugin, ResetService resetService) {
        this.plugin = plugin;
        this.resetService = resetService;
        Bukkit.getPluginManager().registerEvents(this, plugin);
        try { new VersionCompat(plugin, this::onChatMessage); } catch (Throwable ignored) {}
    }

    // ------------- Common helpers -------------

    private static ItemStack namedComponent(Material mat, Component name, Component... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null && lore.length > 0) {
                List<Component> lc = new ArrayList<>();
                for (Component c : lore) lc.add(c == null ? Component.empty() : c);
                meta.lore(lc);
            }
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack toggleItem(boolean on, Material mat, String name) {
        Material use = on ? mat : Material.BARRIER;
        Component title = TextComponents.white(name);
        Component state = on ? TextComponents.green("[ON]") : TextComponents.red("[OFF]");
        return GuiHolderComponents.namedComponent(use, title, state);
    }

    private static String baseName(String input) {
        if (input.endsWith("_nether")) return input.substring(0, input.length() - 7);
        if (input.endsWith("_the_end")) return input.substring(0, input.length() - 8);
        return input;
    }

    // ------------- Event guards -------------

    @EventHandler(priority = EventPriority.LOWEST)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top != null && top.getHolder() instanceof GuiHolder) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.LOWEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        Inventory top = e.getView().getTopInventory();
        if (top == null || !(top.getHolder() instanceof GuiHolder)) return;

        // Always cancel our GUI interactions
        e.setCancelled(true);

        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta()) return;
        ItemMeta meta = it.getItemMeta();
        Component dnComp = meta.displayName();
        if (dnComp == null) return;
        String displayName = PlainTextComponentSerializer.plainText().serialize(dnComp);

        GuiHolder holder = (GuiHolder) top.getHolder();
        switch (holder.getType()) {
            case MAIN -> {
                switch (displayName.toLowerCase(Locale.ROOT)) {
                    case "reset worlds" -> openWorldSelect(p);
                    case "backups" -> openBackups(p);
                    case "settings" -> openSettings(p);
                    default -> {}
                }
            }
            case SELECT -> {
                if (displayName.equalsIgnoreCase("Back")) openMain(p);
                else openResetOptions(p, displayName);
            }
            case RESET_OPTIONS -> handleResetOptionsClick(p, displayName);
            case BACKUPS -> handleBackupsClick(p, displayName, meta);
            case BACKUP_OPTIONS -> handleBackupOptionsClick(p, displayName, meta);
            case DELETE_BACKUP -> handleDeleteBackupClick(p, displayName, meta);
            case DELETE_ALL -> handleDeleteAllClick(p, displayName);
            case SETTINGS -> handleSettingsClick(p, displayName, e.getClick());
            case SEED_SELECTOR -> handleSeedSelectorClick(p, displayName);
            case MESSAGES -> handleMessagesClick(p, displayName);
        }
    }

    // ------------- GUI openers -------------

    public void openMain(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.MAIN, TITLE_MAIN);
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE_MAIN);
        holder.setInventory(inv);
        inv.setItem(11, namedComponent(Material.GRASS_BLOCK, TextComponents.green("Reset Worlds"), TextComponents.gray("Pick a base world")));
        inv.setItem(13, namedComponent(Material.COMPARATOR, TextComponents.blue("Settings"), TextComponents.gray("Change options")));
        inv.setItem(15, namedComponent(Material.CHEST, TextComponents.gold("Backups"), TextComponents.gray("Browse & restore")));
        p.openInventory(inv);
    }

    public void openWorldSelect(Player p) {
        String base = baseName(p.getWorld().getName());
        GuiHolder holder = new GuiHolder(GuiHolder.Type.SELECT, TITLE_SELECT);
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE_SELECT);
        holder.setInventory(inv);
        inv.setItem(13, namedComponent(Material.GRASS_BLOCK, TextComponents.green(base), TextComponents.gray("Click to configure")));
        inv.setItem(22, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
        p.openInventory(inv);
    }

    private void openResetOptions(Player p, String base) {
        selectedBase.put(p.getUniqueId(), base);
        EnumSet<ResetService.Dimension> dims = selectedDims.computeIfAbsent(p.getUniqueId(), k -> EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END));
        GuiHolder holder = new GuiHolder(GuiHolder.Type.RESET_OPTIONS, Component.empty().append(TITLE_RESET_FOR).append(Component.text(base)));
        Inventory inv = Bukkit.createInventory(holder, 45, holder.getTitle());
        holder.setInventory(inv);
        inv.setItem(10, toggleItem(dims.contains(ResetService.Dimension.OVERWORLD), Material.GRASS_BLOCK, "Overworld"));
        inv.setItem(12, toggleItem(dims.contains(ResetService.Dimension.NETHER), Material.NETHERRACK, "Nether"));
        inv.setItem(14, toggleItem(dims.contains(ResetService.Dimension.END), Material.END_STONE, "The End"));
        inv.setItem(22, namedComponent(Material.LIME_WOOL, TextComponents.green("Reset Selected (Random Seed)")));
        inv.setItem(24, namedComponent(Material.CYAN_WOOL, TextComponents.blue("Reset Selected (Custom Seed)")));
        inv.setItem(40, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
        p.openInventory(inv);
    }

    private void openBackups(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.BACKUPS, TITLE_BACKUPS);
        Inventory inv = Bukkit.createInventory(holder, 54, TITLE_BACKUPS);
        holder.setInventory(inv);
        // Header actions per base: Delete ALL
        List<BackupManager.BackupRef> refs = resetService.listBackups();
        LinkedHashSet<String> bases = new LinkedHashSet<>();
        for (var r : refs) bases.add(r.base());
        int hslot = 0;
        for (String b : bases) {
            if (hslot > 8) break;
            inv.setItem(hslot++, namedComponent(Material.LAVA_BUCKET, TextComponents.darkRed("Delete ALL " + b), TextComponents.gray("Click to confirm")));
        }
        int slot = 9;
        for (var ref : refs) {
            if (slot >= 48) break;
            Component label = Component.empty().append(TextComponents.gold(ref.base())).append(TextComponents.gray(" @ ")).append(TextComponents.yellow(ref.timestamp()));
            List<Component> lore = new ArrayList<>();
            lore.add(TextComponents.gray("Click to restore"));
            ItemStack item = new ItemStack(Material.CHEST);
            ItemMeta im = item.getItemMeta();
            if (im != null) { im.displayName(label); im.lore(lore); item.setItemMeta(im); }
            inv.setItem(slot++, item);
        }
        inv.setItem(49, namedComponent(Material.SHEARS, TextComponents.red("Prune Now")));
        inv.setItem(53, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
        p.openInventory(inv);
    }

    private void openBackupOptions(Player p, String base, String ts) {
        Component title = Component.empty().append(TITLE_BACKUP_OPTIONS).append(Component.text(base)).append(TextComponents.gray(" @ ")).append(Component.text(ts));
        GuiHolder holder = new GuiHolder(GuiHolder.Type.BACKUP_OPTIONS, title);
        Inventory inv = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inv);
        inv.setItem(10, namedComponent(Material.LIME_WOOL, TextComponents.green("Restore ALL")));
        inv.setItem(12, namedComponent(Material.GRASS_BLOCK, TextComponents.white("Restore Overworld")));
        inv.setItem(14, namedComponent(Material.NETHERRACK, TextComponents.white("Restore Nether")));
        inv.setItem(16, namedComponent(Material.END_STONE, TextComponents.white("Restore End")));
        inv.setItem(20, namedComponent(Material.TNT, TextComponents.red("Delete Backup")));
        inv.setItem(22, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
        // Stamp ts into PDC for callbacks
        ItemMeta meta = inv.getItem(10).getItemMeta();
        if (meta != null) {
            meta.getPersistentDataContainer().set(new NamespacedKey(plugin, "backup_timestamp"), PersistentDataType.STRING, ts);
            inv.getItem(10).setItemMeta(meta);
        }
        p.openInventory(inv);
    }

    private void openDeleteConfirm(Player p, String base, String ts) {
        Component title = Component.empty().append(TITLE_DELETE_BACKUP).append(Component.text(base)).append(TextComponents.gray(" @ ")).append(Component.text(ts));
        GuiHolder holder = new GuiHolder(GuiHolder.Type.DELETE_BACKUP, title);
        Inventory inv = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inv);
        inv.setItem(11, namedComponent(Material.RED_CONCRETE, TextComponents.darkRed("Confirm Delete")));
        inv.setItem(15, namedComponent(Material.ARROW, TextComponents.yellow("Cancel")));
        p.openInventory(inv);
    }

    private void openDeleteAllConfirm(Player p, String base) {
        Component title = TextComponents.darkRed("Delete ALL | " + base);
        GuiHolder holder = new GuiHolder(GuiHolder.Type.DELETE_ALL, title);
        Inventory inv = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inv);
        inv.setItem(11, namedComponent(Material.RED_CONCRETE, TextComponents.darkRed("Confirm Delete All")));
        inv.setItem(15, namedComponent(Material.ARROW, TextComponents.yellow("Cancel")));
        p.openInventory(inv);
    }

    private void openSeedSelector(Player p, String base, EnumSet<ResetService.Dimension> dims) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.SEED_SELECTOR, TextComponents.darkPurple("Select Seed | ").append(Component.text(base)));
        Inventory inv = Bukkit.createInventory(holder, 27, holder.getTitle());
        holder.setInventory(inv);
        List<Long> seeds = plugin.getSeedHistory().list();
        int slot = 10;
        if (seeds.isEmpty()) inv.setItem(13, namedComponent(Material.PAPER, TextComponents.gray("No recent seeds")));
        else for (int i = 0; i < seeds.size() && slot < 17; i++) inv.setItem(slot++, namedComponent(Material.PAPER, TextComponents.white("[" + i + "] " + seeds.get(i)), TextComponents.gray("Click to use")));
        inv.setItem(22, namedComponent(Material.CYAN_WOOL, TextComponents.blue("Type Custom Seed")));
        inv.setItem(26, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
        awaitingSeedForWorld.put(p.getUniqueId(), base + ";" + dims.stream().map(Enum::name).collect(Collectors.joining(",")));
        p.openInventory(inv);
    }

    // ------------- Click handlers -------------

    private void handleResetOptionsClick(Player p, String displayName) {
        String base = selectedBase.get(p.getUniqueId());
        if (base == null) return;
        EnumSet<ResetService.Dimension> dims = selectedDims.computeIfAbsent(p.getUniqueId(), k -> EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END));
        switch (displayName) {
            case "Overworld" -> toggleDim(p, base, dims, ResetService.Dimension.OVERWORLD);
            case "Nether" -> toggleDim(p, base, dims, ResetService.Dimension.NETHER);
            case "The End" -> toggleDim(p, base, dims, ResetService.Dimension.END);
            case "Back" -> openWorldSelect(p);
            case "Reset Selected (Random Seed)" -> { p.closeInventory(); resetService.startReset(p, base, dims); }
            case "Reset Selected (Custom Seed)" -> { p.closeInventory(); openSeedSelector(p, base, dims); }
            default -> {}
        }
    }

    private void handleBackupsClick(Player p, String displayName, ItemMeta meta) {
        if (displayName.equalsIgnoreCase("Back")) { openMain(p); return; }
        if (displayName.equalsIgnoreCase("Prune Now")) { p.performCommand("betterreset prune --confirm"); return; }
        if (displayName.startsWith("Delete ALL ")) { String base = displayName.substring("Delete ALL ".length()); openDeleteAllConfirm(p, base); return; }
        // Fallback: parse title as base @ ts
        String dn = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        String[] parts = dn.split(" @ ", 2);
        if (parts.length == 2) openBackupOptions(p, parts[0], parts[1]);
    }

    private void handleBackupOptionsClick(Player p, String displayName, ItemMeta meta) {
        String ts = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "backup_timestamp"), PersistentDataType.STRING);
        String base = selectedBase.getOrDefault(p.getUniqueId(), baseName(p.getWorld().getName()));
        if (ts == null) ts = "";
        switch (displayName) {
            case "Back" -> openBackups(p);
            case "Restore ALL" -> { p.closeInventory(); resetService.restoreBackupAsync(p, base, ts); }
            case "Restore Overworld" -> { p.closeInventory(); resetService.restoreBackupAsync(p, base, ts, EnumSet.of(ResetService.Dimension.OVERWORLD)); }
            case "Restore Nether" -> { p.closeInventory(); resetService.restoreBackupAsync(p, base, ts, EnumSet.of(ResetService.Dimension.NETHER)); }
            case "Restore End" -> { p.closeInventory(); resetService.restoreBackupAsync(p, base, ts, EnumSet.of(ResetService.Dimension.END)); }
            case "Delete Backup" -> openDeleteConfirm(p, base, ts);
            default -> {}
        }
    }

    private void handleDeleteBackupClick(Player p, String displayName, ItemMeta meta) {
        String invTitle = PlainTextComponentSerializer.plainText().serialize(((GuiHolder) p.getOpenInventory().getTopInventory().getHolder()).getTitle());
        String raw = invTitle.replace(PlainTextComponentSerializer.plainText().serialize(TITLE_DELETE_BACKUP), "");
        String[] parts = raw.split(" @ ", 2);
        if (parts.length != 2) { openBackups(p); return; }
        String base = parts[0]; String ts = parts[1];
        switch (displayName) {
            case "Confirm Delete" -> { p.closeInventory(); resetService.deleteBackupAsync(p, base, ts); }
            case "Cancel" -> openBackupOptions(p, base, ts);
            default -> {}
        }
    }

    private void handleDeleteAllClick(Player p, String displayName) {
        String invTitle = PlainTextComponentSerializer.plainText().serialize(((GuiHolder) p.getOpenInventory().getTopInventory().getHolder()).getTitle());
        String base = invTitle.replace("Delete ALL | ", "");
        if (displayName.equalsIgnoreCase("Cancel")) openBackups(p);
        else if (displayName.equalsIgnoreCase("Confirm Delete All")) { p.closeInventory(); resetService.deleteAllBackupsForBaseAsync(p, base); }
    }

    private void handleSeedSelectorClick(Player p, String displayName) {
        if (displayName.equalsIgnoreCase("Back")) { openResetOptions(p, selectedBase.getOrDefault(p.getUniqueId(), baseName(p.getWorld().getName()))); return; }
        if (displayName.equalsIgnoreCase("Type Custom Seed")) { p.closeInventory(); Messages.send(p, "&eType a &lseed number&re in chat, or type 'random'."); return; }
        if (displayName.startsWith("[")) {
            int end = displayName.indexOf(']');
            if (end > 0) {
                try {
                    int idx = Integer.parseInt(displayName.substring(1, end));
                    List<Long> seeds = plugin.getSeedHistory().list();
                    if (idx >= 0 && idx < seeds.size()) {
                        long seed = seeds.get(idx);
                        String ctx = awaitingSeedForWorld.remove(p.getUniqueId());
                        String base = selectedBase.getOrDefault(p.getUniqueId(), baseName(p.getWorld().getName()));
                        EnumSet<ResetService.Dimension> dims = EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END);
                        if (ctx != null && ctx.contains(";")) {
                            String[] parts = ctx.split(";", 2);
                            base = parts[0];
                            if (parts.length > 1 && !parts[1].isEmpty()) {
                                dims = EnumSet.noneOf(ResetService.Dimension.class);
                                for (String s : parts[1].split(",")) dims.add(ResetService.Dimension.valueOf(s));
                            }
                        }
                        p.closeInventory();
                        resetService.startResetWithCountdown(p, base, Optional.of(seed), dims);
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private void handleMessagesClick(Player p, String displayName) {
        if (displayName.equalsIgnoreCase("Back")) { openSettings(p); return; }
        String path = "messages." + displayName;
        awaitingConfigPath.put(p.getUniqueId(), path);
        Messages.send(p, "&eType new text in chat. Color codes use &.");
    }

    // ------------- Settings helpers -------------

    private void handleSettingsClick(Player p, String dn, org.bukkit.event.inventory.ClickType click) {
        if (dn.equalsIgnoreCase("Back")) { openMain(p); return; }
        if (dn.equalsIgnoreCase("Preload Enabled")) { flip(p, "preload.enabled"); return; }
        if (dn.equalsIgnoreCase("Auto-Disable on Low TPS")) { flip(p, "preload.autoDisableHighLag"); return; }
        if (dn.startsWith("TPS Threshold")) { adjustDouble(p, "preload.tpsThreshold", click, 0.5, 0.0, 20.0); return; }
        if (dn.startsWith("Countdown Seconds")) { adjustInt(p, "countdown.seconds", click, 1, 1, 600); return; }
        if (dn.equalsIgnoreCase("Broadcast Countdown To All")) { flip(p, "countdown.broadcastToAll"); return; }
        if (dn.equalsIgnoreCase("Require Confirmation")) { flip(p, "confirmation.requireConfirm"); return; }
        if (dn.startsWith("Confirm Timeout")) { adjustInt(p, "confirmation.timeoutSeconds", click, 1, 1, 300); return; }
        if (dn.equalsIgnoreCase("Console Bypasses Confirm")) { flip(p, "confirmation.consoleBypasses"); return; }
        if (dn.equalsIgnoreCase("Use Same Seed For All Dims")) { flip(p, "seeds.useSameSeedForAllDimensions"); return; }
        if (dn.equalsIgnoreCase("Return Players To New Spawn")) { flip(p, "players.returnToNewSpawnAfterReset"); return; }
        if (dn.equalsIgnoreCase("Force Respawn To New World")) { flip(p, "players.forceRespawnToNewOverworld"); return; }
        if (dn.startsWith("Respawn Window")) { adjustInt(p, "players.respawnRecentWindowSeconds", click, 10, 0, 3600); return; }
        if (dn.equalsIgnoreCase("Fresh Start On Reset")) { flip(p, "players.freshStartOnReset"); return; }
        if (dn.startsWith("Fallback World")) { cycleFallback(p, click); return; }
        if (dn.startsWith("Max Online For Reset")) { adjustInt(p, "limits.maxOnlineForReset", click, 1, -1, 500); return; }
        if (dn.equalsIgnoreCase("Backups Enabled")) { flip(p, "backups.enabled"); return; }
        if (dn.startsWith("Backups Per Base")) { adjustInt(p, "backups.maxPerBase", click, 1, 0, 500); return; }
        if (dn.startsWith("Backups Max Total")) { adjustInt(p, "backups.maxTotal", click, 1, 0, 1000); return; }
        if (dn.startsWith("Backups Max Age")) { adjustInt(p, "backups.maxAgeDays", click, 1, 0, 3650); return; }
        if (dn.startsWith("PruneNow Keep Per Base")) { adjustInt(p, "backups.pruneNowKeepPerBase", click, 1, 0, 50); return; }
        if (dn.equalsIgnoreCase("Edit Messages")) { openMessagesEditor(p); }
    }

    private void flip(Player p, String path) {
        boolean cur = plugin.getConfig().getBoolean(path);
        plugin.getConfig().set(path, !cur);
        plugin.saveConfig();
        openSettings(p);
    }

    private void adjustInt(Player p, String path, org.bukkit.event.inventory.ClickType click, int step, int min, int max) {
        int cur = plugin.getConfig().getInt(path);
        int delta = (click.isLeftClick() ? -step : step) * (click.isShiftClick() ? 10 : 1);
        int val = Math.max(min, Math.min(max, cur + delta));
        plugin.getConfig().set(path, val);
        plugin.saveConfig();
        openSettings(p);
    }

    private void adjustDouble(Player p, String path, org.bukkit.event.inventory.ClickType click, double step, double min, double max) {
        double cur = plugin.getConfig().getDouble(path);
        double delta = (click.isLeftClick() ? -step : step);
        double val = Math.max(min, Math.min(max, Math.round((cur + delta) * 10.0) / 10.0));
        plugin.getConfig().set(path, val);
        plugin.saveConfig();
        openSettings(p);
    }

    private void cycleFallback(Player p, org.bukkit.event.inventory.ClickType click) {
        if (click.isShiftClick()) { awaitingConfigPath.put(p.getUniqueId(), "teleport.fallbackWorldName"); Messages.send(p, "&eType a world name in chat, or 'none'."); return; }
        List<String> bases = Bukkit.getWorlds().stream().map(World::getName).map(GuiManager::baseName).distinct().sorted().collect(Collectors.toList());
        String current = plugin.getConfig().getString("teleport.fallbackWorldName", "");
        int idx = bases.indexOf(current);
        idx = click.isLeftClick() ? (idx <= 0 ? bases.size() - 1 : idx - 1) : (idx + 1) % (bases.isEmpty() ? 1 : bases.size());
        String next = bases.isEmpty() ? "" : bases.get(Math.max(0, idx));
        plugin.getConfig().set("teleport.fallbackWorldName", next);
        plugin.saveConfig();
        openSettings(p);
    }

    private void openMessagesEditor(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.MESSAGES, TextComponents.aqua("Messages"));
        Inventory inv = Bukkit.createInventory(holder, 27, TextComponents.aqua("Messages"));
        holder.setInventory(inv);
        inv.setItem(10, namedComponent(Material.PAPER, TextComponents.white("noPermission"), TextComponents.gray(plugin.getConfig().getString("messages.noPermission", ""))));
        inv.setItem(11, namedComponent(Material.PAPER, TextComponents.white("confirmationWarning"), TextComponents.gray("Click to edit")));
        inv.setItem(12, namedComponent(Material.PAPER, TextComponents.white("confirmationHowTo"), TextComponents.gray("Click to edit")));
        inv.setItem(13, namedComponent(Material.PAPER, TextComponents.white("countdownTitle"), TextComponents.gray(plugin.getConfig().getString("messages.countdownTitle", ""))));
        inv.setItem(14, namedComponent(Material.PAPER, TextComponents.white("countdownSubtitle"), TextComponents.gray(plugin.getConfig().getString("messages.countdownSubtitle", ""))));
        inv.setItem(22, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
        p.openInventory(inv);
    }

    // ------------- Chat callbacks -------------

    private void onChatMessage(VersionCompat.ChatMessage msg) {
        UUID id = msg.getPlayer().getUniqueId();
        String seedCtx = awaitingSeedForWorld.get(id);
        if (seedCtx != null) {
            String text = msg.getMessageText().trim();
            Optional<Long> seed = Optional.empty();
            if (!text.equalsIgnoreCase("random")) {
                try { seed = Optional.of(Long.parseLong(text)); } catch (NumberFormatException ex) { Messages.send(msg.getPlayer(), "&cInvalid seed. Type a number or 'random'."); return; }
            }
            awaitingSeedForWorld.remove(id);
            String base = seedCtx;
            EnumSet<ResetService.Dimension> dims = selectedDims.getOrDefault(id, EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END));
            if (seedCtx.contains(";")) {
                String[] parts = seedCtx.split(";", 2);
                base = parts[0];
                if (parts.length > 1 && parts[1] != null && !parts[1].isEmpty()) {
                    try { EnumSet<ResetService.Dimension> parsed = EnumSet.noneOf(ResetService.Dimension.class); for (String s : parts[1].split(",")) parsed.add(ResetService.Dimension.valueOf(s)); dims = parsed; } catch (Exception ignored) {}
                }
            }
            Player p = msg.getPlayer();
            final String baseFinal = base; final EnumSet<ResetService.Dimension> dimsFinal = EnumSet.copyOf(dims); final Optional<Long> seedFinal = seed;
            Bukkit.getScheduler().runTask(plugin, () -> resetService.startResetWithCountdown(p, baseFinal, seedFinal, dimsFinal));
            return;
        }
        String path = awaitingConfigPath.get(id);
        if (path != null) {
            String text = msg.getMessageText().trim();
            if (path.equals("teleport.fallbackWorldName") && text.equalsIgnoreCase("none")) text = "";
            plugin.getConfig().set(path, text);
            plugin.saveConfig();
            awaitingConfigPath.remove(id);
            Bukkit.getScheduler().runTask(plugin, () -> openSettings(msg.getPlayer()));
        }
    }

    private void toggleDim(Player p, String base, EnumSet<ResetService.Dimension> dims, ResetService.Dimension d) {
        if (dims.contains(d)) dims.remove(d); else dims.add(d);
        if (dims.isEmpty()) dims.add(ResetService.Dimension.OVERWORLD);
        openResetOptions(p, base);
    }
}
                                        dims = EnumSet.noneOf(ResetService.Dimension.class);
                                        for (String s : parts[1].split(",")) dims.add(ResetService.Dimension.valueOf(s));
                                    }
                                    resetService.startResetWithCountdown(p, base, Optional.of(seed), dims);
                                }
                            }
                        } catch (NumberFormatException ignored) {}
                    }
                }
            }

            // -- GUI builders --
            public void openMain(Player p) {
                GuiHolder holder = new GuiHolder(GuiHolder.Type.MAIN, TITLE_MAIN);
                Inventory inv = Bukkit.createInventory(holder, 27, TITLE_MAIN);
                holder.setInventory(inv);
                inv.setItem(11, namedComponent(Material.GRASS_BLOCK, TextComponents.green("Reset Worlds"), TextComponents.gray("Pick a world and choose")));
                inv.setItem(15, namedComponent(Material.CHEST, TextComponents.gold("Backups"), TextComponents.gray("Browse and restore backups")));
                inv.setItem(13, namedComponent(Material.COMPARATOR, TextComponents.blue("Settings"), TextComponents.gray("Change plugin options")));
                p.openInventory(inv);
            }

            public void openWorldSelect(Player p) {
                String base = baseName(p.getWorld().getName());
                GuiHolder holder = new GuiHolder(GuiHolder.Type.SELECT, TITLE_SELECT);
                Inventory inv = Bukkit.createInventory(holder, 27, TITLE_SELECT);
                holder.setInventory(inv);
                inv.setItem(13, namedComponent(Material.GRASS_BLOCK, TextComponents.green(base), TextComponents.gray("Click to configure reset")));
                inv.setItem(22, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
                p.openInventory(inv);
            }

            private void openResetOptions(Player p, String base) {
                selectedBase.put(p.getUniqueId(), base);
                selectedDims.putIfAbsent(p.getUniqueId(), EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END));
                EnumSet<ResetService.Dimension> dims = selectedDims.get(p.getUniqueId());
                GuiHolder holder = new GuiHolder(GuiHolder.Type.RESET_OPTIONS, Component.empty().append(TITLE_RESET_FOR).append(Component.text(base)));
                Inventory inv = Bukkit.createInventory(holder, 45, holder.getTitle());
                holder.setInventory(inv);
                inv.setItem(10, toggleItem(dims.contains(ResetService.Dimension.OVERWORLD), Material.GRASS_BLOCK, "Overworld"));
                inv.setItem(12, toggleItem(dims.contains(ResetService.Dimension.NETHER), Material.NETHERRACK, "Nether"));
                inv.setItem(14, toggleItem(dims.contains(ResetService.Dimension.END), Material.END_STONE, "The End"));
                inv.setItem(22, namedComponent(Material.LIME_WOOL, TextComponents.green("Reset Selected (Random Seed)")));
                inv.setItem(24, namedComponent(Material.CYAN_WOOL, TextComponents.blue("Reset Selected (Custom Seed)")));
                inv.setItem(40, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
                p.openInventory(inv);
            }

            private void openBackups(Player p) {
                GuiHolder holder = new GuiHolder(GuiHolder.Type.BACKUPS, TITLE_BACKUPS);
                Inventory inv = Bukkit.createInventory(holder, 54, TITLE_BACKUPS);
                holder.setInventory(inv);
                List<BackupManager.BackupRef> refs = resetService.listBackups();
                LinkedHashSet<String> bases = new LinkedHashSet<>();
                for (var r : refs) bases.add(r.base());
                int hslot = 0;
                for (String b : bases) {
                    if (hslot > 8) break;
                    inv.setItem(hslot++, namedComponent(Material.LAVA_BUCKET, TextComponents.darkRed("Delete ALL " + b), TextComponents.gray("Click to confirm")));
                }
                int slot = 9;
                for (var ref : refs) {
                    if (slot >= 48) break;
                    Component label = Component.empty().append(TextComponents.gold(ref.base())).append(TextComponents.gray(" @ ")).append(TextComponents.yellow(ref.timestamp()));
                    List<Component> lore = new ArrayList<>();
                    lore.add(TextComponents.gray("Click to restore"));
                    ItemStack item = new ItemStack(Material.CHEST);
                    ItemMeta im = item.getItemMeta();
                    if (im != null) { im.displayName(label); im.lore(lore); item.setItemMeta(im); }
                    inv.setItem(slot++, item);
                }
                inv.setItem(49, namedComponent(Material.SHEARS, TextComponents.red("Prune Now")));
                inv.setItem(53, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
                p.openInventory(inv);
            }

            private void openBackupOptions(Player p, String base, String ts) {
                Component title = Component.empty().append(TITLE_BACKUP_OPTIONS).append(Component.text(base)).append(TextComponents.gray(" @ ")).append(Component.text(ts));
                GuiHolder holder = new GuiHolder(GuiHolder.Type.BACKUP_OPTIONS, title);
                Inventory inv = Bukkit.createInventory(holder, 27, title);
                holder.setInventory(inv);
                inv.setItem(10, namedComponent(Material.LIME_WOOL, TextComponents.green("Restore ALL")));
                inv.setItem(12, namedComponent(Material.GRASS_BLOCK, TextComponents.white("Restore Overworld")));
                inv.setItem(14, namedComponent(Material.NETHERRACK, TextComponents.white("Restore Nether")));
                inv.setItem(16, namedComponent(Material.END_STONE, TextComponents.white("Restore End")));
                inv.setItem(20, namedComponent(Material.TNT, TextComponents.red("Delete Backup")));
                inv.setItem(22, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
                p.openInventory(inv);
            }

            private void openDeleteConfirm(Player p, String base, String ts) {
                Component title = Component.empty().append(TITLE_DELETE_BACKUP).append(Component.text(base)).append(TextComponents.gray(" @ ")).append(Component.text(ts));
                GuiHolder holder = new GuiHolder(GuiHolder.Type.DELETE_BACKUP, title);
                Inventory inv = Bukkit.createInventory(holder, 27, title);
                holder.setInventory(inv);
                inv.setItem(11, namedComponent(Material.RED_CONCRETE, TextComponents.darkRed("Confirm Delete")));
                inv.setItem(15, namedComponent(Material.ARROW, TextComponents.yellow("Cancel")));
                p.openInventory(inv);
            }

            private void openDeleteAllConfirm(Player p, String base) {
                Component title = TextComponents.darkRed("Delete ALL | " + base);
                GuiHolder holder = new GuiHolder(GuiHolder.Type.DELETE_ALL, title);
                Inventory inv = Bukkit.createInventory(holder, 27, title);
                holder.setInventory(inv);
                inv.setItem(11, namedComponent(Material.RED_CONCRETE, TextComponents.darkRed("Confirm Delete All")));
                inv.setItem(15, namedComponent(Material.ARROW, TextComponents.yellow("Cancel")));
                p.openInventory(inv);
            }

            private void openSeedSelector(Player p, String base, EnumSet<ResetService.Dimension> dims) {
                GuiHolder holder = new GuiHolder(GuiHolder.Type.SEED_SELECTOR, TextComponents.darkPurple("Select Seed | ").append(Component.text(base)));
                Inventory inv = Bukkit.createInventory(holder, 27, holder.getTitle());
                holder.setInventory(inv);
                List<Long> seeds = plugin.getSeedHistory().list();
                int slot = 10;
                if (seeds.isEmpty()) inv.setItem(13, namedComponent(Material.PAPER, TextComponents.gray("No recent seeds")));
                else for (int i = 0; i < seeds.size() && slot < 17; i++) inv.setItem(slot++, namedComponent(Material.PAPER, TextComponents.white("[" + i + "] " + seeds.get(i)), TextComponents.gray("Click to use")));
                inv.setItem(22, namedComponent(Material.CYAN_WOOL, TextComponents.blue("Type Custom Seed")));
                inv.setItem(26, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
                awaitingSeedForWorld.put(p.getUniqueId(), base + ";" + dims.stream().map(Enum::name).collect(Collectors.joining(",")));
                p.openInventory(inv);
            }

            private void openSettings(Player p) {
                GuiHolder holder = new GuiHolder(GuiHolder.Type.SETTINGS, TITLE_SETTINGS);
                Inventory inv = Bukkit.createInventory(holder, 54, TITLE_SETTINGS);
                holder.setInventory(inv);
                boolean preload = plugin.getConfig().getBoolean("preload.enabled", true);
                boolean auto = plugin.getConfig().getBoolean("preload.autoDisableHighLag", true);
                double tps = plugin.getConfig().getDouble("preload.tpsThreshold", 18.0);
                int cdSec = plugin.getConfig().getInt("countdown.seconds", 10);
                boolean broadcast = plugin.getConfig().getBoolean("countdown.broadcastToAll", true);
                inv.setItem(0, toggleItem(preload, Material.RESPAWN_ANCHOR, "Preload Enabled"));
                inv.setItem(1, toggleItem(auto, Material.CLOCK, "Auto-Disable on Low TPS"));
                inv.setItem(2, namedComponent(Material.REDSTONE, TextComponents.white("TPS Threshold: " + tps)));
                inv.setItem(3, namedComponent(Material.REPEATER, TextComponents.white("Countdown Seconds: " + cdSec)));
                inv.setItem(4, toggleItem(broadcast, Material.BELL, "Broadcast Countdown To All"));
                inv.setItem(9, toggleItem(plugin.getConfig().getBoolean("confirmation.requireConfirm", true), Material.BOOK, "Require Confirmation"));
                inv.setItem(10, namedComponent(Material.CLOCK, TextComponents.white("Confirm Timeout: " + plugin.getConfig().getLong("confirmation.timeoutSeconds", 15) + "s")));
                inv.setItem(11, toggleItem(plugin.getConfig().getBoolean("confirmation.consoleBypasses", true), Material.COMMAND_BLOCK, "Console Bypasses Confirm"));
                inv.setItem(18, toggleItem(plugin.getConfig().getBoolean("seeds.useSameSeedForAllDimensions", true), Material.WHEAT_SEEDS, "Use Same Seed For All Dims"));
                inv.setItem(19, toggleItem(plugin.getConfig().getBoolean("players.returnToNewSpawnAfterReset", true), Material.LODESTONE, "Return Players To New Spawn"));
                inv.setItem(20, toggleItem(plugin.getConfig().getBoolean("players.forceRespawnToNewOverworld", true), Material.TOTEM_OF_UNDYING, "Force Respawn To New World"));
                inv.setItem(22, toggleItem(plugin.getConfig().getBoolean("players.freshStartOnReset", true), Material.MILK_BUCKET, "Fresh Start On Reset"));
                inv.setItem(27, namedComponent(Material.ENDER_PEARL, TextComponents.white("Fallback World: " + plugin.getConfig().getString("teleport.fallbackWorldName", ""))));
                inv.setItem(28, namedComponent(Material.PLAYER_HEAD, TextComponents.white("Max Online For Reset: " + plugin.getConfig().getInt("limits.maxOnlineForReset", -1))));
                inv.setItem(36, toggleItem(plugin.getConfig().getBoolean("backups.enabled", true), Material.SHULKER_BOX, "Backups Enabled"));
                inv.setItem(37, namedComponent(Material.BUNDLE, TextComponents.white("Backups Per Base: " + plugin.getConfig().getInt("backups.maxPerBase", 5))));
                inv.setItem(38, namedComponent(Material.CHEST, TextComponents.white("Backups Max Total: " + plugin.getConfig().getInt("backups.maxTotal", 50))));
                inv.setItem(39, namedComponent(Material.CLOCK, TextComponents.white("Backups Max Age: " + plugin.getConfig().getInt("backups.maxAgeDays", 30) + "d")));
                inv.setItem(40, namedComponent(Material.SHEARS, TextComponents.white("PruneNow Keep Per Base: " + plugin.getConfig().getInt("backups.pruneNowKeepPerBase", 2))));
                inv.setItem(45, namedComponent(Material.PAPER, TextComponents.aqua("Edit Messages")));
                inv.setItem(49, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
                p.openInventory(inv);
            }

            private void handleSettingsClick(Player p, String dn, org.bukkit.event.inventory.ClickType click) {
                if (dn.equalsIgnoreCase("Preload Enabled")) { flip(p, "preload.enabled"); return; }
                if (dn.equalsIgnoreCase("Auto-Disable on Low TPS")) { flip(p, "preload.autoDisableHighLag"); return; }
                if (dn.startsWith("TPS Threshold")) { adjustDouble(p, "preload.tpsThreshold", click, 0.5, 0.0, 20.0); return; }
                if (dn.startsWith("Countdown Seconds")) { adjustInt(p, "countdown.seconds", click, 1, 1, 600); return; }
                if (dn.equalsIgnoreCase("Broadcast Countdown To All")) { flip(p, "countdown.broadcastToAll"); return; }
                if (dn.equalsIgnoreCase("Require Confirmation")) { flip(p, "confirmation.requireConfirm"); return; }
                if (dn.startsWith("Confirm Timeout")) { adjustInt(p, "confirmation.timeoutSeconds", click, 1, 1, 300); return; }
                if (dn.equalsIgnoreCase("Console Bypasses Confirm")) { flip(p, "confirmation.consoleBypasses"); return; }
                if (dn.equalsIgnoreCase("Use Same Seed For All Dims")) { flip(p, "seeds.useSameSeedForAllDimensions"); return; }
                if (dn.equalsIgnoreCase("Return Players To New Spawn")) { flip(p, "players.returnToNewSpawnAfterReset"); return; }
                if (dn.equalsIgnoreCase("Force Respawn To New World")) { flip(p, "players.forceRespawnToNewOverworld"); return; }
                if (dn.startsWith("Respawn Window")) { adjustInt(p, "players.respawnRecentWindowSeconds", click, 10, 0, 3600); return; }
                if (dn.equalsIgnoreCase("Fresh Start On Reset")) { flip(p, "players.freshStartOnReset"); return; }
                if (dn.startsWith("Fallback World")) { cycleFallback(p, click); return; }
                if (dn.startsWith("Max Online For Reset")) { adjustInt(p, "limits.maxOnlineForReset", click, 1, -1, 500); return; }
                if (dn.equalsIgnoreCase("Backups Enabled")) { flip(p, "backups.enabled"); return; }
                if (dn.startsWith("Backups Per Base")) { adjustInt(p, "backups.maxPerBase", click, 1, 0, 500); return; }
                if (dn.startsWith("Backups Max Total")) { adjustInt(p, "backups.maxTotal", click, 1, 0, 1000); return; }
                if (dn.startsWith("Backups Max Age")) { adjustInt(p, "backups.maxAgeDays", click, 1, 0, 3650); return; }
                if (dn.startsWith("PruneNow Keep Per Base")) { adjustInt(p, "backups.pruneNowKeepPerBase", click, 1, 0, 50); return; }
                if (dn.equalsIgnoreCase("Edit Messages")) { openMessagesEditor(p); return; }
                if (dn.equalsIgnoreCase("Back")) { openMain(p); }
            }

            private void openMessagesEditor(Player p) {
                GuiHolder holder = new GuiHolder(GuiHolder.Type.MESSAGES, TextComponents.aqua("Messages"));
                Inventory inv = Bukkit.createInventory(holder, 27, TextComponents.aqua("Messages"));
                holder.setInventory(inv);
                inv.setItem(10, namedComponent(Material.PAPER, TextComponents.white("noPermission"), TextComponents.gray(plugin.getConfig().getString("messages.noPermission", ""))));
                inv.setItem(11, namedComponent(Material.PAPER, TextComponents.white("confirmationWarning"), TextComponents.gray("Click to edit")));
                inv.setItem(12, namedComponent(Material.PAPER, TextComponents.white("confirmationHowTo"), TextComponents.gray("Click to edit")));
                inv.setItem(13, namedComponent(Material.PAPER, TextComponents.white("countdownTitle"), TextComponents.gray(plugin.getConfig().getString("messages.countdownTitle", ""))));
                inv.setItem(14, namedComponent(Material.PAPER, TextComponents.white("countdownSubtitle"), TextComponents.gray(plugin.getConfig().getString("messages.countdownSubtitle", ""))));
                inv.setItem(22, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
                p.openInventory(inv);
            }

            private void flip(Player p, String path) { boolean cur = plugin.getConfig().getBoolean(path); plugin.getConfig().set(path, !cur); plugin.saveConfig(); openSettings(p); }

            private void adjustInt(Player p, String path, org.bukkit.event.inventory.ClickType click, int step, int min, int max) { int cur = plugin.getConfig().getInt(path); int delta = (click.isLeftClick()? -step : step) * (click.isShiftClick()? 10 : 1); int val = Math.max(min, Math.min(max, cur + delta)); plugin.getConfig().set(path, val); plugin.saveConfig(); openSettings(p); }

            private void adjustDouble(Player p, String path, org.bukkit.event.inventory.ClickType click, double step, double min, double max) { double cur = plugin.getConfig().getDouble(path); double delta = (click.isLeftClick()? -step : step); double val = Math.max(min, Math.min(max, Math.round((cur + delta)*10.0)/10.0)); plugin.getConfig().set(path, val); plugin.saveConfig(); openSettings(p); }

            private void cycleFallback(Player p, org.bukkit.event.inventory.ClickType click) {
                if (click.isShiftClick()) { awaitingConfigPath.put(p.getUniqueId(), "teleport.fallbackWorldName"); Messages.send(p, "&eType a world name in chat, or 'none'."); return; }
                List<String> bases = Bukkit.getWorlds().stream().map(World::getName).map(GuiManager::baseName).distinct().sorted().collect(Collectors.toList());
                String current = plugin.getConfig().getString("teleport.fallbackWorldName", "");
                int idx = bases.indexOf(current);
                if (click.isLeftClick()) idx = (idx <= 0 ? bases.size()-1 : idx-1); else idx = (idx+1) % (bases.isEmpty()?1:bases.size());
                String next = bases.isEmpty()? "" : bases.get(Math.max(0, idx));
                plugin.getConfig().set("teleport.fallbackWorldName", next);
                plugin.saveConfig();
                openSettings(p);
            }

            private void onChatMessage(VersionCompat.ChatMessage msg) {
                UUID id = msg.getPlayer().getUniqueId();
                String base = awaitingSeedForWorld.get(id);
                if (base != null) {
                    String text = msg.getMessageText().trim();
                    Optional<Long> seed = Optional.empty();
                    if (!text.equalsIgnoreCase("random")) {
                        try { seed = Optional.of(Long.parseLong(text)); } catch (NumberFormatException ex) { Messages.send(msg.getPlayer(), "&cInvalid seed. Type a number or 'random'."); return; }
                    }
                    awaitingSeedForWorld.remove(id);
                    String ctx = base; String finalBase = ctx;
                    EnumSet<ResetService.Dimension> dims = selectedDims.getOrDefault(id, EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END));
                    if (ctx.contains(";")) { String[] parts = ctx.split(";", 2); finalBase = parts[0]; if (parts.length > 1 && parts[1] != null && !parts[1].isEmpty()) { try { EnumSet<ResetService.Dimension> parsed = EnumSet.noneOf(ResetService.Dimension.class); for (String s : parts[1].split(",")) parsed.add(ResetService.Dimension.valueOf(s)); dims = parsed; } catch (Exception ignored) {} } }
                    Player p = msg.getPlayer(); final EnumSet<ResetService.Dimension> dimsForTask = EnumSet.copyOf(dims); final Optional<Long> seedForTask = seed; final String baseForTask = finalBase; Bukkit.getScheduler().runTask(plugin, () -> resetService.startResetWithCountdown(p, baseForTask, seedForTask, dimsForTask));
                    return;
                }
                String path = awaitingConfigPath.get(id);
                if (path != null) {
                    String text = msg.getMessageText().trim(); if (path.equals("teleport.fallbackWorldName") && text.equalsIgnoreCase("none")) text = ""; plugin.getConfig().set(path, text); plugin.saveConfig(); awaitingConfigPath.remove(id); Bukkit.getScheduler().runTask(plugin, () -> openSettings(msg.getPlayer()));
                }
            }

            private void toggleDim(Player p, String base, EnumSet<ResetService.Dimension> dims, ResetService.Dimension d) { if (dims.contains(d)) dims.remove(d); else dims.add(d); if (dims.isEmpty()) dims.add(ResetService.Dimension.OVERWORLD); openResetOptions(p, base); }

            // human readable helpers
            private static String human(long bytes) { String[] u = {"B","KB","MB","GB","TB"}; double b = bytes; int i=0; while (b>=1024 && i<u.length-1){ b/=1024; i++; } return String.format(java.util.Locale.US, "%.1f %s", b, u[i]); }
            private static String formatDuration(long sec) { long h = sec/3600; long m = (sec%3600)/60; long s = sec%60; if (h>0) return String.format(java.util.Locale.US, "%dh %dm", h, m); if (m>0) return String.format(java.util.Locale.US, "%dm %ds", m, s); return s + "s"; }

        }
        p.openInventory(inv);
    }

    private void openDeleteAllConfirm(Player p, String base) {
    Component title = TextComponents.darkRed("Delete ALL | " + base);
    GuiHolder holder = new GuiHolder(GuiHolder.Type.DELETE_ALL, title);
    Inventory inv = Bukkit.createInventory(holder, 27, title);
    holder.setInventory(inv);
    inv.setItem(11, namedComponent(Material.RED_CONCRETE, TextComponents.darkRed("Confirm Delete All")));
    inv.setItem(15, namedComponent(Material.ARROW, TextComponents.yellow("Cancel")));
        p.openInventory(inv);
    }

    @EventHandler(priority = EventPriority.LOWEST) 
    public void onClick(InventoryClickEvent e) {
        // First check if this is our GUI and the clicker is a player
        if (!(e.getWhoClicked() instanceof Player)) {
            return;
        }
        Player p = (Player) e.getWhoClicked();
        
        if (!(e.getView().getTopInventory().getHolder() instanceof GuiHolder)) {
            return;
        }
        GuiHolder holder = (GuiHolder) e.getView().getTopInventory().getHolder();
        
        // IMMEDIATELY cancel ALL clicks in our GUI inventory
        e.setCancelled(true);
            
        // Get clicked item details
        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta()) {
            return;
        }
            
        ItemMeta meta = it.getItemMeta();
        if (meta == null || !meta.hasDisplayName()) {
            return;
        }
            
        Component displayNameComp = meta.displayName();
        if (displayNameComp == null) {
            return;
        }
            
        String displayName = PlainTextComponentSerializer.plainText().serialize(displayNameComp);
        GuiHolder.Type type = holder.getType();
            
        // Log interaction for debugging
        plugin.getLogger().info(String.format(
            "GUI Click - Player: %s, Type: %s, Item: %s, Slot: %d, Click: %s",
            p.getName(), type, displayName, e.getSlot(), e.getClick()
        ));
        
        switch (type) {
            case MAIN:
                switch (displayName.toLowerCase()) {
                    case "reset worlds":
                        openWorldSelect(p);
                        break;
                    case "backups":
                        openBackups(p);
                        break;
                    case "settings":
                        openSettings(p);
                        break;
                }
                break;
                
            case SELECT:
                if (displayName.equalsIgnoreCase("back")) {
                    openMain(p);
                } else {
                    openResetOptions(p, displayName);
                }
                break;
                
            case SETTINGS:
                handleSettingsClick(p, displayName, e.getClick());
                break;
                break;
            case RESET_OPTIONS:
                {
                    String base = selectedBase.get(p.getUniqueId());
                    if (base == null) return;
                    
                    EnumSet<ResetService.Dimension> dims = selectedDims.computeIfAbsent(
                        p.getUniqueId(), 
                        k -> EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END)
                    );
                    
                    switch (displayName) {
                    case "Overworld":
                        toggleDim(p, base, dims, ResetService.Dimension.OVERWORLD);
                        break;
                    case "Nether":
                        toggleDim(p, base, dims, ResetService.Dimension.NETHER);
                        break;
                    case "The End":
                        toggleDim(p, base, dims, ResetService.Dimension.END);
                        break;
                    case "Back":
                        openWorldSelect(p);
                        break;
                    case "Reset Selected (Random Seed)":
                        if (dims.isEmpty()) {
                            Messages.send(p, "&cNo dimensions selected!");
                            return;
                        }
                        p.closeInventory();
                        resetService.startReset(p, base, dims);
                        break;
                    case "Reset Selected (Custom Seed)":
                        if (dims.isEmpty()) {
                            Messages.send(p, "&cNo dimensions selected!");
                            return;
                        }
                        p.closeInventory();
                        // open seed selector GUI
                        openSeedSelector(p, base, dims);
                        break;
                }
            }
            case MESSAGES:
                if (displayName.equalsIgnoreCase("Back")) {
                    openSettings(p);
                } else {
                    String path = "messages." + displayName;
                    awaitingConfigPath.put(p.getUniqueId(), path);
                    Messages.send(p, "&eType new text in chat. Color codes use &.");
                }
                break;
                
            case BACKUPS:
                if (displayName.equalsIgnoreCase("Back")) {
                    openMain(p);
                    return;
                }
                if (displayName.equalsIgnoreCase("Prune Now")) {
                    p.performCommand("betterreset prune --confirm");
                    return;
                }
                
                if (displayName.startsWith("Delete ALL ")) {
                    String base = displayName.substring("Delete ALL ".length());
                    openDeleteAllConfirm(p, base);
                    return;
                }
                
                // Try to parse backup info from lore
                List<Component> lore = meta.lore();
                if (lore != null) {
                    String backupId = null;
                    for (Component line : lore) {
                        String plainLine = PlainTextComponentSerializer.plainText().serialize(line);
                        if (plainLine.startsWith("ID:")) {
                            backupId = plainLine.substring(3);
                            break;
                        }
                    }
                    if (backupId != null) {
                        String[] parts = backupId.split("[|]");
                        if (parts.length == 2) {
                            openBackupOptions(p, parts[0], parts[1]);
                        }
                    }
                }
            }
            case BACKUP_OPTIONS:
                {
                    String base = selectedBase.get(p.getUniqueId());
                    String ts = meta.getPersistentDataContainer()
                        .get(new NamespacedKey(plugin, "backup_timestamp"), 
                             PersistentDataType.STRING);
                             
                    if (base == null || ts == null) return;
                
                switch (displayName) {
                    case "Back":
                        openBackups(p);
                        break;
                    case "Restore ALL":
                        p.closeInventory();
                        p.performCommand("betterreset restore " + base + " " + ts);
                        break;
                    case "Restore Overworld":
                        p.closeInventory();
                        p.performCommand("betterreset restore " + base + " " + ts + " ow");
                        break;
                    case "Restore Nether":
                        p.closeInventory();
                        p.performCommand("betterreset restore " + base + " " + ts + " nether");
                        break;
                    case "Restore End":
                        p.closeInventory();
                        p.performCommand("betterreset restore " + base + " " + ts + " end");
                        break;
                    case "Delete Backup":
                        openDeleteConfirm(p, base, ts);
                        break;
                }
            }
            case DELETE_BACKUP:
                {
                    String base = selectedBase.get(p.getUniqueId());
                    String ts = meta.getPersistentDataContainer()
                        .get(new NamespacedKey(plugin, "backup_timestamp"), 
                             PersistentDataType.STRING);
                             
                    if (base == null || ts == null) return;
                    
                    switch (displayName) {
                    case "Confirm Delete":
                        p.closeInventory();
                        p.performCommand("betterreset deletebackup " + base + " " + ts + " --confirm");
                        break;
                    case "Cancel":
                        openBackupOptions(p, base, ts);
                        break;
                }
            }
            case DELETE_ALL:
                {
                    String base = selectedBase.get(p.getUniqueId());
                    if (base == null) return;
                
                if (displayName.equalsIgnoreCase("Cancel")) {
                    openBackups(p);
                } else if (displayName.equalsIgnoreCase("Confirm Delete All")) {
                    p.closeInventory();
                    p.performCommand("betterreset deleteallbackups " + base + " --confirm");
                }
                break;
            case SEED_SELECTOR:
                {
                    // Expect item display like [index] seed
                    if (displayName.equalsIgnoreCase("Back")) { 
                        openResetOptions(p, selectedBase.getOrDefault(p.getUniqueId(), baseName(p.getWorld().getName()))); 
                        break;
                    }
                    if (displayName.equalsIgnoreCase("Type Custom Seed")) {
                        p.closeInventory();
                        Messages.send(p, "&eType a &lseed number&re in chat, or type 'random'.");
                        // awaitingSeedForWorld already set by openSeedSelector; ensure it's present
                        break;
                    }
                    // try parse index from displayName starting with [idx]
                    if (displayName.startsWith("[")) {
                        int end = displayName.indexOf(']');
                        if (end > 0) {
                            String idxStr = displayName.substring(1, end);
                            try {
                                int idx = Integer.parseInt(idxStr);
                                List<Long> seeds = plugin.getSeedHistory().list();
                                if (idx >= 0 && idx < seeds.size()) {
                                    long seed = seeds.get(idx);
                                    p.closeInventory();
                                    // parse dims and base from awaitingSeedForWorld mapping
                                    String ctx = awaitingSeedForWorld.remove(p.getUniqueId());
                                    if (ctx != null) {
                                        String[] parts = ctx.split(";", 2);
                                        String base = parts[0];
                                        EnumSet<ResetService.Dimension> dims = EnumSet.of(ResetService.Dimension.OVERWORLD);
                                        if (parts.length > 1) {
                                            dims = EnumSet.noneOf(ResetService.Dimension.class);
                                            for (String s : parts[1].split(",")) dims.add(ResetService.Dimension.valueOf(s));
                                        }
                                        resetService.startResetWithCountdown(p, base, Optional.of(seed), dims);
                                    }
                                }
                            } catch (NumberFormatException ignored) {}
                        }
                    }
                    break;
                }
            }
        }
        Component invTitle = holder.getTitle();
        if (TextExtractor.titleStartsWith(invTitle, TITLE_RESET_FOR)) {
            e.setCancelled(true);
            String base = TextExtractor.getText(invTitle).substring(TextExtractor.getText(TITLE_RESET_FOR).length());
            EnumSet<ResetService.Dimension> dims = selectedDims.computeIfAbsent(p.getUniqueId(), k -> EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END));
            
                String itemDisplayName = TextExtractor.getDisplayName(it.getItemMeta());
                switch (itemDisplayName) {
                case "Overworld" -> toggleDim(p, base, dims, ResetService.Dimension.OVERWORLD);
                case "Nether" -> toggleDim(p, base, dims, ResetService.Dimension.NETHER);
                case "The End" -> toggleDim(p, base, dims, ResetService.Dimension.END);
                    case "Reset Selected (Random Seed)" -> {
                    p.closeInventory();
                    resetService.startReset(p, base, dims);
                }
                case "Reset Selected (Custom Seed)" -> {
                    p.closeInventory();
                    awaitingSeedForWorld.put(p.getUniqueId(), base);
                    Messages.send(p, "&eType a &lseed number&re in chat, or type 'random'.");
                }
                case "Back" -> openWorldSelect(p);
            }
            return;
        }
    if (TextExtractor.titleMatches(invTitle, TITLE_BACKUPS)) {
            e.setCancelled(true);
            
            if (displayName.equalsIgnoreCase("Back")) { openMain(p); return; }
            if (displayName.equalsIgnoreCase("Prune Now")) {
                if (!p.hasPermission("betterreset.prune")) { 
                    Messages.send(p, plugin.getConfig().getString("messages.noPermission")); 
                    return; 
                }
                p.closeInventory();
                resetService.pruneBackupsAsync(p, Optional.empty(), true);
                return;
            }
            if (displayName.startsWith("Delete ALL ")) {
                String base = displayName.substring("Delete ALL ".length());
                if (!p.hasPermission("betterreset.backups")) { 
                    Messages.send(p, plugin.getConfig().getString("messages.noPermission")); 
                    return; 
                }
                openDeleteAllConfirm(p, base);
                return;
            }
            // Expect format: base @ timestamp
            String base = null, ts = null;
            List<String> lore = TextExtractor.getLoreText(it.getItemMeta());
            for (String line : lore) {
                if (line.startsWith("ID:")) {
                    String[] ids = line.substring(3).split("\\|", 2);
                    if (ids.length == 2) { 
                        base = ids[0]; 
                        ts = ids[1]; 
                        break; 
                    }
                }
            }
            if (base == null || ts == null) {
                String[] parts = displayName.split(" @ ", 2);
                if (parts.length == 2) { 
                    base = parts[0]; 
                    ts = parts[1]; 
                }
            }
            if (base != null && ts != null) openBackupOptions(p, base, ts);
            return;
        }
        if (TextExtractor.titleStartsWith(invTitle, TITLE_BACKUP_OPTIONS)) {
            e.setCancelled(true);
            String raw = TextExtractor.getText(invTitle).substring(TextExtractor.getText(TITLE_BACKUP_OPTIONS).length()); // base @ ts
            String[] parts = raw.split(" @ ", 2);
            if (parts.length != 2) { openBackups(p); return; }
            String base = parts[0];
            String ts = parts[1];
            String dn = TextExtractor.getDisplayName(it.getItemMeta());
            switch (dn) {
                case "Back" -> openBackups(p);
                case "Restore ALL" -> { p.closeInventory(); resetService.restoreBackupAsync(p, base, ts); }
                case "Restore Overworld" -> { p.closeInventory(); resetService.restoreBackupAsync(p, base, ts, EnumSet.of(ResetService.Dimension.OVERWORLD)); }
                case "Restore Nether" -> { p.closeInventory(); resetService.restoreBackupAsync(p, base, ts, EnumSet.of(ResetService.Dimension.NETHER)); }
                case "Restore End" -> { p.closeInventory(); resetService.restoreBackupAsync(p, base, ts, EnumSet.of(ResetService.Dimension.END)); }
                case "Delete Backup" -> {
                    if (!p.hasPermission("betterreset.backups")) { Messages.send(p, plugin.getConfig().getString("messages.noPermission")); }
                    else { openDeleteConfirm(p, base, ts); }
                }
                case "Delete ALL for Base" -> {
                    if (!p.hasPermission("betterreset.backups")) { Messages.send(p, plugin.getConfig().getString("messages.noPermission")); }
                    else { openDeleteAllConfirm(p, base); }
                }
            }
            return;
        }
    if (TextExtractor.titleStartsWith(invTitle, TITLE_DELETE_BACKUP)) {
            e.setCancelled(true);
            String raw = TextExtractor.getText(invTitle).substring(TextExtractor.getText(TITLE_DELETE_BACKUP).length()); // base @ ts
            String[] parts = raw.split(" @ ", 2);
            if (parts.length != 2) { openBackups(p); return; }
            String base = parts[0];
            String ts = parts[1];
            switch (displayName) {
                case "Confirm Delete" -> { p.closeInventory(); resetService.deleteBackupAsync(p, base, ts); }
                case "Cancel" -> openBackupOptions(p, base, ts);
            }
            return;
        }
        if (TextExtractor.titleStartsWith(invTitle, TextComponents.darkRed("Delete ALL | "))) {
            e.setCancelled(true);
            String base = TextExtractor.getText(invTitle).substring(TextExtractor.getText(TextComponents.darkRed("Delete ALL | ")).length());
            switch (displayName) {
                case "Confirm Delete All" -> { p.closeInventory(); resetService.deleteAllBackupsForBaseAsync(p, base); }
                case "Cancel" -> openBackups(p);
            }
            return;
        }
    }

    private void openSeedSelector(Player p, String base, EnumSet<ResetService.Dimension> dims) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.SEED_SELECTOR, TextComponents.darkPurple("Select Seed | ").append(Component.text(base)));
        Inventory inv = Bukkit.createInventory(holder, 27, holder.getTitle());
        holder.setInventory(inv);
        List<Long> seeds = plugin.getSeedHistory().list();
        int slot = 10;
        if (seeds.isEmpty()) {
            inv.setItem(13, namedComponent(Material.PAPER, TextComponents.gray("No recent seeds")));
        } else {
            for (int i = 0; i < seeds.size() && slot < 17; i++) {
                long s = seeds.get(i);
                inv.setItem(slot++, namedComponent(Material.PAPER, TextComponents.white("[" + i + "] " + s), TextComponents.gray("Click to use")));
            }
        }
        inv.setItem(22, namedComponent(Material.CYAN_WOOL, TextComponents.blue("Type Custom Seed"), TextComponents.gray("Opens chat to type")));
        inv.setItem(26, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
        // store context for awaiting seed entry
        awaitingSeedForWorld.put(p.getUniqueId(), base + ";" + dims.stream().map(Enum::name).collect(Collectors.joining(",")));
        p.openInventory(inv);
    }

    private void openSettings(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.SETTINGS, TITLE_SETTINGS);
        Inventory inv = Bukkit.createInventory(holder, 54, TITLE_SETTINGS);
        holder.setInventory(inv);
        // Row 1: Preload + Countdown
        boolean preload = plugin.getConfig().getBoolean("preload.enabled", true);
        boolean auto = plugin.getConfig().getBoolean("preload.autoDisableHighLag", true);
        double tps = plugin.getConfig().getDouble("preload.tpsThreshold", 18.0);
        int cdSec = plugin.getConfig().getInt("countdown.seconds", 10);
        boolean broadcast = plugin.getConfig().getBoolean("countdown.broadcastToAll", true);
    inv.setItem(0, toggleItem(preload, Material.RESPAWN_ANCHOR, "Preload Enabled"));
    inv.setItem(1, toggleItem(auto, Material.CLOCK, "Auto-Disable on Low TPS"));
    inv.setItem(2, namedComponent(Material.REDSTONE, TextComponents.white("TPS Threshold: " + tps), TextComponents.gray("Left -0.5 | Right +0.5")));
    inv.setItem(3, namedComponent(Material.REPEATER, TextComponents.white("Countdown Seconds: " + cdSec), TextComponents.gray("Left -1 | Right +1 | Shift ±10")));
    inv.setItem(4, toggleItem(broadcast, Material.BELL, "Broadcast Countdown To All"));

        // Row 2: Confirmation
        boolean req = plugin.getConfig().getBoolean("confirmation.requireConfirm", true);
        long timeout = plugin.getConfig().getLong("confirmation.timeoutSeconds", 15);
        boolean consoleBy = plugin.getConfig().getBoolean("confirmation.consoleBypasses", true);
    inv.setItem(9, toggleItem(req, Material.BOOK, "Require Confirmation"));
    inv.setItem(10, namedComponent(Material.CLOCK, TextComponents.white("Confirm Timeout: " + timeout + "s"), TextComponents.gray("Left -1 | Right +1 | Shift ±5")));
    inv.setItem(11, toggleItem(consoleBy, Material.COMMAND_BLOCK, "Console Bypasses Confirm"));

        // Row 3: Seeds/Players
        boolean sameSeed = plugin.getConfig().getBoolean("seeds.useSameSeedForAllDimensions", true);
        boolean returnSpawn = plugin.getConfig().getBoolean("players.returnToNewSpawnAfterReset", true);
        boolean forceRespawn = plugin.getConfig().getBoolean("players.forceRespawnToNewOverworld", true);
        int respawnWindow = plugin.getConfig().getInt("players.respawnRecentWindowSeconds", 600);
        boolean fresh = plugin.getConfig().getBoolean("players.freshStartOnReset", true);
        inv.setItem(18, toggleItem(sameSeed, Material.WHEAT_SEEDS, "Use Same Seed For All Dims"));
        inv.setItem(19, toggleItem(returnSpawn, Material.LODESTONE, "Return Players To New Spawn"));
        inv.setItem(20, toggleItem(forceRespawn, Material.TOTEM_OF_UNDYING, "Force Respawn To New World"));
    inv.setItem(21, namedComponent(Material.CLOCK, TextComponents.white("Respawn Window: " + respawnWindow + "s"), TextComponents.gray("Left -10 | Right +10 | Shift ±60")));
        inv.setItem(22, toggleItem(fresh, Material.MILK_BUCKET, "Fresh Start On Reset"));

        // Row 4: Teleport/Limits
        String fallback = plugin.getConfig().getString("teleport.fallbackWorldName", "");
        int maxOnline = plugin.getConfig().getInt("limits.maxOnlineForReset", -1);
    inv.setItem(27, namedComponent(Material.ENDER_PEARL, TextComponents.white("Fallback World: " + (fallback.isEmpty()?"<auto>":fallback)), TextComponents.gray("Click to cycle | Shift for chat")));
    inv.setItem(28, namedComponent(Material.PLAYER_HEAD, TextComponents.white("Max Online For Reset: " + maxOnline), TextComponents.gray("Left -1 | Right +1 | Shift ±5")));

        // Row 5: Backups
        boolean backups = plugin.getConfig().getBoolean("backups.enabled", true);
        int perBase = plugin.getConfig().getInt("backups.maxPerBase", 5);
        int maxTotal = plugin.getConfig().getInt("backups.maxTotal", 50);
        int maxAge = plugin.getConfig().getInt("backups.maxAgeDays", 30);
        int keepNow = plugin.getConfig().getInt("backups.pruneNowKeepPerBase", 2);
    inv.setItem(36, toggleItem(backups, Material.SHULKER_BOX, "Backups Enabled"));
    inv.setItem(37, namedComponent(Material.BUNDLE, TextComponents.white("Backups Per Base: " + perBase), TextComponents.gray("Left -1 | Right +1 | Shift ±5")));
    inv.setItem(38, namedComponent(Material.CHEST, TextComponents.white("Backups Max Total: " + maxTotal), TextComponents.gray("Left -1 | Right +1 | Shift ±10")));
    inv.setItem(39, namedComponent(Material.CLOCK, TextComponents.white("Backups Max Age: " + maxAge + "d"), TextComponents.gray("Left -1 | Right +1 | Shift ±5")));
    inv.setItem(40, namedComponent(Material.SHEARS, TextComponents.white("PruneNow Keep Per Base: " + keepNow), TextComponents.gray("Left -1 | Right +1")));

        // Row 6: Messages (open message editor via chat)
    inv.setItem(45, namedComponent(Material.PAPER, TextComponents.aqua("Edit Messages"), TextComponents.gray("Click to edit common messages")));
    inv.setItem(49, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
        p.openInventory(inv);
    }

    private void handleSettingsClick(Player p, String dn, org.bukkit.event.inventory.ClickType click) {
        // Toggles
        if (dn.equalsIgnoreCase("Preload Enabled")) { flip(p, "preload.enabled"); return; }
        if (dn.equalsIgnoreCase("Auto-Disable on Low TPS")) { flip(p, "preload.autoDisableHighLag"); return; }
        if (dn.startsWith("TPS Threshold")) { adjustDouble(p, "preload.tpsThreshold", click, 0.5, 0.0, 20.0); return; }
        if (dn.startsWith("Countdown Seconds")) { adjustInt(p, "countdown.seconds", click, 1, 1, 600); return; }
        if (dn.equalsIgnoreCase("Broadcast Countdown To All")) { flip(p, "countdown.broadcastToAll"); return; }
        if (dn.equalsIgnoreCase("Require Confirmation")) { flip(p, "confirmation.requireConfirm"); return; }
        if (dn.startsWith("Confirm Timeout")) { adjustInt(p, "confirmation.timeoutSeconds", click, 1, 1, 300); return; }
        if (dn.equalsIgnoreCase("Console Bypasses Confirm")) { flip(p, "confirmation.consoleBypasses"); return; }
        if (dn.equalsIgnoreCase("Use Same Seed For All Dims")) { flip(p, "seeds.useSameSeedForAllDimensions"); return; }
        if (dn.equalsIgnoreCase("Return Players To New Spawn")) { flip(p, "players.returnToNewSpawnAfterReset"); return; }
        if (dn.equalsIgnoreCase("Force Respawn To New World")) { flip(p, "players.forceRespawnToNewOverworld"); return; }
        if (dn.startsWith("Respawn Window")) { adjustInt(p, "players.respawnRecentWindowSeconds", click, 10, 0, 3600); return; }
        if (dn.equalsIgnoreCase("Fresh Start On Reset")) { flip(p, "players.freshStartOnReset"); return; }
        if (dn.startsWith("Fallback World")) { cycleFallback(p, click); return; }
        if (dn.startsWith("Max Online For Reset")) { adjustInt(p, "limits.maxOnlineForReset", click, 1, -1, 500); return; }
        if (dn.equalsIgnoreCase("Backups Enabled")) { flip(p, "backups.enabled"); return; }
        if (dn.startsWith("Backups Per Base")) { adjustInt(p, "backups.maxPerBase", click, 1, 0, 500); return; }
        if (dn.startsWith("Backups Max Total")) { adjustInt(p, "backups.maxTotal", click, 1, 0, 1000); return; }
        if (dn.startsWith("Backups Max Age")) { adjustInt(p, "backups.maxAgeDays", click, 1, 0, 3650); return; }
        if (dn.startsWith("PruneNow Keep Per Base")) { adjustInt(p, "backups.pruneNowKeepPerBase", click, 1, 0, 50); return; }
        if (dn.equalsIgnoreCase("Edit Messages")) { openMessagesEditor(p); return; }
        if (dn.equalsIgnoreCase("Back")) { openMain(p); }
    }

    private void openMessagesEditor(Player p) {
    GuiHolder holder = new GuiHolder(GuiHolder.Type.MESSAGES, TextComponents.aqua("Messages"));
    Inventory inv = Bukkit.createInventory(holder, 27, TextComponents.aqua("Messages"));
    holder.setInventory(inv);
    inv.setItem(10, namedComponent(Material.PAPER, TextComponents.white("noPermission"), TextComponents.gray(plugin.getConfig().getString("messages.noPermission", "")), TextComponents.yellow("Click to edit")));
    inv.setItem(11, namedComponent(Material.PAPER, TextComponents.white("confirmationWarning"), TextComponents.gray("Click to edit")));
    inv.setItem(12, namedComponent(Material.PAPER, TextComponents.white("confirmationHowTo"), TextComponents.gray("Click to edit")));
    inv.setItem(13, namedComponent(Material.PAPER, TextComponents.white("countdownTitle"), TextComponents.gray(plugin.getConfig().getString("messages.countdownTitle", "")), TextComponents.yellow("Click to edit")));
    inv.setItem(14, namedComponent(Material.PAPER, TextComponents.white("countdownSubtitle"), TextComponents.gray(plugin.getConfig().getString("messages.countdownSubtitle", "")), TextComponents.yellow("Click to edit")));
    inv.setItem(22, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
    p.openInventory(inv);
    }

    private void flip(Player p, String path) {
        boolean cur = plugin.getConfig().getBoolean(path);
        plugin.getConfig().set(path, !cur);
        plugin.saveConfig();
        openSettings(p);
    }

    private void adjustInt(Player p, String path, org.bukkit.event.inventory.ClickType click, int step, int min, int max) {
        int cur = plugin.getConfig().getInt(path);
        int delta = (click.isLeftClick()? -step : step) * (click.isShiftClick()? 10 : 1);
        int val = Math.max(min, Math.min(max, cur + delta));
        plugin.getConfig().set(path, val);
        plugin.saveConfig();
        openSettings(p);
    }

    private void adjustDouble(Player p, String path, org.bukkit.event.inventory.ClickType click, double step, double min, double max) {
        double cur = plugin.getConfig().getDouble(path);
        double delta = (click.isLeftClick()? -step : step);
        double val = Math.max(min, Math.min(max, Math.round((cur + delta)*10.0)/10.0));
        plugin.getConfig().set(path, val);
        plugin.saveConfig();
        openSettings(p);
    }

    private void cycleFallback(Player p, org.bukkit.event.inventory.ClickType click) {
        String current = plugin.getConfig().getString("teleport.fallbackWorldName", "");
        if (click.isShiftClick()) {
            awaitingConfigPath.put(p.getUniqueId(), "teleport.fallbackWorldName");
            Messages.send(p, "&eType a world name in chat, or 'none'.");
            return;
        }
        // Cycle through bases
        List<String> bases = Bukkit.getWorlds().stream().map(World::getName).map(GuiManager::baseName).distinct().sorted().collect(Collectors.toList());
        int idx = bases.indexOf(current);
        if (click.isLeftClick()) idx = (idx <= 0 ? bases.size()-1 : idx-1); else idx = (idx+1) % bases.size();
        String next = bases.isEmpty()? "" : bases.get(Math.max(0, idx));
        plugin.getConfig().set("teleport.fallbackWorldName", next);
        plugin.saveConfig();
        openSettings(p);
    }

    // VersionCompat -> ChatMessage handler
    private void onChatMessage(VersionCompat.ChatMessage msg) {
        UUID id = msg.getPlayer().getUniqueId();
        // Seed prompt
        String base = awaitingSeedForWorld.get(id);
        if (base != null) {
            String text = msg.getMessageText().trim();
            Optional<Long> seed = Optional.empty();
            if (!text.equalsIgnoreCase("random")) {
                try { seed = Optional.of(Long.parseLong(text)); }
                catch (NumberFormatException ex) { Messages.send(msg.getPlayer(), "&cInvalid seed. Type a number or 'random'."); return; }
            }
            // awaitingSeedForWorld may be stored either as just the base name, or as "base;DIM1,DIM2" when opened from the seed selector.
            awaitingSeedForWorld.remove(id);
            String ctx = base;
            String finalBase = ctx;
            EnumSet<ResetService.Dimension> dims = selectedDims.getOrDefault(id, EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END));
            // If context contains a semicolon, parse dims from it
            if (ctx.contains(";")) {
                String[] parts = ctx.split(";", 2);
                finalBase = parts[0];
                if (parts.length > 1 && parts[1] != null && !parts[1].isEmpty()) {
                    try {
                        EnumSet<ResetService.Dimension> parsed = EnumSet.noneOf(ResetService.Dimension.class);
                        for (String s : parts[1].split(",")) parsed.add(ResetService.Dimension.valueOf(s));
                        dims = parsed;
                    } catch (Exception ignored) {}
                }
            }
            Player p = msg.getPlayer();
            final EnumSet<ResetService.Dimension> dimsForTask = EnumSet.copyOf(dims);
            final Optional<Long> seedForTask = seed;
            final String baseForTask = finalBase;
            Bukkit.getScheduler().runTask(plugin, () -> resetService.startResetWithCountdown(p, baseForTask, seedForTask, dimsForTask));
            return;
        }
        // Config string prompt
        String path = awaitingConfigPath.get(id);
        if (path != null) {
            String text = msg.getMessageText().trim();
            if (path.equals("teleport.fallbackWorldName") && text.equalsIgnoreCase("none")) text = "";
            plugin.getConfig().set(path, text);
            plugin.saveConfig();
            awaitingConfigPath.remove(id);
            Bukkit.getScheduler().runTask(plugin, () -> openSettings(msg.getPlayer()));
        }
    }

    private void toggleDim(Player p, String base, EnumSet<ResetService.Dimension> dims, ResetService.Dimension d) {
        if (dims.contains(d)) dims.remove(d); else dims.add(d);
        if (dims.isEmpty()) dims.add(ResetService.Dimension.OVERWORLD); // keep at least one
        openResetOptions(p, base);
    }

    private static ItemStack toggleItem(boolean on, Material mat, String name) {
        Material use = on ? mat : Material.BARRIER;
        Component title = TextComponents.white(name);
        Component state = on ? TextComponents.green("[ON]") : TextComponents.red("[OFF]");
    return GuiHolderComponents.namedComponent(use, title, state);
    }

    // ...existing code... (removed legacy named helper - use namedComponent instead)

    private static String human(long bytes) {
        String[] u = {"B","KB","MB","GB","TB"};
        double b = bytes; int i=0; while (b>=1024 && i<u.length-1){ b/=1024; i++; }
        return String.format(java.util.Locale.US, "%.1f %s", b, u[i]);
    }

    private static String formatDuration(long sec) {
        long h = sec/3600; long m = (sec%3600)/60; long s = sec%60;
        if (h>0) return String.format(java.util.Locale.US, "%dh %dm", h, m);
        if (m>0) return String.format(java.util.Locale.US, "%dm %ds", m, s);
        return s + "s";
    }

    private static String baseName(String input) {
        if (input.endsWith("_nether")) return input.substring(0, input.length() - 7);
        if (input.endsWith("_the_end")) return input.substring(0, input.length() - 8);
        return input;
    }
}
