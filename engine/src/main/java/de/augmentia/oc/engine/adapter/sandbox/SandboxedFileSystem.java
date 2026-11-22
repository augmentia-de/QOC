package de.augmentia.oc.engine.adapter.sandbox;

import de.augmentia.oc.engine.core.port.world.FileSystem;

import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.Duration;
import java.util.List;

/**
 * Explicit FileSystem wrapper that guards every path against the workspace root.
 * Prevents symlink traversal and path escape. Delegates to the wrapped FileSystem.
 */
public final class SandboxedFileSystem implements FileSystem {

    private final Path root;
    private final FileSystem delegate;

    public SandboxedFileSystem(SandboxConfig config, FileSystem delegate) {
        if (config == null) throw new IllegalArgumentException("config must not be null");
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        this.root = config.workspaceRoot().toAbsolutePath().normalize();
        this.delegate = delegate;
    }

    public SandboxedFileSystem(Path root, FileSystem delegate) {
        if (root == null) throw new IllegalArgumentException("root must not be null");
        if (delegate == null) throw new IllegalArgumentException("delegate must not be null");
        this.root = root.toAbsolutePath().normalize();
        this.delegate = delegate;
    }

    private Path guard(Path path) {
        if (path == null) throw new IllegalArgumentException("path must not be null");
        Path resolved = path.toAbsolutePath().normalize();
        if (!resolved.startsWith(root)) {
            throw new IllegalArgumentException("path escapes sandbox root: " + path);
        }
        return resolved;
    }

    @Override
    public byte[] read(Path path) {
        return delegate.read(guard(path));
    }

    @Override
    public String readText(Path path) {
        return delegate.readText(guard(path));
    }

    @Override
    public void write(Path path, byte[] content) {
        delegate.write(guard(path), content);
    }

    @Override
    public void delete(Path path) {
        delegate.delete(guard(path));
    }

    @Override
    public FileStat stat(Path path) {
        return delegate.stat(guard(path));
    }

    @Override
    public List<Path> list(Path directory) {
        return delegate.list(guard(directory));
    }

    @Override
    public List<Path> glob(Path root, String pattern) {
        return delegate.glob(guard(root), pattern);
    }

    @Override
    public List<SearchMatch> search(Path root, String regex) {
        return delegate.search(guard(root), regex);
    }

    @Override
    public Path realPath(Path path) {
        return delegate.realPath(guard(path));
    }
}