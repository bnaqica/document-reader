package com.bnaqica.documentreader.processors;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
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

    /** A property schema of {@code employerName} carrying one extra keyword. */
    private static JsonNode withKeyword(String keyword, String value) {
        return json("""
                {
                  "type": "object",
                  "properties": {"employerName": {"type": ["string", "null"], "%s": %s}},
                  "required": ["employerName"],
                  "additionalProperties": false
                }
                """.formatted(keyword, value));
    }

    /** OpenAI strict structured output rejects these, so every extraction for the type would fail with HTTP 400. */
    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "not | {\"type\": \"number\"}",
                "patternProperties | {\"^x\": {\"type\": \"string\"}}",
                "if | {\"type\": \"string\"}",
                "propertyNames | {\"pattern\": \"^a\"}",
                "unevaluatedProperties | false",
                "dependentRequired | {}",
                "contains | {\"type\": \"string\"}",
                "minLength | 1"
            })
    void keywordStrictModeRejectsIsAViolation(String keyword, String value) {
        assertThat(DataSchemaRules.violations(withKeyword(keyword, value)))
                .anySatisfy(violation -> assertThat(violation).contains(keyword));
    }

    @ParameterizedTest
    @CsvSource(
            delimiter = '|',
            value = {
                "description | \"The employer\"",
                "format | \"date\"",
                "enum | [\"a\", \"b\", null]",
                "const | \"a\"",
                "pattern | \"^[A-Z]\"",
            })
    void keywordStrictModeAcceptsIsAllowed(String keyword, String value) {
        assertThat(DataSchemaRules.violations(withKeyword(keyword, value))).isEmpty();
    }

    @Test
    void numberAndArrayBoundsAreAllowed() {
        JsonNode schema = json("""
                {
                  "type": "object",
                  "properties": {
                    "amount": {"type": "number", "minimum": 0, "maximum": 10, "exclusiveMinimum": -1,
                               "exclusiveMaximum": 11, "multipleOf": 0.01},
                    "names": {"type": "array", "items": {"type": "string"}, "minItems": 0, "maxItems": 5}
                  },
                  "required": ["amount", "names"],
                  "additionalProperties": false
                }
                """);

        assertThat(DataSchemaRules.violations(schema)).isEmpty();
    }

    /** OpenAI allows 10 levels of nesting, and extraction wraps the schema in one more. */
    @Test
    void schemaNestedTooDeepIsAViolation() {
        assertThat(DataSchemaRules.violations(nested(DataSchemaRules.MAX_DEPTH + 1)))
                .anySatisfy(violation -> assertThat(violation).contains("nested"));
        assertThat(DataSchemaRules.violations(nested(DataSchemaRules.MAX_DEPTH)))
                .isEmpty();
    }

    /** {@code depth} objects inside each other, the root counting as 1. */
    private static JsonNode nested(int depth) {
        String schema = "{\"type\": \"string\"}";
        for (int level = 0; level < depth; level++) {
            schema = "{\"type\": \"object\", \"properties\": {\"inner\": " + schema
                    + "}, \"required\": [\"inner\"], \"additionalProperties\": false}";
        }
        return json(schema);
    }

    /** OpenAI refuses {@code anyOf} at the root, and the root's branches would otherwise go unchecked. */
    @Test
    void anyOfAtTheRootIsAViolation() {
        JsonNode schema = json("""
                {
                  "type": "object",
                  "properties": {"a": {"type": "string"}},
                  "required": ["a"],
                  "additionalProperties": false,
                  "anyOf": [{"type": "object"}]
                }
                """);

        assertThat(DataSchemaRules.violations(schema))
                .anySatisfy(violation -> assertThat(violation).contains("anyOf"));
    }

    @Test
    void booleanSubschemaIsAViolation() {
        JsonNode schema = json("""
                {"type": "object", "properties": {"a": true}, "required": ["a"], "additionalProperties": false}
                """);

        assertThat(DataSchemaRules.violations(schema))
                .anySatisfy(violation -> assertThat(violation).contains("$.properties.a"));
    }

    /** Arrays don't count as a level: only objects do. */
    @Test
    void arraysDoNotAddToTheDepth() {
        String schema = "{\"type\": \"string\"}";
        for (int level = 0; level < DataSchemaRules.MAX_DEPTH; level++) {
            String object = "{\"type\": \"object\", \"properties\": {\"inner\": " + schema
                    + "}, \"required\": [\"inner\"], \"additionalProperties\": false}";
            schema = level == DataSchemaRules.MAX_DEPTH - 1
                    ? object
                    : "{\"type\": \"array\", \"items\": " + object + "}";
        }

        assertThat(DataSchemaRules.violations(json(schema))).isEmpty();
    }
}
