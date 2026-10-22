package de.augmentia.oc.engine.runtime.compaction;

import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.model.EventDraft;
import de.augmentia.oc.engine.core.model.SessionError;
import de.augmentia.oc.engine.core.model.SessionEvent;
import de.augmentia.oc.engine.core.port.store.Journal;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Exactly one compaction at a time; on success the epoch moves to the exact
 * sequence. The raw event log is never rewritten - compaction is a view.
 */
public final class Compaction {

    private final Journal journal;
    private final ProviderAdapter provider;
    private final AtomicBoolean running = new AtomicBoolean();

    public Compaction(Journal journal, ProviderAdapter provider) {
        if (journal == null || provider == null) {
            throw new IllegalArgumentException("journal and provider must not be null");
        }
        this.journal = journal;
        this.provider = provider;
    }

    public CompactionResult compact(CompactionContext context) {
        if (context == null) throw new IllegalArgumentException("context must not be null");
        if (!running.compareAndSet(false, true)) {
            return new CompactionResult.AlreadyInProgress();
        }
        try {
            journal.append(new EventDraft(context.sessionId().value(), 1,
                    new SessionEvent.CompactionStarted(context.sessionId(), context.reason(), context.inputId())));

            List<ModelEvent> events = provider.stream(context.request()).toList();

            for (ModelEvent event : events) {
                if (event instanceof ModelEvent.ProviderFailed failed) {
                    journal.append(new EventDraft(context.sessionId().value(), 1,
                            new SessionEvent.CompactionFailed(context.sessionId(), context.reason(),
                                    new SessionError.CompactionError(
                                            failed.kind().getClass().getSimpleName(), failed.message()),
                                    context.inputId())));
                    return new CompactionResult.Failed(failed.kind(), failed.message());
                }
            }

            String summary = summary(events);
            journal.append(new EventDraft(context.sessionId().value(), 1,
                    new SessionEvent.CompactionEnded(context.sessionId(), context.reason(), summary,
                            context.request().model(), context.cost(), context.tokens())));
            return new CompactionResult.Compacted(summary, context.epochStart());
        } finally {
            running.set(false);
        }
    }

    private static String summary(List<ModelEvent> events) {
        List<String> ended = new ArrayList<>();
        List<String> deltas = new ArrayList<>();
        for (ModelEvent event : events) {
            if (event instanceof ModelEvent.TextEnded text) ended.add(text.text());
            else if (event instanceof ModelEvent.TextDelta delta) deltas.add(delta.text());
        }
        return String.join("", ended.isEmpty() ? deltas : ended);
    }
}
