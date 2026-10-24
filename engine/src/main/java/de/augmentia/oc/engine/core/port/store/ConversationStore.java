package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;

/** Conversation read/write projection. */
public interface ConversationStore extends Conversation {
    void insert(SessionId session, Seq seq, Message message);

    /** Next gapless sequence number for this session. */
    Seq nextSeq(SessionId session);

    /** Sequence of one stored message, or empty when it does not exist. */
    Option<Seq> seqOf(SessionId session, MessageId message);

    /** Deletes all messages at or after the sequence (revert projection). */
    void deleteFrom(SessionId session, Seq seq);
}
