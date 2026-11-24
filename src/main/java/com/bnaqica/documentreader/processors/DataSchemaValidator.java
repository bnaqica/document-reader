package com.bnaqica.documentreader.processors;

import com.networknt.schema.Error;
import com.networknt.schema.Schema;
import com.networknt.schema.SchemaRegistry;
import com.networknt.schema.SpecificationVersion;
import java.util.List;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;

/**
 * Checks extracted data against a document type's JSON Schema (draft 2020-12, the dialect the catalog's strict
 * schemas are written in). The messages go back to the model so it can correct its answer; they can quote the data,
 * so they are never logged.
 */
@Component
public class DataSchemaValidator {

    private final SchemaRegistry registry = SchemaRegistry.withDefaultDialect(SpecificationVersion.DRAFT_2020_12);

    /** Every way {@code data} breaks {@code schema}, in order; empty when it is valid. */
    public List<String> validate(JsonNode schema, JsonNode data) {
        if (data == null || data.isNull() || data.isMissingNode()) {
            return List.of("data is missing");
        }
        // Compiled each time: types are few and extraction calls rare, so a cache isn't worth its invalidation
        Schema compiled = registry.getSchema(schema);
        return compiled.validate(data).stream().map(Error::toString).toList();
    }
}
