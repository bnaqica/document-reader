package com.bnaqica.documentreader.processors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * Data schemas must be strict (every object lists all its properties as required and allows no others), because
 * OpenAI strict structured output rejects anything else and extraction would fail on every call.
 */
class DataSchemaRulesTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    private static JsonNode json(String text) {
        return JSON.readTree(text);
    }

    @Test
    void strictSchemaHasNoViolations() {
        JsonNode schema = json("""
                {
                  "type": "object",
                  "properties": {
                    "employerName": {"type": ["string", "null"]},
                    "deductions": {
                      "type": ["array", "null"],
                      "items": {
                        "type": "object",
                        "properties": {"name": {"type": ["string", "null"]}, "amount": {"type": ["number", "null"]}},
                        "required": ["name", "amount"],
                        "additionalProperties": false
                      }
                    }
                  },
                  "required": ["employerName", "deductions"],
                  "additionalProperties": false
                }
                """);

        assertThat(DataSchemaRules.violations(schema)).isEmpty();
    }

    @Test
    void rootThatIsNotAnObjectIsAViolation() {
        assertThat(DataSchemaRules.violations(json("{\"type\": \"string\"}"))).isNotEmpty();
    }

    @Test
    void objectWithNoPropertiesIsAViolation() {
        JsonNode schema =
                json("{\"type\": \"object\", \"properties\": {}, \"required\": [], \"additionalProperties\": false}");

        assertThat(DataSchemaRules.violations(schema)).isNotEmpty();
    }

    @Test
    void propertyMissingFromRequiredIsAViolation() {
        JsonNode schema = json("""
                {"type": "object", "properties": {"a": {"type": "string"}, "b": {"type": "string"}},
                 "required": ["a"], "additionalProperties": false}
                """);

        assertThat(DataSchemaRules.violations(schema)).anyMatch(violation -> violation.contains("b"));
    }

    @Test
    void missingAdditionalPropertiesFalseIsAViolation() {
        JsonNode schema =
                json("{\"type\": \"object\", \"properties\": {\"a\": {\"type\": \"string\"}}, \"required\": [\"a\"]}");

        assertThat(DataSchemaRules.violations(schema)).isNotEmpty();
    }

    @Test
    void nestedObjectThatAllowsExtraPropertiesIsAViolation() {
        JsonNode schema = json("""
                {"type": "object",
                 "properties": {"deductions": {"type": "object", "properties": {"total": {"type": "number"}}, "required": ["total"]}},
                 "required": ["deductions"], "additionalProperties": false}
                """);

        assertThat(DataSchemaRules.violations(schema)).anyMatch(violation -> violation.contains("deductions"));
    }

    @Test
    void nestedArrayItemWithOptionalPropertyIsAViolation() {
        JsonNode schema = json("""
                {"type": "object",
                 "properties": {"transactions": {"type": "array", "items": {"type": "object",
                   "properties": {"date": {"type": "string"}, "amount": {"type": "number"}},
                   "required": ["date"], "additionalProperties": false}}},
                 "required": ["transactions"], "additionalProperties": false}
                """);

        assertThat(DataSchemaRules.violations(schema)).anyMatch(violation -> violation.contains("amount"));
    }

    @Test
    void schemaThatIsNotValidJsonSchemaIsAViolation() {
        JsonNode schema = json("""
                {"type": "object", "properties": {"a": {"type": "strng"}}, "required": ["a"], "additionalProperties": false}
                """);

        assertThat(DataSchemaRules.violations(schema)).isNotEmpty();
    }

    @Test
    void rootThatMayBeNullIsAViolation() {
        JsonNode schema = json("""
                {"type": ["object", "null"], "properties": {"a": {"type": "string"}}, "required": ["a"],
                 "additionalProperties": false}
                """);

        assertThat(DataSchemaRules.violations(schema)).isNotEmpty();
    }

    @Test
    void rootWithoutObjectTypeIsAViolation() {
        JsonNode schema = json(
                "{\"properties\": {\"a\": {\"type\": \"string\"}}, \"required\": [\"a\"], \"additionalProperties\": false}");

        assertThat(DataSchemaRules.violations(schema)).isNotEmpty();
    }

    /** A $ref'd object is never checked for strictness, so references aren't allowed at all. */
    @Test
    void referenceIsAViolation() {
        JsonNode schema = json("""
                {"type": "object", "properties": {"x": {"$ref": "#/$defs/X"}}, "required": ["x"],
                 "additionalProperties": false,
                 "$defs": {"X": {"type": "object", "properties": {"a": {"type": "string"}}}}}
                """);

        assertThat(DataSchemaRules.violations(schema)).anyMatch(violation -> violation.contains("$ref"));
    }

    @Test
    void allOfAndOneOfAreViolations() {
        JsonNode schema = json("""
                {"type": "object",
                 "properties": {"a": {"allOf": [{"type": "string"}]}, "b": {"oneOf": [{"type": "string"}]}},
                 "required": ["a", "b"], "additionalProperties": false}
                """);

        assertThat(DataSchemaRules.violations(schema))
                .anyMatch(violation -> violation.contains("allOf"))
                .anyMatch(violation -> violation.contains("oneOf"));
    }

    @Test
    void requiredNameThatIsNotAPropertyIsAViolation() {
        JsonNode schema = json("""
                {"type": "object", "properties": {"a": {"type": "string"}}, "required": ["a", "ghost"],
                 "additionalProperties": false}
                """);

        assertThat(DataSchemaRules.violations(schema)).anyMatch(violation -> violation.contains("ghost"));
    }
}
