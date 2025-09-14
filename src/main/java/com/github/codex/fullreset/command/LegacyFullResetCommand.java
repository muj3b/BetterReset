package com.github.codex.fullreset.command;

import com.github.codex.fullreset.FullResetPlugin;
import com.github.codex.fullreset.util.Messages;
import org.bukkit.command.*;
import org.jetbrains.annotations.NotNull;
import org.jetbrains.annotations.Nullable;

import java.util.List;

/**
 * Legacy handler for /fullreset – hints users to use /betterreset fullreset
 * and delegates to FullResetCommand for functionality.
 */
public class LegacyFullResetCommand implements CommandExecutor, TabCompleter {
    private final FullResetPlugin plugin;
    private final FullResetCommand delegate;

    public LegacyFullResetCommand(FullResetPlugin plugin, FullResetCommand delegate) {
        this.plugin = plugin;
        this.delegate = delegate;
    }

    @Override
    public boolean onCommand(@NotNull CommandSender sender, @NotNull Command command, @NotNull String label, @NotNull String[] args) {
        Messages.send(sender, "&7Tip: Use &e/betterreset fullreset &7instead of /fullreset.");
        return delegate.onCommand(sender, command, label, args);
    }

    @Override
    public @Nullable List<String> onTabComplete(@NotNull CommandSender sender, @NotNull Command command, @NotNull String alias, @NotNull String[] args) {
        return delegate.onTabComplete(sender, command, alias, args);
    }
}

