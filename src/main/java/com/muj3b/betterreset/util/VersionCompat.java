package com.muj3b.betterreset.util;

import io.papermc.paper.event.player.AsyncChatEvent;
import net.kyori.adventure.text.Component;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.entity.Player;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.AsyncPlayerChatEvent;
import org.jetbrains.annotations.NotNull;

import java.util.function.Consumer;

/**
 * Utility class for handling different Minecraft versions gracefully
 */
public final class VersionCompat implements Listener {
    
    private static final boolean HAS_PAPER_CHAT = checkPaperChat();
    private static final boolean HAS_MODERN_WORLD = checkModernWorld();
    
    private final Consumer<ChatMessage> messageHandler;

    public VersionCompat(@NotNull org.bukkit.plugin.Plugin plugin, @NotNull Consumer<ChatMessage> messageHandler) {
        this.messageHandler = messageHandler;
        if (plugin != null) Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    private static boolean checkPaperChat() {
        try {
            Class.forName("io.papermc.paper.event.player.AsyncChatEvent");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }

    private static boolean checkModernWorld() {
        try {
            World.class.getDeclaredMethod("getMinHeight");
            return true;
        } catch (NoSuchMethodException e) {
            return false;
        }
    }

    /**
     * Get the minimum height for a world with version compatibility
     *
     * @param world The world
     * @return The minimum height
     */
    public static int getMinHeight(@NotNull World world) {
        if (HAS_MODERN_WORLD) {
            return world.getMinHeight();
        }
        return 0; // Pre-1.16 worlds always started at 0
    }

    /**
     * Get the maximum height for a world with version compatibility
     *
     * @param world The world
     * @return The maximum height
     */
    public static int getMaxHeight(@NotNull World world) {
        if (HAS_MODERN_WORLD) {
            return world.getMaxHeight();
        }
        return 256; // Pre-1.16 worlds were always 256 blocks tall
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    public void onPaperChat(AsyncChatEvent e) {
        if (!HAS_PAPER_CHAT) return;
        messageHandler.accept(new ChatMessage(e.getPlayer(), e.message()));
        e.setCancelled(true);
    }

    @EventHandler(priority = EventPriority.MONITOR, ignoreCancelled = true)
    @SuppressWarnings("deprecation")
    public void onLegacyChat(AsyncPlayerChatEvent e) {
        if (HAS_PAPER_CHAT) return;
        messageHandler.accept(new ChatMessage(e.getPlayer(), Component.text(e.getMessage())));
        e.setCancelled(true);
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
        public Player getPlayer() {
            return player;
        }

        @NotNull
        public Component getMessage() {
            return message;
        }

        @NotNull
        public String getMessageText() {
            return TextExtractor.getText(message);
        }
    }
}