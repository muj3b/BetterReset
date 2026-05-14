package com.muj3b.betterreset.util;

import com.muj3b.betterreset.FullResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.Location;
import org.bukkit.World;
import org.bukkit.event.EventHandler;
import org.bukkit.event.EventPriority;
import org.bukkit.event.Listener;
import org.bukkit.event.player.PlayerPortalEvent;
import org.bukkit.event.player.PlayerTeleportEvent;

/**
 * Keeps BetterReset world sets linked without requiring Multiverse-NetherPortals.
 */
public final class PortalLinkManager implements Listener {
    private final FullResetPlugin plugin;

    public PortalLinkManager(FullResetPlugin plugin) {
        this.plugin = plugin;
        Bukkit.getPluginManager().registerEvents(this, plugin);
    }

    @EventHandler(priority = EventPriority.HIGH, ignoreCancelled = true)
    public void onPlayerPortal(PlayerPortalEvent event) {
        if (!plugin.getConfig().getBoolean("portalLinks.enabled", true)) {
            return;
        }
        if (plugin.getConfig().getBoolean("portalLinks.deferToMultiverseNetherPortals", true)
                && Bukkit.getPluginManager().isPluginEnabled("Multiverse-NetherPortals")) {
            return;
        }

        World fromWorld = event.getFrom().getWorld();
        if (fromWorld == null) {
            return;
        }

        if (event.getCause() == PlayerTeleportEvent.TeleportCause.NETHER_PORTAL) {
            routeNetherPortal(event, fromWorld);
        } else if (event.getCause() == PlayerTeleportEvent.TeleportCause.END_PORTAL) {
            routeEndPortal(event, fromWorld);
        }
    }

    private void routeNetherPortal(PlayerPortalEvent event, World fromWorld) {
        World target;
        Location destination;

        if (fromWorld.getEnvironment() == World.Environment.NETHER && fromWorld.getName().endsWith("_nether")) {
            target = Bukkit.getWorld(stripSuffix(fromWorld.getName(), "_nether"));
            if (target == null) {
                return;
            }
            destination = scaled(event.getFrom(), target, 8.0D);
        } else if (fromWorld.getEnvironment() == World.Environment.NORMAL) {
            target = Bukkit.getWorld(fromWorld.getName() + "_nether");
            if (target == null) {
                return;
            }
            destination = scaled(event.getFrom(), target, 0.125D);
        } else {
            return;
        }

        event.setTo(destination);
        event.setCanCreatePortal(plugin.getConfig().getBoolean("portalLinks.createDestinationPortals", true));
        event.setSearchRadius(plugin.getConfig().getInt("portalLinks.searchRadius", event.getSearchRadius()));
        event.setCreationRadius(plugin.getConfig().getInt("portalLinks.creationRadius", event.getCreationRadius()));
    }

    private void routeEndPortal(PlayerPortalEvent event, World fromWorld) {
        World target;
        if (fromWorld.getEnvironment() == World.Environment.THE_END && fromWorld.getName().endsWith("_the_end")) {
            target = Bukkit.getWorld(stripSuffix(fromWorld.getName(), "_the_end"));
        } else if (fromWorld.getEnvironment() == World.Environment.NORMAL) {
            target = Bukkit.getWorld(fromWorld.getName() + "_the_end");
        } else {
            return;
        }

        if (target == null) {
            return;
        }
        event.setTo(safeSpawn(target));
    }

    private Location scaled(Location from, World target, double scale) {
        double x = from.getX() * scale;
        double z = from.getZ() * scale;
        double y = clamp(from.getY(), target.getMinHeight() + 1.0D, target.getMaxHeight() - 2.0D);
        return new Location(target, x, y, z, from.getYaw(), from.getPitch());
    }

    private Location safeSpawn(World world) {
        Location spawn = world.getSpawnLocation();
        return SafeSpawnFinder.findNear(world, spawn, 64, 8);
    }

    private double clamp(double value, double min, double max) {
        return Math.max(min, Math.min(max, value));
    }

    private String stripSuffix(String value, String suffix) {
        return value.substring(0, value.length() - suffix.length());
    }
}
