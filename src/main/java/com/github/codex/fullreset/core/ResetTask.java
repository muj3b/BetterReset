package com.github.codex.fullreset.core;

import com.github.codex.fullreset.util.Messages;
import org.bukkit.World;
import org.bukkit.entity.Player;

import java.util.EnumSet;
import java.util.List;
import java.util.Optional;

/**
 * Represents an active world reset task with its configuration and state
 */
public class ResetTask {
    private final String baseWorld;
    private final EnumSet<ResetService.Dimension> dimensions;
    private final Player initiator;
    private final Long customSeed;
    private final List<World> affectedWorlds;
    private boolean isCancelled = false;

    public ResetTask(String baseWorld, EnumSet<ResetService.Dimension> dimensions, Player initiator, Long customSeed, List<World> affectedWorlds) {
        this.baseWorld = baseWorld;
        this.dimensions = dimensions;
        this.initiator = initiator;
        this.customSeed = customSeed;
        this.affectedWorlds = affectedWorlds;
    }

    public String getBaseWorld() {
        return baseWorld;
    }

    public EnumSet<ResetService.Dimension> getDimensions() {
        return dimensions;
    }

    public Player getInitiator() {
        return initiator;
    }

    public Optional<Long> getCustomSeed() {
        return Optional.ofNullable(customSeed);
    }

    public List<World> getAffectedWorlds() {
        return affectedWorlds;
    }

    public void cancel() {
        isCancelled = true;
        Messages.sendToRelevant("&cWorld reset cancelled by operator.", affectedWorlds);
    }

    public boolean isCancelled() {
        return isCancelled;
    }
}