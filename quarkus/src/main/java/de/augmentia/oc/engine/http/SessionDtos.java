package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionInfo;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON mapping helpers for session/message/process/task payloads (H7). */
final class SessionDtos {

    private static final java.util.logging.Logger LOG = java.util.logging.Logger.getLogger(SessionDtos.class.getName());

    private SessionDtos() {}

    static SessionDto session(SessionInfo session) {
        return new SessionDto(session.id().value(), session.title(), session.agent().value(),
                session.location().directory().toString(), session.cost().toString(),
                session.tokens().total());
    }

    static MessageDto message(de.augmentia.oc.engine.core.model.Message message) {
        List<ToolCardDto> tools = new ArrayList<>();
        if (message instanceof de.augmentia.oc.engine.core.model.Message.AssistantMessage assistant) {
            for (de.augmentia.oc.engine.core.model.Content content : assistant.content()) {
                if (content instanceof de.augmentia.oc.engine.core.model.Content.ToolCardContent tcc) {
                    de.augmentia.oc.engine.core.model.ToolCard card = tcc.card();
                    String input = "";
                    try {
                        Object v = de.augmentia.oc.engine.core.json.JsonValues.toJava(card.input());
                        input = v == null ? "" : String.valueOf(v);
                    } catch (Exception e) { LOG.fine("ignored: " + e.getMessage()); }
                    String resultText = "";
                    boolean failed = false;
                    de.augmentia.oc.engine.core.model.ToolState state = card.state();
                    if (state instanceof de.augmentia.oc.engine.core.model.ToolState.Completed completed) {
                        StringBuilder sb = new StringBuilder();
                        for (de.augmentia.oc.engine.core.model.ToolContent item : completed.content()) {
                            if (item instanceof de.augmentia.oc.engine.core.model.ToolContent.TextItem ti) {
                                if (sb.length() > 0) sb.append('\n');
                                sb.append(ti.text());
                            }
                        }
                        resultText = sb.toString();
                        if (resultText.length() > 800) resultText = resultText.substring(0, 800) + "…";
                    } else if (state instanceof de.augmentia.oc.engine.core.model.ToolState.Failed failedState) {
                        failed = true;
                        resultText = failedState.error() == null ? "fehlgeschlagen" : String.valueOf(failedState.error());
                        if (resultText.length() > 800) resultText = resultText.substring(0, 800) + "…";
                    } else {
                        resultText = "(kein Ergebnis)";
                    }
                    tools.add(new ToolCardDto(card.id().value(), card.name().value(), input, resultText, failed));
                }
            }
        }
        String time = "";
        try { time = message.time() == null ? "" : message.time().toString(); } catch (Exception e) { LOG.fine("ignored: " + e.getMessage()); }
        return new MessageDto(message.id().value(), message.getClass().getSimpleName(), text(message), tools, time);
    }

    static Map<String, Object> process(
            de.augmentia.oc.engine.runtime.tool.shell.ProcessRegistry.Snapshot process) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", process.id());
        dto.put("state", process.state().name().toLowerCase());
        dto.put("command", process.command());
        dto.put("stdout", process.stdout());
        dto.put("stderr", process.stderr());
        process.exit().ifPresent(exit -> dto.put("exit", exit));
        dto.put("started", process.started().toString());
        if (process.finished() != null) dto.put("finished", process.finished().toString());
        return dto;
    }

    static Map<String, Object> task(
            de.augmentia.oc.engine.runtime.tool.subagent.BackgroundTasks.Snapshot task) {
        Map<String, Object> dto = new LinkedHashMap<>();
        dto.put("id", task.id());
        dto.put("state", task.state().name().toLowerCase());
        dto.put("agent", task.agent());
        dto.put("text", task.text());
        dto.put("error", task.error());
        task.childSession().ifPresent(child -> dto.put("childSession", child.value()));
        dto.put("tokens", task.usage().total());
        dto.put("created", task.created().toString());
        if (task.finished() != null) dto.put("finished", task.finished().toString());
        return dto;
    }

    private static String text(de.augmentia.oc.engine.core.model.Message message) {
        return switch (message) {
            case de.augmentia.oc.engine.core.model.Message.UserMessage user -> user.text();
            case de.augmentia.oc.engine.core.model.Message.SyntheticMessage synthetic -> synthetic.text();
            case de.augmentia.oc.engine.core.model.Message.SystemMessage system -> system.text();
            case de.augmentia.oc.engine.core.model.Message.SkillMessage skill -> skill.text();
            case de.augmentia.oc.engine.core.model.Message.AssistantMessage assistant ->
                    assistant.content().stream()
                            .filter(item -> item instanceof de.augmentia.oc.engine.core.model.Content.TextContent)
                            .map(item -> ((de.augmentia.oc.engine.core.model.Content.TextContent) item).text())
                            .reduce("", String::concat);
            default -> "";
        };
    }
}
