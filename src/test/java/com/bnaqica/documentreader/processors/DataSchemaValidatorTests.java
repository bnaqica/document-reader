package com.bnaqica.documentreader.processors;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import org.junit.jupiter.api.Test;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

class DataSchemaValidatorTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();
    private static final JsonNode SCHEMA = JSON.readTree("""
            {"type":"object",
             "properties":{"employerName":{"type":["string","null"]},"grossPay":{"type":["number","null"]}},
             "required":["employerName","grossPay"],
             "additionalProperties":false}
            """);

    private final DataSchemaValidator validator = new DataSchemaValidator();

    @Test
    void validDataHasNoErrors() {
        assertThat(validator.validate(SCHEMA, JSON.readTree("{\"employerName\":\"Acme\",\"grossPay\":4210.0}")))
                .isEmpty();
        assertThat(validator.validate(SCHEMA, JSON.readTree("{\"employerName\":null,\"grossPay\":null}")))
                .isEmpty();
    }

    @Test
    void missingRequiredFieldIsNamed() {
        List<String> errors = validator.validate(SCHEMA, JSON.readTree("{\"employerName\":\"Acme\"}"));

        assertThat(errors).singleElement().asString().contains("grossPay");
    }

    @Test
    void wrongTypeIsNamed() {
        List<String> errors =
                validator.validate(SCHEMA, JSON.readTree("{\"employerName\":\"Acme\",\"grossPay\":\"lots\"}"));

        assertThat(errors).singleElement().asString().contains("grossPay");
    }

    @Test
    void extraPropertyIsNamed() {
        List<String> errors =
                validator.validate(SCHEMA, JSON.readTree("{\"employerName\":\"Acme\",\"grossPay\":1,\"bonus\":5}"));

        assertThat(errors).singleElement().asString().contains("bonus");
    }

    @Test
    void missingDataIsAnError() {
        assertThat(validator.validate(SCHEMA, null)).containsExactly("data is missing");
    }
}
