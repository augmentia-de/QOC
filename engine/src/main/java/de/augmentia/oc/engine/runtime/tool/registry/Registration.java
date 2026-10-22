package de.augmentia.oc.engine.runtime.tool.registry;

import de.augmentia.oc.engine.core.model.ToolName;

/** Handle of one registration. Closing it removes only this registration. */
public interface Registration extends AutoCloseable {

    ToolName name();

    boolean active();

    @Override
    void close();
}
