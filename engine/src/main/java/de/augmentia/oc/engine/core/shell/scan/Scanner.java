package de.augmentia.oc.engine.core.shell.scan;

import java.nio.file.Path;

public interface Scanner {
    ScanResult scan(String command, String shell, Path cwd);
}