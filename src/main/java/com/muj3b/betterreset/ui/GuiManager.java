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
// removed unused import
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
        return com.muj3b.betterreset.ui.GuiHolderComponents.namedComponent(use, title, state);
    }

    private static String baseName(String input) {
        if (input.endsWith("_nether")) return input.substring(0, input.length() - 7);
        if (input.endsWith("_the_end")) return input.substring(0, input.length() - 8);
        return input;
    }

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

    private void handleSettingsClick(Player p, String dn, org.bukkit.event.inventory.ClickType click) {
        if (dn.equalsIgnoreCase("Back")) { openMain(p); return; }
        if (dn.equalsIgnoreCase("Preload Enabled")) { flip(p, "preload.enabled"); return; }
        // other settings handlers omitted for brevity in this migration
    }

    private void toggleDim(Player p, String base, EnumSet<ResetService.Dimension> dims, ResetService.Dimension d) {
        if (dims.contains(d)) dims.remove(d); else dims.add(d);
        selectedDims.put(p.getUniqueId(), dims);
        openResetOptions(p, base);
    }

    // VersionCompat delivers ChatMessage objects; adapt callback here
    private void onChatMessage(com.muj3b.betterreset.util.VersionCompat.ChatMessage msg) {
        // Default: do nothing. Implementations can use msg.getPlayer() and msg.getMessage()
    }

    // Open the settings GUI (minimal stub to be expanded). Required by click handlers.
    public void openSettings(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.SETTINGS, TITLE_SETTINGS);
        Inventory inv = Bukkit.createInventory(holder, 27, TITLE_SETTINGS);
        holder.setInventory(inv);
        inv.setItem(22, namedComponent(Material.ARROW, TextComponents.yellow("Back")));
        p.openInventory(inv);
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
