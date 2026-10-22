package de.augmentia.oc.engine.core;

import org.junit.jupiter.api.Test;

import java.nio.file.Path;
import java.util.List;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class ShellScanEdgeTest {

    private final PortableScanner scanner = new PortableScanner();

    @Test
    void emptyCommand() {
        ScanResult r = scanner.scan("", "bash", Path.of("/tmp"));
        assertThat(r.commands()).isEmpty();
        assertThat(r.directories()).isEmpty();
    }

    @Test
    void nullCommand() {
        ScanResult r = scanner.scan(null, "bash", Path.of("/tmp"));
        assertThat(r.commands()).isEmpty();
    }

    @Test
    void directoriesDetected() {
        ScanResult r = scanner.scan("cd /etc && ls /tmp", "bash", Path.of("/tmp"));
        assertThat(r.directories()).isNotEmpty();
    }

    @Test
    void scanResultImmutable() {
        ScanResult r = new ScanResult(List.of(new ScannedCommand("a", "a *")), List.of(Path.of("/tmp")));
        assertThatThrownBy(() -> r.commands().add(new ScannedCommand("b", "b *"))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void scannedCommandValidation() {
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new ScannedCommand(" ", "x *"));
        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class, () -> new ScannedCommand("a", " "));
    }
}