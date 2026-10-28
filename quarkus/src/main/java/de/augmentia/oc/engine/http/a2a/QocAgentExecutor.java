package de.augmentia.oc.engine.http.a2a;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.http.Engine;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.agentexecution.AgentExecutor;
import org.a2aproject.sdk.server.agentexecution.RequestContext;
import org.a2aproject.sdk.server.auth.User;
import org.a2aproject.sdk.server.tasks.AgentEmitter;
import org.a2aproject.sdk.spec.A2AError;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.FilePart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;
import org.a2aproject.sdk.spec.UnsupportedOperationError;

import java.util.List;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Bridges A2A task execution to the QOC engine.
 *
 * <p>The executor runs on the A2A agent thread pool. It creates (or reuses) a
 * QOC session per A2A context, sends the user message and streams the final
 * assistant text back as an A2A artifact.</p>
 */
@ApplicationScoped
public class QocAgentExecutor implements AgentExecutor {
    private static final Logger LOG = Logger.getLogger(QocAgentExecutor.class.getName());

    @Inject
    Engine engine;

    @Inject
    A2ASessionRegistry sessions;

    @Inject
    A2AServerConfig config;

    @Override
    public void execute(RequestContext context, AgentEmitter emitter) throws A2AError {
        if (!config.enabled()) {
            emitter.fail(new UnsupportedOperationError(null, "A2A server is disabled", null));
            return;
        }
        String user = username(context);
        try {
            String text = extractInput(context);
            if (text == null || text.isBlank()) {
                emitter.fail(emitter.newAgentMessage(
                        List.of(new TextPart("Message must contain text", null)), null));
                return;
            }

            SessionId sessionId = sessions.sessionFor(
                    user, context.getContextId(), context.getTaskId());
            Engine.PromptResult result = engine.prompt(sessionId, text);
            switch (result.outcome()) {
                case Engine.TurnOutcome.Completed completed -> {
                    String output = completed.text();
                    if (output != null && !output.isBlank()) {
                        emitter.addArtifact(List.of(new TextPart(output, null)));
                    }
                    emitter.complete();
                }
                case Engine.TurnOutcome.Busy busy ->
                        emitter.fail(emitter.newAgentMessage(
                                List.of(new TextPart(busy.message(), null)), null));
                case Engine.TurnOutcome.Refused refused ->
                        emitter.reject(emitter.newAgentMessage(
                                List.of(new TextPart(refusal(refused), null)), null));
                case Engine.TurnOutcome.Failed failed ->
                        emitter.fail(emitter.newAgentMessage(
                                List.of(new TextPart(failure(failed), null)), null));
                case Engine.TurnOutcome.Cancelled ignored -> emitter.cancel();
                case Engine.TurnOutcome.Terminated terminated -> emitter.cancel(
                        emitter.newAgentMessage(List.of(new TextPart(
                                "Turn terminated: " + terminated.reason().name(), null)), null));
            }
        } catch (IllegalStateException | IllegalArgumentException e) {
            LOG.log(Level.WARNING, "A2A prompt rejected: {0}", e.getMessage());
            emitter.fail(emitter.newAgentMessage(
                    List.of(new TextPart(messageOf(e), null)), null));
        } catch (RuntimeException e) {
            LOG.log(Level.WARNING, "A2A execution failed", e);
            emitter.fail(emitter.newAgentMessage(
                    List.of(new TextPart("Internal error: " + messageOf(e), null)), null));
        }
    }

    @Override
    public void cancel(RequestContext context, AgentEmitter emitter) {
        if (!config.enabled()) {
            emitter.cancel();
            return;
        }
        SessionId sessionId = sessions.find(username(context),
                context.getContextId(), context.getTaskId()).orElse(null);
        if (sessionId != null) {
            engine.interrupt(sessionId);
        }
        emitter.cancel();
    }

    private static String extractInput(RequestContext context) {
        Message message = context == null ? null : context.getMessage();
        if (message == null || message.parts() == null || message.parts().isEmpty()) {
            return context == null ? null : context.getUserInput("\n");
        }
        StringBuilder sb = new StringBuilder();
        for (Part<?> part : message.parts()) {
            if (part instanceof TextPart tp && tp.text() != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append(tp.text());
            } else if (part instanceof DataPart dp && dp.data() != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("[data] ").append(dp.data());
            } else if (part instanceof FilePart fp && fp.file() != null) {
                if (sb.length() > 0) sb.append('\n');
                sb.append("[file] ").append(fp.file().name()).append(" (").append(fp.file().mimeType()).append(')');
            }
        }
        String result = sb.toString().trim();
        return result.isEmpty() ? context.getUserInput("\n") : result;
    }

    private static String refusal(Engine.TurnOutcome.Refused refused) {
        if (refused.detail() != null && !refused.detail().isBlank()) {
            return "Refused: " + refused.detail();
        }
        return "Refused: " + (refused.reason() == null ? "unknown" : refused.reason());
    }

    private static String failure(Engine.TurnOutcome.Failed failed) {
        String message = failed.error() == null ? "unknown error" : failed.error().message();
        return "Failed: " + message;
    }

    private static String username(RequestContext context) {
        ServerCallContext callContext = context.getCallContext();
        if (callContext == null) {
            return null;
        }
        User user = callContext.getUser();
        if (user == null || !user.isAuthenticated()) {
            return null;
        }
        String name = user.getUsername();
        return name == null || name.isBlank() ? null : name;
    }

    private static String messageOf(Throwable throwable) {
        if (throwable.getMessage() != null && !throwable.getMessage().isBlank()) {
            return throwable.getMessage();
        }
        return throwable.getClass().getSimpleName();
    }
}
