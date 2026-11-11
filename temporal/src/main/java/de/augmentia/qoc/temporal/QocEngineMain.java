package de.augmentia.qoc.temporal;

import de.augmentia.oc.engine.adapter.StorageFactory;
import de.augmentia.oc.engine.adapter.memory.MemoryBudget;
import de.augmentia.oc.engine.adapter.memory.RuntimeState;
import de.augmentia.oc.engine.adapter.net.JdkHttpTransport;
import de.augmentia.oc.engine.adapter.time.SystemClock;
import de.augmentia.oc.engine.core.wiring.StorageConfig;
import de.augmentia.oc.engine.core.wiring.StorageKind;
import de.augmentia.oc.engine.core.wiring.Stores;
import de.augmentia.oc.engine.runtime.QocEngine;
import de.augmentia.oc.engine.runtime.provider.client.HttpProviderAdapter;
import de.augmentia.oc.engine.runtime.provider.client.OpenAiEventTranslator;
import de.augmentia.oc.engine.runtime.provider.client.OpenAiRequestEncoder;
import de.augmentia.oc.engine.runtime.provider.client.ProviderErrorClassifier;
import de.augmentia.oc.engine.runtime.provider.client.SseReader;

import java.net.URI;
import java.nio.file.Path;
import java.time.Duration;

/**
 * Simple main class that uses QocEngine to run a task with the "build" agent.
 *
 * <p>Usage: {@code java -cp ... de.augmentia.qoc.temporal.QocEngineMain "your prompt"}</p>
 *
 * <p>If OPENAI_API_KEY is set (via .env or environment), an HttpProviderAdapter
 * is wired and the prompt is executed against the LLM.</p>
 */
public final class QocEngineMain {

    public static void main(String[] args) {
        String prompt = args.length > 0 ? String.join(" ", args) : "Hello, build agent!";

        System.out.println("=== QOC Engine Main ===");
        System.out.println("Prompt: " + prompt);
        System.out.println();

        // Create QOC Engine (in-memory)
        StorageConfig config = new StorageConfig(StorageKind.MEMORY);
        RuntimeState memory = new RuntimeState(new MemoryBudget(1L << 30));
        Stores stores = StorageFactory.create(config, memory, null,
                new SystemClock(), new de.augmentia.oc.engine.adapter.fs.NioFileSystem(Path.of(".")));

        // Wire provider if OPENAI_API_KEY is set
        de.augmentia.oc.engine.core.contract.provider.ProviderAdapter provider = null;
        String apiKey = System.getenv("OPENAI_API_KEY");
        String baseUrl = System.getenv("OPENAI_BASE_URL");
        String model = System.getenv("OPENAI_MODEL");
        if (apiKey != null && !apiKey.isBlank() && !"demo-key".equals(apiKey)) {
            String url = (baseUrl != null && !baseUrl.isBlank() ? baseUrl : "set url via OPENAI_BASE_URL")
                    + "/chat/completions";
            String modelName = (model != null && !model.isBlank()) ? model : "no model set via OPENAI_MODEL";
            System.out.println("Wiring provider: " + url + " model=" + modelName);
            provider = new HttpProviderAdapter(
                    new JdkHttpTransport(),
                    URI.create(url),
                    java.util.Map.of("authorization", "Bearer " + apiKey,
                            "User-Agent", "qoc-engine/1.0"),
                    new OpenAiRequestEncoder(modelName),
                    new SseReader(),
                    OpenAiEventTranslator::new,
                    new ProviderErrorClassifier(),
                    Duration.ofMinutes(2));
        } else {
            System.out.println("No OPENAI_API_KEY set — running without LLM provider");
        }

        QocEngine engine = QocEngine.create(stores, config, new SystemClock(), false, provider, de.augmentia.oc.engine.runtime.QocEngine.EngineConfig.defaults(), de.augmentia.oc.engine.runtime.callback.EventPublisher.noop(), de.augmentia.oc.engine.runtime.callback.AgentResolver.noop(), de.augmentia.oc.engine.runtime.callback.TracerProvider.noop(), de.augmentia.oc.engine.adapter.ToolFactory.create(java.nio.file.Path.of(".")));

        // Run the prompt with the "build" agent
        System.out.println("Running prompt with 'build' agent...");
        de.augmentia.oc.engine.core.model.EngineResult result = engine.runPrompt("build", prompt);
        String resultText = result.text();
        System.out.println("Result: " + resultText);
        System.out.println();

        // Show session info
        System.out.println("Sessions: " + engine.listSessions("temporal", true).size());
        System.out.println("Storage: " + engine.storageConfig().defaultKind());
        System.out.println("Provider wired: " + (engine.provider() != null));
        System.out.println("Done.");
    }
}