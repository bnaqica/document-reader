package com.bnaqica.documentreader.config;

import static org.assertj.core.api.Assertions.assertThat;

import com.bnaqica.documentreader.processors.DataSchemaRules;
import java.io.IOException;
import java.io.InputStream;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The fixed answer schemas meet OpenAI strict structured output, by the same rules as the catalog's schemas. */
class AnswerSchemasTests {

    @ParameterizedTest
    @ValueSource(strings = {"separation", "classification", "type-match"})
    void followsStrictSchemaRules(String name) throws IOException {
        JsonNode schema;
        try (InputStream in = new ClassPathResource("prompts/schemas/" + name + ".json").getInputStream()) {
            schema = JsonMapper.builder().build().readTree(in);
        }

        assertThat(DataSchemaRules.violations(schema)).isEmpty();
    }
}
