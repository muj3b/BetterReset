package com.github.codex.fullreset.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;

/**
 * Utility class for handling text formatting using Adventure API
 */
public class TextUtils {
    public static Component formatTitle(String text, NamedTextColor color) {
        return Component.text(text)
                .color(color)
                .decoration(TextDecoration.BOLD, true);
    }

    public static Component darkAqua(String text) {
        return Component.text(text).color(NamedTextColor.DARK_AQUA);
    }

    public static Component darkGreen(String text) {
        return Component.text(text).color(NamedTextColor.DARK_GREEN);
    }

    public static Component darkRed(String text) {
        return Component.text(text).color(NamedTextColor.DARK_RED);
    }

    public static Component gold(String text) {
        return Component.text(text).color(NamedTextColor.GOLD);
    }

    public static Component darkPurple(String text) {
        return Component.text(text).color(NamedTextColor.DARK_PURPLE);
    }

    public static Component blue(String text) {
        return Component.text(text).color(NamedTextColor.BLUE);
    }

    public static Component gray(String text) {
        return Component.text(text).color(NamedTextColor.GRAY);
    }

    public static Component red(String text) {
        return Component.text(text).color(NamedTextColor.RED);
    }

    public static Component green(String text) {
        return Component.text(text).color(NamedTextColor.GREEN);
    }

    public static Component yellow(String text) {
        return Component.text(text).color(NamedTextColor.YELLOW);
    }

    public static Component white(String text) {
        return Component.text(text).color(NamedTextColor.WHITE);
    }

    public static Component darkGray(String text) {
        return Component.text(text).color(NamedTextColor.DARK_GRAY);
    }

    public static String stripColor(String text) {
        return text.replaceAll("§[0-9a-fk-or]", "");
    }
}