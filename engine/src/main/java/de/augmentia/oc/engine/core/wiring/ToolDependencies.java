package de.augmentia.oc.engine.core.wiring;

import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.core.port.world.HttpTransport;
import de.augmentia.oc.engine.core.port.world.ProcessRunner;
import de.augmentia.oc.engine.runtime.tool.registry.ToolRegistry;

import java.nio.file.Path;
import java.util.function.Consumer;

public record ToolDependencies(
        FileSystem fileSystem,
        ProcessRunner processRunner,
        HttpTransport httpTransport,
        Path workspacePath,
        Consumer<ToolRegistry> toolRegistrar
) {
    public ToolDependencies {
        if (workspacePath == null) workspacePath = java.nio.file.Path.of(".").toAbsolutePath().normalize();
        if (toolRegistrar == null) toolRegistrar = r -> {};
    }

    /** Backward-compatible constructor without toolRegistrar (no-op). */
    public ToolDependencies(FileSystem fileSystem, ProcessRunner processRunner,
                            HttpTransport httpTransport, Path workspacePath) {
        this(fileSystem, processRunner, httpTransport, workspacePath, r -> {});
    }

    public static ToolDependencies none() {
        return new ToolDependencies(null, null, null, java.nio.file.Path.of(".").toAbsolutePath().normalize(), r -> {});
    }
}