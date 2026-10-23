package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;

/** Conversation read/write projection. */
public interface ConversationStore extends Conversation {
    void insert(SessionId session, Seq seq, Message message);

    /** Next gapless sequence number for this session. */
    Seq nextSeq(SessionId session);
}
