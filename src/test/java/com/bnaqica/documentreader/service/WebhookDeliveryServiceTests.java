package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.config.ClockConfig;
import com.bnaqica.documentreader.config.DocumentReaderProperties;
import com.bnaqica.documentreader.config.WebhookClientConfig;
import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.NewBatch;
import com.bnaqica.documentreader.dto.NewSourceFile;
import com.bnaqica.documentreader.dto.PageRange;
import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.dto.response.WebhookAttemptRecord;
import com.bnaqica.documentreader.entity.BatchStatus;
import com.bnaqica.documentreader.entity.InboxStatus;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.entity.WebhookStatus;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executors;
import java.util.function.Supplier;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest(properties = "app.document-reader.webhook.timeout=1s")
@Import({
    TestcontainersConfiguration.class,
    ClockConfig.class,
    WebhookClientConfig.class,
    WebhookDeliveryServiceImpl.class,
    WebhookDeliveryAttemptServiceImpl.class,
    BatchQueryServiceImpl.class,
    BatchServiceImpl.class,
    LoanPurposeServiceImpl.class,
    SourceFileServiceImpl.class,
    DocumentServiceImpl.class,
    DocumentTypeServiceImpl.class,
    ClassificationRunServiceImpl.class,
    ClassificationResultServiceImpl.class,
    ExtractionResultServiceImpl.class,
    InboxMessageServiceImpl.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@EnableConfigurationProperties(DocumentReaderProperties.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class WebhookDeliveryServiceTests {

    private static final String SUBJECT = "webhook-tests";

    @Autowired
    private WebhookDeliveryServiceImpl delivery;

    @Autowired
    private BatchService batches;

    @Autowired
    private SourceFileService sourceFiles;

    @Autowired
    private DocumentService documents;

    @Autowired
    private WebhookDeliveryAttemptService attempts;

    @Autowired
    private BatchQueryService query;

    @Autowired
    private InboxMessageService inbox;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    private HttpServer receiver;

    /** What the receiver got, in order. */
    private final Queue<Received> received = new ConcurrentLinkedQueue<>();

    /** How the receiver answers each request in turn; the last one repeats. */
    private final Queue<Answer> answers = new ConcurrentLinkedQueue<>();

    private record Received(String method, String path, Map<String, List<String>> headers, String body) {}

    private record Answer(int status, long delayMillis, String location) {}

    @BeforeEach
    void startReceiver() throws IOException {
        receiver = HttpServer.create(new InetSocketAddress("localhost", 0), 0);
        receiver.createContext("/", exchange -> {
            String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            received.add(new Received(
                    exchange.getRequestMethod(),
                    exchange.getRequestURI().getPath(),
                    Map.copyOf(exchange.getRequestHeaders()),
                    body));
            Answer answer = answers.size() > 1 ? answers.poll() : answers.peek();
            if (answer == null) {
                answer = new Answer(204, 0, null);
            }
            try {
                Thread.sleep(answer.delayMillis());
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
            }
            if (answer.location() != null) {
                exchange.getResponseHeaders().add("Location", answer.location());
            }
            exchange.sendResponseHeaders(answer.status(), -1);
            exchange.close();
        });
        // a slow answer mustn't hold up the next request: each one gets its own thread
        receiver.setExecutor(Executors.newVirtualThreadPerTaskExecutor());
        receiver.start();
    }

    @AfterEach
    void cleanUp() {
        receiver.stop(0);
        ProcessingFixtures fixtures = new ProcessingFixtures(jdbc);
        for (UUID batch : jdbc.sql("SELECT id FROM document_reader.batch WHERE requested_by = ?")
                .param(SUBJECT)
                .query(UUID.class)
                .list()) {
            jdbc.sql("DELETE FROM document_reader.inbox_message WHERE aggregate_id = ?")
                    .param(batch)
                    .update();
            fixtures.deleteBatch(batch);
        }
    }

    // --- Delivering

    @Test
    void postsBatchEnvelopeWithTwoHeaders() {
        UUID batch = completedAsyncBatch(url("/hook"));
        UUID deliveryId = UUID.randomUUID();

        delivery.deliver(batch, deliveryId);

        assertThat(received).singleElement().satisfies(request -> {
            assertThat(request.method()).isEqualTo("POST");
            assertThat(request.path()).isEqualTo("/hook");
            assertThat(header(request, "X-document-reader-batch")).isEqualTo(batch.toString());
            assertThat(header(request, "X-document-reader-delivery")).isEqualTo(deliveryId.toString());
            assertThat(header(request, "Content-type")).startsWith("application/json");
            assertThat(request.headers().keySet())
                    .noneMatch(name -> name.toLowerCase().contains("signature"));
            JsonNode body = JsonMapper.builder().build().readTree(request.body());
            assertThat(body.get("batchId").asString()).isEqualTo(batch.toString());
            assertThat(body.get("status").asString()).isEqualTo("COMPLETED");
            assertThat(body.get("documents")).hasSize(2);
            assertThat(body.get("files")).hasSize(1);
            assertThat(body.get("completedAt").asString()).isEqualTo("2026-11-20T16:01:12Z");
        });
    }

    @Test
    void twoHundredMarksDelivered() {
        UUID batch = completedAsyncBatch(url("/hook"));
        answers.add(new Answer(200, 0, null));

        delivery.deliver(batch, UUID.randomUUID());

        assertThat(batches.get(batch).webhookStatus()).isEqualTo(WebhookStatus.DELIVERED);
        assertThat(attempts.findByBatch(batch)).singleElement().satisfies(attempt -> {
            assertThat(attempt.httpStatus()).isEqualTo(200);
            assertThat(attempt.error()).isNull();
        });
    }

    @Test
    void fiveHundredSchedulesRetry() {
        UUID batch = completedAsyncBatch(url("/hook"));
        answers.add(new Answer(500, 0, null));

        assertFails(batch, "HTTP 500");

        assertThat(attempts.findByBatch(batch)).singleElement().satisfies(attempt -> {
            assertThat(attempt.httpStatus()).isEqualTo(500);
            assertThat(attempt.error()).isEqualTo("HTTP 500");
        });
    }

    @Test
    void timeoutSchedulesRetry() {
        UUID batch = completedAsyncBatch(url("/hook"));
        answers.add(new Answer(200, 2500, null)); // webhook.timeout is 1 s here

        assertFails(batch, "No response");

        assertThat(attempts.findByBatch(batch)).singleElement().satisfies(attempt -> {
            assertThat(attempt.httpStatus()).isNull();
            assertThat(attempt.error()).startsWith("No response");
        });
    }

    @Test
    void redirectIsNotFollowedAndCountsAsFailure() {
        UUID batch = completedAsyncBatch(url("/hook"));
        answers.add(new Answer(302, 0, url("/elsewhere")));

        assertFails(batch, "HTTP 302");

        assertThat(received).extracting(Received::path).containsExactly("/hook");
        assertThat(attempts.findByBatch(batch))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.httpStatus()).isEqualTo(302));
    }

    @Test
    void unreachableReceiverCountsAsFailure() throws IOException {
        int closedPort;
        try (ServerSocket socket = new ServerSocket(0)) {
            closedPort = socket.getLocalPort();
        }
        UUID batch = completedAsyncBatch("http://localhost:" + closedPort + "/hook");

        assertFails(batch, "No response");

        assertThat(attempts.findByBatch(batch))
                .singleElement()
                .satisfies(attempt -> assertThat(attempt.httpStatus()).isNull());
    }

    @Test
    void requestThatCannotBeSentIsRecordedWithoutTheUrl() {
        // The URL validator accepts any digits as a port; the HTTP client then refuses it
        UUID batch = completedAsyncBatch("http://localhost:70000/hook");

        assertFails(batch, "Request failed");

        assertThat(attempts.findByBatch(batch)).singleElement().satisfies(attempt -> {
            assertThat(attempt.httpStatus()).isNull();
            assertThat(attempt.error()).startsWith("Request failed").doesNotContain("70000");
        });
    }

    @Test
    void everyAttemptRecorded() {
        UUID batch = completedAsyncBatch(url("/hook"));
        answers.add(new Answer(500, 0, null));
        answers.add(new Answer(200, 2500, null));
        answers.add(new Answer(200, 0, null));
        UUID deliveryId = UUID.randomUUID();

        assertThatThrownBy(() -> delivery.deliver(batch, deliveryId)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> delivery.deliver(batch, deliveryId)).isInstanceOf(IllegalStateException.class);
        delivery.deliver(batch, deliveryId);

        List<WebhookAttemptRecord> recorded = attempts.findByBatch(batch);
        assertThat(recorded).extracting(WebhookAttemptRecord::attempt).containsExactly(1, 2, 3);
        assertThat(recorded).extracting(WebhookAttemptRecord::httpStatus).containsExactly(500, null, 200);
        assertThat(recorded.get(0).error()).isNotNull();
        assertThat(recorded.get(1).error()).isNotNull();
        assertThat(recorded.get(2).error()).isNull();
        assertThat(batches.get(batch).webhookStatus()).isEqualTo(WebhookStatus.DELIVERED);
    }

    @Test
    void idempotentWhenDelivered() {
        UUID batch = completedAsyncBatch(url("/hook"));
        delivery.deliver(batch, UUID.randomUUID());
        received.clear();

        delivery.deliver(batch, UUID.randomUUID());

        assertThat(received).isEmpty();
        assertThat(attempts.findByBatch(batch)).hasSize(1);
    }

    @Test
    void syncBatchSendsNothing() {
        UUID batch = committed(() -> {
            UUID id = batches.create(new NewBatch("CAR_REFINANCE", false, null, SUBJECT, 1))
                    .id();
            batches.updateStatus(id, BatchStatus.PROCESSING, null);
            batches.complete(id, BatchStatus.FAILED, "No document completed", Instant.now());
            return id;
        });

        delivery.deliver(batch, UUID.randomUUID());

        assertThat(received).isEmpty();
        assertThat(attempts.findByBatch(batch)).isEmpty();
    }

    // --- Inbox handler

    @Test
    void handleDeliversAndMarksMessageDone() {
        UUID batch = completedAsyncBatch(url("/hook"));
        InboxMessageRecord message = inboxMessage(batch);

        assertThat(delivery.type()).isEqualTo(MessageType.DELIVER_WEBHOOK);
        delivery.handle(message);

        assertThat(header(received.peek(), "X-document-reader-delivery"))
                .isEqualTo(message.id().toString());
        assertThat(batches.get(batch).webhookStatus()).isEqualTo(WebhookStatus.DELIVERED);
        assertThat(inbox.get(message.id()).status()).isEqualTo(InboxStatus.DONE);
    }

    @Test
    void exhaustedMarksWebhookFailedButKeepsResult() {
        UUID batch = completedAsyncBatch(url("/hook"));

        inTransaction(() -> delivery.onExhausted(inboxMessage(batch), "IllegalStateException: HTTP 500"));

        assertThat(batches.get(batch).webhookStatus()).isEqualTo(WebhookStatus.FAILED);
        assertThat(batches.get(batch).status()).isEqualTo(BatchStatus.COMPLETED);
        assertThat(query.getResult(batch, new Caller(SUBJECT, false)).documents())
                .hasSize(2);
    }

    @Test
    void exhaustedLeavesDeliveredWebhookAlone() {
        UUID batch = completedAsyncBatch(url("/hook"));
        delivery.deliver(batch, UUID.randomUUID());

        inTransaction(() -> delivery.onExhausted(inboxMessage(batch), "late"));

        assertThat(batches.get(batch).webhookStatus()).isEqualTo(WebhookStatus.DELIVERED);
    }

    // --- Helpers

    private void assertFails(UUID batch, String reason) {
        assertThatThrownBy(() -> delivery.deliver(batch, UUID.randomUUID()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining(reason)
                .hasMessageNotContaining("localhost");
        assertThat(batches.get(batch).webhookStatus()).isEqualTo(WebhookStatus.PENDING);
    }

    private String url(String path) {
        return "http://localhost:" + receiver.getAddress().getPort() + path;
    }

    private static String header(Received request, String name) {
        return request.headers().entrySet().stream()
                .filter(entry -> entry.getKey().equalsIgnoreCase(name))
                .map(entry -> entry.getValue().getFirst())
                .findFirst()
                .orElse(null);
    }

    /** An async batch completed with one file of two documents, whose webhook is pending, as WI-10 leaves it. */
    private UUID completedAsyncBatch(String webhookUrl) {
        return committed(() -> {
            UUID batch = batches.create(new NewBatch("CAR_REFINANCE", true, webhookUrl, SUBJECT, 1))
                    .id();
            batches.updateStatus(batch, BatchStatus.PROCESSING, null);
            UUID file = sourceFiles
                    .createAll(batch, List.of(new NewSourceFile("file.pdf", "application/pdf", 10, "a".repeat(64), 2)))
                    .getFirst()
                    .id();
            documents.createAll(file, List.of(new PageRange(1, 1), new PageRange(2, 2)));
            batches.complete(batch, BatchStatus.COMPLETED, null, Instant.parse("2026-11-20T16:01:12Z"));
            batches.updateWebhookStatus(batch, WebhookStatus.PENDING);
            return batch;
        });
    }

    private InboxMessageRecord inboxMessage(UUID batch) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.inbox_message
                            (id, message_type, aggregate_id, payload, status, attempts, next_attempt_at, created_at)
                        VALUES (?, 'DELIVER_WEBHOOK', ?, CAST(? AS jsonb), 'PROCESSING', 1, now(), now())
                        """).params(id, batch, "{\"batchId\":\"" + batch + "\"}").update();
        return inbox.get(id);
    }

    private <T> T committed(Supplier<T> work) {
        return new TransactionTemplate(transactionManager).execute(status -> work.get());
    }

    private void inTransaction(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }
}
