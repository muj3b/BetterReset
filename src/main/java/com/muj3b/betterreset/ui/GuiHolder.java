package com.muj3b.betterreset.ui;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class GuiHolder implements InventoryHolder {
    // Simplified GUI types - fewer menus, clearer purpose
    public enum Type {
        MAIN,           // Main menu with 3 options
        RESET,          // Reset/Teleport options for current world
        ARCHIVES,       // Browse and restore archives
        ARCHIVE_OPTIONS,// Options for a specific archive
        SETTINGS        // All settings on one page
    }

    private final Type type;
    private final Component title;
    private Inventory inventory;

    public GuiHolder(@NotNull Type type, @NotNull Component title) {
        this.type = type;
        this.title = title;
    }

    @NotNull
    public Type getType() { return type; }

    @NotNull
    public Component getTitle() { return title; }

    @Override
    @Nullable
    public Inventory getInventory() { return inventory; }

    void setInventory(@NotNull Inventory inventory) { this.inventory = inventory; }
}
