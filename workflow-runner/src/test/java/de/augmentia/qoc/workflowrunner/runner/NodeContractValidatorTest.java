package de.augmentia.qoc.workflowrunner.runner;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

class NodeContractValidatorTest {

    @Test
    void noContract_passesThrough() {
        NodeContractValidator.validate(Map.of(), Map.of("anything", 42));
        NodeContractValidator.validate(null, Map.of("anything", 42));
    }

    @Test
    void validOutput_passes() {
        Map<String, Object> contract = Map.of(
                "type", "object",
                "properties", Map.of(
                        "status", Map.of("type", "string", "enum", List.of("OK", "FAIL")),
                        "count", Map.of("type", "integer", "minimum", 0)
                ),
                "required", List.of("status", "count"),
                "additionalProperties", false
        );
        NodeContractValidator.validate(contract, Map.of("status", "OK", "count", 5));
    }

    @Test
    void missingRequiredField_throws() {
        Map<String, Object> contract = Map.of(
                "type", "object",
                "properties", Map.of("status", Map.of("type", "string")),
                "required", List.of("status", "count")
        );
        ContractViolationException ex = assertThrows(ContractViolationException.class, () ->
                NodeContractValidator.validate(contract, Map.of("status", "OK")));
        assertEquals("$.count", ex.path());
        assertEquals("required_missing", ex.kind());
    }

    @Test
    void wrongType_throws() {
        Map<String, Object> contract = Map.of(
                "type", "object",
                "properties", Map.of("count", Map.of("type", "integer"))
        );
        ContractViolationException ex = assertThrows(ContractViolationException.class, () ->
                NodeContractValidator.validate(contract, Map.of("count", "five")));
        assertEquals("$.count", ex.path());
        assertEquals("type_mismatch", ex.kind());
    }

    @Test
    void additionalProperty_throws() {
        Map<String, Object> contract = Map.of(
                "type", "object",
                "properties", Map.of("status", Map.of("type", "string")),
                "additionalProperties", false
        );
        ContractViolationException ex = assertThrows(ContractViolationException.class, () ->
                NodeContractValidator.validate(contract, Map.of("status", "OK", "extra", true)));
        assertEquals("$.extra", ex.path());
        assertEquals("additional_property", ex.kind());
    }

    @Test
    void invalidEnum_throws() {
        Map<String, Object> contract = Map.of(
                "type", "object",
                "properties", Map.of("status", Map.of("type", "string", "enum", List.of("OK", "FAIL")))
        );
        ContractViolationException ex = assertThrows(ContractViolationException.class, () ->
                NodeContractValidator.validate(contract, Map.of("status", "MAYBE")));
        assertEquals("$.status", ex.path());
        assertEquals("enum_violation", ex.kind());
    }

    @Test
    void minimumViolation_throws() {
        Map<String, Object> contract = Map.of(
                "type", "object",
                "properties", Map.of("count", Map.of("type", "integer", "minimum", 0))
        );
        ContractViolationException ex = assertThrows(ContractViolationException.class, () ->
                NodeContractValidator.validate(contract, Map.of("count", -1)));
        assertEquals("$.count", ex.path());
        assertEquals("minimum_violation", ex.kind());
    }

    @Test
    void arrayItems_validated() {
        Map<String, Object> contract = Map.of(
                "type", "array",
                "items", Map.of("type", "string")
        );
        NodeContractValidator.validate(contract, List.of("a", "b"));
        ContractViolationException ex = assertThrows(ContractViolationException.class, () ->
                NodeContractValidator.validate(contract, List.of("a", 42)));
        assertEquals("$[1]", ex.path());
    }

    @Test
    void nestedObject_validated() {
        Map<String, Object> contract = Map.of(
                "type", "object",
                "properties", Map.of(
                        "inner", Map.of("type", "object", "properties",
                                Map.of("x", Map.of("type", "integer")), "required", List.of("x")),
                        "additionalProperties", false)
        );
        NodeContractValidator.validate(contract, Map.of("inner", Map.of("x", 5)));
        assertThrows(ContractViolationException.class, () ->
                NodeContractValidator.validate(contract, Map.of("inner", Map.of())));
    }
}