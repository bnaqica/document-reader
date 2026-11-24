package com.bnaqica.documentreader;

import static org.assertj.core.api.Assertions.assertThat;

import com.bnaqica.documentreader.entity.OpenAiCallPurpose;
import com.bnaqica.documentreader.listener.InboxWorker;
import com.bnaqica.documentreader.processors.DataSchemaValidator;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.test.annotation.DirtiesContext;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

/**
 * The pipeline end to end: intake, separation, classification, extraction, completion and webhook delivery, through
 * the real application with its workers on, Postgres and S3 (LocalStack). Only OpenAI is replaced, by
 * {@link PipelineChatModel}, which reads the colour marker on each fixture page.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(PipelineEndToEndTests.StubModel.class)
@DirtiesContext // its workers poll the shared database; stop them once this class is done
class PipelineEndToEndTests extends PipelineTestSupport {

    /** This instance's model; the second instance gets its own through the same configuration. */
    @TestConfiguration(proxyBeanMethods = false)
    static class StubModel {

        @Bean
        @Primary
        PipelineChatModel pipelineChatModel() {
            return new PipelineChatModel(JsonMapper.builder().build());
        }
    }

    private static final WebhookReceiver RECEIVER = new WebhookReceiver();

    @Autowired
    PipelineChatModel model;

    @Autowired
    InboxWorker inboxWorker;

    @AfterAll
    static void stopReceiver() {
        RECEIVER.close();
    }

    @Test
    void syncBatchReturnsOnePayloadPerDocument() throws Exception {
        HttpResponse<String> response = submit(false, null);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(200);
        assertFullResult(jsonMapper.readTree(response.body()));
    }

    @Test
    void asyncBatchDeliversOneWebhook() throws Exception {
        String url = RECEIVER.newUrl();

        HttpResponse<String> response = submit(true, url);

        assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
        UUID batchId = UUID.fromString(
                jsonMapper.readTree(response.body()).get("batchId").asString());
        awaitDelivery(url);
        Thread.sleep(2000); // long enough for a duplicate delivery to show up
        List<WebhookReceiver.Delivery> deliveries = RECEIVER.deliveriesTo(url);
        assertThat(deliveries).hasSize(1);
        assertThat(deliveries.getFirst().batchHeader()).isEqualTo(batchId.toString());
        JsonNode delivered = jsonMapper.readTree(deliveries.getFirst().body());
        assertFullResult(delivered);
        assertThat(delivered).isEqualTo(getBatch(batchId));
    }

    @Test
    void twoAppInstancesShareWorkWithoutDuplicates() throws Exception {
        int callsBefore = model.calls();
        try (ConfigurableApplicationContext other = startInstance(StubModel.class)) {
            PipelineChatModel otherModel = other.getBean(PipelineChatModel.class);
            Map<UUID, String> batches = new LinkedHashMap<>();
            for (int i = 0; i < 10; i++) {
                String url = RECEIVER.newUrl();
                HttpResponse<String> response = submit(true, url);
                assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
                batches.put(
                        UUID.fromString(jsonMapper
                                .readTree(response.body())
                                .get("batchId")
                                .asString()),
                        url);
            }

            for (Map.Entry<UUID, String> batch : batches.entrySet()) {
                assertThat(awaitFinal(batch.getKey())).isEqualTo("COMPLETED");
                awaitDelivery(batch.getValue());
            }
            Thread.sleep(2000); // long enough for a duplicate delivery to show up

            UUID[] ids = batches.keySet().toArray(UUID[]::new);
            // per batch: 3 separation runs of the packet (a one-page file isn't separated), 5 classification runs
            // per document, and per document 3 passes, a consolidation and a final reading
            assertThat(count("""
                    SELECT count(*) FROM document_reader.separation_run r
                    JOIN document_reader.source_file f ON f.id = r.source_file_id WHERE f.batch_id = ANY(?)""", ids)).isEqualTo(10 * 3);
            assertThat(count("""
                    SELECT count(*) FROM document_reader.classification_run r
                    JOIN document_reader.document d ON d.id = r.document_id WHERE d.batch_id = ANY(?)""", ids)).isEqualTo(10 * 4 * 5);
            assertThat(count("""
                    SELECT count(*) FROM document_reader.extraction_run r
                    JOIN document_reader.document d ON d.id = r.document_id WHERE d.batch_id = ANY(?)""", ids)).isEqualTo(10 * 4 * (3 + 1 + 1));
            assertThat(count("""
                    SELECT count(*) FROM document_reader.extraction_result r
                    JOIN document_reader.document d ON d.id = r.document_id WHERE d.batch_id = ANY(?)""", ids)).isEqualTo(10 * 4);
            // every message was handled to completion by one instance, on its first attempt
            assertThat(jdbc.queryForList("""
                            SELECT m.status || ':' || m.attempts FROM document_reader.inbox_message m
                            WHERE m.aggregate_id = ANY(?)
                               OR m.aggregate_id IN (SELECT id FROM document_reader.source_file WHERE batch_id = ANY(?))
                               OR m.aggregate_id IN (SELECT id FROM document_reader.document WHERE batch_id = ANY(?))""", String.class, ids, ids, ids))
                    .hasSize(10 * (2 + 4 + 4 + 1))
                    .containsOnly("DONE:1");
            for (Map.Entry<UUID, String> batch : batches.entrySet()) {
                assertThat(RECEIVER.deliveriesTo(batch.getValue())).hasSize(1);
                assertFullResult(getBatch(batch.getKey()));
            }
            // both instances did part of the work
            assertThat(otherModel.calls()).isPositive();
            assertThat(model.calls()).isGreaterThan(callsBefore);
        }
    }

    /**
     * Instance A claims the licence's extraction and dies during it, leaving the message claimed. This instance's
     * workers are paused meanwhile so that A is the one to claim it. The crash is on the final reading, which runs on
     * the handler's own thread: an error in a pass is caught by the pass executor and becomes an ordinary failure.
     */
    @Test
    void instanceKilledMidExtractionIsRecovered() throws Exception {
        UUID batchId;
        inboxWorker.stop();
        try {
            // only A's lease is short: it claims the message and dies, and its lease is what has to run out
            try (ConfigurableApplicationContext instanceA =
                    startInstance(StubModel.class, Map.of("app.document-reader.inbox.lease", "3s"))) {
                PipelineChatModel modelA = instanceA.getBean(PipelineChatModel.class);
                modelA.crashOn(OpenAiCallPurpose.FINAL);

                HttpResponse<String> response =
                        submit(true, RECEIVER.newUrl(), List.of(Map.entry(Fixtures.LICENSE, Fixtures.license())));
                assertThat(response.statusCode()).as(response.body()).isEqualTo(202);
                batchId = UUID.fromString(
                        jsonMapper.readTree(response.body()).get("batchId").asString());

                assertThat(modelA.crashes().await(DEADLINE.toSeconds(), TimeUnit.SECONDS))
                        .isTrue();
            }
            assertThat(extractMessage(batchId))
                    .containsEntry("status", "PROCESSING")
                    .containsEntry("attempts", 1);
        } finally {
            inboxWorker.start();
        }

        assertThat(awaitFinal(batchId)).isEqualTo("COMPLETED");
        assertThat(extractMessage(batchId)).containsEntry("status", "DONE").containsEntry("attempts", 2);
    }

    private Map<String, Object> extractMessage(UUID batchId) {
        return jdbc.queryForMap("""
                SELECT m.status, m.attempts FROM document_reader.inbox_message m
                JOIN document_reader.document d ON d.id = m.aggregate_id
                WHERE m.message_type = 'EXTRACT_DOCUMENT' AND d.batch_id = ?""", batchId);
    }

    private long count(String sql, UUID[] batchIds) {
        return jdbc.queryForObject(sql, Long.class, (Object) batchIds);
    }

    private static void awaitDelivery(String url) throws InterruptedException {
        Instant deadline = Instant.now().plus(DEADLINE);
        while (RECEIVER.deliveriesTo(url).isEmpty()) {
            if (Instant.now().isAfter(deadline)) {
                throw new AssertionError("No webhook delivery within " + DEADLINE);
            }
            Thread.sleep(200);
        }
    }

    /** The spec's packet-and-licence result: four documents in file then page order, each with valid data. */
    private void assertFullResult(JsonNode result) {
        assertThat(result.get("status").asString()).as(result.toString()).isEqualTo("COMPLETED");

        JsonNode files = result.get("files");
        assertThat(files).hasSize(2);
        assertThat(files.get(0).get("index").asInt()).isZero();
        assertThat(files.get(0).get("documentCount").asInt()).isEqualTo(3);
        assertThat(files.get(1).get("documentCount").asInt()).isEqualTo(1);
        UUID packetId = UUID.fromString(files.get(0).get("id").asString());
        UUID licenseId = UUID.fromString(files.get(1).get("id").asString());

        List<String> documents = new ArrayList<>();
        for (JsonNode document : result.get("documents")) {
            UUID file = UUID.fromString(document.get("sourceFileId").asString());
            documents.add((file.equals(packetId) ? "0" : file.equals(licenseId) ? "1" : "?") + ":"
                    + document.get("pages").get("start").asInt() + "-"
                    + document.get("pages").get("end").asInt() + ":"
                    + document.get("documentType").get("name").asString());
            String typeName = document.get("documentType").get("name").asString();
            assertThat(new DataSchemaValidator()
                            .validate(Fixtures.seededSchema(jsonMapper, typeName), document.get("data")))
                    .as("data of %s", typeName)
                    .isEmpty();
            assertThat(document.get("summary").asString()).isNotBlank();
        }
        assertThat(documents)
                .containsExactly(
                        "0:1-2:Pay stubs",
                        "0:3-3:W-2 forms",
                        "0:4-6:Bank statements",
                        "1:1-1:Driver's license or government photo ID");
    }
}
