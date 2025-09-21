package com.muj3b.betterreset.ui;

import com.muj3b.betterreset.FullResetPlugin;
import com.muj3b.betterreset.core.ResetService;
import com.muj3b.betterreset.util.BackupManager;
import com.muj3b.betterreset.util.Messages;
import com.muj3b.betterreset.util.TextComponents;
import com.muj3b.betterreset.util.VersionCompat;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.Bukkit;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.persistence.PersistentDataType;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.configuration.ConfigurationSection;

import java.util.*;
import java.util.stream.Collectors;

public class GuiManager implements Listener {

    private static final Component TITLE_MAIN = TextComponents.darkAqua("BetterReset | Manager");
    private static final Component TITLE_SELECT = TextComponents.darkGreen("BetterReset | Select World");
    private static final Component TITLE_SETTINGS = TextComponents.blue("BetterReset | Settings");
    private static final Component TITLE_RESET_FOR = TextComponents.darkRed("BetterReset | Reset | ");
    private static final Component TITLE_ARCHIVES = TextComponents.gold("BetterReset | Archives");
    private static final Component TITLE_ARCHIVE_OPTIONS = TextComponents.gold("BetterReset | Archive | ");
    private static final Component TITLE_DELETE_ARCHIVE = TextComponents.darkRed("BetterReset | Delete Archive | ");
    private static final Component TITLE_DELETE_ALL_GLOBAL = TextComponents.darkRed("BetterReset | Delete ALL Archives | ALL BASES");

    private final FullResetPlugin plugin;
    private final ResetService resetService;

    private final Map<UUID, String> awaitingSeedForWorld = new HashMap<>();
    private final Map<UUID, String> awaitingConfigPath = new HashMap<>();
    private final Map<UUID, String> awaitingValueType = new HashMap<>(); // STRING, BOOLEAN, INT, LONG, DOUBLE
    private final Map<UUID, String> lastSettingsSection = new HashMap<>();
    private final Map<UUID, String> selectedBase = new HashMap<>();
    private final Map<UUID, EnumSet<ResetService.Dimension>> selectedDims = new HashMap<>();
    // track per-player selected backup (base -> timestamp)
    private final Map<UUID, BackupSelection> selectedBackup = new HashMap<>();
    // Archives pagination and filter state per-player
    private final Map<UUID, Integer> archivesPage = new HashMap<>();
    private final Map<UUID, String> archivesFilter = new HashMap<>();

    private static record BackupSelection(String base, String timestamp) {}

    public GuiManager(FullResetPlugin plugin, ResetService resetService) {
        this.plugin = plugin;
        this.resetService = resetService;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    try { new VersionCompat(plugin, this::onChatMessage); } catch (Throwable ignored) {}
    }

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
            // Tag as BetterReset UI so click handler accepts it
            try {
                meta.getPersistentDataContainer().set(new NamespacedKey("betterreset","ui"), org.bukkit.persistence.PersistentDataType.BYTE, (byte)1);
            } catch (Throwable ignored) {}
            it.setItemMeta(meta);
        }
        return it;
    }

    private static ItemStack toggleItem(boolean on, Material mat, String name) {
        Material use = on ? mat : Material.BARRIER;
        Component title = TextComponents.white(name);
        Component state = on ? TextComponents.green("[ON]") : TextComponents.red("[OFF]");
        return com.muj3b.betterreset.ui.GuiHolderComponents.namedComponent(use, title, state);
    }

    private static String baseName(String input) {
        if (input.endsWith("_nether")) return input.substring(0, input.length() - 7);
        if (input.endsWith("_the_end")) return input.substring(0, input.length() - 8);
        return input;
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (top != null && top.getHolder() instanceof GuiHolder) e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.HIGHEST)
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player)) return;
        Player p = (Player) e.getWhoClicked();
        Inventory top = e.getView().getTopInventory();
        if (top == null || !(top.getHolder() instanceof GuiHolder)) return;

        e.setCancelled(true);

        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta()) return;
        ItemMeta meta = it.getItemMeta();
        // Strict ownership check: only handle items tagged by BetterReset
        try {
            if (!meta.getPersistentDataContainer().has(new NamespacedKey("betterreset","ui"), PersistentDataType.BYTE)) return;
        } catch (Throwable ignored) {}
        Component dnComp = meta.displayName();
        if (dnComp == null) return;
        String displayName = PlainTextComponentSerializer.plainText().serialize(dnComp);

        GuiHolder holder = (GuiHolder) top.getHolder();
        // Debug logging for GUI clicks
        if (isGuiDebug()) {
            String baseMeta = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "backup_base"), PersistentDataType.STRING);
            String tsMeta = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "backup_timestamp"), PersistentDataType.STRING);
            plugin.getLogger().info("GUI click: player=" + p.getName() + ", type=" + holder.getType() + ", item=\"" + displayName + "\", base=" + String.valueOf(baseMeta) + ", ts=" + String.valueOf(tsMeta));
        }
        switch (holder.getType()) {
            case MAIN -> {
                switch (displayName.toLowerCase(Locale.ROOT)) {
                    case "reset worlds" -> openWorldSelect(p);
                    case "archives" -> openBackups(p);
                    case "settings" -> openSimpleSettings(p);
                    default -> {}
                }
            }
            case SELECT -> {
                if (displayName.equalsIgnoreCase("Back") || displayName.equalsIgnoreCase("Back to BetterReset")) {
                    Bukkit.getScheduler().runTask(plugin, () -> openMain(p));
                } else openResetOptions(p, displayName);
            }
            case RESET_OPTIONS -> handleResetOptionsClick(p, displayName);
            case BACKUPS -> handleBackupsClick(p, displayName, meta);
            case BACKUP_OPTIONS -> handleBackupOptionsClick(p, displayName, meta);
            case DELETE_BACKUP -> handleDeleteBackupClick(p, displayName, meta);
            case DELETE_ALL -> handleDeleteAllClick(p, displayName);
            case DELETE_ALL_GLOBAL -> handleDeleteAllGlobalClick(p, displayName);
            case SIMPLE_SETTINGS -> handleSimpleSettingsClick(p, displayName);
            case SETTINGS -> handleSettingsClick(p, displayName, e.getClick());
            case SETTINGS_SECTION -> handleSettingsSectionClick(p, meta, displayName);
            case SETTING_EDIT -> handleSettingEditorClick(p, meta, displayName);
            case CONFIG_BROWSER -> handleConfigBrowserClick(p, meta, displayName);
            case SEED_SELECTOR -> handleSeedSelectorClick(p, displayName);
            case MESSAGES -> handleMessagesClick(p, displayName);
            // Missing case labels for legacy SimpleGuiManager compatibility
            case RESET -> {} // Handled by SimpleGuiManager
            case ARCHIVES -> {} // Handled by SimpleGuiManager
            case ARCHIVE_OPTIONS -> {} // Handled by SimpleGuiManager
            // Removed duplicate CONFIG_BROWSER case
        }
    }

    public void openMain(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.MAIN, TITLE_MAIN);
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE_MAIN);
        holder.setInventory(inv);
        inv.setItem(11, namedComponent(Material.GRASS_BLOCK, TextComponents.green("Reset Worlds"), TextComponents.gray("Pick a base world")));
        inv.setItem(13, namedComponent(Material.COMPARATOR, TextComponents.blue("Settings"), TextComponents.gray("Change options")));
        inv.setItem(15, namedComponent(Material.CHEST, TextComponents.gold("Archives"), TextComponents.gray("Browse & restore")));
        p.openInventory(inv);
    }

    public void openWorldSelect(Player p) {
        String base = baseName(p.getWorld().getName());
        GuiHolder holder = new GuiHolder(GuiHolder.Type.SELECT, TITLE_SELECT);
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE_SELECT);
        holder.setInventory(inv);
        inv.setItem(13, namedComponent(Material.GRASS_BLOCK, TextComponents.green(base), TextComponents.gray("Click to configure")));
        inv.setItem(22, namedComponent(Material.ARROW, TextComponents.yellow("Back to BetterReset")));
        p.openInventory(inv);
    }

    private void openResetOptions(Player p, String base) {
        selectedBase.put(p.getUniqueId(), base);
        EnumSet<ResetService.Dimension> dims = selectedDims.computeIfAbsent(p.getUniqueId(), k -> EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END));
        GuiHolder holder = new GuiHolder(GuiHolder.Type.RESET_OPTIONS, Component.empty().append(TITLE_RESET_FOR).append(Component.text(base)));
        Inventory inv = Bukkit.createInventory(holder, 45, holder.getTitle());
        holder.setInventory(inv);
        // Mode banner
        boolean teleportMode = plugin.getConfig().getBoolean("teleportMode.enabled", false);
        inv.setItem(4, namedComponent(Material.PAPER, TextComponents.white("Mode: " + (teleportMode ? "Teleport" : "Reset")),
                TextComponents.gray("Use the lever below to switch modes")));
        inv.setItem(10, toggleItem(dims.contains(ResetService.Dimension.OVERWORLD), Material.GRASS_BLOCK, "Overworld"));
        inv.setItem(12, toggleItem(dims.contains(ResetService.Dimension.NETHER), Material.NETHERRACK, "Nether"));
        inv.setItem(14, toggleItem(dims.contains(ResetService.Dimension.END), Material.END_STONE, "The End"));
        if (teleportMode) {
            inv.setItem(22, namedComponent(Material.LIME_WOOL, TextComponents.green("Teleport Selected (Random Seed)"),
                    TextComponents.gray("Teleport you ~" + plugin.getConfig().getInt("teleportMode.playerDistance",15000) + " blocks"),
                    TextComponents.gray("Others ~" + plugin.getConfig().getInt("teleportMode.othersDistance",50000) + " blocks"),
                    TextComponents.gray("Nether/End reset simultaneously (if enabled)")));
            inv.setItem(24, namedComponent(Material.CYAN_WOOL, TextComponents.blue("Teleport Selected (Custom Seed)"),
                    TextComponents.gray("Custom seed for Nether/End reset")));
            inv.setItem(31, namedComponent(Material.ENDER_EYE, TextComponents.white("Teleport Now"),
                    TextComponents.gray("Skip seed selection; use random")));
            boolean enabled = plugin.getConfig().getBoolean("teleportMode.enabled", false);
            inv.setItem(32, namedComponent(Material.LEVER, TextComponents.white("Mode: " + (enabled ? "Teleport" : "Reset")),
                    TextComponents.gray("Click to switch mode")));
        } else {
            inv.setItem(22, namedComponent(Material.LIME_WOOL, TextComponents.green("Reset Selected (Random Seed)")));
            inv.setItem(24, namedComponent(Material.CYAN_WOOL, TextComponents.blue("Reset Selected (Custom Seed)")));
        }
        inv.setItem(40, namedComponent(Material.ARROW, TextComponents.yellow("Back to BetterReset")));
        p.openInventory(inv);
    }

    private void openBackups(Player p) { openBackupsFiltered(p, null); }

    // ===== Simple Settings (single page) =====
    public void openSimpleSettings(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.SIMPLE_SETTINGS, TextComponents.blue("BetterReset | Simple Settings"));
        Inventory inv = Bukkit.createInventory(holder, 45, holder.getTitle()); // Increased from 27 to 45 for more options
        holder.setInventory(inv);

        boolean teleportMode = plugin.getConfig().getBoolean("teleportMode.enabled", false);
        boolean requireConfirm = plugin.getConfig().getBoolean("confirmation.requireConfirm", true);
        boolean freshStart = plugin.getConfig().getBoolean("players.freshStartOnReset", true);
        boolean preload = plugin.getConfig().getBoolean("preload.enabled", true);
        boolean backups = plugin.getConfig().getBoolean("backups.enabled", true);
        boolean returnPlayers = plugin.getConfig().getBoolean("players.returnToNewSpawnAfterReset", true);
        boolean resetAllOnline = plugin.getConfig().getBoolean("players.resetAllOnlineAfterReset", true);
        boolean broadcastCountdown = plugin.getConfig().getBoolean("countdown.broadcastToAll", true);
        boolean resetNetherEnd = plugin.getConfig().getBoolean("teleportMode.resetNetherEnd", true);
        int seconds = plugin.getConfig().getInt("countdown.seconds", 10);

        // Row 1: Core mode settings
        inv.setItem(10, namedComponent(Material.LEVER, TextComponents.white("Mode: " + (teleportMode ? "Teleport" : "Reset")),
                TextComponents.gray("Click to switch")));
        inv.setItem(12, namedComponent(requireConfirm ? Material.LIME_DYE : Material.RED_DYE, TextComponents.white("Require Confirmation"),
                TextComponents.gray(requireConfirm ? "ON" : "OFF")));
        inv.setItem(14, namedComponent(freshStart ? Material.GOLDEN_APPLE : Material.ROTTEN_FLESH, TextComponents.white("Fresh Start Players"),
                TextComponents.gray(freshStart ? "ON" : "OFF")));
        inv.setItem(16, namedComponent(backups ? Material.CHEST : Material.BARRIER, TextComponents.white("Create Backups"),
                TextComponents.gray(backups ? "ON" : "OFF")));

        // Row 2: Countdown and player settings
        String cdLabel = seconds <= 0 ? "Countdown: OFF" : ("Countdown: " + seconds + "s");
        inv.setItem(19, namedComponent(Material.CLOCK, TextComponents.white(cdLabel),
                TextComponents.gray("Click to cycle 0/3/5/10/15/30")));
        inv.setItem(21, namedComponent(broadcastCountdown ? Material.BELL : Material.REDSTONE_TORCH, TextComponents.white("Broadcast Countdown"),
                TextComponents.gray(broadcastCountdown ? "To all players" : "To affected players only")));
        inv.setItem(23, namedComponent(returnPlayers ? Material.COMPASS : Material.BARRIER, TextComponents.white("Return Players to Spawn"),
                TextComponents.gray(returnPlayers ? "ON" : "OFF")));
        inv.setItem(25, namedComponent(resetAllOnline ? Material.PLAYER_HEAD : Material.SKELETON_SKULL, TextComponents.white("Reset All Online Players"),
                TextComponents.gray(resetAllOnline ? "Fresh start for all" : "Only affected players")));

        // Row 3: Advanced settings
        inv.setItem(28, namedComponent(preload ? Material.ICE : Material.BLUE_ICE, TextComponents.white("Preload Worlds"),
                TextComponents.gray(preload ? "ON" : "OFF")));

        // Teleport mode specific settings
        if (teleportMode) {
            int pd = plugin.getConfig().getInt("teleportMode.playerDistance", 15000);
            boolean setWorldSpawn = plugin.getConfig().getBoolean("teleportMode.setWorldSpawn", true);
            boolean ensureSafe = plugin.getConfig().getBoolean("teleportMode.ensureSafe", true);
            inv.setItem(30, namedComponent(Material.COMPASS, TextComponents.white("Player Distance: " + pd),
                    TextComponents.gray("Click to cycle")));
            inv.setItem(32, namedComponent(setWorldSpawn ? Material.BEACON : Material.BARRIER, TextComponents.white("Set World Spawn"),
                    TextComponents.gray(setWorldSpawn ? "ON - World spawn updated" : "OFF - Keep old spawn")));
            inv.setItem(34, namedComponent(resetNetherEnd ? Material.NETHERRACK : Material.BARRIER, TextComponents.white("Reset Nether & End"),
                    TextComponents.gray(resetNetherEnd ? "ON - Nether/End reset too" : "OFF - Only teleport players")));
            inv.setItem(36, namedComponent(ensureSafe ? Material.SHIELD : Material.BARRIER, TextComponents.white("Ensure Safe Location"),
                    TextComponents.gray(ensureSafe ? "ON - Find safe surface" : "OFF - Basic teleport")));
        }

        // Admin-only Advanced Settings
        if (p.hasPermission("betterreset.admin")) {
            inv.setItem(36, namedComponent(Material.REDSTONE_BLOCK, TextComponents.darkRed("Advanced Settings"),
                    TextComponents.gray("Full config editor")));
        }

        inv.setItem(44, namedComponent(Material.ARROW, TextComponents.yellow("Back to BetterReset")));

        p.openInventory(inv);
    }

    private void handleSimpleSettingsClick(Player p, String displayName) {
        switch (displayName) {
            case "Back", "Back to BetterReset" -> { openMain(p); return; }
            case "Advanced Settings" -> {
                if (p.hasPermission("betterreset.admin")) openSettings(p);
                else Messages.send(p, plugin.getConfig().getString("messages.noPermission"));
                return;
            }
        }
        if (displayName.startsWith("Mode: ")) {
            boolean enabled = plugin.getConfig().getBoolean("teleportMode.enabled", false);
            plugin.getConfig().set("teleportMode.enabled", !enabled);
            plugin.saveConfig();
            openSimpleSettings(p);
            return;
        }
        if (displayName.equals("Require Confirmation")) {
            boolean cur = plugin.getConfig().getBoolean("confirmation.requireConfirm", true);
            plugin.getConfig().set("confirmation.requireConfirm", !cur);
            plugin.saveConfig();
            openSimpleSettings(p);
            return;
        }
        if (displayName.equals("Fresh Start Players")) {
            boolean cur = plugin.getConfig().getBoolean("players.freshStartOnReset", true);
            plugin.getConfig().set("players.freshStartOnReset", !cur);
            plugin.saveConfig();
            openSimpleSettings(p);
            return;
        }
        if (displayName.equals("Create Backups")) {
            boolean cur = plugin.getConfig().getBoolean("backups.enabled", true);
            plugin.getConfig().set("backups.enabled", !cur);
            plugin.saveConfig();
            openSimpleSettings(p);
            return;
        }
        if (displayName.equals("Broadcast Countdown")) {
            boolean cur = plugin.getConfig().getBoolean("countdown.broadcastToAll", true);
            plugin.getConfig().set("countdown.broadcastToAll", !cur);
            plugin.saveConfig();
            openSimpleSettings(p);
            return;
        }
        if (displayName.equals("Return Players to Spawn")) {
            boolean cur = plugin.getConfig().getBoolean("players.returnToNewSpawnAfterReset", true);
            plugin.getConfig().set("players.returnToNewSpawnAfterReset", !cur);
            plugin.saveConfig();
            openSimpleSettings(p);
            return;
        }
        if (displayName.equals("Reset All Online Players")) {
            boolean cur = plugin.getConfig().getBoolean("players.resetAllOnlineAfterReset", true);
            plugin.getConfig().set("players.resetAllOnlineAfterReset", !cur);
            plugin.saveConfig();
            openSimpleSettings(p);
            return;
        }
        if (displayName.equals("Reset Nether & End")) {
            boolean cur = plugin.getConfig().getBoolean("teleportMode.resetNetherEnd", true);
            plugin.getConfig().set("teleportMode.resetNetherEnd", !cur);
            plugin.saveConfig();
            openSimpleSettings(p);
            return;
        }
        if (displayName.startsWith("Countdown:")) {
            int cur = plugin.getConfig().getInt("countdown.seconds", 10);
            int[] steps = new int[]{0,3,5,10,15,30};
            int nextIndex = 0;
            for (int i = 0; i < steps.length; i++) { if (steps[i] == cur) { nextIndex = (i+1)%steps.length; break; } }
            plugin.getConfig().set("countdown.seconds", steps[nextIndex]);
            plugin.saveConfig();
            openSimpleSettings(p);
            return;
        }
        if (displayName.startsWith("Preload Worlds")) {
            boolean cur = plugin.getConfig().getBoolean("preload.enabled", true);
            plugin.getConfig().set("preload.enabled", !cur);
            plugin.saveConfig();
            openSimpleSettings(p);
            return;
        }
        if (displayName.equals("Set World Spawn")) {
            boolean cur = plugin.getConfig().getBoolean("teleportMode.setWorldSpawn", true);
            plugin.getConfig().set("teleportMode.setWorldSpawn", !cur);
            plugin.saveConfig();
            openSimpleSettings(p);
            return;
        }
        if (displayName.equals("Ensure Safe Location")) {
            boolean cur = plugin.getConfig().getBoolean("teleportMode.ensureSafe", true);
            plugin.getConfig().set("teleportMode.ensureSafe", !cur);
            plugin.saveConfig();
            openSimpleSettings(p);
            return;
        }
        if (displayName.startsWith("Player Distance:")) {
            int cur = plugin.getConfig().getInt("teleportMode.playerDistance", 15000);
            int[] steps = new int[]{5000,10000,15000,20000,50000};
            int nextIndex = 0; for (int i=0;i<steps.length;i++){ if (steps[i]==cur){ nextIndex=(i+1)%steps.length; break; } }
            plugin.getConfig().set("teleportMode.playerDistance", steps[nextIndex]);
            plugin.saveConfig();
            openSimpleSettings(p);
            return;
        }
    }

    private void openBackupsFiltered(Player p, String filterBase) {
        // update filter & reset page if changed
        UUID id = p.getUniqueId();
        String prev = archivesFilter.get(id);
        if (filterBase == null) archivesFilter.remove(id); else archivesFilter.put(id, filterBase);
        if (!Objects.equals(prev, filterBase)) archivesPage.put(id, 0);
        int page = Math.max(0, archivesPage.getOrDefault(id, 0));
        Component title = (filterBase == null) ? TITLE_ARCHIVES : Component.empty().append(TITLE_ARCHIVES).append(TextComponents.gray(" | ")).append(Component.text(filterBase));
        GuiHolder holder = new GuiHolder(GuiHolder.Type.BACKUPS, title);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);
        List<BackupManager.BackupRef> refs = resetService.listBackups();
        if (filterBase != null) {
            refs = refs.stream().filter(r -> r.base().equalsIgnoreCase(filterBase)).toList();
        }
        LinkedHashSet<String> bases = new LinkedHashSet<>();
        for (var r : refs) bases.add(r.base());
        int hslot = 0;
        // compute per-base stats
        Map<String, long[]> baseStats = new LinkedHashMap<>(); // base -> [count, sizeBytes]
        for (String b : bases) baseStats.put(b, new long[]{0L, 0L});
        for (var r : refs) {
            long[] arr = baseStats.get(r.base());
            if (arr != null) { arr[0] += 1; if (r.sizeBytes() > 0) arr[1] += r.sizeBytes(); }
        }
        for (String b : bases) {
            if (hslot > 8) break;
            long[] arr = baseStats.getOrDefault(b, new long[]{0L,0L});
            // Per-base totals item (distinct icon)
            if (hslot <= 8) {
                ItemStack info = namedComponent(
                        Material.BARREL,
                        TextComponents.white("Base: " + b),
                        TextComponents.gray("Count: ").append(TextComponents.white(String.valueOf(arr[0]))),
                        TextComponents.gray("Size: ").append(TextComponents.white(human(arr[1])))
                );
                ItemMeta im = info.getItemMeta();
                if (im != null) {
                    im.getPersistentDataContainer().set(new NamespacedKey(plugin, "header_base"), PersistentDataType.STRING, b);
                    info.setItemMeta(im);
                }
                inv.setItem(hslot++, info);
            }
            // Delete ALL for base (keep as separate item)
            if (hslot <= 8) {
                inv.setItem(hslot++, namedComponent(
                        Material.LAVA_BUCKET,
                        TextComponents.darkRed("Delete ALL " + b),
                        TextComponents.gray("Click to confirm")
                ));
            }
        }
        long totalBytes = 0L; int totalCount = refs.size();
        // pagination setup
        int pageSize = 36; // slots 9..44 inclusive
        int pageCount = Math.max(1, (int) Math.ceil(totalCount / (double) pageSize));
        if (page >= pageCount) { page = pageCount - 1; archivesPage.put(id, page); }
        int start = page * pageSize;
        int end = Math.min(totalCount, start + pageSize);
        int slot = 9;
        for (int i = start; i < end; i++) {
            var ref = refs.get(i);
            if (slot > 44) break;
            Component label = Component.empty().append(TextComponents.gold(ref.base())).append(TextComponents.gray(" @ ")).append(TextComponents.yellow(ref.timestamp()));
            List<Component> lore = new ArrayList<>();
            lore.add(TextComponents.gray("Click to restore"));
            if (ref.sizeBytes() >= 0) lore.add(TextComponents.gray("Size: ").append(TextComponents.white(human(ref.sizeBytes()))));
            if (ref.playtimeSeconds() >= 0) lore.add(TextComponents.gray("Playtime: ").append(TextComponents.white(formatDuration(ref.playtimeSeconds()))));
            if (ref.sizeBytes() > 0) totalBytes += ref.sizeBytes();
            ItemStack item = new ItemStack(Material.CHEST);
            ItemMeta im = item.getItemMeta();
            if (im != null) {
                im.displayName(label);
                im.lore(lore);
                // store persistent metadata so handlers don't need to parse display names
                im.getPersistentDataContainer().set(new NamespacedKey(plugin, "backup_base"), PersistentDataType.STRING, ref.base());
                im.getPersistentDataContainer().set(new NamespacedKey(plugin, "backup_timestamp"), PersistentDataType.STRING, ref.timestamp());
                item.setItemMeta(im);
            }
            inv.setItem(slot++, item);
        }
        int keep = 2;
        try { keep = Math.max(0, plugin.getConfig().getInt("backups.pruneNowKeepPerBase", 2)); } catch (Exception ignored) {}
        inv.setItem(49, namedComponent(Material.SHEARS, TextComponents.red("Prune ALL (Keep " + keep + ")")));
        inv.setItem(51, namedComponent(Material.TNT, TextComponents.darkRed("Delete ALL Archives"), TextComponents.gray("All bases")));
        // Totals info
        inv.setItem(45, namedComponent(Material.PAPER, TextComponents.white("Archives Info"),
                TextComponents.gray("Total: ").append(TextComponents.white(String.valueOf(totalCount))),
                TextComponents.gray("Size: ").append(TextComponents.white(human(totalBytes)))));
        // Pagination controls
        if (pageCount > 1) {
            inv.setItem(46, namedComponent(Material.ARROW, TextComponents.yellow("Prev Page")));
            inv.setItem(47, namedComponent(Material.PAPER, TextComponents.white("Page " + (page+1) + "/" + pageCount)));
            inv.setItem(48, namedComponent(Material.ARROW, TextComponents.yellow("Next Page")));
        }
        if (filterBase != null) {
            inv.setItem(52, namedComponent(Material.BOOK, TextComponents.white("Show All Archives")));
        }
        inv.setItem(53, namedComponent(Material.ARROW, TextComponents.yellow("Back to BetterReset")));
        p.openInventory(inv);
    }

    private void openBackupOptions(Player p, String base, String ts) {
        Component title = Component.empty().append(TITLE_ARCHIVE_OPTIONS).append(Component.text(base)).append(TextComponents.gray(" @ ")).append(Component.text(ts));
        GuiHolder holder = new GuiHolder(GuiHolder.Type.BACKUP_OPTIONS, title);
        Inventory inv = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inv);
        inv.setItem(10, namedComponent(Material.LIME_WOOL, TextComponents.green("Restore ALL")));
        inv.setItem(12, namedComponent(Material.GRASS_BLOCK, TextComponents.white("Restore Overworld")));
        inv.setItem(14, namedComponent(Material.NETHERRACK, TextComponents.white("Restore Nether")));
        inv.setItem(16, namedComponent(Material.END_STONE, TextComponents.white("Restore End")));
        int keep = 2; try { keep = Math.max(0, plugin.getConfig().getInt("backups.pruneNowKeepPerBase", 2)); } catch (Exception ignored) {}
        inv.setItem(18, namedComponent(Material.SHEARS, TextComponents.red("Prune Base (Keep " + keep + ")")));
        inv.setItem(20, namedComponent(Material.TNT, TextComponents.red("Delete Archive")));
        inv.setItem(22, namedComponent(Material.ARROW, TextComponents.yellow("Back to BetterReset")));
        // attach timestamp/base to all actionable items
        for (int idx : new int[]{10,12,14,16,18,20}) {
            ItemStack it = inv.getItem(idx);
            if (it == null) continue;
            ItemMeta m = it.getItemMeta();
            if (m == null) continue;
            m.getPersistentDataContainer().set(new NamespacedKey(plugin, "backup_timestamp"), PersistentDataType.STRING, ts);
            m.getPersistentDataContainer().set(new NamespacedKey(plugin, "backup_base"), PersistentDataType.STRING, base);
            it.setItemMeta(m);
        }
        // remember per-player selection so delete-confirm can read without parsing title
        selectedBackup.put(p.getUniqueId(), new BackupSelection(base, ts));
        p.openInventory(inv);
    }

    private void openDeleteConfirm(Player p, String base, String ts) {
        Component title = Component.empty().append(TITLE_DELETE_ARCHIVE).append(Component.text(base)).append(TextComponents.gray(" @ ")).append(Component.text(ts));
        GuiHolder holder = new GuiHolder(GuiHolder.Type.DELETE_BACKUP, title);
        Inventory inv = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inv);
        inv.setItem(11, namedComponent(Material.RED_CONCRETE, TextComponents.darkRed("Confirm Delete")));
        inv.setItem(15, namedComponent(Material.ARROW, TextComponents.yellow("Cancel")));
        p.openInventory(inv);
    }

    private void openDeleteAllConfirm(Player p, String base) {
        Component title = TextComponents.darkRed("BetterReset | Delete ALL | " + base);
        GuiHolder holder = new GuiHolder(GuiHolder.Type.DELETE_ALL, title);
        Inventory inv = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inv);
        inv.setItem(11, namedComponent(Material.RED_CONCRETE, TextComponents.darkRed("Confirm Delete All")));
        inv.setItem(15, namedComponent(Material.ARROW, TextComponents.yellow("Cancel")));
        p.openInventory(inv);
    }

    private void openDeleteAllGlobalConfirm(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.DELETE_ALL_GLOBAL, TITLE_DELETE_ALL_GLOBAL);
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE_DELETE_ALL_GLOBAL);
        holder.setInventory(inv);
        inv.setItem(11, namedComponent(Material.RED_CONCRETE, TextComponents.darkRed("Confirm Delete ALL")));
        inv.setItem(15, namedComponent(Material.ARROW, TextComponents.yellow("Cancel")));
        p.openInventory(inv);
    }

    private void openSeedSelector(Player p, String base, EnumSet<ResetService.Dimension> dims) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.SEED_SELECTOR, TextComponents.darkPurple("BetterReset | Select Seed | ").append(Component.text(base)));
        Inventory inv = Bukkit.createInventory(holder, 27, holder.getTitle());
        holder.setInventory(inv);
        List<Long> seeds = plugin.getSeedHistory().list();
        int slot = 10;
        if (seeds.isEmpty()) inv.setItem(13, namedComponent(Material.PAPER, TextComponents.gray("No recent seeds")));
        else for (int i = 0; i < seeds.size() && slot < 17; i++) inv.setItem(slot++, namedComponent(Material.PAPER, TextComponents.white("[" + i + "] " + seeds.get(i)), TextComponents.gray("Click to use")));
        inv.setItem(22, namedComponent(Material.CYAN_WOOL, TextComponents.blue("Type Custom Seed")));
        inv.setItem(26, namedComponent(Material.ARROW, TextComponents.yellow("Back to BetterReset")));
        awaitingSeedForWorld.put(p.getUniqueId(), base + ";" + dims.stream().map(Enum::name).collect(Collectors.joining(",")));
        p.openInventory(inv);
    }

    private void handleResetOptionsClick(Player p, String displayName) {
        String base = selectedBase.get(p.getUniqueId());
        if (base == null) return;
        EnumSet<ResetService.Dimension> dims = selectedDims.computeIfAbsent(p.getUniqueId(), k -> EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END));
        switch (displayName) {
            case "Overworld" -> toggleDim(p, base, dims, ResetService.Dimension.OVERWORLD);
            case "Nether" -> toggleDim(p, base, dims, ResetService.Dimension.NETHER);
            case "The End" -> toggleDim(p, base, dims, ResetService.Dimension.END);
            case "Back", "Back to BetterReset" -> openWorldSelect(p);
            case "Reset Selected (Random Seed)" -> {
                p.closeInventory();
                if (plugin.getConfig().getBoolean("teleportMode.enabled", false)) resetService.startTeleportWithCountdown(p, base, Optional.empty(), dims);
                else resetService.startReset(p, base, dims);
            }
            case "Reset Selected (Custom Seed)" -> { p.closeInventory(); openSeedSelector(p, base, dims); }
            case "Teleport Selected (Random Seed)" -> { p.closeInventory(); resetService.startTeleportWithCountdown(p, base, Optional.empty(), dims); }
            case "Teleport Selected (Custom Seed)" -> { p.closeInventory(); openSeedSelector(p, base, dims); }
            case "Teleport Now" -> { p.closeInventory(); resetService.startTeleportWithCountdown(p, base, Optional.empty(), dims); }
            case "Mode: Teleport" -> { plugin.getConfig().set("teleportMode.enabled", false); plugin.saveConfig(); openResetOptions(p, base); }
            case "Mode: Reset" -> { plugin.getConfig().set("teleportMode.enabled", true); plugin.saveConfig(); openResetOptions(p, base); }
            default -> {}
        }
    }

    private void handleBackupsClick(Player p, String displayName, ItemMeta meta) {
        if (displayName.equalsIgnoreCase("Back") || displayName.equalsIgnoreCase("Back to BetterReset")) { Bukkit.getScheduler().runTask(plugin, () -> openMain(p)); return; }
        if (displayName.equalsIgnoreCase("Prune Now") || displayName.startsWith("Prune ALL")) { resetService.pruneBackupsAsync(p, Optional.empty(), true); return; }
        if (displayName.equalsIgnoreCase("Delete ALL Archives")) { openDeleteAllGlobalConfirm(p); return; }
        if (displayName.equalsIgnoreCase("Show All Archives")) { openBackupsFiltered(p, null); return; }
        if (displayName.equalsIgnoreCase("Prev Page")) {
            UUID id = p.getUniqueId();
            int page = Math.max(0, archivesPage.getOrDefault(id, 0) - 1);
            archivesPage.put(id, page);
            openBackupsFiltered(p, archivesFilter.get(id));
            return;
        }
        if (displayName.equalsIgnoreCase("Next Page")) {
            UUID id = p.getUniqueId();
            String f = archivesFilter.get(id);
            List<BackupManager.BackupRef> all = resetService.listBackups();
            if (f != null) all = all.stream().filter(r -> r.base().equalsIgnoreCase(f)).toList();
            int pageSize = 36;
            int pageCount = Math.max(1, (int) Math.ceil(all.size() / (double) pageSize));
            int page = Math.min(pageCount - 1, archivesPage.getOrDefault(id, 0) + 1);
            archivesPage.put(id, page);
            openBackupsFiltered(p, f);
            return;
        }
        if (displayName.startsWith("Delete ALL ")) { String base = displayName.substring("Delete ALL ".length()); openDeleteAllConfirm(p, base); return; }
        // Click on base info item filters list
        String headerBase = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "header_base"), PersistentDataType.STRING);
        if (headerBase != null) { openBackupsFiltered(p, headerBase); return; }
        // Prefer persistent metadata for reliability
        String base = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "backup_base"), PersistentDataType.STRING);
        String ts = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "backup_timestamp"), PersistentDataType.STRING);
        if (base != null && ts != null) { openBackupOptions(p, base, ts); return; }
        // Fallback to parsing the display name if metadata is missing
        String dn = PlainTextComponentSerializer.plainText().serialize(meta.displayName());
        String[] parts = dn.split(" @ ", 2);
        if (parts.length == 2) openBackupOptions(p, parts[0], parts[1]);
    }

    private void handleBackupOptionsClick(Player p, String displayName, ItemMeta meta) {
        String ts = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "backup_timestamp"), PersistentDataType.STRING);
        String base = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "backup_base"), PersistentDataType.STRING);
        if (base == null) base = selectedBase.getOrDefault(p.getUniqueId(), baseName(p.getWorld().getName()));
        if (ts == null) ts = "";
        switch (displayName) {
            case "Back", "Back to Archives" -> { UUID id = p.getUniqueId(); String f = archivesFilter.get(id); Bukkit.getScheduler().runTask(plugin, () -> openBackupsFiltered(p, f)); }
            case "Restore ALL" -> { p.closeInventory(); resetService.restoreBackupAsync(p, base, ts); }
            case "Restore Overworld" -> { p.closeInventory(); resetService.restoreBackupAsync(p, base, ts, EnumSet.of(ResetService.Dimension.OVERWORLD)); }
            case "Restore Nether" -> { p.closeInventory(); resetService.restoreBackupAsync(p, base, ts, EnumSet.of(ResetService.Dimension.NETHER)); }
            case "Restore End" -> { p.closeInventory(); resetService.restoreBackupAsync(p, base, ts, EnumSet.of(ResetService.Dimension.END)); }
            case "Delete Archive" -> openDeleteConfirm(p, base, ts);
            default -> { if (displayName.startsWith("Prune Base")) { resetService.pruneBackupsAsync(p, Optional.of(base), true); openBackups(p); } }
        }
    }

    private void handleDeleteBackupClick(Player p, String displayName, ItemMeta meta) {
        // prefer per-player selected backup if present
        BackupSelection sel = selectedBackup.get(p.getUniqueId());
        String base = null; String ts = null;
        if (sel != null) { base = sel.base(); ts = sel.timestamp(); }
        if (base == null || ts == null) {
            String invTitle = PlainTextComponentSerializer.plainText().serialize(((GuiHolder) p.getOpenInventory().getTopInventory().getHolder()).getTitle());
            String raw = invTitle.replace(PlainTextComponentSerializer.plainText().serialize(TITLE_DELETE_ARCHIVE), "");
            String[] parts = raw.split(" @ ", 2);
            if (parts.length != 2) { openBackups(p); return; }
            base = parts[0]; ts = parts[1];
        }
        switch (displayName) {
            case "Confirm Delete" -> { p.closeInventory(); resetService.deleteBackupAsync(p, base, ts); }
            case "Cancel" -> { String b = base; String t = ts; Bukkit.getScheduler().runTask(plugin, () -> openBackupOptions(p, b, t)); }
            default -> {}
        }
    }

    private void handleDeleteAllClick(Player p, String displayName) {
        String invTitle = PlainTextComponentSerializer.plainText().serialize(((GuiHolder) p.getOpenInventory().getTopInventory().getHolder()).getTitle());
        String base = invTitle.replace("BetterReset | Delete ALL | ", "").replace("Delete ALL | ", "");
        if (displayName.equalsIgnoreCase("Cancel")) { Bukkit.getScheduler().runTask(plugin, () -> openBackups(p)); }
        else if (displayName.equalsIgnoreCase("Confirm Delete All")) { p.closeInventory(); resetService.deleteAllBackupsForBaseAsync(p, base); }
    }

    private void handleSeedSelectorClick(Player p, String displayName) {
        if (displayName.equalsIgnoreCase("Back") || displayName.equalsIgnoreCase("Back to BetterReset")) { String base = selectedBase.getOrDefault(p.getUniqueId(), baseName(p.getWorld().getName())); Bukkit.getScheduler().runTask(plugin, () -> openResetOptions(p, base)); return; }
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
                        if (plugin.getConfig().getBoolean("teleportMode.enabled", false)) {
                            resetService.startTeleportWithCountdown(p, base, Optional.of(seed), dims);
                        } else {
                            resetService.startResetWithCountdown(p, base, Optional.of(seed), dims);
                        }
                    }
                } catch (NumberFormatException ignored) {}
            }
        }
    }

    private void handleDeleteAllGlobalClick(Player p, String displayName) {
        if (displayName.equalsIgnoreCase("Cancel")) { openBackups(p); return; }
        if (displayName.equalsIgnoreCase("Confirm Delete ALL")) { p.closeInventory(); resetService.deleteAllBackupsAsync(p); }
    }

    // ===== Config Browser (recursive) =====
    public void openConfigBrowser(Player p, String path) {
        boolean root = (path == null || path.isEmpty());
        Component title = root ? TextComponents.blue("BetterReset | All Settings") : TextComponents.blue("BetterReset | Settings | ").append(Component.text(path));
        GuiHolder holder = new GuiHolder(GuiHolder.Type.CONFIG_BROWSER, title);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);

        org.bukkit.configuration.ConfigurationSection sec = root ? plugin.getConfig() : plugin.getConfig().getConfigurationSection(path);
        int slot = 10;
        if (sec != null) {
            for (String key : new java.util.TreeSet<>(sec.getKeys(false))) {
                if (slot >= 44) break;
                String full = root ? key : (path + "." + key);
                Object val = plugin.getConfig().get(full);
                if (val instanceof org.bukkit.configuration.ConfigurationSection) {
                    ItemStack it = namedComponent(Material.BOOK, TextComponents.white(key + "/"), TextComponents.gray("Open section"));
                    ItemMeta m = it.getItemMeta();
                    if (m != null) {
                        m.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_path"), PersistentDataType.STRING, full);
                        m.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_type"), PersistentDataType.STRING, "SECTION");
                        it.setItemMeta(m);
                    }
                    inv.setItem(slot++, it);
                } else {
                    ItemStack it = buildSettingItemForPath(full, val);
                    inv.setItem(slot++, it);
                }
            }
        } else {
            inv.setItem(13, namedComponent(Material.BARRIER, TextComponents.red("No such section")));
        }
        inv.setItem(49, namedComponent(Material.ARROW, TextComponents.yellow("Back to BetterReset")));
        p.openInventory(inv);
    }

    private ItemStack buildSettingItemForPath(String fullPath, Object val) {
        String key = fullPath.substring(fullPath.lastIndexOf('.')+1);
        Material icon = Material.PAPER;
        String type = "STRING";
        Component name = TextComponents.white(key);
        List<Component> lore = new ArrayList<>();
        if (val instanceof Boolean b) {
            icon = b ? Material.LIME_DYE : Material.RED_DYE;
            type = "BOOLEAN";
            lore.add(TextComponents.gray("Type: Boolean"));
            lore.add(TextComponents.gray("Click to toggle"));
            lore.add(TextComponents.gray("Current: ").append(TextComponents.white(String.valueOf(b))));
        } else if (val instanceof Integer i) {
            icon = Material.COMPARATOR;
            type = "INT";
            lore.add(TextComponents.gray("Type: Integer"));
            lore.add(TextComponents.gray("Click to set via chat"));
            lore.add(TextComponents.gray("Current: ").append(TextComponents.white(String.valueOf(i))));
        } else if (val instanceof Long l) {
            icon = Material.COMPARATOR;
            type = "LONG";
            lore.add(TextComponents.gray("Type: Long"));
            lore.add(TextComponents.gray("Click to set via chat"));
            lore.add(TextComponents.gray("Current: ").append(TextComponents.white(String.valueOf(l))));
        } else if (val instanceof Double d) {
            icon = Material.CLOCK;
            type = "DOUBLE";
            lore.add(TextComponents.gray("Type: Double"));
            lore.add(TextComponents.gray("Click to set via chat"));
            lore.add(TextComponents.gray("Current: ").append(TextComponents.white(String.valueOf(d))));
        } else {
            icon = Material.PAPER;
            type = "STRING";
            lore.add(TextComponents.gray("Type: String"));
            lore.add(TextComponents.gray("Click to set via chat"));
            lore.add(TextComponents.gray("Current: ").append(TextComponents.white(String.valueOf(val))));
        }
        ItemStack it = new ItemStack(icon);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(name);
            m.lore(lore);
            // Tag UI and store path/type
            try { m.getPersistentDataContainer().set(new NamespacedKey("betterreset","ui"), PersistentDataType.BYTE, (byte)1); } catch (Throwable ignored) {}
            m.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_path"), PersistentDataType.STRING, fullPath);
            m.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_type"), PersistentDataType.STRING, type);
            it.setItemMeta(m);
        }
        return it;
    }

    private void handleConfigBrowserClick(Player p, ItemMeta meta, String displayName) {
        if (displayName.equalsIgnoreCase("Back") || displayName.equalsIgnoreCase("Back to BetterReset")) { Bukkit.getScheduler().runTask(plugin, () -> openSettings(p)); return; }
        String path = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "cfg_path"), PersistentDataType.STRING);
        String type = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "cfg_type"), PersistentDataType.STRING);
        if (path == null) return;
        if ("SECTION".equals(type) || displayName.endsWith("/")) {
            Bukkit.getScheduler().runTask(plugin, () -> openConfigBrowser(p, path));
            return;
        }
        // setting value
        switch (type == null ? "STRING" : type) {
            case "BOOLEAN" -> {
                boolean cur = plugin.getConfig().getBoolean(path, false);
                plugin.getConfig().set(path, !cur);
                plugin.saveConfig();
                Messages.send(p, (!cur ? "&aEnabled &r" : "&cDisabled &r") + path);
                String parent = path.contains(".") ? path.substring(0, path.lastIndexOf('.')) : "";
                String reopen = parent;
                Bukkit.getScheduler().runTask(plugin, () -> openConfigBrowser(p, reopen));
            }
            case "INT", "LONG", "DOUBLE", "STRING" -> {
                awaitingConfigPath.put(p.getUniqueId(), path);
                awaitingValueType.put(p.getUniqueId(), type);
                lastSettingsSection.put(p.getUniqueId(), path.contains(".") ? path.substring(0, path.indexOf('.')) : path);
                Messages.send(p, "&eType new value in chat for &r" + path + " &7(Type: " + type + ")");
                p.closeInventory();
            }
        }
    }

    private String human(long bytes) {
        String[] u = {"B","KB","MB","GB","TB"};
        double b = Math.max(0, bytes);
        int i = 0; while (b >= 1024 && i < u.length - 1) { b /= 1024; i++; }
        return String.format(java.util.Locale.US, "%.1f %s", b, u[i]);
    }

    private String formatDuration(long seconds) {
        if (seconds < 0) return "-";
        long h = seconds / 3600; long m = (seconds % 3600) / 60; long s = seconds % 60;
        if (h > 0) return String.format(java.util.Locale.US, "%dh %02dm %02ds", h, m, s);
        return String.format(java.util.Locale.US, "%dm %02ds", m, s);
    }

    private void handleMessagesClick(Player p, String displayName) {
        if (displayName.equalsIgnoreCase("Back") || displayName.equalsIgnoreCase("Back to BetterReset")) { Bukkit.getScheduler().runTask(plugin, () -> openSettings(p)); return; }
        String path = "messages." + displayName;
        awaitingConfigPath.put(p.getUniqueId(), path);
        Messages.send(p, "&eType new text in chat. Color codes use &.");
    }

    private void handleSettingsClick(Player p, String dn, org.bukkit.event.inventory.ClickType click) {
        if (dn.equalsIgnoreCase("Back") || dn.equalsIgnoreCase("Back to BetterReset")) { Bukkit.getScheduler().runTask(plugin, () -> openMain(p)); return; }
        switch (dn) {
            case "Confirmation" -> { openSettingsSection(p, "confirmation"); return; }
            case "Players" -> { openSettingsSection(p, "players"); return; }
            case "Limits" -> { openSettingsSection(p, "limits"); return; }
            case "Countdown" -> { openSettingsSection(p, "countdown"); return; }
            case "Preload" -> { openSettingsSection(p, "preload"); return; }
            case "Teleport" -> { openSettingsSection(p, "teleport"); return; }
            case "Backups" -> { openSettingsSection(p, "backups"); return; }
            case "Seeds" -> { openSettingsSection(p, "seeds"); return; }
            case "Deletion" -> { openSettingsSection(p, "deletion"); return; }
            case "Debug" -> { openSettingsSection(p, "debug"); return; }
            case "Teleport Mode" -> { openSettingsSection(p, "teleportMode"); return; }
            case "Messages" -> {
                if (p.hasPermission("betterreset.messages")) { openMessages(p); }
                else { Messages.send(p, plugin.getConfig().getString("messages.noPermission","&cYou don't have permission.")); }
                return;
            }
            case "All Settings (Browser)" -> {
                if (p.hasPermission("betterreset.admin")) { openConfigBrowser(p, ""); }
                else { Messages.send(p, plugin.getConfig().getString("messages.noPermission","&cYou don't have permission.")); }
                return;
            }
            default -> {}
        }
    }

    private void toggleDim(Player p, String base, EnumSet<ResetService.Dimension> dims, ResetService.Dimension d) {
        if (dims.contains(d)) dims.remove(d); else dims.add(d);
        selectedDims.put(p.getUniqueId(), dims);
        openResetOptions(p, base);
    }

    private boolean isGuiDebug() { try { return plugin.getConfig().getBoolean("debug.gui", false); } catch (Exception ignored) { return false; } }

    // VersionCompat delivers ChatMessage objects; adapt callback here
    private void onChatMessage(com.muj3b.betterreset.util.VersionCompat.ChatMessage msg) {
        Player p = msg.getPlayer();
        UUID id = p.getUniqueId();
        String path = awaitingConfigPath.remove(id);
        if (path == null) return;
        String text = msg.getMessageText();
        if (text == null) text = "";
        // Determine expected type
        String expType = awaitingValueType.remove(id);
        if (expType == null) {
            Object cur = plugin.getConfig().get(path);
            if (cur instanceof Boolean) expType = "BOOLEAN";
            else if (cur instanceof Integer) expType = "INT";
            else if (cur instanceof Long) expType = "LONG";
            else if (cur instanceof Double) expType = "DOUBLE";
            else expType = "STRING";
        }
        boolean ok = true;
        switch (expType) {
            case "BOOLEAN" -> {
                String t = text.trim().toLowerCase(java.util.Locale.ROOT);
                boolean val = t.equals("true") || t.equals("on") || t.equals("yes") || t.equals("1");
                plugin.getConfig().set(path, val);
            }
            case "INT" -> {
                try { plugin.getConfig().set(path, Integer.parseInt(text.trim())); }
                catch (Exception ex) { ok = false; Messages.send(p, "&cInvalid integer: &r" + text); }
            }
            case "LONG" -> {
                try { plugin.getConfig().set(path, Long.parseLong(text.trim())); }
                catch (Exception ex) { ok = false; Messages.send(p, "&cInvalid long: &r" + text); }
            }
            case "DOUBLE" -> {
                try { plugin.getConfig().set(path, Double.parseDouble(text.trim())); }
                catch (Exception ex) { ok = false; Messages.send(p, "&cInvalid number: &r" + text); }
            }
            default -> {
                plugin.getConfig().set(path, text);
            }
        }
        if (ok) {
            plugin.saveConfig();
            Messages.send(p, "&aUpdated &e" + path + "&a to: &r" + text);
        }
        // Reopen appropriate UI
        if (path.startsWith("messages.")) {
            Bukkit.getScheduler().runTask(plugin, () -> openMessages(p));
        } else {
            String sec = lastSettingsSection.get(id);
            if (sec == null && path.contains(".")) sec = path.substring(0, path.indexOf('.'));
            final String reopen = sec;
            if (reopen != null) Bukkit.getScheduler().runTask(plugin, () -> openSettingsSection(p, reopen));
        }
    }

    // Open the settings GUI (minimal stub to be expanded). Required by click handlers.
    public void openSettings(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.SETTINGS, TITLE_SETTINGS);
        Inventory inv = Bukkit.createInventory(holder, 54, TITLE_SETTINGS);
        holder.setInventory(inv);
        // Status banner
        boolean tm = plugin.getConfig().getBoolean("teleportMode.enabled", false);
        boolean preload = plugin.getConfig().getBoolean("preload.enabled", true);
        boolean dbg = plugin.getConfig().getBoolean("debug.gui", false) || plugin.getConfig().getBoolean("debug.backups", false);
        inv.setItem(4, namedComponent(Material.PAPER, TextComponents.white("Status"),
                TextComponents.gray("Teleport Mode: ").append(TextComponents.white(tm?"ON":"OFF")),
                TextComponents.gray("Preload: ").append(TextComponents.white(preload?"ON":"OFF")),
                TextComponents.gray("Debug: ").append(TextComponents.white(dbg?"ON":"OFF"))));
        // Categories (with explanatory lore)
        inv.setItem(10, namedComponent(Material.BOOK, TextComponents.white("Confirmation"), TextComponents.gray("Confirm prompts & timeouts")));
        inv.setItem(11, namedComponent(Material.ARMOR_STAND, TextComponents.white("Players"), TextComponents.gray("Respawn, fresh-start, return")));
        inv.setItem(12, namedComponent(Material.COMPARATOR, TextComponents.white("Limits"), TextComponents.gray("Online limits, cooldowns")));
        inv.setItem(13, namedComponent(Material.CLOCK, TextComponents.white("Countdown"), TextComponents.gray("Seconds & broadcast scope")));
        inv.setItem(14, namedComponent(Material.ICE, TextComponents.white("Preload"), TextComponents.gray("Prepare temp worlds in advance")));
        inv.setItem(15, namedComponent(Material.ENDER_PEARL, TextComponents.white("Teleport"), TextComponents.gray("Fallback world & safety")));
        inv.setItem(19, namedComponent(Material.CHEST, TextComponents.white("Backups"), TextComponents.gray("Prune rules & caps")));
        inv.setItem(20, namedComponent(Material.WHEAT_SEEDS, TextComponents.white("Seeds"), TextComponents.gray("Same-seed or random")));
        inv.setItem(21, namedComponent(Material.IRON_PICKAXE, TextComponents.white("Deletion"), TextComponents.gray("Threading & cleanup")));
        inv.setItem(22, namedComponent(Material.REDSTONE, TextComponents.white("Debug"), TextComponents.gray("GUI/backup debug logs")));
        inv.setItem(23, namedComponent(Material.ENDER_EYE, TextComponents.white("Teleport Mode"), TextComponents.gray("Soft-reset: teleport + reset NE")));
        if (p.hasPermission("betterreset.messages")) {
            inv.setItem(16, namedComponent(Material.PAPER, TextComponents.white("Messages"), TextComponents.gray("Edit configurable text")));
        }
        if (p.hasPermission("betterreset.admin")) {
            inv.setItem(25, namedComponent(Material.MAP, TextComponents.white("All Settings (Browser)"), TextComponents.gray("Explore entire config")));
        }
        inv.setItem(49, namedComponent(Material.ARROW, TextComponents.yellow("Back to BetterReset")));
        p.openInventory(inv);
    }

    public void openMessages(Player p) {
        Component title = TextComponents.blue("BetterReset | Messages");
        GuiHolder holder = new GuiHolder(GuiHolder.Type.MESSAGES, title);
        Inventory inv = Bukkit.createInventory(holder, 54, title);
        holder.setInventory(inv);
        org.bukkit.configuration.ConfigurationSection sec = plugin.getConfig().getConfigurationSection("messages");
        int slot = 10;
        if (sec != null) {
            for (String key : new java.util.TreeSet<>(sec.getKeys(false))) {
                if (slot >= 44) break;
                String val = plugin.getConfig().getString("messages." + key, "");
                inv.setItem(slot++, namedComponent(Material.PAPER, TextComponents.white(key), TextComponents.gray(val)));
            }
        } else {
            inv.setItem(13, namedComponent(Material.BARRIER, TextComponents.red("No messages section")));
        }
        inv.setItem(49, namedComponent(Material.ARROW, TextComponents.yellow("Back to BetterReset")));
        p.openInventory(inv);
    }

    public void openSettingsSection(Player p, String section) {
        lastSettingsSection.put(p.getUniqueId(), section);
        // Title composed purely for readability retained in holder below
        GuiHolder holder = new GuiHolder(GuiHolder.Type.SETTINGS_SECTION, TextComponents.blue("BetterReset | Settings | ").append(Component.text(section)));
        Inventory inv = Bukkit.createInventory(holder, 54, holder.getTitle());
        holder.setInventory(inv);
        ConfigurationSection cs = plugin.getConfig().getConfigurationSection(section);
        int slot = 10;
        if (cs != null) {
            for (String key : new java.util.TreeSet<>(cs.getKeys(false))) {
                if (slot >= 44) break;
                String path = section + "." + key;
                Object val = plugin.getConfig().get(path);
                ItemStack item = buildSettingItem(section, key, val);
                inv.setItem(slot++, item);
            }
        } else {
            inv.setItem(13, namedComponent(Material.BARRIER, TextComponents.red("No such section: "+section)));
        }
        inv.setItem(49, namedComponent(Material.ARROW, TextComponents.yellow("Back to Categories")));
        p.openInventory(inv);
    }

    private ItemStack buildSettingItem(String section, String key, Object val) {
        Material icon = Material.PAPER;
        String type = "STRING";
        Component name = TextComponents.white(key);
        List<Component> lore = new ArrayList<>();
        if (val instanceof Boolean b) {
            icon = b ? Material.LIME_DYE : Material.RED_DYE;
            type = "BOOLEAN";
            lore.add(TextComponents.gray("Type: Boolean"));
            lore.add(TextComponents.gray("Click to toggle"));
            lore.add(TextComponents.gray("Current: ").append(TextComponents.white(String.valueOf(b))));
        } else if (val instanceof Integer i) {
            icon = Material.COMPARATOR;
            type = "INT";
            lore.add(TextComponents.gray("Type: Integer"));
            lore.add(TextComponents.gray("Click to set via chat"));
            lore.add(TextComponents.gray("Current: ").append(TextComponents.white(String.valueOf(i))));
        } else if (val instanceof Long l) {
            icon = Material.COMPARATOR;
            type = "LONG";
            lore.add(TextComponents.gray("Type: Long"));
            lore.add(TextComponents.gray("Click to set via chat"));
            lore.add(TextComponents.gray("Current: ").append(TextComponents.white(String.valueOf(l))));
        } else if (val instanceof Double d) {
            icon = Material.CLOCK;
            type = "DOUBLE";
            lore.add(TextComponents.gray("Type: Double"));
            lore.add(TextComponents.gray("Click to set via chat"));
            lore.add(TextComponents.gray("Current: ").append(TextComponents.white(String.valueOf(d))));
        } else {
            icon = Material.PAPER;
            type = "STRING";
            lore.add(TextComponents.gray("Type: String"));
            lore.add(TextComponents.gray("Click to set via chat"));
            lore.add(TextComponents.gray("Current: ").append(TextComponents.white(String.valueOf(val))));
        }
        ItemStack it = new ItemStack(icon);
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.displayName(name);
            m.lore(lore);
            // Tag item as BetterReset UI
            try { m.getPersistentDataContainer().set(new NamespacedKey("betterreset","ui"), PersistentDataType.BYTE, (byte)1); } catch (Throwable ignored) {}
            m.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_section"), PersistentDataType.STRING, section);
            m.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_key"), PersistentDataType.STRING, key);
            m.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_type"), PersistentDataType.STRING, type);
            it.setItemMeta(m);
        }
        return it;
    }

    private void handleSettingsSectionClick(Player p, ItemMeta meta, String displayName) {
        // Back navigation buttons
        if ("Back to Categories".equalsIgnoreCase(displayName) || "Back".equalsIgnoreCase(displayName)) { Bukkit.getScheduler().runTask(plugin, () -> openSettings(p)); return; }
        String section = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "cfg_section"), PersistentDataType.STRING);
        String key = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "cfg_key"), PersistentDataType.STRING);
        String type = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "cfg_type"), PersistentDataType.STRING);
        if (section == null || key == null) return;
        String path = section + "." + key;
        switch (type == null ? "STRING" : type) {
            case "BOOLEAN" -> {
                boolean cur = plugin.getConfig().getBoolean(path, false);
                plugin.getConfig().set(path, !cur);
                plugin.saveConfig();
                Messages.send(p, (!cur ? "&aEnabled &r" : "&cDisabled &r") + path);
                openSettingsSection(p, section);
            }
            case "INT", "LONG", "DOUBLE" -> {
                openSettingEditor(p, section, key, type);
            }
            case "STRING" -> {
                awaitingConfigPath.put(p.getUniqueId(), path);
                awaitingValueType.put(p.getUniqueId(), type);
                lastSettingsSection.put(p.getUniqueId(), section);
                Messages.send(p, "&eType new value in chat for &r" + path + " &7(Type: " + type + ")");
                p.closeInventory();
            }
        }
    }

    private void openSettingEditor(Player p, String section, String key, String type) {
        String path = section + "." + key;
        Component title = TextComponents.blue("Edit | ").append(Component.text(path));
        GuiHolder holder = new GuiHolder(GuiHolder.Type.SETTING_EDIT, title);
        Inventory inv = Bukkit.createInventory(holder, 27, title);
        holder.setInventory(inv);
        // Determine current value
        double curD = 0; long curL = 0; int curI = 0;
        if ("DOUBLE".equals(type)) curD = plugin.getConfig().getDouble(path);
        else if ("LONG".equals(type)) curL = plugin.getConfig().getLong(path);
        else curI = plugin.getConfig().getInt(path);
        // Build buttons
        addSettingEditorButton(inv, 10, Material.REDSTONE, "-BIG", section, key, type, "dec_big");
        addSettingEditorButton(inv, 11, Material.REDSTONE_TORCH, "-SMALL", section, key, type, "dec_small");
        ItemStack cur = namedComponent(Material.PAPER, TextComponents.white("Current: " + ("DOUBLE".equals(type) ? curD : ("LONG".equals(type) ? curL : curI))));
        ItemMeta cm = cur.getItemMeta();
        if (cm != null) {
            cm.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_section"), PersistentDataType.STRING, section);
            cm.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_key"), PersistentDataType.STRING, key);
            cm.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_type"), PersistentDataType.STRING, type);
            cur.setItemMeta(cm);
        }
        inv.setItem(13, cur);
        addSettingEditorButton(inv, 15, Material.GLOWSTONE_DUST, "+SMALL", section, key, type, "inc_small");
        addSettingEditorButton(inv, 16, Material.GLOWSTONE, "+BIG", section, key, type, "inc_big");
        addSettingEditorButton(inv, 20, Material.NAME_TAG, "Type Value", section, key, type, "type_chat");
        addSettingEditorButton(inv, 22, Material.ARROW, "Back", section, key, type, "back");
        p.openInventory(inv);
    }

    private void addSettingEditorButton(Inventory inv, int slot, Material mat, String label, String section, String key, String type, String op) {
        ItemStack it = namedComponent(mat, TextComponents.white(label));
        ItemMeta m = it.getItemMeta();
        if (m != null) {
            m.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_section"), PersistentDataType.STRING, section);
            m.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_key"), PersistentDataType.STRING, key);
            m.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_type"), PersistentDataType.STRING, type);
            m.getPersistentDataContainer().set(new NamespacedKey(plugin, "cfg_op"), PersistentDataType.STRING, op);
            it.setItemMeta(m);
        }
        inv.setItem(slot, it);
    }

    private void handleSettingEditorClick(Player p, ItemMeta meta, String displayName) {
        String section = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "cfg_section"), PersistentDataType.STRING);
        String key = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "cfg_key"), PersistentDataType.STRING);
        String type = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "cfg_type"), PersistentDataType.STRING);
        String op = meta.getPersistentDataContainer().get(new NamespacedKey(plugin, "cfg_op"), PersistentDataType.STRING);
        if (section == null || key == null || type == null || op == null) return;
        String path = section + "." + key;
        switch (op) {
            case "back" -> { openSettingsSection(p, section); return; }
            case "type_chat" -> {
                awaitingConfigPath.put(p.getUniqueId(), path);
                awaitingValueType.put(p.getUniqueId(), type);
                lastSettingsSection.put(p.getUniqueId(), section);
                Messages.send(p, "&eType new value in chat for &r" + path + " &7(Type: " + type + ")");
                p.closeInventory();
                return;
            }
        }
        // numeric ops
        if ("DOUBLE".equals(type)) {
            double cur = plugin.getConfig().getDouble(path);
            double small = plugin.getConfig().getDouble("editorSteps."+section+"."+key+".small", 0.1);
            double big = plugin.getConfig().getDouble("editorSteps."+section+"."+key+".big", 1.0);
            if (op.equals("dec_small")) cur -= small;
            else if (op.equals("dec_big")) cur -= big;
            else if (op.equals("inc_small")) cur += small;
            else if (op.equals("inc_big")) cur += big;
            plugin.getConfig().set(path, cur);
            plugin.saveConfig();
            openSettingEditor(p, section, key, type);
        } else if ("LONG".equals(type)) {
            long cur = plugin.getConfig().getLong(path);
            long small = (long) plugin.getConfig().getDouble("editorSteps."+section+"."+key+".small", 1);
            long big = (long) plugin.getConfig().getDouble("editorSteps."+section+"."+key+".big", 10);
            if (op.equals("dec_small")) cur -= small;
            else if (op.equals("dec_big")) cur -= big;
            else if (op.equals("inc_small")) cur += small;
            else if (op.equals("inc_big")) cur += big;
            plugin.getConfig().set(path, cur);
            plugin.saveConfig();
            openSettingEditor(p, section, key, type);
        } else { // INT default
            int cur = plugin.getConfig().getInt(path);
            int small = (int) plugin.getConfig().getDouble("editorSteps."+section+"."+key+".small", 1);
            int big = (int) plugin.getConfig().getDouble("editorSteps."+section+"."+key+".big", 10);
            if (op.equals("dec_small")) cur -= small;
            else if (op.equals("dec_big")) cur -= big;
            else if (op.equals("inc_small")) cur += small;
            else if (op.equals("inc_big")) cur += big;
            plugin.getConfig().set(path, cur);
            plugin.saveConfig();
            openSettingEditor(p, section, key, type);
        }
    }

    // Flip a boolean config path and notify the player (stub implementation).
    public void flip(Player p, String path) {
        // Toggle present boolean in memory (best-effort); persist back to config if possible.
        boolean current = plugin.getConfig().getBoolean(path, false);
        plugin.getConfig().set(path, !current);
        plugin.saveConfig();
        Messages.send(p, (current ? "&cDisabled &r" : "&aEnabled &r") + path);
        openSettings(p);
    }
}
