package de.augmentia.qoc.workflowrunner.schema;

import java.lang.reflect.Array;
import java.lang.reflect.Field;
import java.lang.reflect.Modifier;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Converts a Java record or POJO class into a JSON Schema (as Map) that
 * {@code JsonSchema.parse} (engine) accepts and the LLM can consume via
 * {@code responseSchema}.
 */
public final class JavaRecordToJsonSchema {

    private JavaRecordToJsonSchema() {}

    public static Map<String, Object> fromClass(Class<?> clazz) {
        if (clazz == null) throw new IllegalArgumentException("clazz must not be null");
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();

        List<Member> members = membersOf(clazz);
        for (Member m : members) {
            String name = m.name();
            Type type = m.type();
            Map<String, Object> fieldSchema = schemaFor(type, new ArrayList<>());
            // description from @Schema(description=...) if present
            String desc = m.description();
            if (desc != null && !desc.isBlank()) fieldSchema.put("description", desc);
            props.put(name, fieldSchema);
            if (m.required()) required.add(name);
        }
        schema.put("properties", props);
        if (!required.isEmpty()) schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private record Member(String name, Type type, String description, boolean required) {}

    private static List<Member> membersOf(Class<?> clazz) {
        List<Member> out = new ArrayList<>();
        if (clazz.isRecord()) {
            for (RecordComponent rc : clazz.getRecordComponents()) {
                String name = rc.getName();
                // @JsonProperty override
                JsonProperty jp = rc.getAnnotation(JsonProperty.class);
                if (jp != null && !jp.value().isBlank()) name = jp.value();
                out.add(new Member(name, rc.getGenericType(), descriptionOf(rc.getAnnotations()), !isOptional(rc.getGenericType())));
            }
        } else {
            for (Field f : clazz.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || Modifier.isTransient(f.getModifiers())) continue;
                String name = f.getName();
                JsonProperty jp = f.getAnnotation(JsonProperty.class);
                if (jp != null && !jp.value().isBlank()) name = jp.value();
                out.add(new Member(name, f.getGenericType(), descriptionOf(f.getAnnotations()), !isOptional(f.getGenericType())));
            }
        }
        return out;
    }

    private static boolean isOptional(Type t) {
        if (t instanceof ParameterizedType pt && pt.getRawType() instanceof Class<?> c) {
            return c == Optional.class;
        }
        return false;
    }

    private static String descriptionOf(java.lang.annotation.Annotation[] anns) {
        for (java.lang.annotation.Annotation a : anns) {
            if (a.annotationType().getSimpleName().equals("Schema")) {
                try {
                    Object v = a.annotationType().getMethod("description").invoke(a);
                    if (v instanceof String s && !s.isBlank()) return s;
                } catch (Exception ignored) {}
            }
        }
        return null;
    }

    private static Map<String, Object> schemaFor(Type type, List<Type> seen) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (type instanceof Class<?> c) {
            if (c == String.class || c == Character.class || c == char.class) {
                s.put("type", "string");
            } else if (c == int.class || c == Integer.class || c == long.class || c == Long.class
                    || c == short.class || c == Short.class || c == byte.class || c == Byte.class
                    || c == java.math.BigInteger.class) {
                s.put("type", "integer");
            } else if (c == float.class || c == Float.class || c == double.class || c == Double.class
                    || c == java.math.BigDecimal.class) {
                s.put("type", "number");
            } else if (c == boolean.class || c == Boolean.class) {
                s.put("type", "boolean");
            } else if (c.isEnum()) {
                s.put("type", "string");
                List<String> vals = new ArrayList<>();
                for (Object o : c.getEnumConstants()) vals.add(o.toString());
                s.put("enum", vals);
            } else if (c == java.time.Instant.class || c == java.time.OffsetDateTime.class
                    || c == java.time.ZonedDateTime.class) {
                s.put("type", "string"); s.put("format", "date-time");
            } else if (c == java.time.LocalDate.class || c == java.sql.Date.class) {
                s.put("type", "string"); s.put("format", "date");
            } else if (c == java.time.LocalDateTime.class) {
                s.put("type", "string"); s.put("format", "date-time");
            } else if (c == java.util.Date.class) {
                s.put("type", "string"); s.put("format", "date-time");
            } else if (c == java.util.UUID.class) {
                s.put("type", "string"); s.put("format", "uuid");
            } else if (c.isArray()) {
                s.put("type", "array");
                s.put("items", schemaFor(c.getComponentType(), seen));
            } else if (List.class.isAssignableFrom(c) || java.util.Set.class.isAssignableFrom(c) || java.util.Collection.class.isAssignableFrom(c)) {
                // generic item type unknown here; handled by ParameterizedType branch below
                s.put("type", "array");
                s.put("items", Map.of("type", "string")); // fallback
            } else if (Map.class.isAssignableFrom(c)) {
                s.put("type", "object");
                s.put("additionalProperties", true);
            } else {
                // nested object
                s.putAll(fromClass(c));
            }
        } else if (type instanceof ParameterizedType pt) {
            Type raw = pt.getRawType();
            if (raw instanceof Class<?> c && (List.class.isAssignableFrom(c) || java.util.Set.class.isAssignableFrom(c) || java.util.Collection.class.isAssignableFrom(c))) {
                s.put("type", "array");
                Type[] args = pt.getActualTypeArguments();
                if (args != null && args.length == 1) {
                    s.put("items", schemaFor(args[0], seen));
                } else {
                    s.put("items", Map.of("type", "string"));
                }
            } else if (raw instanceof Class<?> c && Map.class.isAssignableFrom(c)) {
                s.put("type", "object");
                Type[] args = pt.getActualTypeArguments();
                if (args != null && args.length == 2) {
                    s.put("additionalProperties", schemaFor(args[1], seen));
                } else {
                    s.put("additionalProperties", true);
                }
            } else if (raw instanceof Class<?> c && c == Optional.class) {
                Type[] args = pt.getActualTypeArguments();
                if (args != null && args.length == 1) {
                    Map<String, Object> inner = schemaFor(args[0], seen);
                    // make nullable via type array not needed; JsonSchema supports anyOf null, but simpler: keep inner and mark not required by caller
                    s.putAll(inner);
                } else {
                    s.put("type", "string");
                }
            } else {
                // fallback: treat as object
                if (raw instanceof Class<?> c) s.putAll(fromClass(c));
                else s.put("type", "object");
            }
        } else {
            s.put("type", "string");
        }
        return s;
    }

}
