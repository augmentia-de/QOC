package de.augmentia.qoc.temporal;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Basic smoke test for the Temporal module.
 */
class TemporalModuleSmokeTest {

    @Test
    void temporalServerClassExists() {
        assertNotNull(TemporalServer.class);
    }
}
