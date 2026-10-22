package de.augmentia.oc.engine.testing;

import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.Conversation;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** In-memory folded conversation. */
public final class InMemoryConversation implements Conversation {

    private final Map<SessionId, List<Entry>> entries = new LinkedHashMap<>();

    public synchronized void add(SessionId session, Seq seq, Message message) {
        if (session == null || seq == null || message == null) {
            throw new IllegalArgumentException("conversation entry fields must not be null");
        }
        entries.computeIfAbsent(session, key -> new ArrayList<>()).add(new Entry(seq, message));
    }

    @Override
    public synchronized List<Message> messages(SessionId session, Seq afterSeq) {
        Seq from = afterSeq == null ? Seq.zero() : afterSeq;
        return entries.getOrDefault(session, List.of()).stream()
                .filter(entry -> entry.seq().value() > from.value())
                .map(Entry::message)
                .toList();
    }

    @Override
    public synchronized Option<Message> message(SessionId session, MessageId id) {
        return Option.of(entries.getOrDefault(session, List.of()).stream()
                .map(Entry::message)
                .filter(message -> message.id().equals(id))
                .findFirst()
                .orElse(null));
    }

    public synchronized int size(SessionId session) {
        return entries.getOrDefault(session, List.of()).size();
    }

    private record Entry(Seq seq, Message message) {}
}
