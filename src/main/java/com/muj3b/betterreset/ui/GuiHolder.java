package com.muj3b.betterreset.ui;

import net.kyori.adventure.text.Component;
import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

public class GuiHolder implements InventoryHolder {
    public enum Type {
        MAIN,
        SELECT,
        RESET_OPTIONS,
        SEED_SELECTOR,
        BACKUPS,
        BACKUP_OPTIONS,
        DELETE_BACKUP,
        DELETE_ALL,
        SETTINGS,
        MESSAGES
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
