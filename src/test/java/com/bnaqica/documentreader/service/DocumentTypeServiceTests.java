package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.config.ClockConfig;
import com.bnaqica.documentreader.dto.request.DocumentTypeRequest;
import com.bnaqica.documentreader.dto.response.DocumentTypeResponse;
import com.bnaqica.documentreader.entity.DocumentTypeOrigin;
import com.bnaqica.documentreader.exception.DocumentTypeInUseException;
import com.bnaqica.documentreader.exception.DocumentTypeKeyConflictException;
import com.bnaqica.documentreader.exception.InvalidDataSchemaException;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import com.bnaqica.documentreader.exception.UnusableNameException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.transaction.AfterTransaction;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Import({TestcontainersConfiguration.class, ClockConfig.class, DocumentTypeServiceImpl.class})
class DocumentTypeServiceTests {

    private static final JsonMapper JSON = JsonMapper.builder().build();

    @Autowired
    private DocumentTypeService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private PlatformTransactionManager transactionManager;

    /** After the test's own transaction has rolled back, so the cleanup can't wait on its locks. */
    @AfterTransaction
    void removeCommittedTypesAndAliases() {
        CatalogCleanup.removeAllButSeeded(transactionManager, jdbc);
    }

    private static JsonNode schema(String... properties) {
        StringBuilder props = new StringBuilder();
        StringBuilder required = new StringBuilder();
        for (String property : properties) {
            if (!props.isEmpty()) {
                props.append(',');
                required.append(',');
            }
            props.append('"').append(property).append("\":{\"type\":[\"string\",\"null\"]}");
            required.append('"').append(property).append('"');
        }
        return JSON.readTree("{\"type\":\"object\",\"properties\":{" + props + "},\"required\":[" + required
                + "],\"additionalProperties\":false}");
    }

    private DocumentTypeResponse payStubs() {
        return service.resolve("Pay stubs").orElseThrow();
    }

    @Test
    void resolvesByOwnNameRegardlessOfSpelling() {
        assertThat(service.resolve("PAY STUB")).map(DocumentTypeResponse::name).contains("Pay stubs");
        assertThat(service.resolve("w2 form")).map(DocumentTypeResponse::name).contains("W-2 forms");
    }

    @Test
    void resolvesByAlias() {
        jdbc.sql("INSERT INTO document_reader.document_type_alias (id, document_type_id, alias, normalized_key) "
                        + "VALUES (?, ?, 'Earnings statement', 'earningstatement')")
                .params(UUID.randomUUID(), payStubs().id())
                .update();

        assertThat(service.resolve("earnings statements"))
                .map(DocumentTypeResponse::name)
                .contains("Pay stubs");
    }

    @Test
    void unknownOrUnusableNameResolvesToNothing() {
        assertThat(service.resolve("Boarding pass")).isEmpty();
        assertThat(service.resolve("N/A")).isEmpty();
        assertThat(service.resolve(null)).isEmpty();
    }

    @Test
    void listsAllTypesOrderedByName() {
        List<DocumentTypeResponse> types = service.findAll();

        assertThat(types).hasSize(41);
        assertThat(types.getFirst().name()).isEqualTo("1099 or K-1 forms");
        assertThat(types).extracting(DocumentTypeResponse::name).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER);
    }

    @Test
    void catalogListsEveryNameWithItsDescription() {
        assertThat(service.catalog()).hasSize(41).allSatisfy(entry -> {
            assertThat(entry.name()).isNotBlank();
            assertThat(entry.description()).isNotBlank();
        });
    }

    @Test
    void unknownIdIsNotFound() {
        UUID unknown = UUID.randomUUID();

        assertThatThrownBy(() -> service.get(unknown)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> service.delete(unknown)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void createsManualTypeAtVersionOne() {
        DocumentTypeResponse created =
                service.create(new DocumentTypeRequest("Lease agreement", "A rental lease.", schema("landlord")));

        assertThat(created.normalizedKey()).isEqualTo("leaseagreement");
        assertThat(created.origin()).isEqualTo(DocumentTypeOrigin.MANUAL);
        assertThat(created.schemaVersion()).isEqualTo(1);
        assertThat(service.get(created.id()).dataSchema()).isEqualTo(schema("landlord"));
    }

    @Test
    void duplicateNameIsRefusedNamingTheOwner() {
        assertThatThrownBy(() -> service.create(new DocumentTypeRequest("Paystubs", "Duplicate.", schema("a"))))
                .isInstanceOf(DocumentTypeKeyConflictException.class)
                .hasMessageContaining("Pay stubs");
    }

    @Test
    void nameWithNoKeyIsRefused() {
        assertThatThrownBy(() -> service.create(new DocumentTypeRequest("N/A", "Nothing.", schema("a"))))
                .isInstanceOf(UnusableNameException.class);
    }

    @Test
    void invalidSchemaIsRefused() {
        assertThatThrownBy(() -> service.create(
                        new DocumentTypeRequest("Lease agreement", "A lease.", JSON.readTree("{\"type\":\"string\"}"))))
                .isInstanceOf(InvalidDataSchemaException.class);
    }

    @Test
    void schemaChangeBumpsVersion() {
        DocumentTypeResponse gift = service.resolve("Gift letter").orElseThrow();

        DocumentTypeResponse updated =
                service.update(gift.id(), new DocumentTypeRequest(gift.name(), gift.description(), schema("donor")));

        assertThat(updated.schemaVersion()).isEqualTo(2);
        assertThat(updated.dataSchema()).isEqualTo(schema("donor"));
    }

    @Test
    void sameSchemaReformattedKeepsVersion() {
        DocumentTypeResponse gift = service.resolve("Gift letter").orElseThrow();
        JsonNode sameSchemaParsedAgain = JSON.readTree(JSON.writeValueAsString(gift.dataSchema()));

        DocumentTypeResponse updated = service.update(
                gift.id(), new DocumentTypeRequest(gift.name(), "A new description.", sameSchemaParsedAgain));

        assertThat(updated.schemaVersion()).isEqualTo(1);
        assertThat(updated.description()).isEqualTo("A new description.");
    }

    @Test
    void renameGivesNewKey() {
        DocumentTypeResponse gift = service.resolve("Gift letter").orElseThrow();

        DocumentTypeResponse renamed = service.update(
                gift.id(),
                new DocumentTypeRequest("Gift letters and donor statements", gift.description(), gift.dataSchema()));

        assertThat(renamed.normalizedKey()).isEqualTo("giftletteranddonorstatement");
        assertThat(service.resolve("Gift letter")).isEmpty();
    }

    @Test
    void renameToTakenNameIsRefusedNamingTheOwner() {
        DocumentTypeResponse gift = service.resolve("Gift letter").orElseThrow();

        assertThatThrownBy(() -> service.update(
                        gift.id(), new DocumentTypeRequest("Pay stub", gift.description(), gift.dataSchema())))
                .isInstanceOf(DocumentTypeKeyConflictException.class)
                .hasMessageContaining("Pay stubs");
    }

    @Test
    void removedTypeNoLongerResolves() {
        DocumentTypeResponse lease =
                service.create(new DocumentTypeRequest("Lease agreement", "A rental lease.", schema("landlord")));

        service.delete(lease.id());

        assertThat(service.resolve("Lease agreement")).isEmpty();
    }

    /** Runs outside the test transaction, so the two creates really race in separate transactions. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void concurrentCreatesOfOneKeyCreateExactlyOneType() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<String>> creates = new ArrayList<>();
        for (String name : List.of("Lease agreement", "Lease agreements")) {
            creates.add(() -> {
                start.await();
                try {
                    service.create(new DocumentTypeRequest(name, "A rental lease.", schema("landlord")));
                    return "created";
                } catch (DocumentTypeKeyConflictException e) {
                    return "conflict";
                }
            });
        }
        try (ExecutorService executor = Executors.newFixedThreadPool(2)) {
            List<Future<String>> results = new ArrayList<>();
            for (Callable<String> create : creates) {
                results.add(executor.submit(create));
            }
            start.countDown();
            List<String> outcomes = new ArrayList<>();
            for (Future<String> result : results) {
                outcomes.add(result.get());
            }

            assertThat(outcomes).containsExactlyInAnyOrder("created", "conflict");
            assertThat(jdbc.sql(
                                    "SELECT count(*) FROM document_reader.document_type WHERE normalized_key = 'leaseagreement'")
                            .query(Integer.class)
                            .single())
                    .isEqualTo(1);
        } finally {
            jdbc.sql("DELETE FROM document_reader.document_type WHERE normalized_key = 'leaseagreement'")
                    .update();
        }
    }

    /** JSONB would sort these by key length (zeta, alpha, middle); extraction relies on the authored order. */
    @Test
    void schemaKeepsItsPropertyOrder() {
        DocumentTypeResponse created = service.create(
                new DocumentTypeRequest("Lease agreement", "A rental lease.", schema("middle", "zeta", "alpha")));

        service.findAll(); // flushes the insert, so the database holds the schema
        String stored = jdbc.sql("SELECT data_schema::text FROM document_reader.document_type WHERE id = ?")
                .param(created.id())
                .query(String.class)
                .single();

        assertThat(JSON.readTree(stored).path("properties").propertyNames()).containsExactly("middle", "zeta", "alpha");
    }

    @Test
    void seededSchemaKeepsItsAuthoredOrder() {
        assertThat(payStubs().dataSchema().path("properties").propertyNames())
                .startsWith("employerName", "employeeName", "payPeriodStart", "payPeriodEnd", "payDate");
    }

    @Test
    void renameToOneOfItsOwnAliasesIsRefusedNamingTheType() {
        jdbc.sql("INSERT INTO document_reader.document_type_alias (id, document_type_id, alias, normalized_key) "
                        + "VALUES (?, ?, 'Earnings statement', 'earningstatement')")
                .params(UUID.randomUUID(), payStubs().id())
                .update();
        DocumentTypeResponse payStubs = payStubs();

        assertThatThrownBy(() -> service.update(
                        payStubs.id(),
                        new DocumentTypeRequest("Earnings statement", payStubs.description(), payStubs.dataSchema())))
                .isInstanceOf(DocumentTypeKeyConflictException.class)
                .hasMessageContaining("Pay stubs");
    }

    @Test
    void updatingUnknownIdIsNotFound() {
        assertThatThrownBy(() -> service.update(
                        UUID.randomUUID(), new DocumentTypeRequest("Lease agreement", "A lease.", schema("landlord"))))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    /**
     * Classification (WI-13) catches a lost race and re-reads the winner inside its own transaction, so a conflict
     * must not leave that transaction aborted. Another transaction inserts the key without committing; this create's
     * check misses it, its insert waits, and then fails once the other commits.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void conflictInsideACallersTransactionLeavesItUsable() throws Exception {
        CountDownLatch inserted = new CountDownLatch(1);
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        try (ExecutorService other = Executors.newSingleThreadExecutor()) {
            Future<?> winner = other.submit(() -> transaction.executeWithoutResult(status -> {
                jdbc.sql("""
                                INSERT INTO document_reader.document_type
                                    (id, name, normalized_key, description, data_schema, schema_version, origin, created_at)
                                VALUES (?, 'Lease agreement', 'leaseagreement', 'A rental lease.', ?::json, 1, 'MANUAL', now())
                                """)
                        .params(UUID.randomUUID(), schema("landlord").toString())
                        .update();
                inserted.countDown();
                sleep(1500); // hold the row uncommitted while the create below checks and inserts
            }));
            inserted.await();

            Optional<DocumentTypeResponse> reread = transaction.execute(status -> {
                assertThatThrownBy(() -> service.create(
                                new DocumentTypeRequest("Lease agreements", "A rental lease.", schema("landlord"))))
                        .isInstanceOf(DocumentTypeKeyConflictException.class);
                return service.resolve("Lease agreement");
            });
            winner.get();

            assertThat(reread).map(DocumentTypeResponse::name).contains("Lease agreement");
        } finally {
            jdbc.sql("DELETE FROM document_reader.document_type WHERE normalized_key = 'leaseagreement'")
                    .update();
        }
    }

    /** Runs on committed rows, like the document case above: a type that only a classification run resolved to. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void typeThatOnlyAClassificationRunUsesCannotBeRemoved() {
        ProcessingFixtures fixtures = new ProcessingFixtures(jdbc);
        UUID document = fixtures.document();
        UUID w2 = fixtures.type("W-2 forms");
        try {
            jdbc.sql("""
                            INSERT INTO document_reader.classification_run
                                (id, document_id, round, run_number, raw_answer, document_type_id, confidence,
                                 reasoning, succeeded, created_at)
                            VALUES (?, ?, 'INITIAL', 1, 'W-2', ?, 0.7, 'wage statement', true, now())
                            """).params(UUID.randomUUID(), document, w2).update();

            assertThatThrownBy(() -> service.delete(w2)).isInstanceOf(DocumentTypeInUseException.class);
            assertThat(service.resolve("W-2 forms")).isPresent();
        } finally {
            fixtures.deleteBatch(fixtures.batchOf(document));
        }
    }

    /** Runs on committed rows: a type that only a classification result refers to. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void typeThatOnlyAClassificationResultUsesCannotBeRemoved() {
        ProcessingFixtures fixtures = new ProcessingFixtures(jdbc);
        UUID document = fixtures.document();
        UUID w2 = fixtures.type("W-2 forms");
        try {
            jdbc.sql("""
                            INSERT INTO document_reader.classification_result
                                (document_id, document_type_id, votes, agreement, tie_broken, decided_by, decided_at)
                            VALUES (?, ?, '{}'::json, 1, false, 'VOTE', now())
                            """).params(document, w2).update();

            assertThatThrownBy(() -> service.delete(w2)).isInstanceOf(DocumentTypeInUseException.class);
            assertThat(service.resolve("W-2 forms")).isPresent();
        } finally {
            fixtures.deleteBatch(fixtures.batchOf(document));
        }
    }

    /** Runs on committed rows: a type that only an extraction result refers to. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void typeThatOnlyAnExtractionResultUsesCannotBeRemoved() {
        ProcessingFixtures fixtures = new ProcessingFixtures(jdbc);
        UUID document = fixtures.document();
        UUID payStubs = payStubs().id();
        try {
            jdbc.sql("""
                            INSERT INTO document_reader.extraction_result
                                (document_id, document_type_id, schema_version, data, summary, created_at)
                            VALUES (?, ?, 1, '{}'::json, 'summary', now())
                            """).params(document, payStubs).update();

            assertThatThrownBy(() -> service.delete(payStubs)).isInstanceOf(DocumentTypeInUseException.class);
            assertThat(service.resolve("Pay stubs")).isPresent();
        } finally {
            fixtures.deleteBatch(fixtures.batchOf(document));
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }

    /**
     * Runs on committed rows outside the test transaction: the refused delete aborts the transaction it runs in, so
     * checking afterwards that the type survived needs a fresh one.
     */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void typeThatADocumentUsesCannotBeRemoved() {
        UUID payStubs = payStubs().id();
        UUID batch = UUID.randomUUID();
        UUID file = UUID.randomUUID();
        try {
            jdbc.sql("""
                            INSERT INTO document_reader.batch
                                (id, loan_purpose_code, async, status, webhook_status, file_count, requested_by, received_at)
                            VALUES (?, 'CAR_REFINANCE', false, 'PROCESSING', 'NOT_APPLICABLE', 1, 'user-1', now())
                            """).param(batch).update();
            jdbc.sql("""
                            INSERT INTO document_reader.source_file
                                (id, batch_id, file_index, original_filename, content_type, size_bytes, sha256, page_count, status)
                            VALUES (?, ?, 0, 'scan.pdf', 'application/pdf', 100, repeat('a', 64), 1, 'SEPARATED')
                            """).params(file, batch).update();
            jdbc.sql("""
                            INSERT INTO document_reader.document
                                (id, batch_id, source_file_id, sequence, start_page, end_page, status, document_type_id)
                            VALUES (?, ?, ?, 1, 1, 1, 'CLASSIFIED', ?)
                            """).params(UUID.randomUUID(), batch, file, payStubs).update();

            jdbc.sql("INSERT INTO document_reader.document_type_alias (id, document_type_id, alias, normalized_key) "
                            + "VALUES (?, ?, 'Earnings statement', 'earningstatement')")
                    .params(UUID.randomUUID(), payStubs)
                    .update();

            assertThatThrownBy(() -> service.delete(payStubs))
                    .isInstanceOf(DocumentTypeInUseException.class)
                    .hasMessageContaining("Pay stubs")
                    .hasCauseInstanceOf(org.springframework.dao.DataIntegrityViolationException.class);
            assertThat(service.resolve("Pay stubs")).isPresent();
            assertThat(service.resolve("Earnings statement"))
                    .map(DocumentTypeResponse::name)
                    .contains("Pay stubs");
        } finally {
            jdbc.sql("DELETE FROM document_reader.document_type_alias WHERE normalized_key = 'earningstatement'")
                    .update();
            jdbc.sql("DELETE FROM document_reader.document WHERE batch_id = ?")
                    .param(batch)
                    .update();
            jdbc.sql("DELETE FROM document_reader.source_file WHERE batch_id = ?")
                    .param(batch)
                    .update();
            jdbc.sql("DELETE FROM document_reader.batch WHERE id = ?")
                    .param(batch)
                    .update();
        }
    }
}
