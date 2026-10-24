package de.augmentia.oc.engine.runtime.context;

import de.augmentia.oc.engine.core.contract.provider.ModelContent;
import de.augmentia.oc.engine.core.contract.provider.ModelMessage;
import de.augmentia.oc.engine.core.contract.provider.ModelRequest;
import de.augmentia.oc.engine.core.contract.provider.ProviderAdapter;
import de.augmentia.oc.engine.core.eval.CacheAffinity;
import de.augmentia.oc.engine.core.eval.PromptLoader;
import de.augmentia.oc.engine.core.model.Content;
import de.augmentia.oc.engine.core.model.Message;
import de.augmentia.oc.engine.core.model.ModelRef;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.ProjectId;
import de.augmentia.oc.engine.core.model.SessionId;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;

/**
 * Generates a session title from the first message (TTL-MESSAGE) or the
 * conversation (TTL-CONVERSATION). The output is truncated to 100 characters.
 */
public final class TitleGenerator {

    public static final int MAX_TITLE_LENGTH = 100;
    public static final int MAX_CONTEXT_CHARS = 8_000;
    public static final int MAX_FIRST_MESSAGE_CHARS = 2_000;

    private final ProviderAdapter provider;
    private final ModelRef model;

    public TitleGenerator(ProviderAdapter provider, ModelRef model) {
        if (provider == null) throw new IllegalArgumentException("provider must not be null");
        if (model == null) throw new IllegalArgumentException("model must not be null");
        this.provider = provider;
        this.model = model;
    }

    /** Title from the first message (TTL-MESSAGE). */
    public String fromFirstMessage(String text, SessionId sessionId, ProjectId projectId) {
        if (text == null) text = "";
        String bounded = text.length() > MAX_FIRST_MESSAGE_CHARS
                ? text.substring(0, MAX_FIRST_MESSAGE_CHARS) : text;
        return generate(PromptLoader.get("ttl-message").replace("{text}", bounded),
                headers(sessionId, projectId));
    }

    /** Title from the full conversation (TTL-CONVERSATION). */
    public String fromConversation(List<Message> messages, SessionId sessionId, ProjectId projectId) {
        String text = messages.stream()
                .map(TitleGenerator::messageText)
                .collect(Collectors.joining("\n"));
        if (text.length() > MAX_CONTEXT_CHARS) text = text.substring(0, MAX_CONTEXT_CHARS);
        return generate(PromptLoader.get("ttl-conversation").replace("{text}", text),
                headers(sessionId, projectId));
    }

    private String generate(String prompt, Map<String, String> headers) {
        List<de.augmentia.oc.engine.core.contract.provider.ModelEvent> events = provider.stream(
                new ModelRequest(model, List.of(prompt), List.of(), List.of(), Option.none(),
                        null, Map.of(), headers, Option.none())).toList();
        StringBuilder deltas = new StringBuilder();
        String ended = null;
        for (de.augmentia.oc.engine.core.contract.provider.ModelEvent event : events) {
            if (event instanceof de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextDelta delta) {
                deltas.append(delta.text());
            } else if (event instanceof de.augmentia.oc.engine.core.contract.provider.ModelEvent.TextEnded textEnded) {
                ended = textEnded.text();
            }
        }
        // Deltas already contain the full text; TextEnded would duplicate it.
        String result = (deltas.length() > 0 ? deltas.toString() : ended == null ? "" : ended).trim();
        if (result.length() > MAX_TITLE_LENGTH) result = result.substring(0, MAX_TITLE_LENGTH);
        return result;
    }

    private static Map<String, String> headers(SessionId sessionId, ProjectId projectId) {
        if (sessionId == null) throw new IllegalArgumentException("sessionId must not be null");
        if (projectId == null) throw new IllegalArgumentException("projectId must not be null");
        String affinity = CacheAffinity.affinity(sessionId, Option.none(), Option.none());
        Map<String, String> headers = new LinkedHashMap<>(CacheAffinity.headers(affinity));
        headers.put("X-Project-Id", projectId.value());
        return Map.copyOf(headers);
    }

    private static String messageText(Message message) {
        return switch (message) {
            case Message.UserMessage user -> user.text();
            case Message.SyntheticMessage synthetic -> synthetic.text();
            case Message.SystemMessage system -> system.text();
            case Message.SkillMessage skill -> skill.text();
            case Message.AssistantMessage assistant -> assistant.content().stream()
                    .filter(c -> c instanceof Content.TextContent)
                    .map(c -> ((Content.TextContent) c).text())
                    .reduce("", String::concat);
            default -> "";
        };
    }
}
