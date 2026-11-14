package de.augmentia.oc.quarkus;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface Assistant {

    @SystemMessage("""
            You are a precise assistant.
            Answer concisely and factually, in English.
            """)
    String chat(@UserMessage String message);
}
