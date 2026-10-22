package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.contract.provider.FailureKind;
import de.augmentia.oc.engine.core.contract.provider.InvalidOutputClassification;
import de.augmentia.oc.engine.core.contract.provider.ModelEvent;
import de.augmentia.oc.engine.core.error.Defect;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.Option;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Publisher-state validation (provider/02 §2/§4). Protocol violations are
 * defects; content filter and unknown finish become provider failures.
 */
public final class StreamValidator {

    private final Set<String> startedBlocks = new HashSet<>();
    private final Set<String> endedBlocks = new HashSet<>();
    private final Set<CallId> inputStarted = new HashSet<>();
    private final Set<CallId> inputEnded = new HashSet<>();
    private final Map<CallId, String> names = new HashMap<>();
    private final Set<CallId> calledIds = new HashSet<>();
    private final Set<CallId> results = new HashSet<>();
    private boolean terminal;

    public List<ModelEvent> validate(List<ModelEvent> events) {
        if (events == null) throw new IllegalArgumentException("events must not be null");
        List<ModelEvent> out = new ArrayList<>();
        for (ModelEvent event : events) {
            if (terminal) {
                throw new Defect("event after terminal event: " + event.getClass().getSimpleName());
            }
            switch (event) {
                case ModelEvent.TextStarted started ->
                        startBlock("text:" + started.blockId());
                case ModelEvent.TextDelta delta ->
                        requireBlock("text:" + delta.blockId());
                case ModelEvent.TextEnded ended -> endBlock("text:" + ended.blockId());
                case ModelEvent.ReasoningStarted started ->
                        startBlock("reasoning:" + started.blockId());
                case ModelEvent.ReasoningDelta delta ->
                        requireBlock("reasoning:" + delta.blockId());
                case ModelEvent.ReasoningEnded ended -> endBlock("reasoning:" + ended.blockId());
                case ModelEvent.ToolInputStarted started -> {
                    if (!inputStarted.add(started.callId())) {
                        throw new Defect("tool input started twice: " + started.callId());
                    }
                    names.putIfAbsent(started.callId(), started.name().value());
                }
                case ModelEvent.ToolInputDelta delta -> requireInput(delta.callId());
                case ModelEvent.ToolInputEnded ended -> {
                    requireInput(ended.callId());
                    if (!inputEnded.add(ended.callId())) {
                        throw new Defect("tool input ended twice: " + ended.callId());
                    }
                }
                case ModelEvent.ToolCallRequested call -> {
                    String previous = names.putIfAbsent(call.callId(), call.name().value());
                    if (previous != null && !previous.equals(call.name().value())) {
                        throw new Defect("tool call name changed: " + call.callId());
                    }
                    if (!calledIds.add(call.callId())) {
                        throw new Defect("tool call requested twice: " + call.callId());
                    }
                    if (results.contains(call.callId())) {
                        throw new Defect("tool call after result: " + call.callId());
                    }
                }
                case ModelEvent.ToolResultProvided result -> {
                    requireCall(result.callId());
                    if (!results.add(result.callId())) {
                        throw new Defect("second result for tool call: " + result.callId());
                    }
                }
                case ModelEvent.ToolFailed failed -> {
                    requireCall(failed.callId());
                    if (!results.add(failed.callId())) {
                        throw new Defect("second result for tool call: " + failed.callId());
                    }
                }
                case ModelEvent.Finished finished -> {
                    terminal = true;
                    closeBlocks();
                    out.add(normalize(finished));
                    continue;
                }
                case ModelEvent.ProviderFailed failure -> {
                    terminal = true;
                    out.add(failure);
                    continue;
                }
                default -> {
                    // No other event types exist; the sealed hierarchy makes this exhaustive.
                }
            }
            out.add(event);
        }
        return List.copyOf(out);
    }

    /** Appends an incomplete-stream failure when the stream ended without a terminal event. */
    public List<ModelEvent> finish(List<ModelEvent> validated) {
        if (!terminal) {
            List<ModelEvent> out = new ArrayList<>(validated);
            out.add(new ModelEvent.ProviderFailed(
                    new FailureKind.InvalidOutput(InvalidOutputClassification.INCOMPLETE_STREAM),
                    "stream ended without a terminal event", Option.none(), Option.none()));
            terminal = true;
            return List.copyOf(out);
        }
        return List.copyOf(validated);
    }

    public boolean terminalSeen() {
        return terminal;
    }

    private ModelEvent normalize(ModelEvent.Finished finished) {
        if (finished.reason() == FinishReason.CONTENT_FILTER) {
            return new ModelEvent.ProviderFailed(new FailureKind.ContentPolicy(),
                    "content filter", Option.none(), Option.none());
        }
        if (finished.reason() == FinishReason.UNKNOWN) {
            return new ModelEvent.ProviderFailed(
                    new FailureKind.InvalidOutput(InvalidOutputClassification.INCOMPLETE_STREAM),
                    "unknown finish reason", Option.none(), Option.none());
        }
        return finished;
    }

    private void startBlock(String id) {
        if (!startedBlocks.add(id)) throw new Defect("block started twice: " + id);
    }

    private void requireBlock(String id) {
        if (!startedBlocks.contains(id)) throw new Defect("delta before block start: " + id);
        if (endedBlocks.contains(id)) throw new Defect("delta after block end: " + id);
    }

    private void endBlock(String id) {
        requireBlock(id);
        if (!endedBlocks.add(id)) throw new Defect("block ended twice: " + id);
    }

    private void closeBlocks() {
        for (String block : startedBlocks) {
            if (!endedBlocks.contains(block)) throw new Defect("block was not ended: " + block);
        }
    }

    private void requireInput(CallId callId) {
        if (!inputStarted.contains(callId)) throw new Defect("tool input delta before start: " + callId);
    }

    private void requireCall(CallId callId) {
        if (!calledIds.contains(callId)) throw new Defect("tool result before call: " + callId);
    }
}
