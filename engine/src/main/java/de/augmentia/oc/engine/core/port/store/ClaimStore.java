package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.Claim;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;

import java.time.Instant;
import java.util.List;

/** Execution claim: presence means "work was started but not completed". */
public interface ClaimStore {

    Option<Claim> acquire(SessionId session, Instant at);

    void release(SessionId session);

    List<Claim> open();
}
