package de.augmentia.oc.engine.adapter.memory;

import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.StoredEvent;
import de.augmentia.oc.engine.core.port.store.Journal;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * In-memory journal: append-only event log per aggregate with gapless sequences.
 */
public final class InMemoryJournal implements Journal {

    private final RuntimeState state;
    private final de.augmentia.oc.engine.core.port.runtime.Clock clock;

    private static final class EventLog {
        final List<StoredEvent> events = new ArrayList<>();
        final AtomicLong seq = new AtomicLong(0);
    }

    public InMemoryJournal(RuntimeState state) {
        this(state, null);
    }

    public InMemoryJournal(RuntimeState state, de.augmentia.oc.engine.core.port.runtime.Clock clock) {
        this.state = state == null ? new RuntimeState(new MemoryBudget(1L << 30)) : state;
        this.clock = clock == null ? new de.augmentia.oc.engine.adapter.time.SystemClock() : clock;
    }

    private EventLog log(String aggregateId) {
        return (EventLog) state.journals.computeIfAbsent(aggregateId, k -> new EventLog());
    }

    @Override
    public Seq append(EventDraft draft) {
        if (draft == null) throw new IllegalArgumentException("draft must not be null");
        EventLog log = log(draft.aggregateId());
        synchronized (log) {
            long next = log.seq.incrementAndGet();
            Seq seq = Seq.of(next);
            Instant now = clock.now();
            StoredEvent event = StoredEvent.from(draft.aggregateId(), seq, draft, now);
            log.events.add(event);
            return seq;
        }
    }

    @Override
    public List<Seq> appendAll(List<EventDraft> drafts) {
        if (drafts == null) return List.of();
        List<Seq> result = new ArrayList<>();
        for (EventDraft d : drafts) result.add(append(d));
        return List.copyOf(result);
    }

    @Override
    public List<StoredEvent> history(String aggregateId, Seq after) {
        if (aggregateId == null || aggregateId.isBlank()) return List.of();
        EventLog log = (EventLog) state.journals.get(aggregateId);
        if (log == null) return List.of();
        long threshold = after == null ? 0 : after.value();
        List<StoredEvent> result = new ArrayList<>();
        synchronized (log) {
            for (StoredEvent e : log.events) {
                if (e.seq().value() > threshold) result.add(e);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public List<StoredEvent> historyOfType(String aggregateId, String type, Seq after) {
        if (aggregateId == null || aggregateId.isBlank() || type == null || type.isBlank()) return List.of();
        EventLog log = (EventLog) state.journals.get(aggregateId);
        if (log == null) return List.of();
        long threshold = after == null ? 0 : after.value();
        List<StoredEvent> result = new ArrayList<>();
        synchronized (log) {
            for (StoredEvent e : log.events) {
                if (e.seq().value() > threshold && type.equals(e.type())) result.add(e);
            }
        }
        return List.copyOf(result);
    }

    @Override
    public void deleteAggregate(String aggregateId) {
        if (aggregateId == null || aggregateId.isBlank()) return;
        state.journals.remove(aggregateId);
    }

    @Override
    public void deleteOlderThan(java.time.Instant cutoff) {
        if (cutoff == null) return;
        for (Object o : state.journals.values()) {
            EventLog log = (EventLog) o;
            synchronized (log) {
                log.events.removeIf(e -> e.time().isBefore(cutoff));
            }
        }
    }
}
