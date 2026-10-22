package de.augmentia.oc.engine.runtime.tool.fs;

import de.augmentia.oc.engine.core.port.world.FileSystem;

import java.nio.file.Path;

/**
 * Path resolution for the filesystem tools. The boundary decision is exposed
 * so the permission chain can check {@code external_directory} first (W-30).
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
        this.location = normalize(location);
        this.projectRoot = projectRoot == null ? this.location : normalize(projectRoot);
    }

    public Resolved resolve(String raw, boolean directory) {
        if (raw == null || raw.isBlank()) throw new IllegalArgumentException("path must not be blank");
        Path input = expandHome(raw);
        Path absolute = input.isAbsolute()
                ? input.normalize()
                : location.resolve(input).normalize();
        boolean internal = absolute.startsWith(location)
                || (absolute.startsWith(projectRoot) && !absolute.equals(projectRoot));
        String resource = internal ? location.relativize(absolute).toString() : absolute.toString();
        Path boundary = directory ? absolute : absolute.getParent();
        return new Resolved(absolute, resource, internal, boundary);
    }

    public void write(Path path, byte[] content) {
        fileSystem.write(path, content);
    }

    private static Path expandHome(String raw) {
        if (raw.equals("~")) return Path.of(System.getProperty("user.home", "."));
        if (raw.startsWith("~/")) {
            return Path.of(System.getProperty("user.home", ".")).resolve(raw.substring(2));
        }
        return Path.of(raw);
    }

    private Path normalize(Path path) {
        return path.toAbsolutePath().normalize();
    }

    public record Resolved(Path absolute, String resource, boolean external, Path boundary) {
        public Resolved {
            if (absolute == null) throw new IllegalArgumentException("absolute must not be null");
            if (resource == null) resource = "";
        }
    }
}
