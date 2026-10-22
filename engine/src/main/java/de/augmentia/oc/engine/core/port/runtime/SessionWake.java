package de.augmentia.oc.engine.core.port.runtime;

import de.augmentia.oc.engine.core.model.SessionId;

/**
 * The only port that points upward: the session writes an input and asks the
 * execution layer to run it, without knowing the execution path.
 */
public interface SessionWake {

    void wake(SessionId session);
}
