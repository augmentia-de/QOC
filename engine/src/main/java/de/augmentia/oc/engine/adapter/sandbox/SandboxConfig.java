package de.augmentia.oc.engine.adapter.sandbox;

import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;

import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

/**
 * Configuration for the optional sandbox adapter.
 */
public record SandboxConfig(
        String mode,          // "local" | "container"
        String image,
        String network,       // "outbound-only" | "none" | "allowlist"
        int cpuLimit,
        long memoryMb,
        long timeoutSeconds,
        Path workspaceRoot,
        List<String> allowedHosts) {

    public SandboxConfig {
        if (mode == null || mode.isBlank()) mode = "local";
        if (image == null || image.isBlank()) image = "qoc-sandbox:latest";
        if (network == null || network.isBlank()) network = "outbound-only";
        if (cpuLimit <= 0) cpuLimit = 1;
        if (memoryMb <= 0) memoryMb = 512;
        if (timeoutSeconds <= 0) timeoutSeconds = 300;
        if (workspaceRoot == null) workspaceRoot = Path.of(".").toAbsolutePath().normalize();
        allowedHosts = allowedHosts == null ? List.of() : List.copyOf(allowedHosts);
    }

    public boolean isContainerMode() {
        return "container".equalsIgnoreCase(mode);
    }

    public boolean isLocalMode() {
        return "local".equalsIgnoreCase(mode);
    }
}