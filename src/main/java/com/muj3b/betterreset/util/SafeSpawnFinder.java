package com.muj3b.betterreset.util;

import org.bukkit.HeightMap;
import org.bukkit.Location;
import org.bukkit.Material;
import org.bukkit.World;
import org.bukkit.block.Block;

/**
 * Finds player-safe overworld spawn positions on the terrain surface.
 */
public final class SafeSpawnFinder {
    private SafeSpawnFinder() {
    }

    public static Location findNear(World world, Location preferred, int radius, int step) {
        if (world == null) {
            return preferred;
        }

        Location start = preferred != null ? preferred : world.getSpawnLocation();
        int safeRadius = Math.max(0, radius);
        int safeStep = Math.max(4, step);

        Location direct = surfaceAt(world, start.getBlockX(), start.getBlockZ());
        if (direct != null) {
            return withFacing(direct, start);
        }

        for (int distance = safeStep; distance <= safeRadius; distance += safeStep) {
            for (int x = start.getBlockX() - distance; x <= start.getBlockX() + distance; x += safeStep) {
                Location north = surfaceAt(world, x, start.getBlockZ() - distance);
                if (north != null) return withFacing(north, start);
                Location south = surfaceAt(world, x, start.getBlockZ() + distance);
                if (south != null) return withFacing(south, start);
            }

            for (int z = start.getBlockZ() - distance + safeStep; z <= start.getBlockZ() + distance - safeStep; z += safeStep) {
                Location west = surfaceAt(world, start.getBlockX() - distance, z);
                if (west != null) return withFacing(west, start);
                Location east = surfaceAt(world, start.getBlockX() + distance, z);
                if (east != null) return withFacing(east, start);
            }
        }

        Location origin = surfaceAt(world, 0, 0);
        if (origin != null) {
            return withFacing(origin, start);
        }

        Location fallback = start.clone();
        fallback.setY(Math.max(world.getSeaLevel() + 1, 65));
        return fallback;
    }

    public static Location surfaceAt(World world, int x, int z) {
        if (world == null) {
            return null;
        }

        try {
            world.getChunkAt(x >> 4, z >> 4).load(true);
            int groundY = surfaceY(world, x, z);
            if (groundY < world.getMinHeight() || groundY >= world.getMaxHeight() - 2) {
                return null;
            }

            Block ground = world.getBlockAt(x, groundY, z);
            Block feet = world.getBlockAt(x, groundY + 1, z);
            Block head = world.getBlockAt(x, groundY + 2, z);

            if (isSafeGround(ground) && isSafeSpace(feet) && isSafeSpace(head)) {
                return new Location(world, x + 0.5D, groundY + 1.0D, z + 0.5D);
            }
        } catch (Throwable ignored) {
        }

        return null;
    }

    private static int surfaceY(World world, int x, int z) {
        try {
            int y = world.getHighestBlockYAt(x, z, HeightMap.MOTION_BLOCKING_NO_LEAVES);
            if (y >= world.getMinHeight()) {
                return y;
            }
        } catch (Throwable ignored) {
        }

        int maxY = world.getMaxHeight() - 1;
        int minY = world.getMinHeight();
        for (int y = maxY; y >= minY; y--) {
            Block ground = world.getBlockAt(x, y, z);
            if (isSafeGround(ground)
                    && y + 2 < world.getMaxHeight()
                    && isSafeSpace(world.getBlockAt(x, y + 1, z))
                    && isSafeSpace(world.getBlockAt(x, y + 2, z))) {
                return y;
            }
        }
        return -1;
    }

    private static boolean isSafeGround(Block block) {
        Material type = block.getType();
        return type.isSolid()
                && !block.isLiquid()
                && !isDangerous(type)
                && type != Material.BEDROCK;
    }

    private static boolean isSafeSpace(Block block) {
        Material type = block.getType();
        return !block.isLiquid()
                && !isDangerous(type)
                && (block.isPassable() || type.isAir());
    }

    private static boolean isDangerous(Material type) {
        return type == Material.LAVA
                || type == Material.FIRE
                || type == Material.SOUL_FIRE
                || type == Material.CAMPFIRE
                || type == Material.SOUL_CAMPFIRE
                || type == Material.MAGMA_BLOCK
                || type == Material.CACTUS
                || type == Material.POWDER_SNOW;
    }

    private static Location withFacing(Location location, Location facing) {
        if (facing != null) {
            location.setYaw(facing.getYaw());
            location.setPitch(facing.getPitch());
        }
        return location;
    }
}
