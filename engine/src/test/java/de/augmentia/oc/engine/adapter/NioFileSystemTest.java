package de.augmentia.oc.engine.adapter;

import de.augmentia.oc.engine.adapter.fs.NioFileSystem;
import de.augmentia.oc.engine.core.port.world.FileSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Path;
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
