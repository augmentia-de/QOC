package de.augmentia.qoc.temporal.activity;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.runtime.QocEngine;
import de.augmentia.oc.engine.core.model.SessionInfo;

import java.nio.file.Path;
import java.util.List;

/**
 * Activity implementation: uses QocEngine to create a session and run a prompt.
 */
public class QocActivityImpl implements QocActivity {

    private final QocEngine engine;

    public QocActivityImpl(QocEngine engine) {
        this.engine = engine;
    }

    @Override
    public String runPrompt(String agentName, String prompt) {
        return engine.runPrompt(
                agentName == null || agentName.isBlank() ? "build" : agentName,
                prompt).text();
    }
}
