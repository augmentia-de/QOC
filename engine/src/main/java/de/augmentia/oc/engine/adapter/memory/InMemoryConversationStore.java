package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.MessageId;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.store.Conversation;
import de.augmentia.oc.engine.core.port.store.ConversationStore;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.locks.ReentrantLock;

/**
 * In-memory conversation store: messages per session in ArrayList, gapless seq.
 */
public final class InMemoryConversationStore implements ConversationStore {

    private final RuntimeState state;

    private static final class ConvoLog {
        final List<Message> messages = new ArrayList<>();
        final List<MessageId> parents = new ArrayList<>();
        final java.util.concurrent.atomic.AtomicLong seq = new java.util.concurrent.atomic.AtomicLong(0);
    }

    public InMemoryConversationStore(RuntimeState state) {
        this.state = state == null ? new RuntimeState(new MemoryBudget(1L << 30)) : state;
    }

    private ConvoLog log(SessionId session) {
        return (ConvoLog) state.conversations.computeIfAbsent(session, k -> new ConvoLog());
    }

    @Override
    public void insertWithParent(SessionId session, Seq seq, Message message, Option<MessageId> parent) {
        if (session == null || seq == null || message == null) throw new IllegalArgumentException("null argument");
        ReentrantLock lock = state.lock(session);
        lock.lock();
        try {
            ConvoLog log = log(session);
            // Ensure list is large enough
            while (log.messages.size() < seq.value()) {
                log.messages.add(null);
                log.parents.add(null);
            }
            int idx = (int) (seq.value() - 1);
            if (idx < log.messages.size()) {
                log.messages.set(idx, message);
                log.parents.set(idx, parent == null || parent.isEmpty() ? null : parent.orElseThrow());
            } else {
                log.messages.add(message);
                log.parents.add(parent == null || parent.isEmpty() ? null : parent.orElseThrow());
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public Seq nextSeq(SessionId session) {
        if (session == null) throw new IllegalArgumentException("session must not be null");
        ConvoLog log = log(session);
        return Seq.of(log.seq.incrementAndGet());
    }

    @Override
    public Option<Seq> seqOf(SessionId session, MessageId message) {
        if (session == null || message == null) return Option.none();
        ConvoLog log = (ConvoLog) state.conversations.get(session);
        if (log == null) return Option.none();
        ReentrantLock lock = state.lock(session);
        lock.lock();
        try {
            for (int i = 0; i < log.messages.size(); i++) {
                Message m = log.messages.get(i);
                if (m != null && m.id().equals(message)) {
                    return Option.some(Seq.of(i + 1));
                }
            }
        } finally {
            lock.unlock();
        }
        return Option.none();
    }

    @Override
    public void deleteFrom(SessionId session, Seq seq) {
        if (session == null || seq == null) return;
        ConvoLog log = (ConvoLog) state.conversations.get(session);
        if (log == null) return;
        ReentrantLock lock = state.lock(session);
        lock.lock();
        try {
            int from = (int) (seq.value() - 1);
            if (from < 0) from = 0;
            while (log.messages.size() > from) {
                log.messages.remove(log.messages.size() - 1);
                log.parents.remove(log.parents.size() - 1);
            }
        } finally {
            lock.unlock();
        }
    }

    @Override
    public List<Message> messages(SessionId session, Seq afterSeq) {
        if (session == null) return List.of();
        ConvoLog log = (ConvoLog) state.conversations.get(session);
        if (log == null) return List.of();
        long threshold = afterSeq == null ? 0 : afterSeq.value();
        List<Message> result = new ArrayList<>();
        ReentrantLock lock = state.lock(session);
        lock.lock();
        try {
            for (int i = 0; i < log.messages.size(); i++) {
                if (i + 1 > threshold) {
                    Message m = log.messages.get(i);
                    if (m != null) result.add(m);
                }
            }
        } finally {
            lock.unlock();
        }
        return List.copyOf(result);
    }

    @Override
    public Option<Message> message(SessionId session, MessageId id) {
        if (session == null || id == null) return Option.none();
        ConvoLog log = (ConvoLog) state.conversations.get(session);
        if (log == null) return Option.none();
        ReentrantLock lock = state.lock(session);
        lock.lock();
        try {
            for (Message m : log.messages) {
                if (m != null && m.id().equals(id)) return Option.some(m);
            }
        } finally {
            lock.unlock();
        }
        return Option.none();
    }

    @Override
    public Option<MessageId> parentOf(SessionId session, MessageId id) {
        if (session == null || id == null) return Option.none();
        ConvoLog log = (ConvoLog) state.conversations.get(session);
        if (log == null) return Option.none();
        ReentrantLock lock = state.lock(session);
        lock.lock();
        try {
            for (int i = 0; i < log.messages.size(); i++) {
                Message m = log.messages.get(i);
                if (m != null && m.id().equals(id)) {
                    MessageId parent = (i < log.parents.size()) ? log.parents.get(i) : null;
                    return parent == null ? Option.none() : Option.some(parent);
                }
            }
        } finally {
            lock.unlock();
        }
        return Option.none();
    }
}
