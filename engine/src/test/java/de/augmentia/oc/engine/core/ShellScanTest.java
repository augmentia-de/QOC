package de.augmentia.oc.engine.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;

import static org.assertj.core.api.Assertions.assertThat;

class ShellScanTest {

    private final PortableScanner scanner = new PortableScanner();

    @Test
    void gitStatus() {
        ScanResult r = scanner.scan("git status", "bash", Path.of("/tmp"));
        assertThat(r.commands()).hasSize(1);
        assertThat(r.commands().get(0).resource()).isEqualTo("git status");
        assertThat(r.commands().get(0).save()).isEqualTo("git status *");
    }

    @Test
    void gitCommit() {
        ScanResult r = scanner.scan("git commit -m \"x\"", "bash", Path.of("/tmp"));
        assertThat(r.commands()).hasSize(1);
        assertThat(r.commands().get(0).resource()).isEqualTo("git commit -m \"x\"");
        assertThat(r.commands().get(0).save()).isEqualTo("git commit *");
    }

    @Test
    void ls() {
        ScanResult r = scanner.scan("ls -la /tmp", "bash", Path.of("/tmp"));
        assertThat(r.commands()).hasSize(1);
        assertThat(r.commands().get(0).save()).isEqualTo("ls *");
    }

    @Test
    void chainedCommands() {
        ScanResult r = scanner.scan("cat a.txt && rm b.txt", "bash", Path.of("/tmp"));
        assertThat(r.commands()).hasSize(2);
        assertThat(r.commands().get(0).save()).isEqualTo("cat *");
        assertThat(r.commands().get(1).save()).isEqualTo("rm *");
    }
}