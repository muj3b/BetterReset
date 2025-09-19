package com.muj3b.betterreset.util;

import com.muj3b.betterreset.FullResetPlugin;

import java.io.BufferedWriter;
import java.io.File;
import java.io.FileWriter;
import java.io.IOException;
import java.time.Instant;

public class ResetAuditLogger {

    public void log(FullResetPlugin plugin, String line) {
        try {
            File dir = new File(plugin.getDataFolder(), "logs");
            if (!dir.exists()) dir.mkdirs();
            File file = new File(dir, "resets.log");
            try (BufferedWriter bw = new BufferedWriter(new FileWriter(file, true))) {
                bw.write("[" + Instant.now() + "] " + line + System.lineSeparator());
            }
        } catch (IOException ignored) {}
    }
}

