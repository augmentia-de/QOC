package de.augmentia.oc.quarkus;

import dev.langchain4j.service.SystemMessage;
import dev.langchain4j.service.UserMessage;
import io.quarkiverse.langchain4j.RegisterAiService;

@RegisterAiService
public interface Assistant {

    @SystemMessage("""
            Du bist ein präziser Assistent.
            Antworte knapp, sachlich und auf Deutsch.
            """)
    String chat(@UserMessage String message);
}
