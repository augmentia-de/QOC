package de.augmentia.oc.engine.runtime.provider.client;

import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.DecisionRequest;
import de.augmentia.oc.engine.core.model.DecisionResult;
import de.augmentia.oc.engine.core.port.runtime.DecisionModel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * HTTP adapter for the TypeSafe {@code /v1/systemone} decision API (and Flide,
 * which serves the same API). Implements {@link DecisionModel}; fail-open: on
 * any error a low-confidence result (noul=true) is returned so callers fall
 * back to their default behaviour. Uses the engine's own {@link Json} (no
 * Jackson) to keep the core free of external JSON libraries.
 */
public final class TypeSafeDecisionAdapter implements DecisionModel {

    private static final Logger LOG = LoggerFactory.getLogger(TypeSafeDecisionAdapter.class);
    private static final int MAX_INPUT_CHARS = 2000;

    private final String baseUrl;
    private final String modelName;
    private final String apiKey;
    private final int timeoutSeconds;
    private final int maxRetries;

    public TypeSafeDecisionAdapter(String baseUrl, String modelName, String apiKey,
                                  int timeoutSeconds, int maxRetries) {
        this.baseUrl = baseUrl == null ? "https://api.typesafe.ai" : baseUrl;
        this.modelName = modelName == null || modelName.isBlank() ? "jev-latest" : modelName;
        this.apiKey = apiKey;
        this.timeoutSeconds = Math.max(1, timeoutSeconds);
        this.maxRetries = Math.max(0, maxRetries);
    }

    @Override
    public boolean isAvailable() {
        return baseUrl != null && !baseUrl.isBlank();
    }

    @Override
    public DecisionResult decide(DecisionRequest request) {
        long start = System.currentTimeMillis();
        try {
            String body = buildBody(request);
            String responseBody = post(body);
            return parse(request.taskType(), responseBody, start);
        } catch (RuntimeException e) {
            LOG.warn("TypeSafe decision failed ({}): {}", request.taskType(), e.getMessage());
            return new DecisionResult(request.taskType(), "", List.of(), List.of(), 0.0, true,
                    System.currentTimeMillis() - start);
        }
    }

    private String buildBody(DecisionRequest req) {
        Map<String, JsonValue> body = new LinkedHashMap<>();
        body.put("model", JsonValue.of(modelName));
        String state = req.context() == null || req.context().isBlank()
                ? (req.question() == null ? "" : req.question())
                : req.context();
        if (state.length() > MAX_INPUT_CHARS) {
            LOG.warn("Decision input truncated from {} to {} chars", state.length(), MAX_INPUT_CHARS);
            state = state.substring(0, MAX_INPUT_CHARS);
        }
        body.put("state", JsonValue.of(state));

        Map<String, JsonValue> questions = new LinkedHashMap<>();
        List<String> qs = req.questions();
        if (qs == null || qs.isEmpty()) {
            qs = List.of(req.question() == null ? "" : req.question());
        }
        int i = 0;
        for (String q : qs) {
            String name = "q" + i++;
            Map<String, JsonValue> tq = new LinkedHashMap<>();
            String type = resolveType(req.taskType(), req.options());
            switch (type) {
                case "choice" -> {
                    tq.put("type", JsonValue.of("choice"));
                    tq.put("instructions", JsonValue.of(q));
                    Map<String, JsonValue> options = new LinkedHashMap<>();
                    Object opts = req.options().get("options");
                    if (opts instanceof Map<?, ?> om) {
                        for (Map.Entry<?, ?> e : om.entrySet()) {
                            if (e.getKey() == null) continue;
                            options.put(String.valueOf(e.getKey()),
                                    JsonValue.of(e.getValue() == null ? "" : String.valueOf(e.getValue())));
                        }
                    }
                    tq.put("criteria", JsonValue.of(options));
                }
                case "scale" -> {
                    tq.put("type", JsonValue.of("score"));
                    tq.put("instructions", JsonValue.of(q));
                    List<JsonValue> levels = new ArrayList<>();
                    Object ls = req.options().get("levels");
                    if (ls instanceof List<?> ll) {
                        for (Object o : ll) if (o != null) levels.add(JsonValue.of(String.valueOf(o)));
                    }
                    if (levels.isEmpty()) levels.add(JsonValue.of("low"));
                    if (levels.size() == 1) levels.add(JsonValue.of("high"));
                    tq.put("criteria", JsonValue.of(levels));
                }
                default -> { // yesno
                    tq.put("type", JsonValue.of("noul"));
                    tq.put("instructions", JsonValue.of(q));
                }
            }
            questions.put(name, JsonValue.of(tq));
        }
        body.put("questions", JsonValue.of(questions));

        return Json.encode(JsonValue.of(body));
    }

    private String resolveType(String taskType, Map<String, Object> options) {
        if (options != null && options.get("type") instanceof String t) return t.toLowerCase(java.util.Locale.ROOT);
        if (taskType == null) return "yesno";
        String t = taskType.toLowerCase(java.util.Locale.ROOT);
        if (t.contains("choice") || t.contains("classify")) return "choice";
        if (t.contains("scale") || t.contains("score")) return "scale";
        return "yesno";
    }

    private String post(String body) {
        return SystemOneHttpClient.post(baseUrl, "v1/systemone", apiKey, Map.of(),
                body, timeoutSeconds, maxRetries);
    }

    private DecisionResult parse(String taskType, String responseBody, long start) {
        var parsed = Json.parse(responseBody);
        if (parsed instanceof de.augmentia.oc.engine.core.json.Result.Err<?> e) {
            throw new IllegalStateException("parse decision response: " + e.error());
        }
        JsonValue root = ((de.augmentia.oc.engine.core.json.Result.Ok<JsonValue>) parsed).value();
        if (!(root instanceof JsonValue.Obj obj)) {
            throw new IllegalStateException("decision response is not an object");
        }
        Map<String, JsonValue> answersRaw = new LinkedHashMap<>();
        JsonValue answersVal = obj.values().get("answers");
        if (answersVal instanceof JsonValue.Obj ao) {
            answersRaw.putAll(ao.values());
        }

        List<String> answerList = new ArrayList<>();
        List<Double> probList = new ArrayList<>();
        double margin = 0.0;
        boolean noul = false;
        for (Map.Entry<String, JsonValue> qe : answersRaw.entrySet()) {
            if (!(qe.getValue() instanceof JsonValue.Obj am)) continue;
            JsonValue value = am.values().get("value");
            if (value == null) value = am.values().get("choice");
            if (value == null) value = am.values().get("mean");
            if (value == null) value = am.values().get("score");
            if (value == null) {
                JsonValue nu = am.values().get("noul");
                if (nu instanceof JsonValue.Num n) {
                    value = JsonValue.of(n.value().doubleValue() >= 0.5 ? "yes" : "no");
                }
            }
            String strVal = (value instanceof JsonValue.Str s) ? s.value()
                    : (value instanceof JsonValue.Num n) ? n.value().toPlainString()
                    : (value == null ? "" : value.toString());
            answerList.add(strVal);

            double maxP = 0.0;
            JsonValue probs = am.values().get("probabilities");
            if (probs instanceof JsonValue.Obj po) {
                for (JsonValue v : po.values().values()) {
                    if (v instanceof JsonValue.Num n && n.value().doubleValue() > maxP) maxP = n.value().doubleValue();
                }
            } else if (probs instanceof JsonValue.Arr pa) {
                for (JsonValue v : pa.values()) {
                    if (v instanceof JsonValue.Num n && n.value().doubleValue() > maxP) maxP = n.value().doubleValue();
                }
            }
            JsonValue prob = am.values().get("probability");
            if (prob instanceof JsonValue.Num n && n.value().doubleValue() > maxP) maxP = n.value().doubleValue();
            probList.add(maxP);

            JsonValue m = am.values().get("margin");
            if (m instanceof JsonValue.Num n) margin = n.value().doubleValue();

            JsonValue nu = am.values().get("noul");
            if (nu instanceof JsonValue.Num n) noul = n.value().doubleValue() >= 0.5;
        }
        String answer = answerList.isEmpty() ? "" : answerList.get(0);
        long latency = System.currentTimeMillis() - start;
        return new DecisionResult(taskType, answer, answerList, probList, margin, noul, latency);
    }
}
