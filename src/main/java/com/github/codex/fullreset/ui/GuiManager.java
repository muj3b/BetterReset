package com.github.codex.fullreset.ui;

import com.github.codex.fullreset.FullResetPlugin;
import com.github.codex.fullreset.core.ResetService;
import com.github.codex.fullreset.util.Messages;
import org.bukkit.Bukkit;
import org.bukkit.ChatColor;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.Listener;
import org.bukkit.event.inventory.InventoryClickEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.stream.Collectors;

/**
 * GUI manager with:
 * - Main menu: Reset Worlds or Backups
 * - Reset flow: pick base world, then choose which dimensions to reset + seed option
 * - Backups: list saved snapshots to restore
 */
public class GuiManager implements Listener {

    private static final String TITLE_MAIN = ChatColor.DARK_AQUA + "BetterReset | Manager";
    private static final String TITLE_SELECT = ChatColor.DARK_GREEN + "Reset | Select World";
    private static final String TITLE_RESET_FOR = ChatColor.DARK_RED + "Reset | ";
    private static final String TITLE_BACKUPS = ChatColor.GOLD + "Backups";
    private static final String TITLE_BACKUP_OPTIONS = ChatColor.DARK_PURPLE + "Restore | ";
    private static final String TITLE_DELETE_BACKUP = ChatColor.DARK_RED + "Delete | ";

    private final FullResetPlugin plugin;
    private final ResetService resetService;

    private final Map<UUID, String> awaitingSeedForWorld = new HashMap<>();
    private final Map<UUID, String> selectedBase = new HashMap<>();
    private final Map<UUID, EnumSet<ResetService.Dimension>> selectedDims = new HashMap<>();

    public GuiManager(FullResetPlugin plugin, ResetService resetService) {
        this.plugin = plugin;
        this.resetService = resetService;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void openMain(Player p) {
        Inventory inv = Bukkit.createInventory(p, 27, TITLE_MAIN);
        // Reset Worlds button
        inv.setItem(11, named(Material.GRASS_BLOCK, ChatColor.GREEN + "Reset Worlds",
                ChatColor.GRAY + "Pick a world and choose",
                ChatColor.GRAY + "Overworld/Nether/End or All"));
        // Backups button
        inv.setItem(15, named(Material.CHEST, ChatColor.GOLD + "Backups",
                ChatColor.GRAY + "Browse and restore backups"));
        p.openInventory(inv);
    }

    public void openWorldSelect(Player p) {
        Inventory inv = Bukkit.createInventory(p, 54, TITLE_SELECT);
        Set<String> bases = Bukkit.getWorlds().stream()
                .map(World::getName)
                .filter(n -> !n.startsWith("betterreset_safe_"))
                .map(GuiManager::baseName)
                .collect(Collectors.toCollection(LinkedHashSet::new));
        int slot = 0;
        for (String base : bases) {
            if (slot >= inv.getSize()) break;
            inv.setItem(slot++, named(Material.GRASS_BLOCK, ChatColor.GREEN + base,
                    ChatColor.GRAY + "Click to configure reset"));
        }
        p.openInventory(inv);
    }

    private void openResetOptions(Player p, String base) {
        selectedBase.put(p.getUniqueId(), base);
        selectedDims.putIfAbsent(p.getUniqueId(), EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END));
        EnumSet<ResetService.Dimension> dims = selectedDims.get(p.getUniqueId());

        Inventory inv = Bukkit.createInventory(p, 45, TITLE_RESET_FOR + base);
        // Toggles
        inv.setItem(10, toggleItem(dims.contains(ResetService.Dimension.OVERWORLD), Material.GRASS_BLOCK, "Overworld"));
        inv.setItem(12, toggleItem(dims.contains(ResetService.Dimension.NETHER), Material.NETHERRACK, "Nether"));
        inv.setItem(14, toggleItem(dims.contains(ResetService.Dimension.END), Material.END_STONE, "The End"));

        // Confirm buttons
        inv.setItem(22, named(Material.LIME_WOOL, ChatColor.GREEN + "Reset Selected (Random Seed)", ChatColor.GRAY + "Starts countdown"));
        inv.setItem(24, named(Material.CYAN_WOOL, ChatColor.AQUA + "Reset Selected (Custom Seed)", ChatColor.GRAY + "Type seed in chat"));
        // Navigation
        inv.setItem(40, named(Material.ARROW, ChatColor.YELLOW + "Back"));
        p.openInventory(inv);
    }

    private void openBackups(Player p) {
        Inventory inv = Bukkit.createInventory(p, 54, TITLE_BACKUPS);
        List<com.github.codex.fullreset.util.BackupManager.BackupRef> refs = resetService.listBackups();
        int slot = 0;
        for (var ref : refs) {
            if (slot >= 53) break; // keep last slot for back
            String label = ChatColor.GOLD + ref.base() + ChatColor.GRAY + " @ " + ChatColor.YELLOW + ref.timestamp();
            List<String> lore = new ArrayList<>();
            lore.add(ChatColor.GRAY + "Click to restore");
            java.util.Properties meta = new java.util.Properties();
            try (java.io.InputStream is = java.nio.file.Files.newInputStream(ref.path().resolve("meta.properties"))) { meta.load(is); } catch (Exception ignored) {}
            String sizeStr = meta.getProperty("sizeBytes");
            String play = meta.getProperty("playtimeSeconds");
            if (sizeStr != null) {
                try {
                    long sz = Long.parseLong(sizeStr);
                    lore.add(ChatColor.DARK_GRAY + "Size: " + human(sz));
                } catch (NumberFormatException ignored) {}
            }
            if (play != null) {
                try {
                    long sec = Long.parseLong(play);
                    lore.add(ChatColor.DARK_GRAY + "Playtime: " + formatDuration(sec));
                } catch (NumberFormatException ignored) {}
            }
            lore.add(ChatColor.DARK_GRAY + "ID:" + ref.base() + "|" + ref.timestamp());
            inv.setItem(slot++, named(Material.CHEST, label, lore.toArray(new String[0])));
        }
        // Prune Now button and Back
        inv.setItem(49, named(Material.SHEARS, ChatColor.RED + "Prune Now"));
        inv.setItem(53, named(Material.ARROW, ChatColor.YELLOW + "Back"));
        p.openInventory(inv);
    }

    private void openBackupOptions(Player p, String base, String ts) {
        Inventory inv = Bukkit.createInventory(p, 27, TITLE_BACKUP_OPTIONS + base + ChatColor.GRAY + " @ " + ts);
        inv.setItem(10, named(Material.LIME_WOOL, ChatColor.GREEN + "Restore ALL"));
        inv.setItem(12, named(Material.GRASS_BLOCK, ChatColor.WHITE + "Restore Overworld"));
        inv.setItem(14, named(Material.NETHERRACK, ChatColor.WHITE + "Restore Nether"));
        inv.setItem(16, named(Material.END_STONE, ChatColor.WHITE + "Restore End"));
        inv.setItem(20, named(Material.TNT, ChatColor.RED + "Delete Backup"));
        inv.setItem(22, named(Material.ARROW, ChatColor.YELLOW + "Back"));
        p.openInventory(inv);
    }

    private void openDeleteConfirm(Player p, String base, String ts) {
        Inventory inv = Bukkit.createInventory(p, 27, TITLE_DELETE_BACKUP + base + ChatColor.GRAY + " @ " + ts);
        inv.setItem(11, named(Material.RED_CONCRETE, ChatColor.DARK_RED + "Confirm Delete"));
        inv.setItem(15, named(Material.ARROW, ChatColor.YELLOW + "Cancel"));
        p.openInventory(inv);
    }

    private void openDeleteAllConfirm(Player p, String base) {
        Inventory inv = Bukkit.createInventory(p, 27, ChatColor.DARK_RED + "Delete ALL | " + base);
        inv.setItem(11, named(Material.RED_CONCRETE, ChatColor.DARK_RED + "Confirm Delete All"));
        inv.setItem(15, named(Material.ARROW, ChatColor.YELLOW + "Cancel"));
        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (title == null) return;
        ItemStack it = e.getCurrentItem();
        if (it == null || !it.hasItemMeta() || it.getItemMeta().getDisplayName() == null) return;
        String dn = ChatColor.stripColor(it.getItemMeta().getDisplayName());

        if (TITLE_MAIN.equals(title)) {
            e.setCancelled(true);
            if (dn.equalsIgnoreCase("Reset Worlds")) {
                openWorldSelect(p);
            } else if (dn.equalsIgnoreCase("Backups")) {
                openBackups(p);
            }
            return;
        }
        if (TITLE_SELECT.equals(title)) {
            e.setCancelled(true);
            String base = ChatColor.stripColor(it.getItemMeta().getDisplayName());
            openResetOptions(p, base);
            return;
        }
        if (title.startsWith(TITLE_RESET_FOR)) {
            e.setCancelled(true);
            String base = ChatColor.stripColor(title.substring(TITLE_RESET_FOR.length()));
            EnumSet<ResetService.Dimension> dims = selectedDims.computeIfAbsent(p.getUniqueId(), k -> EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END));
            switch (dn) {
                case "Overworld" -> toggleDim(p, base, dims, ResetService.Dimension.OVERWORLD);
                case "Nether" -> toggleDim(p, base, dims, ResetService.Dimension.NETHER);
                case "The End" -> toggleDim(p, base, dims, ResetService.Dimension.END);
                case "Reset Selected (Random Seed)" -> {
                    p.closeInventory();
                    resetService.startResetWithCountdown(p, base, Optional.empty(), dims.clone());
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
        if (TITLE_BACKUPS.equals(title)) {
            e.setCancelled(true);
            if (dn.equalsIgnoreCase("Back")) { openMain(p); return; }
            if (dn.equalsIgnoreCase("Prune Now")) {
                if (!p.hasPermission("betterreset.prune")) { Messages.send(p, plugin.getConfig().getString("messages.noPermission")); return; }
                p.closeInventory();
                resetService.pruneBackupsAsync(p, Optional.empty());
                return;
            }
            // Expect format: base @ timestamp
            String base = null, ts = null;
            List<String> lore = it.getItemMeta().getLore();
            if (lore != null) {
                for (String line : lore) {
                    String s = ChatColor.stripColor(line);
                    if (s.startsWith("ID:")) {
                        String[] ids = s.substring(3).split("\\|", 2);
                        if (ids.length == 2) { base = ids[0]; ts = ids[1]; break; }
                    }
                }
            }
            if (base == null || ts == null) {
                String raw = ChatColor.stripColor(it.getItemMeta().getDisplayName());
                String[] parts = raw.split(" @ ", 2);
                if (parts.length == 2) { base = parts[0]; ts = parts[1]; }
            }
            if (base != null && ts != null) openBackupOptions(p, base, ts);
            return;
        }
        if (title.startsWith(TITLE_BACKUP_OPTIONS)) {
            e.setCancelled(true);
            String raw = ChatColor.stripColor(title.substring(TITLE_BACKUP_OPTIONS.length())); // base @ ts
            String[] parts = raw.split(" @ ", 2);
            if (parts.length != 2) { openBackups(p); return; }
            String base = parts[0];
            String ts = parts[1];
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
        if (title.startsWith(TITLE_DELETE_BACKUP)) {
            e.setCancelled(true);
            String raw = ChatColor.stripColor(title.substring(TITLE_DELETE_BACKUP.length())); // base @ ts
            String[] parts = raw.split(" @ ", 2);
            if (parts.length != 2) { openBackups(p); return; }
            String base = parts[0];
            String ts = parts[1];
            switch (dn) {
                case "Confirm Delete" -> { p.closeInventory(); resetService.deleteBackupAsync(p, base, ts); }
                case "Cancel" -> openBackupOptions(p, base, ts);
            }
            return;
        }
        if (title.startsWith(ChatColor.DARK_RED + "Delete ALL | ")) {
            e.setCancelled(true);
            String base = ChatColor.stripColor(title.substring((ChatColor.DARK_RED + "Delete ALL | ").length()));
            switch (dn) {
                case "Confirm Delete All" -> { p.closeInventory(); resetService.deleteAllBackupsForBaseAsync(p, base); }
                case "Cancel" -> openBackups(p);
            }
            return;
        }
    }

    @EventHandler
    public void onChat(AsyncPlayerChatEvent e) {
        UUID id = e.getPlayer().getUniqueId();
        String base = awaitingSeedForWorld.get(id);
        if (base == null) return;
        e.setCancelled(true);
        String msg = e.getMessage().trim();
        Optional<Long> seed = Optional.empty();
        if (!msg.equalsIgnoreCase("random")) {
            try { seed = Optional.of(Long.parseLong(msg)); }
            catch (NumberFormatException ex) { Messages.send(e.getPlayer(), "&cInvalid seed. Type a number or 'random'."); return; }
        }
        awaitingSeedForWorld.remove(id);
        EnumSet<ResetService.Dimension> dims = selectedDims.getOrDefault(id, EnumSet.of(ResetService.Dimension.OVERWORLD, ResetService.Dimension.NETHER, ResetService.Dimension.END));
        Player p = e.getPlayer();
        final EnumSet<ResetService.Dimension> dimsCopy = EnumSet.copyOf(dims);
        final String finalBase = base;
        final Optional<Long> seedFinal = seed;
        Bukkit.getScheduler().runTask(plugin, () -> resetService.startResetWithCountdown(p, finalBase, seedFinal, dimsCopy));
    }

    private void toggleDim(Player p, String base, EnumSet<ResetService.Dimension> dims, ResetService.Dimension d) {
        if (dims.contains(d)) dims.remove(d); else dims.add(d);
        if (dims.isEmpty()) dims.add(ResetService.Dimension.OVERWORLD); // keep at least one
        openResetOptions(p, base);
    }

    private static ItemStack toggleItem(boolean on, Material mat, String name) {
        ItemStack it = new ItemStack(on ? mat : Material.BARRIER);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(ChatColor.WHITE + name);
        m.setLore(Collections.singletonList(on ? ChatColor.GREEN + "[ON]" : ChatColor.RED + "[OFF]"));
        it.setItemMeta(m);
        return it;
    }

    private static ItemStack named(Material mat, String name, String... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta m = it.getItemMeta();
        m.setDisplayName(name);
        if (lore != null && lore.length > 0) m.setLore(Arrays.asList(lore));
        it.setItemMeta(m);
        return it;
    }

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
