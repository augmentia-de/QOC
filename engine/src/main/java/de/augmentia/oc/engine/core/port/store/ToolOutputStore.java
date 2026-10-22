package de.augmentia.oc.engine.core.port.store;

import java.nio.file.Path;
import java.time.Instant;

/** Store for full tool output that was too large for the model context. */
public interface ToolOutputStore {

    Path save(String suffix, byte[] bytes);

    byte[] load(Path path);

    void sweep(Instant olderThan);
}
