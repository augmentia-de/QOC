package de.augmentia.qoc.workflowrunner.runner;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON-Schema validator for node output contracts.
 *
 * <p>Supports the subset needed for node data contracts:
 * {@code type} (object/array/string/integer/number/boolean),
 * {@code required}, {@code additionalProperties}, {@code properties},
 * {@code enum}, {@code minimum}/{@code maximum} for numbers,
 * {@code items} for arrays. Anything else is accepted (permissive).</p>
 *
 * <p>Throws {@link ContractViolationException} on the first violation,
 * carrying the JSON path of the offending field.</p>
 */
public final class NodeContractValidator {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    private NodeContractValidator() {}

    public static void validate(Map<String, Object> contract, Object value) {
        if (contract == null || contract.isEmpty()) return; // no contract -> passthrough
        validateNode("$", contract, value);
    }

    @SuppressWarnings("unchecked")
    private static void validateNode(String path, Map<String, Object> schema, Object value) {
        Object typeObj = schema.get("type");
        if (typeObj != null) {
            String type = String.valueOf(typeObj);
            if (!matchesType(type, value)) {
                throw new ContractViolationException(path, "type_mismatch",
                        "expected " + type + ", got " + jsonType(value));
            }
        }

        // enum
        Object enumObj = schema.get("enum");
        if (enumObj instanceof List<?> allowed && value != null) {
            boolean ok = false;
            for (Object a : allowed) {
                if (ObjectsEqual(a, value)) { ok = true; break; }
            }
            if (!ok) {
                throw new ContractViolationException(path, "enum_violation",
                        "value not in enum: " + allowed);
            }
        }

        // number bounds
        if (value instanceof Number n) {
            Object min = schema.get("minimum");
            if (min instanceof Number m && n.doubleValue() < m.doubleValue()) {
                throw new ContractViolationException(path, "minimum_violation",
                        "value " + n + " below minimum " + m);
            }
            Object max = schema.get("maximum");
            if (max instanceof Number m && n.doubleValue() > m.doubleValue()) {
                throw new ContractViolationException(path, "maximum_violation",
                        "value " + n + " above maximum " + m);
            }
        }

        // object
        if (value instanceof Map<?, ?> m && "object".equals(String.valueOf(typeObj == null ? "object" : typeObj))) {
            // required
            Object req = schema.get("required");
            if (req instanceof List<?> required) {
                for (Object r : required) {
                    if (r != null && !m.containsKey(String.valueOf(r))) {
                        throw new ContractViolationException(path + "." + r, "required_missing",
                                "required field missing: " + r);
                    }
                }
            }
            // properties + additionalProperties
            Object props = schema.get("properties");
            Map<String, Object> properties = (props instanceof Map<?, ?> pm) ? (Map<String, Object>) pm : Map.of();
            Object addl = schema.get("additionalProperties");
            boolean allowAdditional = !(Boolean.FALSE.equals(addl));
            for (Object keyObj : m.keySet()) {
                String key = String.valueOf(keyObj);
                if (properties.containsKey(key)) {
                    validateNode(path + "." + key, castMap(properties.get(key)), m.get(keyObj));
                } else if (!allowAdditional) {
                    throw new ContractViolationException(path + "." + key, "additional_property",
                            "additional property not allowed: " + key);
                }
            }
        }

        // array
        if (value instanceof List<?> l) {
            Object items = schema.get("items");
            if (items instanceof Map<?, ?> im) {
                for (int i = 0; i < l.size(); i++) {
                    validateNode(path + "[" + i + "]", (Map<String, Object>) im, l.get(i));
                }
            }
        }
    }

    private static boolean matchesType(String type, Object value) {
        return switch (type) {
            case "object" -> value instanceof Map<?, ?>;
            case "array" -> value instanceof List<?>;
            case "string" -> value instanceof String;
            case "integer" -> value instanceof Integer || value instanceof Long || (value instanceof Number n && n.doubleValue() == Math.floor(n.doubleValue()));
            case "number" -> value instanceof Number;
            case "boolean" -> value instanceof Boolean;
            case "null" -> value == null;
            default -> true;
        };
    }

    private static String jsonType(Object value) {
        if (value == null) return "null";
        if (value instanceof Map<?, ?>) return "object";
        if (value instanceof List<?>) return "array";
        if (value instanceof String) return "string";
        if (value instanceof Boolean) return "boolean";
        if (value instanceof Number) return "number";
        return value.getClass().getSimpleName();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Object o) {
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new HashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (e.getKey() != null) out.put(String.valueOf(e.getKey()), e.getValue());
            }
            return out;
        }
        return Map.of();
    }

    private static boolean ObjectsEqual(Object a, Object b) {
        if (a == null && b == null) return true;
        if (a == null || b == null) return false;
        // numeric equality
        if (a instanceof Number na && b instanceof Number nb) {
            return na.doubleValue() == nb.doubleValue();
        }
        return a.equals(b) || String.valueOf(a).equals(String.valueOf(b));
    }
}