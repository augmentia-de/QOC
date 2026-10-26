package de.augmentia.oc.engine.adapter;

import de.augmentia.oc.engine.adapter.fs.NioFileSystem;
import de.augmentia.oc.engine.core.port.world.FileSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class NioFileSystemTest {

    @TempDir
    Path temp;

    @Test
    void writeThenReadRoundTrips() {
        NioFileSystem fs = new NioFileSystem(temp);
        fs.write(temp.resolve("a.txt"), new byte[]{1, 2, 3});
        assertThat(fs.read(temp.resolve("a.txt"))).containsExactly(1, 2, 3);
        assertThat(fs.stat(temp.resolve("a.txt")).directory()).isFalse();
        assertThat(fs.stat(temp.resolve("a.txt")).size()).isEqualTo(3);
    }

    @Test
    void directoriesAreCreatedAndListed() {
        NioFileSystem fs = new NioFileSystem(temp);
        fs.write(temp.resolve("dir/sub/a.txt"), "hello".getBytes());
        assertThat(fs.stat(temp.resolve("dir")).directory()).isTrue();
        assertThat(fs.list(temp)).contains(temp.resolve("dir"));
        assertThat(fs.list(temp.resolve("dir"))).contains(temp.resolve("dir/sub"));
    }

    @Test
    void globAndSearchWork() {
        NioFileSystem fs = new NioFileSystem(temp);
        fs.write(temp.resolve("a.txt"), "alpha\nbeta".getBytes());
        fs.write(temp.resolve("b.md"), "gamma".getBytes());

        assertThat(fs.glob(temp, "*.txt")).containsExactly(temp.resolve("a.txt"));
        assertThat(fs.search(temp, "a")).hasSize(3);
        assertThat(fs.search(temp, "a").getFirst().line()).isEqualTo(1);
    }

    @Test
    void searchSkipsIgnoredHiddenAndBinaryFiles() throws Exception {
        NioFileSystem fs = new NioFileSystem(temp);
        fs.write(temp.resolve("src/a.txt"), "needle".getBytes());
        fs.write(temp.resolve("target/generated.txt"), "needle".getBytes());
        fs.write(temp.resolve(".hidden.txt"), "needle".getBytes());
        Files.write(temp.resolve("binary.bin"), new byte[]{'n', 'e', 'e', 'd', 'l', 'e', 0});

        FileSystem.SearchOutcome outcome = fs.search(temp, "needle", FileSystem.SearchOptions.defaults());

        assertThat(outcome.matches()).hasSize(1);
        assertThat(outcome.matches().getFirst().path()).isEqualTo(temp.resolve("src/a.txt"));
        assertThat(outcome.skipped()).isGreaterThanOrEqualTo(3);
    }

    @Test
    void searchSupportsCaseContextPagingAndLimits() {
        NioFileSystem fs = new NioFileSystem(temp);
        fs.write(temp.resolve("a.txt"), "alpha\nBETA\ngamma".getBytes());
        fs.write(temp.resolve("b.txt"), "beta one".getBytes());
        fs.write(temp.resolve("c.txt"), "beta two".getBytes());
        fs.write(temp.resolve("huge.txt"), "beta ".repeat(100).getBytes());

        FileSystem.SearchOptions options = new FileSystem.SearchOptions(
                false, 0, 2, 1, 8, false, true, 40, 20, Duration.ofSeconds(5));
        FileSystem.SearchOutcome outcome = fs.search(temp, "beta", options);

        assertThat(outcome.matches()).hasSize(2);
        assertThat(outcome.matches().getFirst().before()).containsExactly("alpha");
        assertThat(outcome.total()).isGreaterThanOrEqualTo(3);
        assertThat(outcome.truncated()).isTrue();
        assertThat(outcome.skipped()).isGreaterThanOrEqualTo(1);
    }

    @Test
    void searchCapsHitsPerFile() {
        NioFileSystem fs = new NioFileSystem(temp);
        fs.write(temp.resolve("many.txt"), "beta\nbeta\nbeta\n".getBytes());

        FileSystem.SearchOptions options = new FileSystem.SearchOptions(
                true, 0, 10, 0, 8, false, true, 1_000, 1, Duration.ofSeconds(5));
        FileSystem.SearchOutcome outcome = fs.search(temp, "beta", options);

        assertThat(outcome.matches()).hasSize(1);
        assertThat(outcome.truncated()).isTrue();
    }

    @Test
    void relativePathsAreResolvedAgainstTheConfiguredRoot() {
        NioFileSystem fs = new NioFileSystem(temp);
        fs.write(Path.of("relative.txt"), "hello".getBytes());

        assertThat(temp.resolve("relative.txt")).exists();
        assertThat(fs.readText(Path.of("relative.txt"))).isEqualTo("hello");
    }

    @Test
    void globSupportsPagingHiddenAndDepth() {
        NioFileSystem fs = new NioFileSystem(temp);
        fs.write(temp.resolve("a.txt"), "a".getBytes());
        fs.write(temp.resolve("b.txt"), "b".getBytes());
        fs.write(temp.resolve("c.txt"), "c".getBytes());
        fs.write(temp.resolve(".hidden.txt"), "h".getBytes());
        fs.write(temp.resolve("deep/nested/deep.txt"), "d".getBytes());

        FileSystem.GlobOutcome page = fs.glob(temp, "*.txt",
                new FileSystem.GlobOptions(1, 1, 2, false, true, Duration.ofSeconds(5)));
        FileSystem.GlobOutcome hidden = fs.glob(temp, "*.txt",
                new FileSystem.GlobOptions(0, 10, 2, true, true, Duration.ofSeconds(5)));
        FileSystem.GlobOutcome shallow = fs.glob(temp, "**/*.txt",
                new FileSystem.GlobOptions(0, 10, 2, true, true, Duration.ofSeconds(5)));

        assertThat(page.matches()).containsExactly(temp.resolve("b.txt"));
        assertThat(page.total()).isEqualTo(3);
        assertThat(page.truncated()).isTrue();
        assertThat(hidden.matches()).anyMatch(path -> path.endsWith(".hidden.txt"));
        assertThat(shallow.matches()).noneMatch(path -> path.endsWith("deep.txt"));
    }

    @Test
    void realPathNormalizes() {
        NioFileSystem fs = new NioFileSystem(temp);
        assertThat(fs.realPath(temp.resolve("x/../y"))).isEqualTo(temp.resolve("y"));
    }

    @Test
    void missingFileThrowsUncheckedIoException() {
        NioFileSystem fs = new NioFileSystem(temp);
        assertThatThrownBy(() -> fs.read(temp.resolve("missing"))).isInstanceOf(java.io.UncheckedIOException.class);
        assertThatThrownBy(() -> fs.stat(temp.resolve("missing"))).isInstanceOf(java.io.UncheckedIOException.class);
    }

    @Test
    void writeIsDefensive() {
        NioFileSystem fs = new NioFileSystem(temp);
        byte[] content = {1, 2};
        fs.write(temp.resolve("a.bin"), content);
        content[0] = 9;
        assertThat(fs.read(temp.resolve("a.bin"))).containsExactly(1, 2);
    }
}
