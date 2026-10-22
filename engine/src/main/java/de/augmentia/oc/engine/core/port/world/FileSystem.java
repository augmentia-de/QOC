package de.augmentia.oc.engine.core.port.world;

import java.nio.file.Path;
import java.time.Instant;
import java.util.List;

/** Filesystem access needed by the tools, bounding and revert. */
public interface FileSystem {

    byte[] read(Path path);

    String readText(Path path);

    void write(Path path, byte[] content);

    FileStat stat(Path path);

    List<Path> list(Path directory);

    List<Path> glob(Path root, String pattern);

    List<SearchMatch> search(Path root, String regex);

    Path realPath(Path path);

    record FileStat(Path path, boolean directory, long size, Instant modified) {
        public FileStat {
            if (path == null) throw new IllegalArgumentException("path must not be null");
            if (modified == null) modified = Instant.EPOCH;
            if (size < 0) throw new IllegalArgumentException("size must not be negative");
        }
    }

    record SearchMatch(Path path, int line, String text) {
        public SearchMatch {
            if (path == null) throw new IllegalArgumentException("path must not be null");
            if (line < 1) throw new IllegalArgumentException("line must be positive");
            if (text == null) text = "";
        }
    }
}
