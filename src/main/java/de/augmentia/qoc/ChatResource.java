package de.augmentia.qoc;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

@Path("/api/chat")
@Consumes(MediaType.APPLICATION_JSON)
@Produces(MediaType.APPLICATION_JSON)
public class ChatResource {

    private final Assistant assistant;

    public ChatResource(Assistant assistant) {
        this.assistant = assistant;
    }

    @POST
    public ChatResponse chat(ChatRequest request) {
        return new ChatResponse(assistant.chat(request.message()));
    }

    public record ChatRequest(String message) {
    }

    public record ChatResponse(String answer) {
    }
}
