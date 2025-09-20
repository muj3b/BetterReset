package com.muj3b.betterreset.ui;

import com.muj3b.betterreset.FullResetPlugin;
import com.muj3b.betterreset.core.ResetService;
import com.muj3b.betterreset.util.BackupManager;
import com.muj3b.betterreset.util.Messages;
import com.muj3b.betterreset.util.TextComponents;

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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.inventory.InventoryDragEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.*;

/**
 * Simplified GUI manager - fewer menus, clearer navigation, no jumping to other plugins
 */
public class SimpleGuiManager implements Listener {
    
    private static final String GUI_ID = "BetterResetGUI";
    private final FullResetPlugin plugin;
    private final ResetService resetService;
    
    // Track what archive page each player is on
    private final Map<UUID, Integer> archivePage = new HashMap<>();
    // Track selected dimensions for reset
    private final Map<UUID, EnumSet<ResetService.Dimension>> selectedDims = new HashMap<>();
    
    public SimpleGuiManager(FullResetPlugin plugin, ResetService resetService) {
        this.plugin = plugin;
        this.resetService = resetService;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }
    
    // Create an item with name and lore
    private ItemStack createItem(Material mat, String name, String... lore) {
        ItemStack item = new ItemStack(mat);
        ItemMeta meta = item.getItemMeta();
        if (meta != null) {
            meta.displayName(TextComponents.white(name));
            if (lore.length > 0) {
                List<Component> loreList = new ArrayList<>();
                for (String line : lore) {
                    loreList.add(TextComponents.gray(line));
                }
                meta.lore(loreList);
            }
            // Mark this as a BetterReset item
            meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "gui_id"),
                PersistentDataType.STRING,
                GUI_ID
            );
            item.setItemMeta(meta);
        }
        return item;
    }
    
    // Check if an inventory belongs to BetterReset
    private boolean isBetterResetGUI(Inventory inv) {
        if (inv == null || !(inv.getHolder() instanceof GuiHolder)) return false;
        
        // Additional check - verify at least one item has our GUI_ID
        for (ItemStack item : inv.getContents()) {
            if (item != null && item.hasItemMeta()) {
                ItemMeta meta = item.getItemMeta();
                String id = meta.getPersistentDataContainer().get(
                    new NamespacedKey(plugin, "gui_id"),
                    PersistentDataType.STRING
                );
                if (GUI_ID.equals(id)) return true;
            }
        }
        return false;
    }
    
    @EventHandler(priority = EventPriority.HIGHEST, ignoreCancelled = false)
    public void onInventoryClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        
        Inventory top = e.getView().getTopInventory();
        if (!isBetterResetGUI(top)) return;
        
        e.setCancelled(true);
        
        ItemStack clicked = e.getCurrentItem();
        if (clicked == null || !clicked.hasItemMeta()) return;
        
        GuiHolder holder = (GuiHolder) top.getHolder();
        String itemName = PlainTextComponentSerializer.plainText()
            .serialize(clicked.getItemMeta().displayName());
        
        // Route to the appropriate handler based on GUI type
        switch (holder.getType()) {
            case MAIN -> handleMainClick(p, itemName);
            case RESET -> handleResetClick(p, itemName);
            case ARCHIVES -> handleArchivesClick(p, itemName, clicked.getItemMeta());
            case ARCHIVE_OPTIONS -> handleArchiveOptionsClick(p, itemName, clicked.getItemMeta());
            case SETTINGS -> handleSettingsClick(p, itemName);
        }
    }
    
    @EventHandler(priority = EventPriority.HIGHEST)
    public void onInventoryDrag(InventoryDragEvent e) {
        Inventory top = e.getView().getTopInventory();
        if (isBetterResetGUI(top)) {
            e.setCancelled(true);
        }
    }
    
    @EventHandler
    public void onInventoryClose(InventoryCloseEvent e) {
        // Clean up any temporary data when GUI closes
        if (e.getPlayer() instanceof Player p) {
            // Could clean up temporary maps here if needed
        }
    }
    
    // ============= MAIN MENU =============
    
    public void openMainMenu(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.MAIN, 
            TextComponents.darkAqua("BetterReset"));
        Inventory inv = Bukkit.createInventory(holder, 27, holder.getTitle());
        holder.setInventory(inv);
        
        // Simple 3-option menu
        boolean teleportMode = plugin.getConfig().getBoolean("teleportMode.enabled", false);
        
        inv.setItem(11, createItem(
            teleportMode ? Material.ENDER_PEARL : Material.GRASS_BLOCK,
            teleportMode ? "Teleport" : "Reset",
            "Current world: " + baseName(p.getWorld().getName()),
            "Click to open options"
        ));
        
        inv.setItem(13, createItem(
            Material.CHEST,
            "Archives",
            "Browse & restore backups",
            "Total: " + resetService.listBackups().size()
        ));
        
        inv.setItem(15, createItem(
            Material.COMPARATOR,
            "Settings",
            "Quick settings toggle"
        ));
        
        p.openInventory(inv);
    }
    
    private void handleMainClick(Player p, String itemName) {
        switch (itemName) {
            case "Reset", "Teleport" -> openResetMenu(p);
            case "Archives" -> openArchivesMenu(p, 0);
            case "Settings" -> openSettingsMenu(p);
        }
    }
    
    // ============= RESET/TELEPORT MENU =============
    
    public void openResetMenu(Player p) {
        String base = baseName(p.getWorld().getName());
        EnumSet<ResetService.Dimension> dims = selectedDims.computeIfAbsent(
            p.getUniqueId(), 
            k -> EnumSet.of(ResetService.Dimension.OVERWORLD)
        );
        
        boolean teleportMode = plugin.getConfig().getBoolean("teleportMode.enabled", false);
        String modeText = teleportMode ? "Teleport" : "Reset";
        
        GuiHolder holder = new GuiHolder(GuiHolder.Type.RESET,
            TextComponents.darkGreen(modeText + " - " + base));
        Inventory inv = Bukkit.createInventory(holder, 36, holder.getTitle());
        holder.setInventory(inv);
        
        // Mode toggle at top
        inv.setItem(4, createItem(
            Material.LEVER,
            "Mode: " + modeText,
            "Click to switch"
        ));
        
        // Dimension toggles
        inv.setItem(11, createItem(
            dims.contains(ResetService.Dimension.OVERWORLD) ? Material.GRASS_BLOCK : Material.BARRIER,
            "Overworld",
            dims.contains(ResetService.Dimension.OVERWORLD) ? "ENABLED" : "DISABLED",
            "Click to toggle"
        ));
        
        inv.setItem(13, createItem(
            dims.contains(ResetService.Dimension.NETHER) ? Material.NETHERRACK : Material.BARRIER,
            "Nether",
            dims.contains(ResetService.Dimension.NETHER) ? "ENABLED" : "DISABLED",
            "Click to toggle"
        ));
        
        inv.setItem(15, createItem(
            dims.contains(ResetService.Dimension.END) ? Material.END_STONE : Material.BARRIER,
            "End",
            dims.contains(ResetService.Dimension.END) ? "ENABLED" : "DISABLED",
            "Click to toggle"
        ));
        
        // Action button
        inv.setItem(22, createItem(
            Material.LIME_WOOL,
            modeText + " Now!",
            "Random seed",
            getSelectedDimsText(dims)
        ));
        
        // Back button
        inv.setItem(31, createItem(Material.ARROW, "Back to BetterReset"));
        
        p.openInventory(inv);
    }
    
    private void handleResetClick(Player p, String itemName) {
        UUID id = p.getUniqueId();
        String base = baseName(p.getWorld().getName());
        EnumSet<ResetService.Dimension> dims = selectedDims.get(id);
        if (dims == null || dims.isEmpty()) {
            dims = EnumSet.of(ResetService.Dimension.OVERWORLD);
        }
        
        switch (itemName) {
            case "Back to BetterReset" -> openMainMenu(p);
            case "Mode: Reset" -> {
                plugin.getConfig().set("teleportMode.enabled", true);
                plugin.saveConfig();
                openResetMenu(p);
            }
            case "Mode: Teleport" -> {
                plugin.getConfig().set("teleportMode.enabled", false);
                plugin.saveConfig();
                openResetMenu(p);
            }
            case "Overworld" -> {
                toggleDimension(dims, ResetService.Dimension.OVERWORLD);
                openResetMenu(p);
            }
            case "Nether" -> {
                toggleDimension(dims, ResetService.Dimension.NETHER);
                openResetMenu(p);
            }
            case "End" -> {
                toggleDimension(dims, ResetService.Dimension.END);
                openResetMenu(p);
            }
            case "Reset Now!", "Teleport Now!" -> {
                p.closeInventory();
                if (plugin.getConfig().getBoolean("teleportMode.enabled", false)) {
                    resetService.startTeleportWithCountdown(p, base, Optional.empty(), dims);
                } else {
                    resetService.startReset(p, base, dims);
                }
            }
        }
    }
    
    private void toggleDimension(EnumSet<ResetService.Dimension> dims, ResetService.Dimension dim) {
        if (dims.contains(dim)) {
            dims.remove(dim);
        } else {
            dims.add(dim);
        }
    }
    
    private String getSelectedDimsText(EnumSet<ResetService.Dimension> dims) {
        List<String> names = new ArrayList<>();
        if (dims.contains(ResetService.Dimension.OVERWORLD)) names.add("Overworld");
        if (dims.contains(ResetService.Dimension.NETHER)) names.add("Nether");  
        if (dims.contains(ResetService.Dimension.END)) names.add("End");
        return names.isEmpty() ? "None selected" : String.join(", ", names);
    }
    
    // ============= ARCHIVES MENU =============
    
    public void openArchivesMenu(Player p, int page) {
        List<BackupManager.BackupRef> backups = resetService.listBackups();
        
        GuiHolder holder = new GuiHolder(GuiHolder.Type.ARCHIVES,
            TextComponents.gold("Archives"));
        Inventory inv = Bukkit.createInventory(holder, 54, holder.getTitle());
        holder.setInventory(inv);
        
        // Header info
        inv.setItem(4, createItem(
            Material.BOOK,
            "Archive Info",
            "Total: " + backups.size(),
            "Click any to restore"
        ));
        
        // Pagination
        int pageSize = 45; // Use slots 9-53
        int totalPages = Math.max(1, (backups.size() + pageSize - 1) / pageSize);
        page = Math.min(Math.max(0, page), totalPages - 1);
        archivePage.put(p.getUniqueId(), page);
        
        int start = page * pageSize;
        int end = Math.min(start + pageSize, backups.size());
        
        // Display archives
        int slot = 9;
        for (int i = start; i < end && slot < 54; i++, slot++) {
            BackupManager.BackupRef ref = backups.get(i);
            ItemStack item = createItem(
                Material.CHEST,
                ref.base() + " @ " + ref.timestamp(),
                "Click to view options"
            );
            
            // Store backup info in metadata
            ItemMeta meta = item.getItemMeta();
            meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "backup_base"),
                PersistentDataType.STRING,
                ref.base()
            );
            meta.getPersistentDataContainer().set(
                new NamespacedKey(plugin, "backup_timestamp"),
                PersistentDataType.STRING,
                ref.timestamp()
            );
            item.setItemMeta(meta);
            
            inv.setItem(slot, item);
        }
        
        // Navigation
        if (page > 0) {
            inv.setItem(0, createItem(Material.ARROW, "Previous Page"));
        }
        if (page < totalPages - 1) {
            inv.setItem(8, createItem(Material.ARROW, "Next Page"));
        }
        inv.setItem(1, createItem(Material.ARROW, "Back to BetterReset"));
        
        p.openInventory(inv);
    }
    
    private void handleArchivesClick(Player p, String itemName, ItemMeta meta) {
        int page = archivePage.getOrDefault(p.getUniqueId(), 0);
        
        switch (itemName) {
            case "Back to BetterReset" -> openMainMenu(p);
            case "Previous Page" -> openArchivesMenu(p, page - 1);
            case "Next Page" -> openArchivesMenu(p, page + 1);
            default -> {
                // Check if it's an archive item
                String base = meta.getPersistentDataContainer().get(
                    new NamespacedKey(plugin, "backup_base"),
                    PersistentDataType.STRING
                );
                String timestamp = meta.getPersistentDataContainer().get(
                    new NamespacedKey(plugin, "backup_timestamp"),
                    PersistentDataType.STRING
                );
                if (base != null && timestamp != null) {
                    openArchiveOptions(p, base, timestamp);
                }
            }
        }
    }
    
    // ============= ARCHIVE OPTIONS =============
    
    private void openArchiveOptions(Player p, String base, String timestamp) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.ARCHIVE_OPTIONS,
            TextComponents.gold("Archive: " + base));
        Inventory inv = Bukkit.createInventory(holder, 27, holder.getTitle());
        holder.setInventory(inv);
        
        // Store backup info in all items
        ItemStack restoreAll = createItem(
            Material.LIME_WOOL,
            "Restore Everything",
            "Restore all dimensions"
        );
        ItemMeta meta = restoreAll.getItemMeta();
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, "backup_base"),
            PersistentDataType.STRING,
            base
        );
        meta.getPersistentDataContainer().set(
            new NamespacedKey(plugin, "backup_timestamp"),
            PersistentDataType.STRING,
            timestamp
        );
        restoreAll.setItemMeta(meta);
        
        inv.setItem(13, restoreAll);
        inv.setItem(22, createItem(Material.ARROW, "Back to BetterReset"));
        
        p.openInventory(inv);
    }
    
    private void handleArchiveOptionsClick(Player p, String itemName, ItemMeta meta) {
        switch (itemName) {
            case "Back to BetterReset" -> openArchivesMenu(p, archivePage.getOrDefault(p.getUniqueId(), 0));
            case "Restore Everything" -> {
                String base = meta.getPersistentDataContainer().get(
                    new NamespacedKey(plugin, "backup_base"),
                    PersistentDataType.STRING
                );
                String timestamp = meta.getPersistentDataContainer().get(
                    new NamespacedKey(plugin, "backup_timestamp"),
                    PersistentDataType.STRING
                );
                if (base != null && timestamp != null) {
                    p.closeInventory();
                    resetService.restoreBackupAsync(p, base, timestamp);
                }
            }
        }
    }
    
    // ============= SETTINGS MENU =============
    
    public void openSettingsMenu(Player p) {
        GuiHolder holder = new GuiHolder(GuiHolder.Type.SETTINGS,
            TextComponents.blue("Settings"));
        Inventory inv = Bukkit.createInventory(holder, 36, holder.getTitle());
        holder.setInventory(inv);
        
        // Most important settings only - simple toggles
        boolean teleportMode = plugin.getConfig().getBoolean("teleportMode.enabled", false);
        boolean confirmation = plugin.getConfig().getBoolean("confirmation.require", true);
        boolean freshStart = plugin.getConfig().getBoolean("players.freshStart", true);
        boolean preload = plugin.getConfig().getBoolean("preload.enabled", false);
        boolean countdown = plugin.getConfig().getBoolean("countdown.enabled", true);
        
        inv.setItem(10, createItem(
            teleportMode ? Material.ENDER_PEARL : Material.GRASS_BLOCK,
            "Mode: " + (teleportMode ? "Teleport" : "Reset"),
            "Click to toggle",
            "Currently: " + (teleportMode ? "Players teleport far away" : "World fully resets")
        ));
        
        inv.setItem(11, createItem(
            confirmation ? Material.LIME_DYE : Material.RED_DYE,
            "Require Confirmation",
            "Click to toggle",
            "Currently: " + (confirmation ? "ON" : "OFF")
        ));
        
        inv.setItem(12, createItem(
            freshStart ? Material.LIME_DYE : Material.RED_DYE,
            "Fresh Start Players",
            "Click to toggle",
            "Currently: " + (freshStart ? "ON" : "OFF"),
            "Clears inventory/XP on reset"
        ));
        
        inv.setItem(13, createItem(
            countdown ? Material.LIME_DYE : Material.RED_DYE,
            "Countdown",
            "Click to toggle",
            "Currently: " + (countdown ? "ON" : "OFF")
        ));
        
        inv.setItem(14, createItem(
            preload ? Material.LIME_DYE : Material.RED_DYE,
            "Preload Worlds",
            "Click to toggle",
            "Currently: " + (preload ? "ON" : "OFF"),
            "Pre-generate worlds for faster resets"
        ));
        
        // Countdown seconds - click to cycle
        int seconds = plugin.getConfig().getInt("countdown.seconds", 5);
        inv.setItem(21, createItem(
            Material.CLOCK,
            "Countdown: " + seconds + "s",
            "Click to change",
            "Cycles: 3, 5, 10, 15, 30"
        ));
        
        // Teleport distances
        if (teleportMode) {
            int playerDist = plugin.getConfig().getInt("teleportMode.playerDistance", 15000);
            int othersDist = plugin.getConfig().getInt("teleportMode.othersDistance", 50000);
            
            inv.setItem(22, createItem(
                Material.COMPASS,
                "Your Distance: " + playerDist,
                "Click to cycle",
                "How far you teleport"
            ));
            
            inv.setItem(23, createItem(
                Material.COMPASS,
                "Others Distance: " + othersDist,
                "Click to cycle", 
                "How far others teleport"
            ));
        }
        
        inv.setItem(31, createItem(Material.ARROW, "Back to BetterReset"));
        
        p.openInventory(inv);
    }
    
    private void handleSettingsClick(Player p, String itemName) {
        if (itemName.equals("Back to BetterReset")) {
            openMainMenu(p);
            return;
        }
        
        // Toggle settings
        if (itemName.startsWith("Mode:")) {
            boolean current = plugin.getConfig().getBoolean("teleportMode.enabled", false);
            plugin.getConfig().set("teleportMode.enabled", !current);
            plugin.saveConfig();
            openSettingsMenu(p);
            Messages.send(p, (current ? "&cDisabled" : "&aEnabled") + " teleport mode");
        }
        else if (itemName.equals("Require Confirmation")) {
            boolean current = plugin.getConfig().getBoolean("confirmation.require", true);
            plugin.getConfig().set("confirmation.require", !current);
            plugin.saveConfig();
            openSettingsMenu(p);
        }
        else if (itemName.equals("Fresh Start Players")) {
            boolean current = plugin.getConfig().getBoolean("players.freshStart", true);
            plugin.getConfig().set("players.freshStart", !current);
            plugin.saveConfig();
            openSettingsMenu(p);
        }
        else if (itemName.equals("Countdown")) {
            boolean current = plugin.getConfig().getBoolean("countdown.enabled", true);
            plugin.getConfig().set("countdown.enabled", !current);
            plugin.saveConfig();
            openSettingsMenu(p);
        }
        else if (itemName.equals("Preload Worlds")) {
            boolean current = plugin.getConfig().getBoolean("preload.enabled", false);
            plugin.getConfig().set("preload.enabled", !current);
            plugin.saveConfig();
            openSettingsMenu(p);
        }
        else if (itemName.startsWith("Countdown:")) {
            // Cycle through countdown options
            int current = plugin.getConfig().getInt("countdown.seconds", 5);
            int next = switch(current) {
                case 3 -> 5;
                case 5 -> 10;
                case 10 -> 15;
                case 15 -> 30;
                default -> 3;
            };
            plugin.getConfig().set("countdown.seconds", next);
            plugin.saveConfig();
            openSettingsMenu(p);
        }
        else if (itemName.startsWith("Your Distance:")) {
            // Cycle through distance options
            int current = plugin.getConfig().getInt("teleportMode.playerDistance", 15000);
            int next = switch(current) {
                case 5000 -> 10000;
                case 10000 -> 15000;
                case 15000 -> 25000;
                case 25000 -> 50000;
                default -> 5000;
            };
            plugin.getConfig().set("teleportMode.playerDistance", next);
            plugin.saveConfig();
            openSettingsMenu(p);
        }
        else if (itemName.startsWith("Others Distance:")) {
            // Cycle through distance options
            int current = plugin.getConfig().getInt("teleportMode.othersDistance", 50000);
            int next = switch(current) {
                case 10000 -> 25000;
                case 25000 -> 50000;
                case 50000 -> 75000;
                case 75000 -> 100000;
                default -> 10000;
            };
            plugin.getConfig().set("teleportMode.othersDistance", next);
            plugin.saveConfig();
            openSettingsMenu(p);
        }
    }
    
    // Utility method
    private String baseName(String worldName) {
        if (worldName.endsWith("_nether")) return worldName.substring(0, worldName.length() - 7);
        if (worldName.endsWith("_the_end")) return worldName.substring(0, worldName.length() - 8);
        return worldName;
    }
}
