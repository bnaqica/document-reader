package com.bnaqica.documentreader.processors;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaLocation;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import tools.jackson.databind.JsonNode;

/**
 * Checks that a document type's data schema is one OpenAI strict structured output accepts: valid JSON Schema
 * (draft 2020-12) whose root is exactly {@code "type": "object"} with at least one property, where every object (the
 * root and any nested in {@code properties}, {@code items} or {@code anyOf}) lists exactly its properties as required
 * and allows no others. References and combinators other than {@code anyOf} aren't allowed, because the objects
 * behind them would go unchecked. Only the keywords strict mode accepts may appear, and objects may nest at most
 * {@link #MAX_DEPTH} deep.
 */
public final class DataSchemaRules {

    /**
     * The deepest nesting of objects allowed, the root counting as 1. OpenAI allows 10, and extraction wraps the schema
     * in one more object.
     */
    public static final int MAX_DEPTH = 9;

    private static final List<String> FORBIDDEN_KEYWORDS = List.of("$ref", "$defs", "definitions", "allOf", "oneOf");

    /** The keywords OpenAI strict structured output accepts. */
    private static final Set<String> ALLOWED_KEYWORDS = Set.of(
            "type",
            "properties",
            "required",
            "additionalProperties",
            "items",
            "anyOf",
            "enum",
            "const",
            "description",
            "format",
            "pattern",
            "minimum",
            "maximum",
            "exclusiveMinimum",
            "exclusiveMaximum",
            "multipleOf",
            "minItems",
            "maxItems");

    private static final Schema META_SCHEMA = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12)
            .getSchema(SchemaLocation.of("https://json-schema.org/draft/2020-12/schema"));

    private DataSchemaRules() {}

    /** Every rule the schema breaks, each naming where; empty when the schema is valid. */
    public static List<String> violations(JsonNode schema) {
        List<String> violations = new ArrayList<>();
        for (Error error : META_SCHEMA.validate(schema)) {
            violations.add("not valid JSON Schema: " + error.getMessage());
        }
        JsonNode rootType = schema.path("type");
        if (!rootType.isString() || !rootType.asString().equals("object")) {
            violations.add("$: the root must be exactly \"type\": \"object\"");
            return violations;
        }
        checkKeywords(schema, "$", violations);
        for (String keyword : List.of("anyOf", "items")) {
            if (schema.has(keyword)) {
                violations.add("$: " + keyword + " isn't allowed at the root");
            }
        }
        checkObject(schema, "$", 1, violations);
        return violations;
    }

    private static void checkObject(JsonNode schema, String path, int depth, List<String> violations) {
        if (depth > MAX_DEPTH) {
            violations.add(path + ": objects are nested more than " + MAX_DEPTH + " deep");
            return;
        }
        JsonNode properties = schema.path("properties");
        Set<String> names = new LinkedHashSet<>(properties.isObject() ? properties.propertyNames() : List.of());
        if (names.isEmpty()) {
            violations.add(path + ": an object must have at least one property");
        }

        Set<String> required = new LinkedHashSet<>();
        schema.path("required").forEach(name -> required.add(name.asString()));
        for (String name : names) {
            if (!required.contains(name)) {
                violations.add(path + ".properties." + name + ": must be listed in required (allow null instead)");
            }
        }
        for (String name : required) {
            if (!names.contains(name)) {
                violations.add(path + ".required: " + name + " is not one of the properties");
            }
        }

        JsonNode additional = schema.path("additionalProperties");
        if (!additional.isBoolean() || additional.asBoolean()) {
            violations.add(path + ": additionalProperties must be false");
        }

        for (String name : names) {
            checkNested(properties.get(name), path + ".properties." + name, depth, violations);
        }
    }

    /** A schema inside an object at {@code parentDepth}; an object here is one level deeper. */
    private static void checkNested(JsonNode schema, String path, int parentDepth, List<String> violations) {
        if (!schema.isObject()) {
            violations.add(path + ": must be a schema object, not " + schema.getNodeType());
            return;
        }
        checkKeywords(schema, path, violations);
        if (isObjectSchema(schema)) {
            checkObject(schema, path, parentDepth + 1, violations);
        }
        if (schema.has("items")) {
            checkNested(schema.get("items"), path + ".items", parentDepth, violations);
        }
        JsonNode anyOf = schema.path("anyOf");
        for (int i = 0; i < anyOf.size(); i++) {
            checkNested(anyOf.get(i), path + ".anyOf[" + i + "]", parentDepth, violations);
        }
    }

    private static void checkKeywords(JsonNode schema, String path, List<String> violations) {
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (schema.has(keyword)) {
                violations.add(path + ": " + keyword + " isn't allowed; write the schema out in full");
            }
        }
        for (String keyword : schema.propertyNames()) {
            if (!ALLOWED_KEYWORDS.contains(keyword) && !FORBIDDEN_KEYWORDS.contains(keyword)) {
                violations.add(path + ": " + keyword + " isn't supported by OpenAI strict structured output");
            }
        }
    }

    /** {@code "type": "object"}, a type list containing "object", or a schema that declares properties. */
    private static boolean isObjectSchema(JsonNode schema) {
        JsonNode type = schema.path("type");
        if (type.isString() && type.asString().equals("object")) {
            return true;
        }
        if (type.isArray()) {
            for (JsonNode entry : type) {
                if (entry.asString().equals("object")) {
                    return true;
                }
            }
        }
        return schema.has("properties");
    }
}
