package com.muj3b.betterreset.ui;

import net.kyori.adventure.text.Component;
import org.bukkit.Material;
import org.bukkit.NamespacedKey;
import org.bukkit.inventory.ItemStack;
import org.bukkit.inventory.meta.ItemMeta;
import org.bukkit.persistence.PersistentDataType;

import java.util.Arrays;

public final class GuiHolderComponents {

    private GuiHolderComponents() {}

    public static ItemStack namedComponent(Material mat, Component name, Component... lore) {
        ItemStack it = new ItemStack(mat);
        ItemMeta meta = it.getItemMeta();
        if (meta != null) {
            meta.displayName(name);
            if (lore != null && lore.length > 0) {
                meta.lore(Arrays.asList(lore));
            }
            // Tag as BetterReset UI item for strict ownership checks
            try {
                meta.getPersistentDataContainer().set(new NamespacedKey("betterreset","ui"), PersistentDataType.BYTE, (byte)1);
            } catch (Throwable ignored) {}
            it.setItemMeta(meta);
        }
        return it;
    }

    public static ItemStack named(Material mat, String name, String... lore) {
        Component title = Component.text(name == null ? "" : name);
        Component[] lc = Arrays.stream(lore == null ? new String[0] : lore)
                .map(l -> Component.text(l == null ? "" : l))
                .toArray(Component[]::new);
        return namedComponent(mat, title, lc);
    }
}
