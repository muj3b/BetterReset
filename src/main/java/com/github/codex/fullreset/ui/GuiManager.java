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
import org.bukkit.event.inventory.InventoryCloseEvent;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Minimal GUI manager with world selection and confirmation.
 * Also supports a chat-based custom seed prompt.
 */
public class GuiManager implements Listener {

    private final FullResetPlugin plugin;
    private final ResetService resetService;

    private final Map<UUID, String> awaitingSeedForWorld = new HashMap<>();

    public GuiManager(FullResetPlugin plugin, ResetService resetService) {
        this.plugin = plugin;
        this.resetService = resetService;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    public void openWorldSelect(Player p) {
        Inventory inv = Bukkit.createInventory(p, 54, ChatColor.DARK_AQUA + "BetterReset | World Select");
        Set<String> bases = Bukkit.getWorlds().stream()
                .map(World::getName)
                .map(GuiManager::baseName)
                .collect(Collectors.toCollection(LinkedHashSet::new));

        int slot = 0;
        for (String base : bases) {
            if (slot >= inv.getSize()) break;
            ItemStack it = new ItemStack(Material.GRASS_BLOCK);
            ItemMeta meta = it.getItemMeta();
            meta.setDisplayName(ChatColor.GREEN + base);
            meta.setLore(Arrays.asList(ChatColor.GRAY + "Click to reset:", ChatColor.YELLOW + base + ChatColor.GRAY + " (+ nether + end)"));
            it.setItemMeta(meta);
            inv.setItem(slot++, it);
        }
        p.openInventory(inv);
    }

    private void openConfirm(Player p, String base) {
        Inventory inv = Bukkit.createInventory(p, 27, ChatColor.DARK_RED + "Confirm Reset: " + base);

        // Info item
        ItemStack info = new ItemStack(Material.PAPER);
        ItemMeta mi = info.getItemMeta();
        mi.setDisplayName(ChatColor.YELLOW + "Reset " + base);
        mi.setLore(Arrays.asList(ChatColor.GRAY + "Overworld + Nether + End", ChatColor.DARK_GRAY + "plugin made by muj3b"));
        info.setItemMeta(mi);
        inv.setItem(4, info);

        // Confirm (random seed)
        ItemStack confirm = new ItemStack(Material.GREEN_CONCRETE);
        ItemMeta mc = confirm.getItemMeta();
        mc.setDisplayName(ChatColor.GREEN + "Confirm (Random Seed)");
        confirm.setItemMeta(mc);
        inv.setItem(11, confirm);

        // Custom seed via chat
        ItemStack custom = new ItemStack(Material.WRITABLE_BOOK);
        ItemMeta mcu = custom.getItemMeta();
        mcu.setDisplayName(ChatColor.AQUA + "Confirm (Custom Seed)");
        custom.setItemMeta(mcu);
        inv.setItem(13, custom);

        // Cancel
        ItemStack cancel = new ItemStack(Material.RED_CONCRETE);
        ItemMeta mcan = cancel.getItemMeta();
        mcan.setDisplayName(ChatColor.RED + "Cancel");
        cancel.setItemMeta(mcan);
        inv.setItem(15, cancel);

        p.openInventory(inv);
    }

    @EventHandler
    public void onClick(InventoryClickEvent e) {
        if (!(e.getWhoClicked() instanceof Player p)) return;
        String title = e.getView().getTitle();
        if (title == null) return;
        if (title.contains("BetterReset | World Select")) {
            e.setCancelled(true);
            ItemStack it = e.getCurrentItem();
            if (it == null || !it.hasItemMeta() || it.getItemMeta().getDisplayName() == null) return;
            String name = ChatColor.stripColor(it.getItemMeta().getDisplayName());
            openConfirm(p, name);
        } else if (title.startsWith(ChatColor.DARK_RED + "Confirm Reset: ")) {
            e.setCancelled(true);
            String base = ChatColor.stripColor(title.replace(ChatColor.DARK_RED + "Confirm Reset: ", ""));
            ItemStack it = e.getCurrentItem();
            if (it == null || !it.hasItemMeta()) return;
            String dn = it.getItemMeta().getDisplayName();
            if (dn == null) return;
            String clean = ChatColor.stripColor(dn);
            if (clean.startsWith("Confirm (Random Seed)")) {
                p.closeInventory();
                resetService.startResetWithCountdown(p, base, Optional.empty());
            } else if (clean.startsWith("Confirm (Custom Seed)")) {
                p.closeInventory();
                awaitingSeedForWorld.put(p.getUniqueId(), base);
                Messages.send(p, "&eType a &lseed number&re in chat, or type 'random'.");
            } else if (clean.startsWith("Cancel")) {
                p.closeInventory();
            }
        }
    }

    @EventHandler
    public void onClose(InventoryCloseEvent e) {
        // No-op; could clean state if needed
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
            try {
                seed = Optional.of(Long.parseLong(msg));
            } catch (NumberFormatException ex) {
                Messages.send(e.getPlayer(), "&cInvalid seed. Type a number or 'random'.");
                return;
            }
        }
        awaitingSeedForWorld.remove(id);
        Player p = e.getPlayer();
        final Player player = p;
        final String finalBase = base;
        final Optional<Long> finalSeed = seed;
        Bukkit.getScheduler().runTask(plugin, () -> resetService.startResetWithCountdown(player, finalBase, finalSeed));
    }

    private static String baseName(String input) {
        if (input.endsWith("_nether")) return input.substring(0, input.length() - 7);
        if (input.endsWith("_the_end")) return input.substring(0, input.length() - 8);
        return input;
    }
}
