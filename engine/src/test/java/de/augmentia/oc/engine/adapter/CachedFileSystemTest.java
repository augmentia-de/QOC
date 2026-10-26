package de.augmentia.oc.engine.adapter;

import de.augmentia.oc.engine.adapter.fs.CachedFileSystem;
import de.augmentia.oc.engine.adapter.fs.NioFileSystem;
import de.augmentia.oc.engine.core.port.world.FileSystem;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.nio.file.Files;
import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class CachedFileSystemTest {

    @Test
    void writeInvalidatesCachedGlob(@TempDir Path root) throws Exception {
        NioFileSystem nio = new NioFileSystem(root);
        CachedFileSystem cached = new CachedFileSystem(nio);

        assertThat(cached.glob(root, "*.txt", FileSystem.GlobOptions.defaults()).matches()).isEmpty();

        cached.write(root.resolve("a.txt"), "hello".getBytes());

        assertThat(cached.glob(root, "*.txt", FileSystem.GlobOptions.defaults()).matches())
                .hasSize(1);
    }

    @Test
    void ttlRefreshesScansAfterShellCreatedFiles(@TempDir Path root) throws Exception {
        NioFileSystem nio = new NioFileSystem(root);
        CachedFileSystem cached = new CachedFileSystem(nio);
        Files.writeString(root.resolve("a.txt"), "hello");

        assertThat(cached.glob(root, "*.txt", FileSystem.GlobOptions.defaults()).matches())
                .hasSize(1);

        Files.writeString(root.resolve("b.txt"), "world");
        // Still cached for the short TTL.
        assertThat(cached.glob(root, "*.txt", FileSystem.GlobOptions.defaults()).matches())
                .hasSize(1);

        Thread.sleep(1_100);
        assertThat(cached.glob(root, "*.txt", FileSystem.GlobOptions.defaults()).matches())
                .hasSize(2);
    }
}
