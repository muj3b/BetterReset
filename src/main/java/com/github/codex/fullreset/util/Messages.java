package com.github.codex.fullreset.util;

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
}

