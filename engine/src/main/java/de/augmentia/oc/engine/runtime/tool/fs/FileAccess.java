package de.augmentia.oc.engine.runtime.tool.fs;

import de.augmentia.oc.engine.core.port.world.FileSystem;

import java.io.IOException;
import java.nio.file.Path;

/**
 * Path resolution for the filesystem tools. The boundary decision is exposed
 * so the permission chain can check {@code external_directory} first (W-30).
 *
 * <p>Paths are resolved against the real filesystem (symlinks are followed via
 * {@link #realPath}) so that a link or {@code ..} segment cannot escape the
 * configured {@code location}/{@code projectRoot} allow-list. Logical
 * normalisation alone is not sufficient for this: {@code Path.normalize()}
 * does not resolve symlinks.</p>
 */
public final class FileAccess {

    private final FileSystem fileSystem;
    private final Path location;
    private final Path projectRoot;

    public FileAccess(FileSystem fileSystem, Path location, Path projectRoot) {
        if (fileSystem == null || location == null) {
            throw new IllegalArgumentException("fileSystem and location must not be null");
        }
        this.fileSystem = fileSystem;
        this.location = normalize(fileSystem, location);
        this.projectRoot = projectRoot == null ? this.location : normalize(fileSystem, projectRoot);
    }

    public Resolved resolve(String raw, boolean directory) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("path must not be blank");
        if (raw.startsWith(de.augmentia.oc.engine.core.model.ArtifactRef.SCHEME)) {
            throw new IllegalArgumentException("artifact URIs are read-only");
        }
        Path input = expandHome(raw);
        Path logicalAbsolute = input.isAbsolute()
                ? input.normalize()
                : location.resolve(input).normalize();
        // Resolve symlinks (and any remaining traversal) against the real
        // filesystem before the allow-list check.
        Path absolute = realPath(fileSystem, logicalAbsolute);
        boolean internal = isUnder(location, absolute) || isUnder(projectRoot, absolute);
        String resource = internal ? safeRelativize(location, absolute) : absolute.toString();
        Path boundary = directory ? absolute : (absolute.getParent() == null ? absolute : absolute.getParent());
        return new Resolved(absolute, resource, !internal, boundary);
    }

    public void write(Path path, byte[] content) {
        fileSystem.write(path, content);
    }

    /**
     * True when {@code path} lies inside {@code root} (or equals it).
     * Symlink/normalisation escapes are handled by callers that resolve both
     * sides with {@link #realPath} first.
     */
    public static boolean isUnder(Path root, Path path) {
        if (root == null || path == null) return false;
        Path r = realPath(root).toAbsolutePath().normalize();
        Path p = realPath(path).toAbsolutePath().normalize();
        if (p.equals(r)) return true;
        // Component-wise prefix check. Path.startsWith(String) would also
        // accept "/tmp/basefoo" as under "/tmp/base", so use the Path form.
        return p.startsWith(r) && p.getNameCount() > r.getNameCount();
    }

    /**
     * Resolves {@code path} to its real filesystem location, following
     * symlinks. Missing targets (typical for writes) are handled by resolving
     * the nearest existing parent and re-appending the tail.
     */
    public static Path realPath(FileSystem fileSystem, Path path) {
        if (path == null) return null;
        Path current = path.toAbsolutePath().normalize();
        if (fileSystem != null) {
            try {
                Path resolved = fileSystem.realPath(current);
                if (resolved != null) {
                    resolved = resolved.toAbsolutePath().normalize();
                    if (!resolved.equals(current)) return resolved;
                }
            } catch (RuntimeException ignored) {
                // Fall through to the java.nio fallback below.
            }
        }
        try {
            return current.toRealPath();
        } catch (IOException ignored) {
            Path parent = current.getParent();
            if (parent != null && !parent.equals(current)) {
                Path realParent = realPath(fileSystem, parent);
                if (realParent != null && !realParent.equals(parent)) {
                    return realParent.resolve(current.getFileName()).normalize();
                }
            }
            return current;
        }
    }

    /** Convenience overload that does not need a {@link FileSystem}. */
    public static Path realPath(Path path) {
        return realPath(null, path);
    }

    private static Path expandHome(String raw) {
        if (raw.equals("~")) return Path.of(System.getProperty("user.home", "."));
        if (raw.startsWith("~/")) {
            return Path.of(System.getProperty("user.home", ".")).resolve(raw.substring(2));
        }
        return Path.of(raw);
    }

    private static Path normalize(FileSystem fileSystem, Path path) {
        Path current = path.toAbsolutePath().normalize();
        return realPath(fileSystem, current);
    }

    private static String safeRelativize(Path root, Path path) {
        try {
            return root.relativize(path).toString();
        } catch (IllegalArgumentException e) {
            return path.toString();
        }
    }

    public record Resolved(Path absolute, String resource, boolean external, Path boundary) {
        public Resolved {
            if (absolute == null) throw new IllegalArgumentException("absolute must not be null");
            if (resource == null) resource = "";
        }
    }
}
