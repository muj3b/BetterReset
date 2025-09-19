package com.muj3b.betterreset.util;

import com.muj3b.betterreset.FullResetPlugin;
import org.bukkit.Bukkit;

import java.io.IOException;
import java.nio.file.*;
import java.nio.file.attribute.BasicFileAttributes;
import java.text.SimpleDateFormat;
import java.util.*;

/**
 * Handles snapshotting (moving) world folders into a backups directory and restoring them.
 * Backups are stored at: plugins/BetterReset/backups/<base>/<timestamp>/<world-folder>
 */
public class BackupManager {
    private final FullResetPlugin plugin;
    private final Path backupsRoot;
    private final SimpleDateFormat fmt = new SimpleDateFormat("yyyyMMdd-HHmmss");

    public BackupManager(FullResetPlugin plugin) {
        this.plugin = plugin;
        this.backupsRoot = plugin.getDataFolder().toPath().resolve("backups");
        try { Files.createDirectories(backupsRoot); } catch (IOException ignored) {}
    }

    public String snapshot(String base, Map<String, Path> worldFolders) throws IOException {
        String stamp = fmt.format(new Date());
        Path destBase = backupsRoot.resolve(base).resolve(stamp);
        Files.createDirectories(destBase);
        long totalBytes = 0L;
        for (Map.Entry<String, Path> e : worldFolders.entrySet()) {
            Path src = e.getValue();
            if (src == null || !Files.exists(src)) continue;
            Path dest = destBase.resolve(src.getFileName());
            moveTree(src, dest);
            totalBytes += folderSize(dest);
        }
        // Write metadata
        try {
            java.util.Properties meta = new java.util.Properties();
            meta.setProperty("base", base);
            meta.setProperty("timestamp", stamp);
            meta.setProperty("sizeBytes", String.valueOf(totalBytes));
            long playtime = 0L;
            try { playtime = plugin.getPlaytimeTracker().getSecondsForBase(base); } catch (Throwable ignored) {}
            meta.setProperty("playtimeSeconds", String.valueOf(playtime));
            try (java.io.OutputStream os = java.nio.file.Files.newOutputStream(destBase.resolve("meta.properties"))) {
                meta.store(os, "BetterReset backup metadata");
            }
        } catch (Exception ignored) {}
        // mark snapshot as complete so callers can verify a full successful snapshot
        try {
            Files.createFile(destBase.resolve(".complete"));
        } catch (Exception ignored) {}
        plugin.getLogger().info("Snapshot saved: " + destBase + " (" + human(totalBytes) + ")");
        prune(base);
        return stamp;
    }

    public List<BackupRef> listBackups() {
        List<BackupRef> out = new ArrayList<>();
        try {
            if (!Files.exists(backupsRoot)) return out;
            try (DirectoryStream<Path> bases = Files.newDirectoryStream(backupsRoot)) {
                for (Path base : bases) {
                    if (!Files.isDirectory(base)) continue;
                    try (DirectoryStream<Path> times = Files.newDirectoryStream(base)) {
for (Path ts : times) {
                            if (!Files.isDirectory(ts)) continue;
                            boolean hasComplete = Files.exists(ts.resolve(".complete"));
                            boolean hasMeta = Files.exists(ts.resolve("meta.properties"));
if (hasComplete && hasMeta) {
                                long sizeBytes = -1L;
                                long playtime = -1L;
                                // attempt to read metadata
                                try (java.io.InputStream is = java.nio.file.Files.newInputStream(ts.resolve("meta.properties"))) {
                                    java.util.Properties meta = new java.util.Properties();
                                    meta.load(is);
                                    try { sizeBytes = Long.parseLong(meta.getProperty("sizeBytes", "-1")); } catch (Exception ignored) {}
                                    try { playtime = Long.parseLong(meta.getProperty("playtimeSeconds", "-1")); } catch (Exception ignored) {}
                                } catch (Exception ignored) {}
                                out.add(new BackupRef(base.getFileName().toString(), ts.getFileName().toString(), ts, sizeBytes, playtime));
                            } else {
                                boolean dbg = false;
                                try { dbg = plugin.getConfig().getBoolean("debug.backups", false); } catch (Exception ignored) {}
                                if (dbg) {
                                    plugin.getLogger().info("Skipping backup without markers: " + ts + " (complete=" + hasComplete + ", meta=" + hasMeta + ")");
                                }
                            }
                        }
                    }
                }
            }
        } catch (IOException ignored) {}
        out.sort(Comparator.comparing((BackupRef r) -> r.timestamp).reversed());
        return out;
    }

    public void restore(String base, String timestamp) throws IOException {
        boolean dbg = false; try { dbg = plugin.getConfig().getBoolean("debug.backups", false); } catch (Exception ignored) {}
        if (dbg) plugin.getLogger().info("Restore requested: base=" + base + ", ts=" + timestamp);
        Path src = backupsRoot.resolve(base).resolve(timestamp);
        if (!Files.exists(src) || !Files.isDirectory(src)) throw new IOException("Backup not found: " + src);
        Path worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        try (DirectoryStream<Path> worlds = Files.newDirectoryStream(src)) {
            for (Path w : worlds) {
                Path dest = worldContainer.resolve(w.getFileName().toString());
                // If a current world folder exists, archive it aside first
                if (Files.exists(dest)) {
                    Path aside = backupsRoot.resolve(base).resolve("restore-aside-" + fmt.format(new Date()) + "-" + dest.getFileName());
                    moveTree(dest, aside);
                }
                copyTree(w, dest);
            }
        }
    }

    public void restore(String base, String timestamp, EnumSet<com.muj3b.betterreset.core.ResetService.Dimension> dims) throws IOException {
        boolean dbg = false; try { dbg = plugin.getConfig().getBoolean("debug.backups", false); } catch (Exception ignored) {}
        if (dbg) plugin.getLogger().info("Restore requested: base=" + base + ", ts=" + timestamp + ", dims=" + String.valueOf(dims) + "");
        Path src = backupsRoot.resolve(base).resolve(timestamp);
        if (!Files.exists(src) || !Files.isDirectory(src)) throw new IOException("Backup not found: " + src);
        Path worldContainer = Bukkit.getWorldContainer().toPath().toAbsolutePath().normalize();
        List<String> names = new ArrayList<>();
        if (dims.contains(com.muj3b.betterreset.core.ResetService.Dimension.OVERWORLD)) names.add(base);
        if (dims.contains(com.muj3b.betterreset.core.ResetService.Dimension.NETHER)) names.add(base + "_nether");
        if (dims.contains(com.muj3b.betterreset.core.ResetService.Dimension.END)) names.add(base + "_the_end");
        for (String name : names) {
            Path srcWorld = src.resolve(name);
            if (!Files.exists(srcWorld)) continue;
            Path dest = worldContainer.resolve(name);
            if (Files.exists(dest)) {
                Path aside = backupsRoot.resolve(base).resolve("restore-aside-" + fmt.format(new Date()) + "-" + dest.getFileName());
                moveTree(dest, aside);
            }
            copyTree(srcWorld, dest);
        }
    }

    public record BackupRef(String base, String timestamp, Path path, long sizeBytes, long playtimeSeconds) {}

    private void moveTree(Path src, Path dest) throws IOException {
        try {
            Files.move(src, dest, StandardCopyOption.ATOMIC_MOVE);
            return;
        } catch (Exception ignored) {}
        // Fallback to manual tree move
        Files.createDirectories(dest);
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(dir);
                Files.createDirectories(dest.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(file);
                Files.move(file, dest.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    private long folderSize(Path path) throws IOException {
        final long[] size = {0};
        if (!Files.exists(path)) return 0L;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException { size[0] += Files.size(file); return FileVisitResult.CONTINUE; }
        });
        return size[0];
    }

    private String human(long bytes) {
        String[] u = {"B","KB","MB","GB","TB"};
        double b = bytes; int i=0; while (b>=1024 && i<u.length-1){ b/=1024; i++; }
        return String.format(java.util.Locale.US, "%.1f %s", b, u[i]);
    }

    public void pruneKeepPerBase(String base, int keep) throws IOException {
        Path baseDir = backupsRoot.resolve(base);
        if (!Files.exists(baseDir)) return;
        List<Path> timestamps = new ArrayList<>();
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(baseDir)) { for (Path p : ds) if (Files.isDirectory(p)) timestamps.add(p); }
        timestamps.sort(Comparator.comparing(Path::getFileName)); // oldest first
        int toRemove = Math.max(0, timestamps.size() - keep);
        for (int i = 0; i < toRemove; i++) deleteTree(timestamps.get(i));
    }

    public void pruneKeepAllBases(int keep) throws IOException {
        if (!Files.exists(backupsRoot)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupsRoot)) {
            for (Path base : ds) if (Files.isDirectory(base)) pruneKeepPerBase(base.getFileName().toString(), keep);
        }
    }

    private void copyTree(Path src, Path dest) throws IOException {
        Files.createDirectories(dest);
        Files.walkFileTree(src, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult preVisitDirectory(Path dir, BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(dir);
                Files.createDirectories(dest.resolve(rel));
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Path rel = src.relativize(file);
                Files.copy(file, dest.resolve(rel), StandardCopyOption.REPLACE_EXISTING);
                return FileVisitResult.CONTINUE;
            }
        });
    }

    public void deleteBackup(String base, String timestamp) throws IOException {
        boolean dbg = false; try { dbg = plugin.getConfig().getBoolean("debug.backups", false); } catch (Exception ignored) {}
        if (dbg) plugin.getLogger().info("Delete backup: base=" + base + ", ts=" + timestamp);
        Path dir = backupsRoot.resolve(base).resolve(timestamp);
        deleteTree(dir);
    }

    public void deleteAllForBase(String base) throws IOException {
        boolean dbg = false; try { dbg = plugin.getConfig().getBoolean("debug.backups", false); } catch (Exception ignored) {}
        if (dbg) plugin.getLogger().info("Delete ALL for base: base=" + base);
        Path dir = backupsRoot.resolve(base);
        deleteTree(dir);
    }

    public void deleteAllBases() throws IOException {
        boolean dbg = false; try { dbg = plugin.getConfig().getBoolean("debug.backups", false); } catch (Exception ignored) {}
        if (dbg) plugin.getLogger().info("Delete ALL backups for ALL bases");
        if (!Files.exists(backupsRoot)) return;
        try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupsRoot)) {
            for (Path p : ds) deleteTree(p);
        }
    }

    public int pruneAll() {
        int removed = 0;
        try {
            if (!Files.exists(backupsRoot)) return 0;
            try (DirectoryStream<Path> ds = Files.newDirectoryStream(backupsRoot)) {
                for (Path base : ds) {
                    if (Files.isDirectory(base)) {
                        prune(base.getFileName().toString());
                        // Approximate count by diff size before/after
                    }
                }
            }
        } catch (IOException ignored) {}
        return removed;
    }

    public void prune(String base) {
        try {
            int maxPerBase = plugin.getConfig().getInt("backups.maxPerBase", 5);
            int maxTotal = plugin.getConfig().getInt("backups.maxTotal", 50);
            int maxAge = plugin.getConfig().getInt("backups.maxAgeDays", 30);
            long cutoff = System.currentTimeMillis() - (long) maxAge * 24L * 60L * 60L * 1000L;

            // Per-base prune
            Path baseDir = backupsRoot.resolve(base);
            List<Path> timestamps = new ArrayList<>();
            if (Files.exists(baseDir)) {
                try (DirectoryStream<Path> ds = Files.newDirectoryStream(baseDir)) {
                    for (Path p : ds) if (Files.isDirectory(p)) timestamps.add(p);
                }
            }
            timestamps.sort(Comparator.comparing(Path::getFileName));
            List<Path> toDelete = new ArrayList<>();
            // Age-based
            for (Path p : timestamps) {
                Date d = parseTs(p.getFileName().toString());
                if (d != null && d.getTime() < cutoff) toDelete.add(p);
            }
            // Count-based
            int keep = Math.max(0, maxPerBase);
            if (timestamps.size() - toDelete.size() > keep) {
                int extra = (timestamps.size() - toDelete.size()) - keep;
                for (int i = 0; i < timestamps.size() && extra > 0; i++) {
                    Path p = timestamps.get(i);
                    if (!toDelete.contains(p)) { toDelete.add(p); extra--; }
                }
            }
            for (Path p : toDelete) deleteTree(p);

            // Global cap
            List<com.muj3b.betterreset.util.BackupManager.BackupRef> all = listBackups();
            if (all.size() > maxTotal) {
                int extra = all.size() - maxTotal;
                for (int i = all.size() - 1; i >= 0 && extra > 0; i--) { // oldest at end due to reversed sort
                    deleteTree(all.get(i).path());
                    extra--;
                }
            }
        } catch (Exception ignored) {}
    }

    private Date parseTs(String ts) {
        try { return fmt.parse(ts); } catch (Exception e) { return null; }
    }

    private void deleteTree(Path path) throws IOException {
        if (!Files.exists(path)) return;
        Files.walkFileTree(path, new SimpleFileVisitor<>() {
            @Override
            public FileVisitResult visitFile(Path file, BasicFileAttributes attrs) throws IOException {
                Files.deleteIfExists(file);
                return FileVisitResult.CONTINUE;
            }

            @Override
            public FileVisitResult postVisitDirectory(Path dir, IOException exc) throws IOException {
                Files.deleteIfExists(dir);
                return FileVisitResult.CONTINUE;
            }
        });
    }
}
