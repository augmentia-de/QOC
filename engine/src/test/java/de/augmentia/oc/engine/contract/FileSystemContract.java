package de.augmentia.oc.engine.contract;

import de.augmentia.oc.engine.core.port.world.FileSystem;
import de.augmentia.oc.engine.testing.FakeFileSystem;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Contract of {@link FileSystem}. */
class FileSystemContract {

    private FakeFileSystem fs;
    private Path root;

    @BeforeEach
    void setUp() {
        fs = new FakeFileSystem();
        root = Path.of("/work").toAbsolutePath();
        fs.mkdir(root);
    }

    @Test
    void writeThenReadRoundTripsBytes() {
        Path file = root.resolve("a.txt");
        fs.write(file, new byte[]{1, 2, 3});

        assertThat(fs.read(file)).containsExactly(1, 2, 3);
        assertThat(fs.exists(file)).isTrue();
    }

    @Test
    void directoriesAreDerivedFromFiles() {
        Path file = root.resolve("dir/sub/a.txt");
        fs.putText(file, "hello");

        assertThat(fs.stat(file).directory()).isFalse();
        assertThat(fs.stat(file).size()).isEqualTo(5);
        assertThat(fs.stat(root.resolve("dir")).directory()).isTrue();
        assertThat(fs.list(root.resolve("dir"))).containsExactly(root.resolve("dir/sub"));
    }

    @Test
    void missingPathThrowsUncheckedIoException() {
        assertThatThrownBy(() -> fs.read(root.resolve("missing"))).isInstanceOf(java.io.UncheckedIOException.class);
        assertThatThrownBy(() -> fs.stat(root.resolve("missing"))).isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void listReturnsDirectChildrenOnly() {
        fs.putText(root.resolve("a.txt"), "a");
        fs.putText(root.resolve("dir/b.txt"), "b");

        assertThat(fs.list(root)).containsExactly(root.resolve("a.txt"), root.resolve("dir"));
    }

    @Test
    void globMatchesRelativePaths() {
        fs.putText(root.resolve("a.txt"), "a");
        fs.putText(root.resolve("dir/b.txt"), "b");
        fs.putText(root.resolve("dir/c.md"), "c");

        assertThat(fs.glob(root, "*.txt")).containsExactly(root.resolve("a.txt"));
        assertThat(fs.glob(root, "dir/*.txt")).containsExactly(root.resolve("dir/b.txt"));
    }

    @Test
    void searchMatchesLinesWithRegex() {
        fs.putText(root.resolve("a.txt"), "alpha\nbeta\ngamma");
        fs.putText(root.resolve("b.txt"), "delta");

        var matches = fs.search(root, "a");
        assertThat(matches).hasSize(4);
        assertThat(matches.getFirst().path()).isEqualTo(root.resolve("a.txt"));
        assertThat(matches.getFirst().line()).isEqualTo(1);
    }

    @Test
    void realPathIsAbsoluteAndNormalized() {
        Path path = Path.of("/work/../work/a.txt");
        assertThat(fs.realPath(path)).isEqualTo(root.resolve("a.txt"));
    }

    @Test
    void readTextUsesUtf8() {
        Path file = root.resolve("u.txt");
        fs.putText(file, "grün");
        assertThat(fs.readText(file)).isEqualTo("grün");
    }

    @Test
    void writeIsDefensiveAgainstArrayMutation() {
        Path file = root.resolve("a.bin");
        byte[] content = {1, 2};
        fs.write(file, content);
        content[0] = 9;
        assertThat(fs.read(file)).containsExactly(1, 2);
    }
}
