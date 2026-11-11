package de.augmentia.qoc.workflowrunner.runner;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Normalizes workflow values to the types produced by the engine's JSON codec.
 *
 * <p>Structured node outputs come from Jackson and can contain {@link Integer}
 * or {@link Float}. The journal decoder materializes integral numbers as
 * {@link Long} and floating point numbers as {@link Double}. Normalizing before
 * storing keeps in-memory values and replayed values equal, so conditions and
 * templates behave the same after a restart.</p>
 */
public final class WorkflowValues {

    private WorkflowValues() {}

    public static Object canonical(Object value) {
        if (value == null) return null;
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return ((Number) value).longValue();
        }
        if (value instanceof Float || value instanceof Double) {
            return ((Number) value).doubleValue();
        }
        if (value instanceof BigDecimal decimal) {
            if (decimal.scale() > 0) return decimal;
            try {
                return decimal.longValueExact();
            } catch (ArithmeticException outsideLongRange) {
                return decimal;
            }
        }
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), canonical(entry.getValue()));
            }
            return out;
        }
        if (value instanceof Collection<?> collection) {
            List<Object> out = new ArrayList<>(collection.size());
            for (Object item : collection) out.add(canonical(item));
            return out;
        }
        return value;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> canonicalMap(Map<String, Object> map) {
        if (map == null) return Map.of();
        return (Map<String, Object>) canonical(map);
    }
}
