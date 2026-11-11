package de.augmentia.oc.engine.adapter;

import de.augmentia.oc.engine.core.wiring.ToolDependencies;

import java.nio.file.Path;

public final class ToolFactory {

    private ToolFactory() {}

    public static ToolDependencies create(Path workspacePath) {
        Path ws = (workspacePath == null)
                ? java.nio.file.Path.of(".").toAbsolutePath().normalize()
                : workspacePath.toAbsolutePath().normalize();
        de.augmentia.oc.engine.core.port.world.FileSystem nioFs =
                new de.augmentia.oc.engine.adapter.fs.NioFileSystem(ws);
        de.augmentia.oc.engine.core.port.world.FileSystem cachedFs =
                new de.augmentia.oc.engine.adapter.fs.CachedFileSystem(nioFs);
        de.augmentia.oc.engine.core.port.world.ProcessRunner processRunner =
                new de.augmentia.oc.engine.adapter.process.JdkProcessRunner();
        de.augmentia.oc.engine.core.port.world.HttpTransport httpTransport =
                new de.augmentia.oc.engine.adapter.net.JdkHttpTransport();
        return new ToolDependencies(cachedFs, processRunner, httpTransport, ws);
    }
}
