package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.json.JsonValues;
import de.augmentia.oc.engine.core.json.Schema;
import de.augmentia.oc.engine.core.model.Option;
import de.augmentia.oc.engine.core.model.SessionId;

import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.LinkedHashMap;
import java.util.Map;

/** Maps engine turn results to HTTP responses (H7). */
final class PromptResponses {

    private PromptResponses() {}

    static Response run(Engine engine, String id, String text, Option<Schema> responseSchema) {
        try {
            return promptResponse(engine.prompt(SessionId.parse(id), text, responseSchema));
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        } catch (IllegalStateException e) {
            return Response.status(422)
                    .entity(Map.of("_tag", "UnknownError", "message", e.getMessage())).build();
        }
    }

    static Response promptResponse(Engine.PromptResult result) {
        Object structured = result.structured().map(JsonValues::toJava).orElse(null);
        if (result.outcome() instanceof Engine.TurnOutcome.Busy busy) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("_tag", "SessionBusyError",
                            "outcome", "BUSY",
                            "message", busy.message() == null ? "session is running" : busy.message()))
                    .build();
        }
        if (result.outcome() instanceof Engine.TurnOutcome.Refused refused) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("_tag", "TurnRefusedError");
            body.put("outcome", "REFUSED");
            body.put("reason", refused.reason());
            body.put("message", refused.detail());
            return Response.status(422).entity(body).build();
        }
        if (result.outcome() instanceof Engine.TurnOutcome.Failed failed) {
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("_tag", "TurnFailedError");
            body.put("outcome", "FAILED");
            body.put("retryable", failed.retryable());
            body.put("error", failed.error().type());
            body.put("message", failed.error().message());
            return Response.status(failed.retryable() ? 503 : 502).entity(body).build();
        }
        if (result.outcome() instanceof Engine.TurnOutcome.Terminated terminated) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("_tag", "TurnTerminatedError",
                            "outcome", "TERMINATED",
                            "reason", terminated.reason().name()))
                    .build();
        }
        if (result.outcome() instanceof Engine.TurnOutcome.Cancelled) {
            return Response.status(Response.Status.CONFLICT)
                    .entity(Map.of("_tag", "TurnCancelledError",
                            "outcome", "CANCELLED",
                            "message", "turn was cancelled"))
                    .build();
        }
        return Response.ok(new PromptResponse(outcomeName(result.outcome()),
                result.finish(), result.inputTokens(), result.outputTokens(),
                result.text(), structured)).build();
    }

    private static String outcomeName(Engine.TurnOutcome outcome) {
        return switch (outcome) {
            case Engine.TurnOutcome.Completed ignored -> "COMPLETED";
            case Engine.TurnOutcome.Busy ignored -> "BUSY";
            case Engine.TurnOutcome.Refused ignored -> "REFUSED";
            case Engine.TurnOutcome.Failed ignored -> "FAILED";
            case Engine.TurnOutcome.Terminated ignored -> "TERMINATED";
            case Engine.TurnOutcome.Cancelled ignored -> "CANCELLED";
            case Engine.TurnOutcome.Paused ignored -> "PAUSED";
        };
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .type(MediaType.APPLICATION_JSON)
                .entity(Map.of("_tag", "InvalidRequestError",
                        "message", message == null ? "invalid request" : message))
                .build();
    }
}
