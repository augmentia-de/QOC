package de.augmentia.oc.engine.core.contract.tool;
import de.augmentia.oc.engine.core.model.*;

public record ToolOptions(Option<String> namespace, Option<String> permission, boolean codemode, boolean pinned) {
    public ToolOptions {
        if (namespace == null) namespace = Option.none();
        if (permission == null) permission = Option.none();
    }

    public static ToolOptions defaults() {
        return new ToolOptions(Option.none(), Option.none(), true, false);
    }
}