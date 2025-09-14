package com.github.codex.fullreset.util;

import com.github.codex.fullreset.FullResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.plugin.Plugin;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;

/**
 * Minimal reflection-based Multiverse-Core compatibility. No hard dependency.
 * Attempts to ensure worlds are registered/imported after creation.
 */
public class MultiverseCompat {
    private final FullResetPlugin plugin;

    public MultiverseCompat(FullResetPlugin plugin) {
        this.plugin = plugin;
    }

    public void ensureRegistered(String worldName, World.Environment env, long seed) {
        try {
            Plugin mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
            if (mv == null || !mv.isEnabled()) return;
            Object core = mv; // com.onarandombox.MultiverseCore.MultiverseCore
            Method getMgr = core.getClass().getMethod("getMVWorldManager");
            Object mgr = getMgr.invoke(core);
            if (mgr == null) return;

            // boolean isMVWorld(String name)
            Method isMVWorld = mgr.getClass().getMethod("isMVWorld", String.class);
            boolean known = (boolean) isMVWorld.invoke(mgr, worldName);
            if (known) return;

            // Try: addWorld(String name, Environment env, String seed, WorldType type, boolean genStructures, String generator)
            try {
                Method addWorld = mgr.getClass().getMethod("addWorld", String.class, World.Environment.class, String.class, org.bukkit.WorldType.class, boolean.class, String.class);
                addWorld.invoke(mgr, worldName, env, String.valueOf(seed), org.bukkit.WorldType.NORMAL, true, null);
                plugin.getLogger().info("Multiverse: registered world " + worldName);
                return;
            } catch (NoSuchMethodException ignored) { }

            // Fallback: importWorld(String name, Environment env)
            try {
                Method importWorld = mgr.getClass().getMethod("importWorld", String.class, World.Environment.class);
                importWorld.invoke(mgr, worldName, env);
                plugin.getLogger().info("Multiverse: imported world " + worldName);
                return;
            } catch (NoSuchMethodException ignored) { }

            // Fallback: loadWorld(String name)
            try {
                Method loadWorld = mgr.getClass().getMethod("loadWorld", String.class);
                loadWorld.invoke(mgr, worldName);
                plugin.getLogger().info("Multiverse: loaded world " + worldName);
            } catch (NoSuchMethodException ignored) { }

        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException ex) {
            plugin.getLogger().info("Multiverse compat issue: " + ex.getMessage());
        }
    }
}

