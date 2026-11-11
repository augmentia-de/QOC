package de.augmentia.oc.engine.adapter.security;

import de.augmentia.oc.engine.core.json.Json;
import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.Principal;
import de.augmentia.oc.engine.core.port.world.ToolTokenService;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.List;
import java.util.Map;

/**
 * HMAC-SHA256 based implementation of {@link ToolTokenService}.
 * Issues compact JWT-like tokens (header.payload.signature) carrying origin claims.
 */
public final class HmacToolTokenService implements ToolTokenService {

    private final byte[] key;

    public HmacToolTokenService(String signingKey) {
        if (signingKey == null || signingKey.isBlank()) {
            throw new IllegalArgumentException("signingKey must not be blank");
        }
        this.key = signingKey.getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public String issue(Principal principal, String toolName, String sessionId,
                        List<String> scopes, long ttlSeconds) {
        if (principal == null) principal = Principal.anonymous();
        long now = Instant.now().getEpochSecond();
        long exp = now + Math.max(1, ttlSeconds);

        JsonValue.Obj header = new JsonValue.Obj(Map.of(
                "alg", new JsonValue.Str("HS256"),
                "typ", new JsonValue.Str("QOC-Tool")));
        JsonValue.Obj payload = new JsonValue.Obj(new java.util.LinkedHashMap<>(Map.of(
                "principalId", new JsonValue.Str(principal.id()),
                "roles", new JsonValue.Arr(principal.roles().stream().map(JsonValue.Str::new).map(v -> (JsonValue) v).toList()),
                "sessionId", new JsonValue.Str(sessionId == null ? "" : sessionId),
                "tool", new JsonValue.Str(toolName == null ? "" : toolName),
                "scopes", new JsonValue.Arr((scopes == null ? List.<String>of() : scopes).stream().map(JsonValue.Str::new).map(v -> (JsonValue) v).toList()),
                "iat", new JsonValue.Num(new java.math.BigDecimal(now)),
                "exp", new JsonValue.Num(new java.math.BigDecimal(exp)))));
        // add origin marker
        ((java.util.Map<String, JsonValue>) payload.values()).put("origin", new JsonValue.Str("qoc-tool-token"));

        String h = b64url(Json.encode(header));
        String p = b64url(Json.encode(payload));
        String sig = sign(h + "." + p);
        return h + "." + p + "." + sig;
    }

    @Override
    public Principal validate(String token) {
        if (token == null || token.isBlank()) return null;
        String[] parts = token.split("\\.");
        if (parts.length != 3) return null;
        String expected = sign(parts[0] + "." + parts[1]);
        if (!constantTimeEquals(expected, parts[2])) return null;
        try {
            String json = new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8);
            JsonValue parsed = Json.parse(json).valueOrNull();
            if (!(parsed instanceof JsonValue.Obj obj)) return null;
            long exp = num(obj, "exp");
            if (Instant.now().getEpochSecond() > exp) return null;
            String id = str(obj, "principalId");
            if (id == null || id.isBlank()) id = "anonymous";
            List<String> roles = list(obj, "roles");
            return new Principal(id, id, roles, List.of(), Map.of(), null);
        } catch (RuntimeException e) {
            return null;
        }
    }

    private String sign(String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return b64url(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) {
            throw new de.augmentia.oc.engine.core.error.StoreFailure("signing failed", e);
        }
    }

    private static String b64url(String s) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    private static String b64url(byte[] b) {
        return Base64.getUrlEncoder().withoutPadding().encodeToString(b);
    }

    private static String str(JsonValue.Obj o, String k) {
        return o.values().get(k) instanceof JsonValue.Str s ? s.value() : null;
    }

    private static long num(JsonValue.Obj o, String k) {
        if (o.values().get(k) instanceof JsonValue.Num n) return n.value().longValue();
        return 0L;
    }

    private static List<String> list(JsonValue.Obj o, String k) {
        if (o.values().get(k) instanceof JsonValue.Arr a) {
            return a.values().stream().filter(JsonValue.Str.class::isInstance)
                    .map(v -> ((JsonValue.Str) v).value()).toList();
        }
        return List.of();
    }

    private static boolean constantTimeEquals(String a, String b) {
        if (a == null || b == null || a.length() != b.length()) return false;
        int r = 0;
        for (int i = 0; i < a.length(); i++) r |= a.charAt(i) ^ b.charAt(i);
        return r == 0;
    }
}
