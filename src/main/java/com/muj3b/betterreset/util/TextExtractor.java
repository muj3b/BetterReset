package com.muj3b.betterreset.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer;
import org.bukkit.inventory.meta.ItemMeta;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.Collections;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Utility class for getting text from modern Adventure components.
 */
public final class TextExtractor {
    private static final PlainTextComponentSerializer SERIALIZER = PlainTextComponentSerializer.plainText();

    private TextExtractor() {
        throw new UnsupportedOperationException("Utility class");
    }

    @NotNull
    public static String getText(@Nullable Component component) {
        if (component == null) {
            return "";
        }
        return SERIALIZER.serialize(component);
    }

    @NotNull
    public static String getDisplayName(@Nullable ItemMeta meta) {
        if (meta == null) {
            return "";
        }
        Component displayName = meta.displayName();
        return getText(displayName);
    }

    @NotNull
    public static List<String> getLoreText(@Nullable ItemMeta meta) {
        if (meta == null) {
            return Collections.emptyList();
        }
        List<Component> lore = meta.lore();
        if (lore == null) {
            return Collections.emptyList();
        }
        return lore.stream().map(TextExtractor::getText).collect(Collectors.toList());
    }

    public static boolean titleMatches(@Nullable Component title, @NotNull Component match) {
        return getText(match).equals(getText(title));
    }

    public static boolean titleStartsWith(@Nullable Component title, @NotNull Component prefix) {
        return getText(title).startsWith(getText(prefix));
    }
}
