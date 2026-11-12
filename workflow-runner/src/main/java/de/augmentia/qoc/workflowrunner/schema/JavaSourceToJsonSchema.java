package de.augmentia.qoc.workflowrunner.schema;

import com.github.javaparser.JavaParser;
import com.github.javaparser.ast.CompilationUnit;
import com.github.javaparser.ast.Modifier;
import com.github.javaparser.ast.Node;
import com.github.javaparser.ast.body.ClassOrInterfaceDeclaration;
import com.github.javaparser.ast.body.EnumConstantDeclaration;
import com.github.javaparser.ast.body.EnumDeclaration;
import com.github.javaparser.ast.body.FieldDeclaration;
import com.github.javaparser.ast.body.Parameter;
import com.github.javaparser.ast.body.RecordDeclaration;
import com.github.javaparser.ast.body.TypeDeclaration;
import com.github.javaparser.ast.body.VariableDeclarator;
import com.github.javaparser.ast.expr.AnnotationExpr;
import com.github.javaparser.ast.expr.MemberValuePair;
import com.github.javaparser.ast.expr.NormalAnnotationExpr;
import com.github.javaparser.ast.expr.SingleMemberAnnotationExpr;
import com.github.javaparser.ast.type.Type;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.HashSet;

/**
 * Converts Java source code (Records, POJOs/DTOs, Enums) into a JSON Schema
 * (as {@code Map}) without executing or compiling the code. Uses JavaParser
 * to extract type declarations and their members from the AST.
 *
 * <p>This is the file-based counterpart to {@link JavaRecordToJsonSchema},
 * which operates on compiled classes via reflection. The type-mapping logic
 * mirrors {@code JavaRecordToJsonSchema} as closely as possible.</p>
 */
public final class JavaSourceToJsonSchema {

    private JavaSourceToJsonSchema() {}

    public static Map<String, Object> fromSource(String source) {
        if (source == null || source.isBlank()) {
            throw new IllegalArgumentException("source must not be blank");
        }
        JavaParser parser = new JavaParser();
        CompilationUnit cu = parser.parse(source).getResult()
                .orElseThrow(() -> new IllegalArgumentException("Ungültiger Java-Quellcode"));

        Map<String, TypeDef> defs = collectTypeDefs(cu);
        if (defs.isEmpty()) {
            throw new IllegalArgumentException("Keine Klasse, Record oder Enum gefunden");
        }
        TypeDef first = null;
        for (TypeDef def : defs.values()) {
            if (def.topLevel) {
                first = def;
                break;
            }
        }
        if (first == null) first = defs.values().iterator().next();
        return convertTypeDef(first, defs, new HashSet<>());
    }

    public static String extractClassName(String source) {
        if (source == null || source.isBlank()) return null;
        JavaParser parser = new JavaParser();
        CompilationUnit cu = parser.parse(source).getResult().orElse(null);
        if (cu == null) return null;
        Map<String, TypeDef> defs = collectTypeDefs(cu);
        for (TypeDef def : defs.values()) {
            if (def.topLevel) return def.name;
        }
        return defs.isEmpty() ? null : defs.values().iterator().next().name;
    }

    // ------------------------------------------------------------------ //
    //  Internal model
    // ------------------------------------------------------------------ //

    private static final class TypeDef {
        final String name;
        final List<Member> members;
        final boolean isEnum;
        final List<String> enumConstants;
        final boolean topLevel;

        TypeDef(String name, List<Member> members, boolean isEnum, List<String> enumConstants, boolean topLevel) {
            this.name = name;
            this.members = members;
            this.isEnum = isEnum;
            this.enumConstants = enumConstants;
            this.topLevel = topLevel;
        }
    }

    private static final class Member {
        final String name;
        final Type type;
        final String description;
        final boolean required;

        Member(String name, Type type, String description, boolean required) {
            this.name = name;
            this.type = type;
            this.description = description;
            this.required = required;
        }
    }

    // ------------------------------------------------------------------ //
    //  Collection
    // ------------------------------------------------------------------ //

    private static Map<String, TypeDef> collectTypeDefs(CompilationUnit cu) {
        Map<String, TypeDef> defs = new LinkedHashMap<>();
        for (TypeDeclaration<?> td : cu.getTypes()) {
            collectTypeDef(td, defs, true);
        }
        return defs;
    }

    private static void collectTypeDef(TypeDeclaration<?> td, Map<String, TypeDef> defs, boolean topLevel) {
        if (td instanceof RecordDeclaration rd) {
            List<Member> members = new ArrayList<>();
            for (Parameter p : rd.getParameters()) {
                String name = p.getNameAsString();
                String jp = jsonPropertyName(p.getAnnotations());
                if (jp != null && !jp.isBlank()) name = jp;
                members.add(new Member(name, p.getType(), descriptionOf(p.getAnnotations()), !isOptional(p.getType())));
            }
            defs.put(rd.getNameAsString(), new TypeDef(rd.getNameAsString(), members, false, List.of(), topLevel));
        } else if (td instanceof EnumDeclaration ed) {
            List<String> constants = new ArrayList<>();
            for (EnumConstantDeclaration ec : ed.getEntries()) {
                constants.add(ec.getNameAsString());
            }
            defs.put(ed.getNameAsString(), new TypeDef(ed.getNameAsString(), List.of(), true, constants, topLevel));
        } else if (td instanceof ClassOrInterfaceDeclaration cd && !cd.isInterface()) {
            List<Member> members = new ArrayList<>();
            for (FieldDeclaration fd : cd.getFields()) {
                if (fd.hasModifier(Modifier.Keyword.STATIC) || fd.hasModifier(Modifier.Keyword.TRANSIENT)) {
                    continue;
                }
                String desc = descriptionOf(fd.getAnnotations());
                String jp = jsonPropertyName(fd.getAnnotations());
                for (VariableDeclarator vd : fd.getVariables()) {
                    String name = vd.getNameAsString();
                    if (jp != null && !jp.isBlank()) name = jp;
                    members.add(new Member(name, vd.getType(), desc, !isOptional(vd.getType())));
                }
            }
            defs.put(cd.getNameAsString(), new TypeDef(cd.getNameAsString(), members, false, List.of(), topLevel));
            // Collect nested types
            for (TypeDeclaration<?> nested : cd.getMembers().stream()
                    .filter(n -> n instanceof TypeDeclaration<?>)
                    .map(n -> (TypeDeclaration<?>) n)
                    .toList()) {
                collectTypeDef(nested, defs, false);
            }
        }
    }

    // ------------------------------------------------------------------ //
    //  Conversion
    // ------------------------------------------------------------------ //

    private static Map<String, Object> convertTypeDef(TypeDef def, Map<String, TypeDef> defs, Set<String> seen) {
        if (def.isEnum) {
            Map<String, Object> s = new LinkedHashMap<>();
            s.put("type", "string");
            if (!def.enumConstants.isEmpty()) s.put("enum", def.enumConstants);
            return s;
        }
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        Map<String, Object> props = new LinkedHashMap<>();
        List<String> required = new ArrayList<>();
        for (Member m : def.members) {
            Map<String, Object> fieldSchema = schemaForType(m.type, defs, seen);
            if (m.description != null && !m.description.isBlank()) {
                fieldSchema.put("description", m.description);
            }
            props.put(m.name, fieldSchema);
            if (m.required) required.add(m.name);
        }
        schema.put("properties", props);
        if (!required.isEmpty()) schema.put("required", required);
        schema.put("additionalProperties", false);
        return schema;
    }

    private static Map<String, Object> schemaForType(Type type, Map<String, TypeDef> defs, Set<String> seen) {
        Map<String, Object> s = new LinkedHashMap<>();
        if (type.isPrimitiveType()) {
            String p = type.asPrimitiveType().getType().name().toLowerCase();
            switch (p) {
                case "boolean" -> s.put("type", "boolean");
                case "int", "long", "short", "byte", "char" -> s.put("type", "integer");
                case "float", "double" -> s.put("type", "number");
                default -> s.put("type", "string");
            }
            return s;
        }
        if (type.isArrayType()) {
            s.put("type", "array");
            s.put("items", schemaForType(type.asArrayType().getComponentType(), defs, seen));
            return s;
        }
        if (!type.isClassOrInterfaceType()) {
            s.put("type", "string");
            return s;
        }
        com.github.javaparser.ast.type.ClassOrInterfaceType cit = type.asClassOrInterfaceType();
        String name = cit.getName().getIdentifier();

        // Optional<T>
        if ("Optional".equals(name)) {
            if (cit.getTypeArguments().isPresent() && !cit.getTypeArguments().get().isEmpty()) {
                return schemaForType(cit.getTypeArguments().get().get(0), defs, seen);
            }
            s.put("type", "string");
            return s;
        }
        // List / Set / Collection
        if (name.equals("List") || name.equals("Set") || name.equals("Collection") || name.equals("Iterable")) {
            s.put("type", "array");
            if (cit.getTypeArguments().isPresent() && !cit.getTypeArguments().get().isEmpty()) {
                s.put("items", schemaForType(cit.getTypeArguments().get().get(0), defs, seen));
            } else {
                s.put("items", Map.of("type", "string"));
            }
            return s;
        }
        // Map
        if (name.equals("Map")) {
            s.put("type", "object");
            s.put("additionalProperties", true);
            return s;
        }
        // Scalars
        switch (name) {
            case "String", "CharSequence", "UUID", "URI", "URL" -> {
                s.put("type", "string");
                return s;
            }
            case "Integer", "Long", "Short", "Byte", "BigInteger", "AtomicInteger", "AtomicLong" -> {
                s.put("type", "integer");
                return s;
            }
            case "Float", "Double", "BigDecimal" -> {
                s.put("type", "number");
                return s;
            }
            case "Boolean" -> {
                s.put("type", "boolean");
                return s;
            }
            case "Instant", "OffsetDateTime", "ZonedDateTime", "LocalDateTime", "Timestamp" -> {
                s.put("type", "string");
                s.put("format", "date-time");
                return s;
            }
            case "LocalDate", "Date", "SqlDate" -> {
                s.put("type", "string");
                s.put("format", "date");
                return s;
            }
            case "LocalTime" -> {
                s.put("type", "string");
                s.put("format", "time");
                return s;
            }
        }
        // Nested type in same source
        TypeDef nested = defs.get(name);
        if (nested != null) {
            if (nested.isEnum) {
                s.put("type", "string");
                if (!nested.enumConstants.isEmpty()) s.put("enum", nested.enumConstants);
                return s;
            }
            if (seen.contains(name)) {
                s.put("type", "object");
                return s;
            }
            seen.add(name);
            Map<String, Object> converted = convertTypeDef(nested, defs, seen);
            seen.remove(name);
            return converted;
        }
        // Fallback
        s.put("type", "object");
        return s;
    }

    // ------------------------------------------------------------------ //
    //  Helpers
    // ------------------------------------------------------------------ //

    private static boolean isOptional(Type t) {
        if (t.isClassOrInterfaceType()) {
            return "Optional".equals(t.asClassOrInterfaceType().getName().getIdentifier());
        }
        return false;
    }

    private static String jsonPropertyName(List<AnnotationExpr> anns) {
        for (AnnotationExpr ann : anns) {
            if (ann.getNameAsString().equals("JsonProperty")) {
                if (ann instanceof SingleMemberAnnotationExpr sa) {
                    return unquote(sa.getMemberValue().toString());
                }
                if (ann instanceof NormalAnnotationExpr na) {
                    for (MemberValuePair pair : na.getPairs()) {
                        if (pair.getNameAsString().equals("value")) {
                            return unquote(pair.getValue().toString());
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String descriptionOf(List<AnnotationExpr> anns) {
        for (AnnotationExpr ann : anns) {
            if (ann.getNameAsString().equals("Schema")) {
                if (ann instanceof NormalAnnotationExpr na) {
                    for (MemberValuePair pair : na.getPairs()) {
                        if (pair.getNameAsString().equals("description")) {
                            return unquote(pair.getValue().toString());
                        }
                    }
                }
            }
        }
        return null;
    }

    private static String unquote(String s) {
        if (s == null) return null;
        s = s.trim();
        if (s.startsWith("\"") && s.endsWith("\"") && s.length() >= 2) {
            return s.substring(1, s.length() - 1);
        }
        return s;
    }
}
