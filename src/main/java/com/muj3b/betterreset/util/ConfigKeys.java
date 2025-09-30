package com.muj3b.betterreset.util;

/**
 * Utility class to hold all configuration key strings as static final constants.
 * This prevents typos and makes it easier to manage configuration options.
 */
public final class ConfigKeys {

    // Prevent instantiation
    private ConfigKeys() {}

    // Limits
    public static final String LIMITS_RESET_COOLDOWN_SECONDS = "limits.resetCooldownSeconds";
    public static final String LIMITS_MAX_ONLINE_FOR_RESET = "limits.maxOnlineForReset";

    // Countdown
    public static final String COUNTDOWN_SECONDS = "countdown.seconds";

    // Seeds
    public static final String SEEDS_HISTORY_CAPACITY = "seeds.historyCapacity";
    public static final String SEEDS_USE_SAME_SEED_FOR_ALL_DIMENSIONS = "seeds.useSameSeedForAllDimensions";

    // Deletion
    public static final String DELETION_PARALLELISM = "deletion.parallelism";

    // Backups
    public static final String BACKUPS_ENABLED = "backups.enabled";
    public static final String BACKUPS_PRUNE_NOW_KEEP_PER_BASE = "backups.pruneNowKeepPerBase";

    // Players
    public static final String PLAYERS_FRESH_START_ON_RESET = "players.freshStartOnReset";
    public static final String PLAYERS_RETURN_TO_NEW_SPAWN_AFTER_RESET = "players.returnToNewSpawnAfterReset";
    public static final String PLAYERS_RESET_ALL_ONLINE_AFTER_RESET = "players.resetAllOnlineAfterReset";

    // Teleport
    public static final String TELEPORT_FALLBACK_WORLD_NAME = "teleport.fallbackWorldName";
    public static final String TELEPORT_MODE_RESET_NETHER_END = "teleportMode.resetNetherEnd";
    public static final String TELEPORT_MODE_PLAYER_DISTANCE = "teleportMode.playerDistance";
    public static final String TELEPORT_MODE_SET_WORLD_SPAWN = "teleportMode.setWorldSpawn";

    // Preload
    public static final String PRELOAD_ENABLED = "preload.enabled";
    public static final String PRELOAD_AUTO_DISABLE_HIGH_LAG = "preload.autoDisableHighLag";
    public static final String PRELOAD_TPS_THRESHOLD = "preload.tpsThreshold";
}