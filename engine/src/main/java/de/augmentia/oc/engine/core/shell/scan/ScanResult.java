package de.augmentia.oc.engine.core.shell.scan;

import java.nio.file.Path;
import java.util.List;

public record ScanResult(List<ScannedCommand> commands, List<Path> directories) {
    public ScanResult {
        if (commands == null) commands = List.of();
        if (directories == null) directories = List.of();
        commands = List.copyOf(commands);
        directories = List.copyOf(directories);
    }
}