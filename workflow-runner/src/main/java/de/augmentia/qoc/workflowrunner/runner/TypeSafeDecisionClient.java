package de.augmentia.qoc.workflowrunner.runner;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Pure-Java HTTP client for the TypeSafe System One decision API
 * ({@code POST <baseUrl>/v1/systemone}).
 *
 * <p>No LangChain4j dependency: the request/response shape is replicated from
 * the public TypeSafe protocol so that the workflow runner stays free of the
 * experimental LangChain4j decision API and its version drift.</p>
 *
 * <p>All LangChain4j-specific types are deliberately avoided; the public entry
 * point {@link #classify(Map, Object)} returns the same normalized result map as
 * the previous implementation so callers (and tests) are unaffected.</p>
 */
public final class TypeSafeDecisionClient {

    private static final ObjectMapper M = new ObjectMapper();

    private TypeSafeDecisionClient() {
    }

    /**
     * Runs a decision-model classification and returns a normalized result map:
     * <pre>
     * {
     *   "modelName": "...",
     *   "answers": { "&lt;q&gt;": { "type":..., "value":..., "probability":..., "probabilities":{...}, "margin":... } },
     *   "lowMargin": boolean,
     *   "tokenUsage": { "input": n, "output": n }
     * }
     * </pre>
     */
    public static Map<String, Object> classify(Map<String, Object> config, Object input) {
        Map<String, Object> c = config == null ? Map.of() : config;

        // ---- build request body ----
        Map<String, Object> body = new LinkedHashMap<>();
        String modelName = str(c.get("modelName"), System.getenv().getOrDefault("TYPESAFE_MODEL_NAME", "jev-latest"));
        body.put("model", modelName);
        body.put("state", toState(input));

        Map<String, Object> questions = new LinkedHashMap<>();
        Object qs = c.get("questions");
        if (qs instanceof List<?> list) {
            for (Object qo : list) {
                if (!(qo instanceof Map<?, ?> qm)) continue;
                Map<String, Object> q = cast(qm);
                String name = str(q.get("name"), null);
                if (name == null || name.isBlank()) continue;
                String type = str(q.get("type"), "yesno").toLowerCase(java.util.Locale.ROOT);
                String text = str(q.get("text"), name);
                Map<String, Object> tq = new LinkedHashMap<>();
                switch (type) {
                    case "choice" -> {
                        tq.put("type", "choice");
                        tq.put("instructions", text);
                        Map<String, Object> options = new LinkedHashMap<>();
                        Object opts = q.get("options");
                        if (opts instanceof Map<?, ?> om) {
                            for (Map.Entry<?, ?> e : om.entrySet()) {
                                if (e.getKey() == null) continue;
                                options.put(String.valueOf(e.getKey()),
                                        e.getValue() == null ? "" : String.valueOf(e.getValue()));
                            }
                        }
                        tq.put("criteria", options);
                    }
                    case "scale" -> {
                        tq.put("type", "score");
                        tq.put("instructions", text);
                        List<Object> levels = new ArrayList<>();
                        List<String> lvlList = new ArrayList<>();
                        Object ls = q.get("levels");
                        if (ls instanceof List<?> ll) {
                            for (Object o : ll) if (o != null) lvlList.add(String.valueOf(o));
                        }
                        if (lvlList.isEmpty()) lvlList.add("low");
                        if (lvlList.size() == 1) lvlList.add("high");
                        List<String> descList = new ArrayList<>();
                        Object descs = q.get("levelDescriptions");
                        if (descs instanceof List<?> dl) {
                            for (Object o : dl) if (o != null) descList.add(String.valueOf(o));
                        }
                        for (int i = 0; i < lvlList.size(); i++) {
                            String lvl = lvlList.get(i);
                            String dsc = (i < descList.size()) ? descList.get(i) : null;
                            if (dsc != null && !dsc.isBlank()) {
                                Map<String, Object> m = new LinkedHashMap<>();
                                m.put("label", lvl);
                                m.put("description", dsc);
                                levels.add(m);
                            } else {
                                levels.add(lvl);
                            }
                        }
                        tq.put("criteria", levels);
                    }
                    default -> { // yesno
                        tq.put("type", "noul");
                        tq.put("instructions", text);
                        String yw = str(q.get("yesWhen"), null);
                        String nw = str(q.get("noWhen"), null);
                        if (yw != null || nw != null) {
                            Map<String, Object> crit = new LinkedHashMap<>();
                            crit.put("true", yw == null ? "" : yw);
                            crit.put("false", nw == null ? "" : nw);
                            tq.put("criteria", crit);
                        }
                    }
                }
                questions.put(name, tq);
            }
        }
        body.put("questions", questions);

        String requestBody;
        try {
            requestBody = M.writeValueAsString(body);
        } catch (Exception e) {
            throw new RuntimeException("serialize request", e);
        }

        // ---- HTTP ----
        String baseUrl = str(c.get("baseUrl"), "https://api.typesafe.ai");
        while (baseUrl.endsWith("/")) baseUrl = baseUrl.substring(0, baseUrl.length() - 1);
        String apiPath = str(c.get("apiPath"), "v1/systemone");
        while (apiPath.startsWith("/")) apiPath = apiPath.substring(1);
        String url = baseUrl + "/" + apiPath;

        String apiKey = str(c.get("apiKey"), System.getenv("TYPESAFE_API_KEY"));

        int timeout = intVal(c.get("timeoutSeconds"), 60);
        int maxRetries = intVal(c.get("maxRetries"), 2);

        String responseBody = null;
        Exception lastErr = null;
        for (int attempt = 0; attempt <= maxRetries; attempt++) {
            try {
                HttpClient client = HttpClient.newBuilder()
                        .connectTimeout(Duration.ofSeconds(Math.max(1, timeout))).build();
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url))
                        .timeout(Duration.ofSeconds(Math.max(1, timeout)))
                        .header("Content-Type", "application/json");
                if (apiKey != null && !apiKey.isBlank()) {
                    rb.header("Authorization", "Bearer " + apiKey);
                }
                Object ch = c.get("customHeaders");
                if (ch instanceof Map<?, ?> m) {
                    for (Map.Entry<?, ?> e : m.entrySet()) {
                        if (e.getKey() == null) continue;
                        rb.header(String.valueOf(e.getKey()),
                                e.getValue() == null ? "" : String.valueOf(e.getValue()));
                    }
                }
                HttpRequest req = rb.POST(HttpRequest.BodyPublishers.ofString(requestBody)).build();
                HttpResponse<String> resp = client.send(req, HttpResponse.BodyHandlers.ofString());
                if (resp.statusCode() >= 200 && resp.statusCode() < 300) {
                    responseBody = resp.body();
                    break;
                } else {
                    lastErr = new RuntimeException("HTTP " + resp.statusCode() + ": " + resp.body());
                }
            } catch (Exception e) {
                lastErr = e;
            }
            if (attempt < maxRetries) {
                try {
                    Thread.sleep(200L * (attempt + 1));
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                }
            }
        }
        if (responseBody == null) {
            throw new RuntimeException("TypeSafe request failed: " +
                    (lastErr == null ? "unknown" : lastErr.getMessage()), lastErr);
        }

        // ---- parse response ----
        Map<String, Object> parsed;
        try {
            parsed = M.readValue(responseBody, Map.class);
        } catch (Exception e) {
            throw new RuntimeException("parse response", e);
        }

        String respModel = str(parsed.get("model"), modelName);
        Map<String, Object> answersRaw = (parsed.get("answers") instanceof Map)
                ? cast(parsed.get("answers")) : Map.of();

        // question type lookup
        Map<String, String> qType = new LinkedHashMap<>();
        if (qs instanceof List<?> list) {
            for (Object qo : list) {
                if (!(qo instanceof Map<?, ?> qm)) continue;
                Map<String, Object> q = cast(qm);
                String name = str(q.get("name"), null);
                if (name != null) qType.put(name, str(q.get("type"), "yesno").toLowerCase(java.util.Locale.ROOT));
            }
        }

        double marginThreshold = doubleVal(c.get("thresholds"), "margin", 0.2);
        Map<String, Double> perQ = new LinkedHashMap<>();
        Object thr = c.get("thresholds");
        if (thr instanceof Map<?, ?> tm) {
            for (Map.Entry<?, ?> e : tm.entrySet()) {
                if (e.getKey() == null || "margin".equals(String.valueOf(e.getKey()))) continue;
                if (e.getValue() instanceof Number n) perQ.put(String.valueOf(e.getKey()), n.doubleValue());
            }
        }

        Map<String, Object> answers = new LinkedHashMap<>();
        boolean lowMargin = false;
        for (Map.Entry<String, Object> qe : questions.entrySet()) {
            String qname = qe.getKey();
            String type = qType.getOrDefault(qname, "yesno");
            Object a = answersRaw.get(qname);
            Map<String, Object> amap = new LinkedHashMap<>();
            if (!(a instanceof Map<?, ?> am)) {
                answers.put(qname, amap);
                continue;
            }
            Map<String, Object> ans = cast(am);
            switch (type) {
                case "choice" -> {
                    String choice = str(ans.get("choice"), null);
                    amap.put("type", "choice");
                    amap.put("value", choice);
                    Map<String, Object> probMap = new LinkedHashMap<>();
                    Object probs = ans.get("probabilities");
                    if (probs instanceof Map<?, ?> pm) {
                        for (Map.Entry<?, ?> e : pm.entrySet()) {
                            if (e.getKey() == null) continue;
                            probMap.put(String.valueOf(e.getKey()), toDouble(e.getValue()));
                        }
                    }
                    amap.put("probabilities", probMap);
                    double max = 0, second = 0;
                    for (Object v : probMap.values()) {
                        double d = (v instanceof Number n) ? n.doubleValue() : 0;
                        if (d > max) { second = max; max = d; }
                        else if (d > second) second = d;
                    }
                    double margin = max - second;
                    amap.put("margin", margin);
                    if (margin < marginThreshold) lowMargin = true;
                }
                case "scale" -> {
                    double mean = toDouble(ans.get("score"));
                    amap.put("type", "scale");
                    amap.put("mean", mean);
                    List<Object> probList = new ArrayList<>();
                    Object probs = ans.get("probabilities");
                    if (probs instanceof List<?> pl) {
                        for (Object o : pl) probList.add(toDouble(o));
                    }
                    amap.put("probabilities", probList);
                }
                default -> { // yesno
                    double noul = toDouble(ans.get("noul"));
                    amap.put("type", "yesno");
                    amap.put("value", noul >= 0.5 ? "yes" : "no");
                    amap.put("probability", noul);
                    Double t = perQ.get(qname);
                    if (t != null && noul > (1.0 - t) && noul < t) lowMargin = true;
                }
            }
            answers.put(qname, amap);
        }

        Map<String, Object> usage = new LinkedHashMap<>();
        Object usageObj = parsed.get("usage");
        if (usageObj instanceof Map<?, ?> um) {
            Map<String, Object> u = cast(um);
            Object in = u.get("inputTokens");
            Object out = u.get("outputTokens");
            if (in != null) usage.put("input", toInt(in));
            if (out != null) usage.put("output", toInt(out));
        }

        Map<String, Object> result = new LinkedHashMap<>();
        result.put("modelName", respModel);
        result.put("answers", answers);
        result.put("lowMargin", lowMargin);
        result.put("tokenUsage", usage);
        return result;
    }

    /**
     * Applies outputMapping: writes each question's chosen value into the given
     * state map under the configured (or default = question name) key.
     */
    @SuppressWarnings("unchecked")
    public static void applyOutputMapping(Map<String, Object> config,
                                         Map<String, Object> execResult,
                                         Map<String, Object> state) {
        Map<String, Object> c = config == null ? Map.of() : config;
        Map<String, Object> mapping = new LinkedHashMap<>();
        Object om = c.get("outputMapping");
        if (om instanceof Map<?, ?> m) {
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) mapping.put(String.valueOf(e.getKey()), e.getValue());
            }
        }
        Object answersObj = execResult.get("answers");
        if (!(answersObj instanceof Map<?, ?> answers)) return;
        for (Map.Entry<?, ?> qe : answers.entrySet()) {
            String qname = String.valueOf(qe.getKey());
            if (!(qe.getValue() instanceof Map<?, ?> am)) continue;
            Map<String, Object> a = (Map<String, Object>) am;
            Object value = a.get("value");
            if (value == null && a.containsKey("mean")) value = a.get("mean");
            String key = mapping.containsKey(qname) ? String.valueOf(mapping.get(qname)) : qname;
            if (key != null && !key.isBlank()) state.put(key, value);
        }
    }

    // ---- helpers ----

    private static Object toState(Object input) {
        if (input instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        if (input instanceof List<?> l) {
            StringBuilder sb = new StringBuilder();
            for (Object o : l) {
                if (sb.length() > 0) sb.append("\n\n");
                sb.append(o == null ? "" : String.valueOf(o));
            }
            return sb.toString();
        }
        return input == null ? "" : String.valueOf(input);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return new LinkedHashMap<>();
    }

    private static String str(Object o, String def) {
        if (o == null) return def;
        String s = String.valueOf(o);
        return s.isBlank() ? def : s;
    }

    private static int intVal(Object o, int def) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try { return Integer.parseInt(String.valueOf(o)); } catch (NumberFormatException ignored) {}
        }
        return def;
    }

    private static double doubleVal(Object container, String key, double def) {
        if (container instanceof Map<?, ?> m) {
            Object v = m.get(key);
            if (v instanceof Number n) return n.doubleValue();
            if (v != null) {
                try { return Double.parseDouble(String.valueOf(v)); } catch (NumberFormatException ignored) {}
            }
        }
        return def;
    }

    private static double toDouble(Object o) {
        if (o instanceof Number n) return n.doubleValue();
        if (o != null) {
            try { return Double.parseDouble(String.valueOf(o)); } catch (NumberFormatException ignored) {}
        }
        return 0.0;
    }

    private static int toInt(Object o) {
        if (o instanceof Number n) return n.intValue();
        if (o != null) {
            try { return Integer.parseInt(String.valueOf(o)); } catch (NumberFormatException ignored) {}
        }
        return 0;
    }
}
