package com.github.codex.fullreset.util;

import net.kyori.adventure.text.Component;
import net.kyori.adventure.text.TextComponent;
import net.kyori.adventure.text.format.TextColor;
import net.kyori.adventure.text.format.TextDecoration;
import net.kyori.adventure.text.serializer.legacy.LegacyComponentSerializer;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

/**
 * Utility class for handling modern text components with backwards compatibility
 */
public final class ComponentUtil {
    private static final boolean HAS_MODERN_API = checkModernAPI();
    
    private ComponentUtil() {
        throw new UnsupportedOperationException("Utility class");
    }
    
    private static boolean checkModernAPI() {
        try {
            Class.forName("net.kyori.adventure.text.Component");
            return true;
        } catch (ClassNotFoundException e) {
            return false;
        }
    }
    
    /**
     * Creates a text component with modern API if available, falls back to legacy methods if not
     *
     * @param text The text content
     * @return A text component
     */
    @NotNull
    public static Component text(@Nullable String text) {
        if (text == null) {
            return Component.empty();
        }
        
        if (HAS_MODERN_API) {
            return LegacyComponentSerializer.legacySection().deserialize(text);
        } else {
            // Use legacy serializer with '&' section style to convert
            return LegacyComponentSerializer.legacyAmpersand().deserialize(text);
        }
    }
    
    /**
     * Creates a colored text component
     *
     * @param text The text content
     * @param color The color to apply
     * @return A colored text component
     */
    @NotNull
    public static Component colored(@Nullable String text, TextColor color) {
        return text(text).color(color);
    }
    
    /**
     * Creates a text component with decorations
     *
     * @param text The text content
     * @param decorations The decorations to apply
     * @return A decorated text component
     */
    @NotNull
    public static Component decorated(@Nullable String text, TextDecoration... decorations) {
        TextComponent component = Component.text(text != null ? text : "");
        for (TextDecoration decoration : decorations) {
            component = component.decoration(decoration, true);
        }
        return component;
    }
    
    /**
     * Converts a component to legacy text format
     *
     * @param component The component to convert
     * @return Legacy formatted string
     */
    @NotNull
    public static String toLegacyString(@Nullable Component component) {
        if (component == null) {
            return "";
        }
        
        if (HAS_MODERN_API) {
            return LegacyComponentSerializer.legacySection().serialize(component);
        } else {
            // Best effort conversion for legacy systems
            return component.toString(); 
        }
    }
    
    /**
     * Checks if modern component API is available
     *
     * @return true if modern API is available
     */
    public static boolean hasModernAPI() {
        return HAS_MODERN_API;
    }
}