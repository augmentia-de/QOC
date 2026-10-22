package de.augmentia.oc.engine.runtime.compaction;

import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.model.CompactionReason;
import de.augmentia.oc.engine.core.model.InboxId;
import de.augmentia.oc.engine.core.model.Money;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.Seq;
import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.model.TokenCount;

/** One compaction assignment. The caller builds the window and the request. */
public record CompactionContext(SessionId sessionId, CompactionReason reason, ModelRequest request,
                                Option<InboxId> inputId, Seq epochStart, Money cost,
                                TokenCount tokens) {

    public CompactionContext {
        if (sessionId == null || reason == null || request == null || epochStart == null) {
            throw new IllegalArgumentException("compaction fields must not be null");
        }
        if (inputId == null) inputId = Option.none();
        if (cost == null) cost = Money.zero();
        if (tokens == null) tokens = TokenCount.zero();
    }
}
