package de.augmentia.oc.engine.core.port.store;

import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;

import java.util.List;

/** Read side of the folded conversation. */
public interface Conversation {

    List<Message> messages(SessionId session, Seq afterSeq);

    Option<Message> message(SessionId session, MessageId id);
}
