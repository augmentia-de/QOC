package de.augmentia.oc.engine.http.a2a;

import de.augmentia.oc.engine.http.security.AuthMode;
import de.augmentia.oc.engine.http.security.SecurityConfig;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.auth.TaskAuthorizationProvider;
import org.a2aproject.sdk.server.auth.TaskOperation;
import org.a2aproject.sdk.server.auth.User;

import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Enforces QOC's security mode and per-user task ownership for A2A requests.
 *
 * <ul>
 *   <li>A2A server disabled: deny everything.</li>
 *   <li>QOC auth mode {@code none}: allow everything (single-user/local mode).</li>
 *   <li>Otherwise: require an authenticated principal and restrict task
 *       read/write access to the user that created the task.</li>
 * </ul>
 */
@ApplicationScoped
public class A2ATaskAuthorizationProvider implements TaskAuthorizationProvider {
    static final String LIST_SCOPE = "";

    @Inject
    A2AServerConfig config;

    @Inject
    SecurityConfig security;

    private final ConcurrentMap<String, String> owners = new ConcurrentHashMap<>();

    @Override
    public boolean checkRead(ServerCallContext context, String taskId, TaskOperation operation) {
        if (!config.enabled()) {
            return false;
        }
        if (security.mode() == AuthMode.NONE) {
            return true;
        }
        if (!authenticated(context)) {
            return false;
        }
        if (LIST_SCOPE.equals(taskId)) {
            return true;
        }
        return owns(context, taskId);
    }

    @Override
    public boolean checkWrite(ServerCallContext context, String taskId, TaskOperation operation) {
        return checkRead(context, taskId, operation);
    }

    @Override
    public boolean checkCreate(ServerCallContext context, TaskOperation operation) {
        if (!config.enabled()) {
            return false;
        }
        return security.mode() == AuthMode.NONE || authenticated(context);
    }

    @Override
    public boolean isTaskRecorded(String taskId) {
        return taskId != null && owners.containsKey(taskId);
    }

    @Override
    public void recordOwnership(ServerCallContext context, String taskId, TaskOperation operation) {
        if (taskId == null || taskId.isBlank()) {
            return;
        }
        String owner = username(context);
        if (owner == null) {
            owner = "anonymous";
        }
        owners.putIfAbsent(taskId, owner);
    }

    private boolean owns(ServerCallContext context, String taskId) {
        if (taskId == null || taskId.isBlank()) {
            return false;
        }
        String owner = owners.get(taskId);
        if (owner == null) {
            return false;
        }
        return owner.equals(username(context));
    }

    private static boolean authenticated(ServerCallContext context) {
        return username(context) != null;
    }

    private static String username(ServerCallContext context) {
        if (context == null) {
            return null;
        }
        User user = context.getUser();
        if (user == null || !user.isAuthenticated()) {
            return null;
        }
        String name = user.getUsername();
        return name == null || name.isBlank() ? null : name;
    }
}
