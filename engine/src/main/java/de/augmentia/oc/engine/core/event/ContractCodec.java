package de.augmentia.oc.engine.core.event;

import de.augmentia.oc.engine.core.json.JsonValue;
import de.augmentia.oc.engine.core.model.Option;

import java.lang.reflect.Constructor;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.ParameterizedType;
import java.lang.reflect.RecordComponent;
import java.lang.reflect.Type;
import java.math.BigDecimal;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reflection-based JSON codec for the closed contract types. It intentionally
 * knows only the shapes that occur in the engine: records, sealed interfaces,
 * {@link Option}, collections, maps and the opaque value types.
 */
public final class ContractCodec {

    private ContractCodec() {}

    public static JsonValue encode(Object value) {
        return encode(value, value == null ? Object.class : value.getClass());
    }

    /**
     * Encodes a value with an explicit declared type. A sealed declared type
     * adds the {@code _type} discriminator that the decoder needs.
     */
    public static JsonValue encode(Object value, Type declaredType) {
        return encodeValue(value, declaredType);
    }

    public static <T> T decode(JsonValue value, Class<T> type) {
        return type.cast(decode(value, (Type) type));
    }

    public static Object decode(JsonValue value, Type type) {
        if (value == null) {
            return null;
        }
        if (type instanceof ParameterizedType parameterized) {
            return decodeParameterized(value, parameterized);
        }
        if (!(type instanceof Class<?> raw)) {
            return generic(value);
        }
        if (raw == JsonValue.class) {
            return value;
        }
        if (value instanceof JsonValue.Null) {
            return null;
        }
        if (raw == Object.class) {
            return generic(value);
        }
        if (raw == String.class) {
            return ((JsonValue.Str) value).value();
        }
        if (raw == boolean.class || raw == Boolean.class) {
            return ((JsonValue.Bool) value).value();
        }
        if (raw == int.class || raw == Integer.class) {
            return ((JsonValue.Num) value).value().intValueExact();
        }
        if (raw == long.class || raw == Long.class) {
            return ((JsonValue.Num) value).value().longValueExact();
        }
        if (raw == double.class || raw == Double.class) {
            return ((JsonValue.Num) value).value().doubleValue();
        }
        if (raw == BigDecimal.class) {
            return ((JsonValue.Num) value).value();
        }
        if (raw == byte[].class) {
            return Base64.getDecoder().decode(((JsonValue.Str) value).value());
        }
        if (raw == Instant.class) {
            return Instant.parse(((JsonValue.Str) value).value());
        }
        if (raw == Duration.class) {
            return Duration.parse(((JsonValue.Str) value).value());
        }
        if (raw == Path.class) {
            return Path.of(((JsonValue.Str) value).value());
        }
        if (raw.isEnum()) {
            return enumValue(raw, ((JsonValue.Str) value).value());
        }
        if (raw.isInterface() || java.lang.reflect.Modifier.isAbstract(raw.getModifiers())) {
            Class<?> subtype = sealedSubtype(raw, typeName(value));
            return decodeRecord(subtype, (JsonValue.Obj) value);
        }
        if (raw.isRecord()) {
            return decodeRecord(raw, (JsonValue.Obj) value);
        }
        return parseOpaque(raw, ((JsonValue.Str) value).value());
    }

    private static Object decodeParameterized(JsonValue value, ParameterizedType type) {
        Class<?> raw = (Class<?>) type.getRawType();
        if (raw == Option.class) {
            if (value instanceof JsonValue.Null) {
                return Option.none();
            }
            return Option.some(decode(value, type.getActualTypeArguments()[0]));
        }
        if (Collection.class.isAssignableFrom(raw)) {
            List<Object> out = new ArrayList<>();
            for (JsonValue item : ((JsonValue.Arr) value).values()) {
                out.add(decode(item, type.getActualTypeArguments()[0]));
            }
            return out;
        }
        if (Map.class.isAssignableFrom(raw)) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, JsonValue> entry : ((JsonValue.Obj) value).values().entrySet()) {
                out.put(entry.getKey(), decode(entry.getValue(), type.getActualTypeArguments()[1]));
            }
            return out;
        }
        return decode(value, raw);
    }

    private static JsonValue encodeValue(Object value, Type declaredType) {
        if (value == null) {
            return JsonValue.ofNull();
        }
        if (value instanceof Option.Some<?> some) {
            return encodeValue(some.value(), optionInner(declaredType));
        }
        if (value instanceof Option.None<?>) {
            return JsonValue.ofNull();
        }
        if (value instanceof JsonValue json) {
            return json;
        }
        if (value instanceof String s) {
            return JsonValue.of(s);
        }
        if (value instanceof Boolean b) {
            return JsonValue.of(b);
        }
        if (value instanceof Byte || value instanceof Short || value instanceof Integer || value instanceof Long) {
            return JsonValue.of(((Number) value).longValue());
        }
        if (value instanceof Float f) {
            return JsonValue.of(f.doubleValue());
        }
        if (value instanceof Double d) {
            return JsonValue.of(d);
        }
        if (value instanceof BigDecimal d) {
            return JsonValue.of(d);
        }
        if (value instanceof Enum<?> e) {
            return JsonValue.of(e.name());
        }
        if (value instanceof Instant i) {
            return JsonValue.of(i.toString());
        }
        if (value instanceof Duration d) {
            return JsonValue.of(d.toString());
        }
        if (value instanceof Path p) {
            return JsonValue.of(p.toString());
        }
        if (value instanceof byte[] bytes) {
            return JsonValue.of(Base64.getEncoder().encodeToString(bytes));
        }
        if (value instanceof Map<?, ?> map) {
            Type valueType = mapValueType(declaredType);
            Map<String, JsonValue> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                out.put(String.valueOf(entry.getKey()), encodeValue(entry.getValue(), valueType));
            }
            return JsonValue.of(out);
        }
        if (value instanceof Collection<?> collection) {
            Type elementType = collectionElementType(declaredType);
            List<JsonValue> out = new ArrayList<>();
            for (Object item : collection) {
                out.add(encodeValue(item, elementType));
            }
            return JsonValue.of(out);
        }
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<JsonValue> out = new ArrayList<>(length);
            for (int i = 0; i < length; i++) {
                out.add(encodeValue(java.lang.reflect.Array.get(value, i), Object.class));
            }
            return JsonValue.of(out);
        }
        if (value.getClass().isRecord()) {
            boolean includeType = declaredType instanceof Class<?> raw
                    && (raw.isInterface() || raw == Object.class)
                    && hasSealedSupertype(value.getClass());
            return encodeRecord(value, includeType);
        }
        String opaque = opaqueValue(value);
        if (opaque != null) {
            return JsonValue.of(opaque);
        }
        throw new IllegalArgumentException("cannot encode " + value.getClass().getName());
    }

    /**
     * Opaque value types (identifiers, tool names) are encoded as their
     * {@code value()} string form.
     */
    private static String opaqueValue(Object value) {
        try {
            java.lang.reflect.Method method = value.getClass().getMethod("value");
            if (method.getReturnType() == String.class) {
                return (String) method.invoke(value);
            }
        } catch (NoSuchMethodException ignored) {
            return null;
        } catch (IllegalAccessException | InvocationTargetException e) {
            return null;
        }
        return null;
    }

    private static JsonValue encodeRecord(Object value, boolean includeType) {
        Map<String, JsonValue> out = new LinkedHashMap<>();
        if (includeType) {
            out.put("_type", JsonValue.of(value.getClass().getSimpleName()));
        }
        for (RecordComponent component : value.getClass().getRecordComponents()) {
            try {
                java.lang.reflect.Method accessor = component.getAccessor();
                accessor.setAccessible(true);
                out.put(component.getName(), encodeValue(accessor.invoke(value), component.getGenericType()));
            } catch (IllegalAccessException | InvocationTargetException e) {
                throw new IllegalArgumentException("cannot read " + component.getName(), e);
            }
        }
        return JsonValue.of(out);
    }

    private static Object decodeRecord(Class<?> recordClass, JsonValue.Obj object) {
        RecordComponent[] components = recordClass.getRecordComponents();
        Class<?>[] parameterTypes = new Class<?>[components.length];
        Object[] arguments = new Object[components.length];
        for (int i = 0; i < components.length; i++) {
            RecordComponent component = components[i];
            parameterTypes[i] = component.getType();
            JsonValue field = object.values().get(component.getName());
            if (field == null) {
                if (component.getGenericType() instanceof ParameterizedType p && p.getRawType() == Option.class) {
                    arguments[i] = Option.none();
                    continue;
                }
                if (component.getType().isPrimitive()) {
                    throw new DecodeException("missing field: " + component.getName());
                }
                arguments[i] = null;
                continue;
            }
            arguments[i] = decode(field, component.getGenericType());
        }
        try {
            Constructor<?> constructor = recordClass.getDeclaredConstructor(parameterTypes);
            constructor.setAccessible(true);
            return constructor.newInstance(arguments);
        } catch (NoSuchMethodException | InstantiationException | IllegalAccessException | InvocationTargetException e) {
            throw new DecodeException("cannot construct " + recordClass.getName() + ": " + e.getMessage());
        }
    }

    private static Object generic(JsonValue value) {
        return switch (value) {
            case JsonValue.Null n -> null;
            case JsonValue.Bool b -> b.value();
            case JsonValue.Str s -> s.value();
            case JsonValue.Num n -> n.value().scale() <= 0
                    && n.value().abs().compareTo(BigDecimal.valueOf(Long.MAX_VALUE)) < 0
                    ? (Object) n.value().longValueExact()
                    : n.value();
            case JsonValue.Arr a -> {
                List<Object> out = new ArrayList<>();
                for (JsonValue item : a.values()) {
                    out.add(generic(item));
                }
                yield out;
            }
            case JsonValue.Obj o -> {
                Map<String, Object> out = new LinkedHashMap<>();
                for (Map.Entry<String, JsonValue> entry : o.values().entrySet()) {
                    out.put(entry.getKey(), generic(entry.getValue()));
                }
                yield out;
            }
        };
    }

    private static Type optionInner(Type declaredType) {
        if (declaredType instanceof ParameterizedType p && p.getRawType() == Option.class) {
            return p.getActualTypeArguments()[0];
        }
        return Object.class;
    }

    private static Type collectionElementType(Type declaredType) {
        if (declaredType instanceof ParameterizedType p && Collection.class.isAssignableFrom((Class<?>) p.getRawType())) {
            return p.getActualTypeArguments()[0];
        }
        return Object.class;
    }

    private static Type mapValueType(Type declaredType) {
        if (declaredType instanceof ParameterizedType p && Map.class.isAssignableFrom((Class<?>) p.getRawType())) {
            return p.getActualTypeArguments()[1];
        }
        return Object.class;
    }

    private static boolean hasSealedSupertype(Class<?> type) {
        for (Class<?> iface : type.getInterfaces()) {
            if (iface.isSealed()) {
                return true;
            }
            if (hasSealedSupertype(iface)) {
                return true;
            }
        }
        Class<?> superclass = type.getSuperclass();
        return superclass != null && superclass != Object.class && superclass.isSealed();
    }

    private static String typeName(JsonValue value) {
        if (value instanceof JsonValue.Obj object
                && object.values().get("_type") instanceof JsonValue.Str type) {
            return type.value();
        }
        throw new DecodeException("missing _type discriminator");
    }

    private static Class<?> sealedSubtype(Class<?> type, String name) {
        // Native images may not expose permitted subclasses without extra
        // reflection metadata. Try the conventional nested and top-level names
        // first; both are present in the registered reflection configuration.
        try {
            return Class.forName(type.getName() + "$" + name);
        } catch (ClassNotFoundException ignored) {
            // try top-level
        }
        try {
            return Class.forName(type.getPackageName() + "." + name);
        } catch (ClassNotFoundException ignored) {
            // fall back to permitted subclasses
        }
        for (Class<?> subtype : type.getPermittedSubclasses()) {
            if (subtype.getSimpleName().equals(name)
                    || subtype.getName().equals(name)
                    || subtype.getCanonicalName() != null && subtype.getCanonicalName().equals(name)) {
                return subtype;
            }
            if (subtype.isSealed()) {
                try {
                    return sealedSubtype(subtype, name);
                } catch (DecodeException ignored) {
                    // try the next permitted subtype
                }
            }
        }
        throw new DecodeException("unknown " + type.getSimpleName() + " subtype: " + name);
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private static Object enumValue(Class<?> type, String name) {
        return Enum.valueOf((Class<? extends Enum>) type, name);
    }

    private static Object parseOpaque(Class<?> type, String raw) {
        try {
            return type.getMethod("parse", String.class).invoke(null, raw);
        } catch (NoSuchMethodException ignored) {
            // fall through
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new DecodeException("cannot parse " + type.getSimpleName() + ": " + e.getMessage());
        }
        try {
            return type.getMethod("of", String.class).invoke(null, raw);
        } catch (NoSuchMethodException ignored) {
            throw new DecodeException("no parser for " + type.getName());
        } catch (IllegalAccessException | InvocationTargetException e) {
            throw new DecodeException("cannot parse " + type.getSimpleName() + ": " + e.getMessage());
        }
    }

    private static final class DecodeException extends RuntimeException {
        DecodeException(String message) {
            super(message);
        }
    }
}
