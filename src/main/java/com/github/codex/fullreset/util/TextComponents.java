package com.github.codex.fullreset.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.format.NamedTextColor;
import net.kyori.adventure.text.format.TextDecoration;
import org.jetbrains.annotations.NotNull;

/**
 * Utility class for creating text components
 */
public final class TextComponents {
    
    private TextComponents() {
        throw new UnsupportedOperationException("Utility class");
    }

    @NotNull
    public static Component text(String text) {
        return Component.text(text != null ? text : "");
    }

    @NotNull 
    public static Component green(String text) {
        return text(text).color(NamedTextColor.GREEN);
    }

    @NotNull
    public static Component darkGreen(String text) {
        return text(text).color(NamedTextColor.DARK_GREEN);
    }

    @NotNull
    public static Component gold(String text) {
        return text(text).color(NamedTextColor.GOLD);
    }

    @NotNull
    public static Component red(String text) {
        return text(text).color(NamedTextColor.RED);
    }

    @NotNull
    public static Component darkRed(String text) {
        return text(text).color(NamedTextColor.DARK_RED);
    }

    @NotNull
    public static Component blue(String text) {
        return text(text).color(NamedTextColor.BLUE);
    }

    @NotNull
    public static Component darkBlue(String text) {
        return text(text).color(NamedTextColor.DARK_BLUE);
    }

    @NotNull
    public static Component aqua(String text) {
        return text(text).color(NamedTextColor.AQUA);
    }

    @NotNull
    public static Component darkAqua(String text) {
        return text(text).color(NamedTextColor.DARK_AQUA);
    }

    @NotNull
    public static Component yellow(String text) {
        return text(text).color(NamedTextColor.YELLOW);
    }

    @NotNull
    public static Component white(String text) {
        return text(text).color(NamedTextColor.WHITE);
    }

    @NotNull
    public static Component gray(String text) {
        return text(text).color(NamedTextColor.GRAY);
    }

    @NotNull
    public static Component darkGray(String text) {
        return text(text).color(NamedTextColor.DARK_GRAY);
    }

    @NotNull
    public static Component purple(String text) {
        return text(text).color(NamedTextColor.LIGHT_PURPLE);
    }

    @NotNull
    public static Component darkPurple(String text) {
        return text(text).color(NamedTextColor.DARK_PURPLE);
    }

    @NotNull
    public static Component bold(Component component) {
        return component.decoration(TextDecoration.BOLD, true);
    }

    @NotNull
    public static Component italic(Component component) {
        return component.decoration(TextDecoration.ITALIC, true);
    }

    @NotNull
    public static Component underlined(Component component) {
        return component.decoration(TextDecoration.UNDERLINED, true);
    }

    @NotNull
    public static Component strikethrough(Component component) {
        return component.decoration(TextDecoration.STRIKETHROUGH, true);
    }
}