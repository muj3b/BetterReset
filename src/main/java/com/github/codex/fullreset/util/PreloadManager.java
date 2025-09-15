package com.github.codex.fullreset.util;

import com.github.codex.fullreset.FullResetPlugin;
import org.bukkit.Bukkit;
import org.bukkit.World;
import org.bukkit.WorldCreator;
import org.bukkit.WorldType;

import java.io.File;
import java.nio.file.Path;
import java.util.*;

/**
 * Pre-creates temporary worlds during countdown so the actual swap feels instant.
 * Worlds are created with names like brprep_<base>[_nether|_the_end] and later renamed.
 */
public class PreloadManager {

    private final FullResetPlugin plugin;
    private final Map<String, EnumSet<Dimension>> prepared = new HashMap<>();

    public enum Dimension { OVERWORLD, NETHER, END }

    public PreloadManager(FullResetPlugin plugin) {
        this.plugin = plugin;
    }

    public void preload(String base, long seed, EnumSet<Dimension> dims) {
        if (!plugin.getConfig().getBoolean("preload.enabled", true)) return;
        EnumSet<Dimension> done = prepared.computeIfAbsent(base.toLowerCase(), k -> EnumSet.noneOf(Dimension.class));
        // Create one per tick to avoid spikes
        List<Runnable> tasks = new ArrayList<>();

        if (dims.contains(Dimension.OVERWORLD) && !done.contains(Dimension.OVERWORLD)) {
            tasks.add(() -> createPrepWorld(base, seed, World.Environment.NORMAL));
            done.add(Dimension.OVERWORLD);
        }
        if (dims.contains(Dimension.NETHER) && !done.contains(Dimension.NETHER)) {
            tasks.add(() -> createPrepWorld(base + "_nether", seed, World.Environment.NETHER));
            done.add(Dimension.NETHER);
        }
        if (dims.contains(Dimension.END) && !done.contains(Dimension.END)) {
            tasks.add(() -> createPrepWorld(base + "_the_end", seed, World.Environment.THE_END));
            done.add(Dimension.END);
        }

        if (tasks.isEmpty()) return;
        final int[] idx = {0};
        Bukkit.getScheduler().runTaskTimer(plugin, task -> {
            if (idx[0] >= tasks.size()) { task.cancel(); return; }
            try { tasks.get(idx[0]++).run(); } catch (Exception ignored) {}
        }, 0L, 20L);
    }

    private void createPrepWorld(String targetName, long seed, World.Environment env) {
        String name = prepName(targetName);
        // Clean stale prep world
        World old = Bukkit.getWorld(name);
        if (old != null) Bukkit.unloadWorld(old, true);
        File worldFolder = new File(Bukkit.getWorldContainer(), name);
        if (worldFolder.exists()) deleteFolder(worldFolder.toPath());

        World w = new WorldCreator(name)
                .environment(env)
                .seed(seed)
                .type(WorldType.NORMAL)
                .createWorld();
        if (w != null) {
            try { w.getChunkAt(w.getSpawnLocation()).load(true); } catch (Exception ignored) {}
        }
    }

    public boolean hasPrepared(String base, Dimension d) {
        EnumSet<Dimension> set = prepared.get(base.toLowerCase());
        return set != null && set.contains(d);
    }

    public String prepName(String targetName) { return "brprep_" + targetName; }

    private void deleteFolder(Path path) {
        try {
            java.nio.file.Files.walkFileTree(path, new java.nio.file.SimpleFileVisitor<>() {
                @Override public java.nio.file.FileVisitResult visitFile(Path file, java.nio.file.attribute.BasicFileAttributes attrs) throws java.io.IOException { java.nio.file.Files.deleteIfExists(file); return java.nio.file.FileVisitResult.CONTINUE; }
                @Override public java.nio.file.FileVisitResult postVisitDirectory(Path dir, java.io.IOException exc) throws java.io.IOException { java.nio.file.Files.deleteIfExists(dir); return java.nio.file.FileVisitResult.CONTINUE; }
            });
        } catch (Exception ignored) {}
    }
}

