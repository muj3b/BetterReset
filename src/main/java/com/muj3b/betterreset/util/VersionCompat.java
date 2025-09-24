package com.muj3b.betterreset.util;

import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.jetbrains.annotations.NotNull;

import java.util.function.Predicate;

/**
 * Version compatibility helper that avoids hard dependencies on Paper-only APIs.
 * - Chat input: uses AsyncPlayerChatEvent on all platforms (Paper, Purpur, Spigot, Bukkit).
 * - World height helpers: reflects modern API when available.
 */
public final class VersionCompat implements Listener {

    private static final boolean HAS_MODERN_WORLD = checkModernWorld();

    private final Predicate<ChatMessage> messageHandler;

    public VersionCompat(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull Predicate<ChatMessage> messageHandler) {
        this.messageHandler = messageHandler;
        if (plugin != null) Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private static boolean checkModernWorld() {
        try {
            World.class.getDeclaredMethod("getMinHeight");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    // Height helpers
    public static int getMinHeight(@NotNull World world) {
        if (HAS_MODERN_WORLD) {
            return world.getMinHeight();
        }
        return 0; // Pre-1.16 worlds always started at 0
    }

    public static int getMaxHeight(@NotNull World world) {
        if (HAS_MODERN_WORLD) {
            return world.getMaxHeight();
        }
        return 256; // Pre-1.16 worlds were always 256 blocks tall
    }

    // Single chat handler that works across Paper/Spigot/Purpur
    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onChat(AsyncPlayerChatEvent e) {
        boolean consumed = false;
        try {
            consumed = messageHandler.test(new ChatMessage(e.getPlayer(), Component.text(e.getMessage())));
        } catch (Exception ex) {
            try {
                Bukkit.getLogger().warning("BetterReset VersionCompat chat handler error: " + ex.getMessage());
            } catch (Exception ignored) {
                // ignore secondary logging failures
            }
        }
        if (consumed) {
            e.setCancelled(true);
        }
    }

    /**
     * Represents a chat message with version-independent components
     */
    public static class ChatMessage {
        private final Player player;
        private final Component message;

        public ChatMessage(@NotNull Player player, @NotNull Component message) {
            this.player = player;
            this.message = message;
        }

        @NotNull
        public Player getPlayer() { return player; }

        @NotNull
        public Component getMessage() { return message; }

        @NotNull
        public String getMessageText() { return TextExtractor.getText(message); }
    }
}