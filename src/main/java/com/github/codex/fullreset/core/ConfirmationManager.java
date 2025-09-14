package com.github.codex.fullreset.core;

import com.github.codex.fullreset.FullResetPlugin;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Manages time-bound confirmations to avoid accidental resets.
 */
public class ConfirmationManager {

    public static final class Pending {
        public final String worldName;
        public final Optional<Long> seed;
        public final Instant createdAt;

        public Pending(String worldName, Optional<Long> seed) {
            this.worldName = worldName;
            this.seed = seed;
            this.createdAt = Instant.now();
        }
    }

    private final FullResetPlugin plugin;
    private final Map<String, Pending> pendingBySender = new HashMap<>();

    public ConfirmationManager(FullResetPlugin plugin) {
        this.plugin = plugin;
    }

    public static String keyFor(CommandSender sender) {
        if (sender instanceof Player p) {
            return "player:" + p.getUniqueId();
        }
        return "console";
    }

    public void createPending(String senderKey, String worldName, Optional<Long> seed) {
        cleanupExpired();
        pendingBySender.put(senderKey, new Pending(worldName, seed));
    }

    public Optional<Pending> getPending(String senderKey) {
        cleanupExpired();
        return Optional.ofNullable(pendingBySender.get(senderKey));
    }

    public boolean consumeIfValid(String senderKey, String worldName) {
        cleanupExpired();
        Pending p = pendingBySender.get(senderKey);
        if (p == null) return false;
        if (!p.worldName.equalsIgnoreCase(worldName)) return false;
        long timeout = plugin.getConfig().getLong("confirmation.timeoutSeconds", 15L);
        if (Duration.between(p.createdAt, Instant.now()).getSeconds() > timeout) {
            pendingBySender.remove(senderKey);
            return false;
        }
        pendingBySender.remove(senderKey);
        return true;
    }

    private void cleanupExpired() {
        long timeout = plugin.getConfig().getLong("confirmation.timeoutSeconds", 15L);
        Instant now = Instant.now();
        pendingBySender.entrySet().removeIf(e -> Duration.between(e.getValue().createdAt, now).getSeconds() > timeout);
    }
}

