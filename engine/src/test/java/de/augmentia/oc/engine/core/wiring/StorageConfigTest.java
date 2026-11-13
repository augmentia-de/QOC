package de.augmentia.oc.engine.core.wiring;

import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class StorageConfigTest {

    @Test
    void defaultConstructor() {
        StorageConfig config = new StorageConfig(StorageKind.MEMORY);
        assertThat(config.defaultKind()).isEqualTo(StorageKind.MEMORY);
        assertThat(config.kindFor("journal")).isEqualTo(StorageKind.MEMORY);
    }

    @Test
    void nullDefaultBecomesPostgres() {
        StorageConfig config = new StorageConfig(null);
        assertThat(config.defaultKind()).isEqualTo(StorageKind.POSTGRES);
    }

    @Test
    void overrides() {
        StorageConfig config = new StorageConfig(StorageKind.MEMORY, Map.of("journal", StorageKind.POSTGRES));
        assertThat(config.kindFor("journal")).isEqualTo(StorageKind.POSTGRES);
        assertThat(config.kindFor("conversation")).isEqualTo(StorageKind.MEMORY);
    }

    @Test
    void kindForWithFallback() {
        StorageConfig config = new StorageConfig(StorageKind.MEMORY);
        assertThat(config.kindFor("x", StorageKind.POSTGRES)).isEqualTo(StorageKind.POSTGRES);
        assertThat(config.kindFor("x", null)).isEqualTo(StorageKind.MEMORY);
    }

    @Test
    void builder() {
        StorageConfig config = StorageConfig.builder(StorageKind.MEMORY)
                .override("journal", StorageKind.POSTGRES)
                .override("", StorageKind.POSTGRES) // ignored
                .override("x", null) // ignored
                .build();
        assertThat(config.kindFor("journal")).isEqualTo(StorageKind.POSTGRES);
        assertThat(config.kindFor("x")).isEqualTo(StorageKind.MEMORY);
    }

    @Test
    void nullOverridesMap() {
        StorageConfig config = new StorageConfig(StorageKind.MEMORY, null);
        assertThat(config.kindFor("x")).isEqualTo(StorageKind.MEMORY);
    }
}
