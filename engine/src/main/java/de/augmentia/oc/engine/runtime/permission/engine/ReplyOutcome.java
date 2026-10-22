package de.augmentia.oc.engine.runtime.permission.engine;

import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.model.Option;

import java.util.List;

/** Result of answering a pending approval. */
public sealed interface ReplyOutcome {

    record Once(PermissionRequest request) implements ReplyOutcome {}

    record Always(PermissionRequest request, List<String> savedPatterns,
                  List<PermissionRequest> released) implements ReplyOutcome {
        public Always {
            savedPatterns = savedPatterns == null ? List.of() : List.copyOf(savedPatterns);
            released = released == null ? List.of() : List.copyOf(released);
        }

        public Always(PermissionRequest request, List<String> savedPatterns) {
            this(request, savedPatterns, List.of());
        }
    }

    record Reject(List<PermissionRequest> requests, Option<String> feedback) implements ReplyOutcome {
        public Reject {
            requests = requests == null ? List.of() : List.copyOf(requests);
            if (feedback == null) feedback = Option.none();
        }
    }
}
