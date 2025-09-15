package com.github.codex.fullreset.ui;

import org.bukkit.inventory.Inventory;
import org.bukkit.inventory.InventoryHolder;

public class GuiHolder implements InventoryHolder {
    public enum Type { MAIN, SELECT, RESET_OPTIONS, BACKUPS, BACKUP_OPTIONS, DELETE_BACKUP, DELETE_ALL, SETTINGS, MESSAGES }
    private final Type type;
    public GuiHolder(Type type) { this.type = type; }
    public Type getType() { return type; }
    @Override public Inventory getInventory() { return null; }
}

