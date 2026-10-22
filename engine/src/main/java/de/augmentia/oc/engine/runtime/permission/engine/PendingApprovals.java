package de.augmentia.oc.engine.runtime.permission.engine;

import de.augmentia.oc.engine.core.contract.permission.PermissionRequest;
import de.augmentia.oc.engine.core.eval.PermissionCheck;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.PermissionRequestId;
import de.augmentia.oc.engine.core.model.SessionId;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Open approval requests per session. Ephemeral: a restart loses them. */
public final class PendingApprovals {

    private final Map<SessionId, LinkedHashMap<PermissionRequestId, PermissionCheck>> pending =
            new LinkedHashMap<>();

    public synchronized void add(PermissionRequest request) {
        if (request == null) throw new IllegalArgumentException("request must not be null");
        add(new PermissionCheck(request, Option.none(), List.of(), List.of(), List.of(), List.of()));
    }

    public synchronized void add(PermissionCheck check) {
        if (check == null) throw new IllegalArgumentException("check must not be null");
        pending.computeIfAbsent(check.request().sessionId(), key -> new LinkedHashMap<>())
                .put(check.request().id(), check);
    }

    public synchronized List<PermissionRequest> list(SessionId session) {
        return checks(session).stream().map(PermissionCheck::request).toList();
    }

    public synchronized List<PermissionCheck> checks(SessionId session) {
        return List.copyOf(pending.getOrDefault(session, new LinkedHashMap<>()).values());
    }

    public synchronized Option<PermissionRequest> remove(SessionId session, PermissionRequestId id) {
        Map<PermissionRequestId, PermissionCheck> requests = pending.get(session);
        if (requests == null) return Option.none();
        PermissionCheck removed = requests.remove(id);
        if (requests.isEmpty()) pending.remove(session);
        return removed == null ? Option.none() : Option.some(removed.request());
    }

    /** Batch removal for a rejection (P-14). */
    public synchronized List<PermissionRequest> removeAll(SessionId session) {
        List<PermissionRequest> removed = new ArrayList<>();
        for (PermissionCheck check : pending.getOrDefault(session, new LinkedHashMap<>()).values()) {
            removed.add(check.request());
        }
        pending.remove(session);
        return List.copyOf(removed);
    }

    public synchronized int count() {
        return pending.values().stream().mapToInt(Map::size).sum();
    }
}
