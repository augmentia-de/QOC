package de.augmentia.oc.engine.core.model;
import de.augmentia.oc.engine.core.json.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Durable session events. A new variant makes the compiler report every
 * projection that has to handle it.
 */
public sealed interface SessionEvent extends Payload {

    record SessionCreated(SessionId sessionId, Location location, AgentId agent,
                          Option<ModelRef> model, Instant time) implements SessionEvent {}

    record SessionRenamed(SessionId sessionId, String title) implements SessionEvent {}

    record SessionArchived(SessionId sessionId) implements SessionEvent {}

    record SessionUnarchived(SessionId sessionId) implements SessionEvent {}

    record SessionMetadataUpdated(SessionId sessionId, Map<String, Object> metadata) implements SessionEvent {}

    record SessionPermissions(SessionId sessionId, List<Rule> permissions) implements SessionEvent {}

    record SessionAgentSelected(SessionId sessionId, AgentId agent, Option<AgentId> previous) implements SessionEvent {}

    record SessionModelSelected(SessionId sessionId, ModelRef model, Option<ModelRef> previous) implements SessionEvent {}

    record SessionViewed(SessionId sessionId, Instant idle) implements SessionEvent {}

    record SessionMoved(SessionId sessionId, Location location) implements SessionEvent {}

    record SessionForked(SessionId sessionId, SessionId parentId, ForkBoundary boundary) implements SessionEvent {}

    record SessionDeleted(SessionId sessionId) implements SessionEvent {}

    record InboxEnqueued(SessionId sessionId, InboxId inboxId, InboxItem item,
                         Seq enqueuedSeq) implements SessionEvent {}

    record InboxDelivered(SessionId sessionId, InboxId inboxId) implements SessionEvent {}

    record InboxCancelled(SessionId sessionId, InboxId inboxId) implements SessionEvent {}

    record InboxDeliveryChanged(SessionId sessionId, InboxId inboxId,
                                Delivery delivery) implements SessionEvent {}

    record InstructionsUpdated(SessionId sessionId, Map<String, Option<String>> delta,
                               Option<String> text) implements SessionEvent {}

    record SyntheticRaised(SessionId sessionId, String text, Option<String> description,
                           Map<String, Object> metadata) implements SessionEvent {}

    record SkillActivated(SessionId sessionId, SkillId skill, String name, String text) implements SessionEvent {}

    record ShellStarted(SessionId sessionId, ShellInfo shell) implements SessionEvent {}

    record ShellEnded(SessionId sessionId, ShellInfo shell, String output) implements SessionEvent {}

    record StepStarted(SessionId sessionId, MessageId assistantMessageId, AgentId agent,
                       ModelRef model, Instant started) implements SessionEvent {}

    record StepStreamed(SessionId sessionId, MessageId assistantMessageId) implements SessionEvent {}

    record StepEnded(SessionId sessionId, MessageId assistantMessageId, FinishReason finish,
                     Money cost, TokenCount tokens) implements SessionEvent {}

    record StepFailed(SessionId sessionId, MessageId assistantMessageId, SessionError error,
                      Money cost, TokenCount tokens) implements SessionEvent {}

    record TextStarted(SessionId sessionId, MessageId assistantMessageId) implements SessionEvent {}

    record TextEnded(SessionId sessionId, MessageId assistantMessageId, String text) implements SessionEvent {}

    record ReasoningStarted(SessionId sessionId, MessageId assistantMessageId) implements SessionEvent {}

    record ReasoningEnded(SessionId sessionId, MessageId assistantMessageId, String text) implements SessionEvent {}

    record ToolInputStarted(SessionId sessionId, MessageId assistantMessageId, CallId callId,
                            ToolName toolName) implements SessionEvent {}

    record ToolInputEnded(SessionId sessionId, MessageId assistantMessageId, CallId callId,
                          String text) implements SessionEvent {}

    record ToolCalled(SessionId sessionId, MessageId assistantMessageId, CallId callId,
                      ToolName toolName, JsonValue input,
                      boolean executed) implements SessionEvent {}

    record ToolSucceeded(SessionId sessionId, MessageId assistantMessageId, CallId callId,
                         List<ToolContent> content, Map<String, Object> metadata,
                         boolean executed) implements SessionEvent {}

    record ToolFailed(SessionId sessionId, MessageId assistantMessageId, CallId callId,
                      SessionError error, Map<String, Object> metadata,
                      boolean executed) implements SessionEvent {}

    record ExecutionStarted(SessionId sessionId) implements SessionEvent {}

    record ExecutionSucceeded(SessionId sessionId) implements SessionEvent {}

    record ExecutionFailed(SessionId sessionId, SessionError error) implements SessionEvent {}

    record ExecutionInterrupted(SessionId sessionId, InterruptReason reason) implements SessionEvent {}

    record CompactionStarted(SessionId sessionId, CompactionReason reason,
                             Option<InboxId> inputId) implements SessionEvent {}

    record CompactionEnded(SessionId sessionId, CompactionReason reason, String summary,
                           ModelRef model, Money cost, TokenCount tokens) implements SessionEvent {}

    record CompactionFailed(SessionId sessionId, CompactionReason reason, SessionError error,
                            Option<InboxId> inputId) implements SessionEvent {}

    record RevertStaged(SessionId sessionId, MessageId messageId) implements SessionEvent {}

    record RevertCleared(SessionId sessionId) implements SessionEvent {}

    record RevertCommitted(SessionId sessionId, Option<MessageId> boundaryMessageId) implements SessionEvent {}

    record RetryScheduled(SessionId sessionId, MessageId assistantMessageId, int attempt,
                          Instant at, SessionError error) implements SessionEvent {}

    record UsageRecorded(SessionId sessionId, Money cost, TokenCount tokens) implements SessionEvent {}
}