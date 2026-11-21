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
 * behind them would go unchecked.
 */
public final class DataSchemaRules {

    private static final List<String> FORBIDDEN_KEYWORDS = List.of("$ref", "$defs", "definitions", "allOf", "oneOf");

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
        checkObject(schema, "$", violations);
        return violations;
    }

    private static void checkObject(JsonNode schema, String path, List<String> violations) {
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
            checkNested(properties.get(name), path + ".properties." + name, violations);
        }
    }

    private static void checkNested(JsonNode schema, String path, List<String> violations) {
        checkKeywords(schema, path, violations);
        if (isObjectSchema(schema)) {
            checkObject(schema, path, violations);
        }
        if (schema.has("items")) {
            checkNested(schema.get("items"), path + ".items", violations);
        }
        JsonNode anyOf = schema.path("anyOf");
        for (int i = 0; i < anyOf.size(); i++) {
            checkNested(anyOf.get(i), path + ".anyOf[" + i + "]", violations);
        }
    }

    private static void checkKeywords(JsonNode schema, String path, List<String> violations) {
        for (String keyword : FORBIDDEN_KEYWORDS) {
            if (schema.has(keyword)) {
                violations.add(path + ": " + keyword + " isn't allowed; write the schema out in full");
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
