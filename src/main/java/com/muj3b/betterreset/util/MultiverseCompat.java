package com.muj3b.betterreset.util;

import com.muj3b.betterreset.FullResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
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
        Plugin mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if (mv == null || !mv.isEnabled()) {
            return;
        }

        if (ensureRegisteredModern(mv, worldName, env)) {
            return;
        }

        ensureRegisteredLegacy(mv, worldName, env, seed);
    }

    private boolean ensureRegisteredModern(Plugin mv, String worldName, World.Environment env) {
        try {
            Object mgr = getModernWorldManager(mv);
            if (mgr == null) {
                return false;
            }

            Method isWorld = mgr.getClass().getMethod("isWorld", String.class);
            boolean known = (boolean) isWorld.invoke(mgr, worldName);
            if (!known) {
                Bukkit.dispatchCommand(Bukkit.getConsoleSender(), "mv import " + worldName + " " + env.name());
                known = (boolean) isWorld.invoke(mgr, worldName);
            }

            if (known) {
                saveModernWorldsConfig(mgr);
                plugin.getLogger().info("Multiverse: registered world " + worldName);
            } else {
                plugin.getLogger().warning("Multiverse did not register world " + worldName + " after import.");
            }
            return true;
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException ex) {
            return false;
        }
    }

    private void ensureRegisteredLegacy(Plugin mv, String worldName, World.Environment env, long seed) {
        try {
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

    public void updateSpawn(String worldName, Location spawn) {
        if (spawn == null) return;
        Plugin mv = Bukkit.getPluginManager().getPlugin("Multiverse-Core");
        if (mv == null || !mv.isEnabled()) {
            return;
        }

        if (updateSpawnModern(mv, worldName, spawn)) {
            return;
        }

        updateSpawnLegacy(mv, worldName, spawn);
    }

    private boolean updateSpawnModern(Plugin mv, String worldName, Location spawn) {
        try {
            Object mgr = getModernWorldManager(mv);
            if (mgr == null) {
                return false;
            }

            Method getWorld = mgr.getClass().getMethod("getWorld", String.class);
            Object option = getWorld.invoke(mgr, worldName);
            Object mvWorld = unwrapOption(option);
            if (mvWorld == null) {
                return true;
            }

            Method setSpawnLocation = mvWorld.getClass().getMethod("setSpawnLocation", Location.class);
            setSpawnLocation.invoke(mvWorld, spawn);
            saveModernWorldsConfig(mgr);
            plugin.getLogger().info("Multiverse: updated spawn for " + worldName);
            return true;
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException ex) {
            return false;
        }
    }

    private void updateSpawnLegacy(Plugin mv, String worldName, Location spawn) {
        try {
            Object core = mv;
            Method getMgr = core.getClass().getMethod("getMVWorldManager");
            Object mgr = getMgr.invoke(core);
            if (mgr == null) return;

            Method getMVWorld = mgr.getClass().getMethod("getMVWorld", String.class);
            Object mvWorld = getMVWorld.invoke(mgr, worldName);
            if (mvWorld == null) return;

            Method setSpawnLocation = mvWorld.getClass().getMethod("setSpawnLocation", Location.class);
            setSpawnLocation.invoke(mvWorld, spawn);
            plugin.getLogger().info("Multiverse: updated spawn for " + worldName);
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException ex) {
            plugin.getLogger().info("Multiverse spawn compat issue: " + ex.getMessage());
        }
    }

    private Object getModernWorldManager(Plugin mv)
            throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        Method getApi = mv.getClass().getMethod("getApi");
        Object api = getApi.invoke(mv);
        if (api == null) {
            return null;
        }
        Method getWorldManager = api.getClass().getMethod("getWorldManager");
        return getWorldManager.invoke(api);
    }

    private Object unwrapOption(Object option)
            throws InvocationTargetException, IllegalAccessException, NoSuchMethodException {
        if (option == null) {
            return null;
        }
        try {
            Method isDefined = option.getClass().getMethod("isDefined");
            if (Boolean.FALSE.equals(isDefined.invoke(option))) {
                return null;
            }
            Method get = option.getClass().getMethod("get");
            return get.invoke(option);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            Method isEmpty = option.getClass().getMethod("isEmpty");
            if (Boolean.TRUE.equals(isEmpty.invoke(option))) {
                return null;
            }
            Method get = option.getClass().getMethod("get");
            return get.invoke(option);
        } catch (NoSuchMethodException ignored) {
        }

        try {
            Method getOrNull = option.getClass().getMethod("getOrNull");
            return getOrNull.invoke(option);
        } catch (NoSuchMethodException ignored) {
        }

        return null;
    }

    private void saveModernWorldsConfig(Object mgr) {
        try {
            Method saveWorldsConfig = mgr.getClass().getMethod("saveWorldsConfig");
            saveWorldsConfig.invoke(mgr);
        } catch (InvocationTargetException | IllegalAccessException | NoSuchMethodException ignored) {
        }
    }
}
