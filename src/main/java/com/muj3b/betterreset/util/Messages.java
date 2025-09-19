package com.muj3b.betterreset.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.bukkit.command.CommandSender;

public final class Messages {

    private Messages() {}

    public static String color(String s) {
        if (s == null) return "";
        return LegacyComponentSerializer.legacyAmpersand().serialize(
                LegacyComponentSerializer.legacyAmpersand().deserialize(s)
        );
    }

    public static void send(CommandSender sender, String s) {
        if (s == null) return;
        Component c = LegacyComponentSerializer.legacyAmpersand().deserialize(s);
        sender.sendMessage(c);
    }
    
    public static void sendToRelevant(String message, java.util.List<org.bukkit.World> worlds) {
        if (message == null || worlds == null || worlds.isEmpty()) return;
        Component c = LegacyComponentSerializer.legacyAmpersand().deserialize(message);
        for (org.bukkit.World world : worlds) {
            for (org.bukkit.entity.Player player : world.getPlayers()) {
                player.sendMessage(c);
            }
        }
    }
}

