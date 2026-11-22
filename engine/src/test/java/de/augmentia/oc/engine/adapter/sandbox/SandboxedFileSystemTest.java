package de.augmentia.oc.engine.adapter.sandbox;

import de.augmentia.oc.engine.core.port.world.FileSystem;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

class SandboxedFileSystemTest {

    static class StubFs implements FileSystem {
        public byte[] readResult;
        public Path lastGuarded;

        @Override public byte[] read(Path path) { lastGuarded = path; return readResult; }
        @Override public String readText(Path path) { return ""; }
        @Override public void write(Path path, byte[] content) {}
        @Override public void delete(Path path) {}
        @Override public FileStat stat(Path path) { return new FileStat(path, false, 0, java.time.Instant.EPOCH); }
        @Override public List<Path> list(Path directory) { return List.of(); }
        @Override public List<Path> glob(Path root, String pattern) { return List.of(); }
        @Override public List<SearchMatch> search(Path root, String regex) { return List.of(); }
        @Override public Path realPath(Path path) { return path; }
    }

    @Test
    void rejectsPathOutsideRoot(@TempDir Path root) {
        StubFs delegate = new StubFs();
        SandboxedFileSystem fs = new SandboxedFileSystem(root, delegate);
        Path outside = root.getParent().resolve("escape.txt");
        assertThrows(IllegalArgumentException.class, () -> fs.read(outside));
    }

    @Test
    void rejectsNullPath(@TempDir Path root) {
        StubFs delegate = new StubFs();
        SandboxedFileSystem fs = new SandboxedFileSystem(root, delegate);
        assertThrows(IllegalArgumentException.class, () -> fs.read(null));
    }

    @Test
    void delegatesRead(@TempDir Path root) {
        StubFs delegate = new StubFs();
        SandboxedFileSystem fs = new SandboxedFileSystem(root, delegate);
        Path inside = root.resolve("file.txt");
        byte[] data = "hello".getBytes();
        delegate.readResult = data;
        assertArrayEquals(data, fs.read(inside));
    }
}