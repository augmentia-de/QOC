package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.adapter.net.JdkHttpTransport;
import de.augmentia.oc.engine.core.contract.provider.*;
import de.augmentia.oc.engine.core.event.ContractCodec;
import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.FinishReason;
import de.augmentia.oc.engine.core.model.TokenCount;
import de.augmentia.oc.engine.runtime.provider.client.HttpProviderAdapter;
import de.augmentia.oc.engine.runtime.provider.client.JsonEventTranslator;
import de.augmentia.oc.engine.runtime.provider.client.ProviderErrorClassifier;
import de.augmentia.oc.engine.runtime.provider.client.SseReader;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import java.net.URI;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

/** Produces either the scripted demo provider or the HTTP/SSE adapter. */
@ApplicationScoped
public class ProviderProducer {

    @Produces
    @ApplicationScoped
    ProviderAdapter provider(
            @ConfigProperty(name = "engine.provider.scripted", defaultValue = "true") boolean scripted,
            @ConfigProperty(name = "engine.provider.url", defaultValue = "https://api.openai.com/v1/chat/completions") String url,
            @ConfigProperty(name = "engine.provider.api-key", defaultValue = "demo-key") String apiKey) {
        if (scripted) return scripted();
        return new HttpProviderAdapter(new JdkHttpTransport(), URI.create(url),
                Map.of("authorization", "Bearer " + apiKey),
                request -> Json.encode(ContractCodec.encode(request)),
                new SseReader(), new JsonEventTranslator(), new ProviderErrorClassifier(),
                Duration.ofMinutes(2));
    }

    private static ProviderAdapter scripted() {
        return request -> {
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
