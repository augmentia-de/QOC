package de.augmentia.oc.engine.core.contract.provider;

import de.augmentia.oc.engine.core.model.ToolName;

public sealed interface Choice {
    record Free() implements Choice {}
    record None() implements Choice {}
    record One(ToolName name) implements Choice {
        public One {
            if (name == null) throw new IllegalArgumentException("name must not be null");
        }
    }
    record Required() implements Choice {}
}