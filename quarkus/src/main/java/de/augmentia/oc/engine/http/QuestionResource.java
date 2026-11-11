package de.augmentia.oc.engine.http;

import de.augmentia.oc.engine.core.model.SessionId;
import de.augmentia.oc.engine.core.port.human.QuestionSource;

import jakarta.inject.Inject;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Question endpoints extracted from {@link HttpApi} (H7). */
@Path("/api")
@Produces(MediaType.APPLICATION_JSON)
public class QuestionResource {

    @Inject
    Engine engine;

    @Inject
    DurableQuestionSource webQuestionSource;

    @Inject
    SessionAccessGuard accessGuard;

    @GET
    @Path("/session/{id}/question")
    public Response pendingQuestion(@PathParam("id") String id,
                                    @QueryParam("includeChildren") @DefaultValue("false") boolean includeChildren) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            if (includeChildren) {
                var pending = engine.pendingQuestionsForChildren(SessionId.parse(id));
                if (pending.isEmpty()) {
                    return Response.ok(Map.of("id", "", "sessionId", id, "questions", List.of())).build();
                }
                var question = pending.get(0);
                return Response.ok(Map.of("id", question.id(), "sessionId",
                        question.session() == null ? id : question.session().value(),
                        "questions", question.questions())).build();
            }
            var pending = engine.pendingQuestion(SessionId.parse(id));
            if (pending.isEmpty()) {
                return Response.ok(Map.of("id", "", "sessionId", id, "questions", List.of())).build();
            }
            var question = pending.orElseThrow();
            return Response.ok(Map.of("id", question.id(), "sessionId", id, "questions", question.questions())).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @POST
    @Path("/session/{id}/question/{questionId}/reply")
    public Response replyQuestion(@PathParam("id") String id,
                                  @PathParam("questionId") String questionId,
                                  QuestionReply request) {
        Response denied = accessGuard.requireAccess(id);
        if (denied != null) return denied;
        try {
            boolean resolved = engine.replyQuestion(SessionId.parse(id), questionId,
                    request == null ? List.of() : request.selected(),
                    request == null ? "" : request.text());
            if (!resolved) {
                return Response.status(404).entity(Map.of("message", "question not found")).build();
            }
            return Response.ok(Map.of("ok", true)).build();
        } catch (IllegalArgumentException e) {
            return badRequest(e.getMessage());
        }
    }

    @GET
    @Path("/questions")
    public Response listQuestions() {
        if (webQuestionSource == null) return Response.ok(Map.of("data", List.of())).build();
        String current = accessGuard.currentUser();
        boolean admin = accessGuard.isAdmin();
        List<Map<String, Object>> data = new ArrayList<>();
        for (WebQuestionSource.Pending p : webQuestionSource.listPending()) {
            if (p.session() != null && !admin && !engine.isOwner(engine.session(p.session()).orElse(null), current)) {
                continue;
            }
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("id", p.id());
            q.put("sessionId", p.session() == null ? null : p.session().value());
            q.put("sessionTitle", p.sessionTitle());
            q.put("createdAt", p.createdAt().toString());
            List<Map<String, Object>> qs = new ArrayList<>();
            for (QuestionSource.Question question : p.questions()) {
                Map<String, Object> qd = new LinkedHashMap<>();
                qd.put("header", question.header());
                qd.put("question", question.question());
                qd.put("multiple", question.multiple());
                List<Map<String, String>> opts = new ArrayList<>();
                for (QuestionSource.Option o : question.options()) {
                    opts.add(Map.of("label", o.label(), "description", o.description()));
                }
                qd.put("options", opts);
                qs.add(qd);
            }
            q.put("questions", qs);
            data.add(q);
        }
        return Response.ok(Map.of("data", data)).build();
    }

    @GET
    @Path("/question/{token}")
    @Produces("text/html")
    public Response questionByEmail(@PathParam("token") String token) {
        if (webQuestionSource == null || token == null || token.isBlank()) {
            return Response.status(Response.Status.NOT_FOUND).entity("Question not found").build();
        }
        String qid = webQuestionSource.findQuestionByToken(token);
        if (qid == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Question not found or expired").build();
        }
        WebQuestionSource.Pending pending = null;
        for (WebQuestionSource.Pending pq : webQuestionSource.listPending()) {
            if (qid.equals(pq.id())) { pending = pq; break; }
        }
        if (pending == null) {
            return Response.status(Response.Status.NOT_FOUND).entity("Question not found").build();
        }
        StringBuilder html = new StringBuilder();
        html.append("<!DOCTYPE html><html><head><meta charset='utf-8'><title>QOC Question</title>");
        html.append("<style>body{font-family:sans-serif;max-width:600px;margin:40px auto;padding:20px}");
        html.append("button{padding:8px 16px;background:#3b82f6;color:#fff;border:none;border-radius:4px;cursor:pointer;margin-top:10px}");
        html.append("textarea{width:100%;min-height:80px;margin-top:8px}</style></head><body>");
        html.append("<h2>QOC Agent Question</h2>");
        for (QuestionSource.Question q : pending.questions()) {
            html.append("<p><strong>").append(escapeHtml(q.question())).append("</strong></p>");
            if (q.options() != null && !q.options().isEmpty()) {
                for (QuestionSource.Option o : q.options()) {
                    html.append("<label style='display:block;margin:4px 0'><input type='radio' name='sel' value='")
                            .append(escapeHtml(o.label())).append("'> ").append(escapeHtml(o.label()))
                            .append(" - ").append(escapeHtml(o.description())).append("</label>");
                }
            }
            html.append("<textarea id='text' placeholder='Antwort (optional)...'></textarea>");
        }
        html.append("<br><button onclick='submit()'>Antworten</button>");
        html.append("<script>async function submit(){const sel=document.querySelector('input[name=sel]:checked');const text=document.getElementById('text').value;");
        html.append("const body={selected:sel?[sel.value]:[],text};");
        html.append("const r=await fetch('/api/question/" + token + "/reply',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(body)});");
        html.append("if(r.ok){document.body.innerHTML='<h2>Antwort gesendet!</h2><p>Du kannst dieses Fenster schliessen.</p>';}else{alert('Fehler: '+r.status);}}");
        html.append("</script></body></html>");
        return Response.ok(html.toString()).build();
    }

    @POST
    @Path("/question/{token}/reply")
    public Response replyQuestionByEmail(@PathParam("token") String token, Map<String, Object> body) {
        if (webQuestionSource == null) return badRequest("no question source");
        List<String> selected = new ArrayList<>();
        if (body != null && body.get("selected") instanceof List<?> list) {
            for (Object o : list) selected.add(String.valueOf(o));
        }
        String text = body == null ? "" : String.valueOf(body.getOrDefault("text", ""));
        boolean ok = webQuestionSource.resolveByToken(token, selected, text);
        if (!ok) return Response.status(Response.Status.NOT_FOUND).entity(Map.of("message", "question not found")).build();
        return Response.ok(Map.of("ok", true)).build();
    }

    private static Response badRequest(String message) {
        return Response.status(Response.Status.BAD_REQUEST)
                .entity(Map.of("_tag", "BadRequest", "message", message == null ? "bad request" : message))
                .build();
    }

    private static String escapeHtml(String s) {
        if (s == null) return "";
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;").replace("\"", "&quot;");
    }
}
