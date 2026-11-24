package com.bnaqica.documentreader;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.springframework.core.io.ClassPathResource;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/** The committed synthetic fixtures ({@link FixtureGenerator}), and seeded type schemas read without a database. */
public final class Fixtures {

    public static final String PACKET = "packet-paystub-w2-bank.pdf";
    public static final String LICENSE = "license.jpg";

    private Fixtures() {}

    public static byte[] packet() {
        return resource("fixtures/" + PACKET);
    }

    public static byte[] license() {
        return resource("fixtures/" + LICENSE);
    }

    /** A seeded type's data schema, as the V4 migration inserts it. */
    public static JsonNode seededSchema(JsonMapper jsonMapper, String typeName) {
        String sql = new String(resource("db/migration/V4__seed_document_types.sql"), StandardCharsets.UTF_8);
        String quotedName = Pattern.quote("'" + typeName.replace("'", "''") + "'");
        Matcher row = Pattern.compile(quotedName + ".*?\\R\\s*'(\\{.*?})'::json", Pattern.DOTALL)
                .matcher(sql);
        if (!row.find()) {
            throw new IllegalArgumentException("No seeded type " + typeName);
        }
        return jsonMapper.readTree(row.group(1).replace("''", "'"));
    }

    /** A test or main classpath resource's bytes. */
    public static byte[] resource(String path) {
        try (InputStream in = new ClassPathResource(path).getInputStream()) {
            return in.readAllBytes();
        } catch (IOException e) {
            throw new UncheckedIOException(path, e);
        }
    }
}
