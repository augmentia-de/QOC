package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.adapter.logging.RotatingLlmTrace;
import de.augmentia.oc.engine.adapter.net.JdkHttpTransport;
import de.augmentia.oc.engine.core.contract.provider.*;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.CallId;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.core.model.ToolName;
import de.augmentia.oc.engine.runtime.provider.client.HttpProviderAdapter;
import de.augmentia.oc.engine.runtime.provider.client.OpenAiEventTranslator;
import de.augmentia.oc.engine.runtime.provider.client.OpenAiRequestEncoder;
import de.augmentia.oc.engine.runtime.provider.client.ProviderErrorClassifier;
import de.augmentia.oc.engine.runtime.provider.client.SseReader;
import de.augmentia.oc.engine.runtime.tool.structured.SubmitResult;
import de.augmentia.oc.engine.core.contract.provider.LlmTrace;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.logging.Logger;

/** Produces either the scripted demo provider or the HTTP/SSE adapter. */
@ApplicationScoped
public class ProviderProducer {

    private static final Logger LOG = Logger.getLogger(ProviderProducer.class.getName());

    @Produces
    @ApplicationScoped
    ProviderAdapter provider(
            @ConfigProperty(name = "engine.provider.type", defaultValue = "http") String providerType,
            @ConfigProperty(name = "engine.provider.scripted", defaultValue = "true") boolean scripted,
            @ConfigProperty(name = "engine.provider.api-key", defaultValue = "demo-key") String apiKey,
            @ConfigProperty(name = "engine.llm-log.enabled", defaultValue = "true") boolean llmLogEnabled,
            @ConfigProperty(name = "engine.llm-log.path", defaultValue = "./log/llm.log") String llmLogPath,
            @ConfigProperty(name = "engine.llm-log.limit-bytes", defaultValue = "2097152") long llmLogLimit,
            @ConfigProperty(name = "engine.llm-log.count", defaultValue = "20") int llmLogCount,
            @ConfigProperty(name = "engine.llm-trace.first-request-max", defaultValue = "4000") int llmTraceFirstRequestMax,
            @ConfigProperty(name = "engine.llm-trace.subsequent-request-max", defaultValue = "800") int llmTraceSubsequentRequestMax,
            @ConfigProperty(name = "engine.llm-trace.response-max", defaultValue = "800") int llmTraceResponseMax,
            EngineConfig config,
            dev.langchain4j.model.chat.ChatModel chatModel,
            dev.langchain4j.model.chat.StreamingChatModel streamingChatModel) {
        if (scripted) {
            LOG.info("LLM provider: scripted mock (ENGINE_PROVIDER_SCRIPTED=true)");
            return scripted();
        }
        if ("langchain4j".equalsIgnoreCase(providerType)) {
            if (chatModel == null && streamingChatModel == null) {
                LOG.warning("LangChain4j requested but no ChatModel bean available; falling back to HTTP");
            } else {
                LOG.info("LLM provider: LangChain4j");
                return new LangChain4jProviderAdapter(streamingChatModel, chatModel);
            }
        }
        if (apiKey == null || apiKey.isBlank() || "demo-key".equals(apiKey)) {
            LOG.warning("LLM provider: real mode enabled but OPENAI_API_KEY is missing "
                    + "or still the demo key; LLM calls will fail.");
        } else {
            LOG.info("LLM provider: " + config.url() + " model=" + config.model());
        }
        LlmTrace trace = openTrace(llmLogEnabled, Path.of(llmLogPath), llmLogLimit, llmLogCount);
        return new HttpProviderAdapter(new JdkHttpTransport(), URI.create(config.url()),
                Map.of("authorization", "Bearer " + apiKey,
                        "User-Agent", "opencode-engine/1.0"),
                new OpenAiRequestEncoder(config.model()),
                new SseReader(), OpenAiEventTranslator::new, new ProviderErrorClassifier(),
                Duration.ofMinutes(2), trace,
                llmTraceFirstRequestMax, llmTraceSubsequentRequestMax, llmTraceResponseMax);
    }

    private static LlmTrace openTrace(boolean enabled, Path path, long limit, int count) {
        if (!enabled) return LlmTrace.noop();
        try {
            RotatingLlmTrace trace = RotatingLlmTrace.open(path, limit, count);
            LOG.info("LLM request/response trace: " + path.toAbsolutePath().normalize()
                    + " (limit=" + limit + " bytes, files=" + count + ")");
            return trace;
        } catch (RuntimeException e) {
            LOG.warning("LLM trace disabled, cannot open " + path + ": " + e.getMessage());
            return LlmTrace.noop();
        }
    }

    private static ProviderAdapter scripted() {
        return request -> {
            boolean structured = request.tools().stream()
                    .anyMatch(tool -> SubmitResult.TOOL_NAME.equals(tool.effectiveName()));
            if (structured) {
                // The demo provider answers the reserved result tool with a
                // minimal object; real models produce schema-specific arguments.
                List<ModelEvent> events = List.of(
                        new ModelEvent.ToolCallRequested(CallId.create(),
                                new ToolName(SubmitResult.TOOL_NAME),
                                JsonValue.of(Map.of("answer", JsonValue.of("structured")))),
                        new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.of(1, 8),
                                JsonValue.ofNull()));
                return events.stream();
            }
            String text = "Hallo aus der Session Engine.";
            List<ModelEvent> events = List.of(
                    new ModelEvent.TextStarted("block-1"),
                    new ModelEvent.TextDelta("block-1", text),
                    new ModelEvent.TextEnded("block-1", text),
                    new ModelEvent.Finished(FinishReason.NORMAL, TokenCount.of(1, 8), JsonValue.ofNull()));
            return events.stream();
        };
    }
}
