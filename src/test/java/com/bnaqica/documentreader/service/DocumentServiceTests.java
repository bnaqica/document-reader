package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.StepClock;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.PageRange;
import com.bnaqica.documentreader.dto.response.DocumentRecord;
import com.bnaqica.documentreader.entity.DocumentStatus;
import com.bnaqica.documentreader.exception.InvalidPageRangesException;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import java.time.Clock;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    DocumentServiceTests.ClockConfig.class,
    DocumentServiceImpl.class,
    SourceFileServiceImpl.class,
    DocumentTypeServiceImpl.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class DocumentServiceTests {

    @TestConfiguration
    static class ClockConfig {
        @Bean
        Clock clock() {
            return new StepClock();
        }
    }

    @Autowired
    private DocumentService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private Clock clock;

    private UUID batch;
    private UUID file0;
    private UUID file1;

    @BeforeEach
    void batchWithTwoSixPageFiles() {
        ((StepClock) clock).reset();
        batch = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.batch
                            (id, loan_purpose_code, async, status, webhook_status, file_count, requested_by, received_at)
                        VALUES (?, 'CAR_REFINANCE', false, 'PROCESSING', 'NOT_APPLICABLE', 2, 'user-1', now())
                        """).param(batch).update();
        file0 = sourceFile(0);
        file1 = sourceFile(1);
    }

    private UUID sourceFile(int index) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.source_file
                            (id, batch_id, file_index, original_filename, content_type, size_bytes, sha256, page_count, status)
                        VALUES (?, ?, ?, 'scan.pdf', 'application/pdf', 100, repeat('a', 64), 6, 'SEPARATING')
                        """).params(id, batch, index).update();
        return id;
    }

    @Test
    void markTruncatedSetsTheFlag() {
        UUID id = service.createAll(file0, List.of(new PageRange(1, 2)))
                .getFirst()
                .id();
        assertThat(service.get(id).truncated()).isFalse();

        service.markTruncated(id);

        assertThat(service.get(id).truncated()).isTrue();
    }

    @Test
    void lockForUpdateReturnsTheDocumentAsItIsNow() {
        UUID id = service.createAll(file0, List.of(new PageRange(1, 2)))
                .getFirst()
                .id();

        assertThat(service.lockForUpdate(id).status()).isEqualTo(DocumentStatus.PENDING);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lockForUpdateOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> service.lockForUpdate(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void documentsAreNumberedInPageOrderAndStartPending() {
        List<DocumentRecord> created =
                service.createAll(file0, List.of(new PageRange(1, 2), new PageRange(3, 3), new PageRange(4, 6)));

        assertThat(created).extracting(DocumentRecord::sequence).containsExactly(1, 2, 3);
        assertThat(created).extracting(DocumentRecord::startPage).containsExactly(1, 3, 4);
        assertThat(created).extracting(DocumentRecord::endPage).containsExactly(2, 3, 6);
        assertThat(created).allSatisfy(document -> {
            assertThat(document.status()).isEqualTo(DocumentStatus.PENDING);
            assertThat(document.batchId()).isEqualTo(batch);
            assertThat(document.sourceFileId()).isEqualTo(file0);
            assertThat(document.documentTypeId()).isNull();
        });
    }

    @Test
    void rangesGivenOutOfOrderAreNumberedByPage() {
        List<DocumentRecord> created = service.createAll(file0, List.of(new PageRange(4, 6), new PageRange(1, 3)));

        assertThat(created).extracting(DocumentRecord::startPage).containsExactly(1, 4);
        assertThat(created).extracting(DocumentRecord::sequence).containsExactly(1, 2);
    }

    @ParameterizedTest(name = "{0}")
    @CsvSource(
            delimiter = '|',
            value = {
                "past the last page | 1,4,5,7",
                "overlapping        | 3,5,4,6",
                "before page one    | 0,2,3,6",
                "ends before start  | 3,2,4,6"
            })
    void invalidRangesCreateNothing(String why, String bounds) {
        String[] b = bounds.split(",");
        List<PageRange> ranges = List.of(
                new PageRange(Integer.parseInt(b[0]), Integer.parseInt(b[1])),
                new PageRange(Integer.parseInt(b[2]), Integer.parseInt(b[3])));

        assertThatThrownBy(() -> service.createAll(file0, ranges)).isInstanceOf(InvalidPageRangesException.class);
        assertThat(service.findBySourceFile(file0)).isEmpty();
    }

    @Test
    void batchDocumentsListByFileThenSequence() {
        service.createAll(file1, List.of(new PageRange(1, 6)));
        service.createAll(file0, List.of(new PageRange(1, 2), new PageRange(3, 6)));

        assertThat(service.findByBatch(batch))
                .extracting(DocumentRecord::sourceFileId, DocumentRecord::sequence)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple(file0, 1),
                        org.assertj.core.groups.Tuple.tuple(file0, 2),
                        org.assertj.core.groups.Tuple.tuple(file1, 1));
    }

    @Test
    void assignedTypeIsRecorded() {
        UUID document = service.createAll(file0, List.of(new PageRange(1, 6)))
                .getFirst()
                .id();
        UUID payStubs = jdbc.sql("SELECT id FROM document_reader.document_type WHERE name = 'Pay stubs'")
                .query(UUID.class)
                .single();

        service.assignType(document, payStubs);

        assertThat(service.get(document).documentTypeId()).isEqualTo(payStubs);
    }

    @Test
    void finishingRecordsTheTimeAndAFailureItsReason() {
        UUID document = service.createAll(file0, List.of(new PageRange(1, 6)))
                .getFirst()
                .id();

        service.updateStatus(document, DocumentStatus.EXTRACTING, "ignored");
        assertThat(service.get(document).completedAt()).isNull();
        assertThat(service.get(document).failureReason()).isNull();

        service.updateStatus(document, DocumentStatus.FAILED, "extraction gave invalid data twice");
        DocumentRecord failed = service.get(document);
        assertThat(failed.status()).isEqualTo(DocumentStatus.FAILED);
        assertThat(failed.failureReason()).isEqualTo("extraction gave invalid data twice");
        assertThat(failed.completedAt()).isEqualTo(StepClock.START);
    }

    @Test
    void unknownDocumentIsNotFound() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void emptyRangeListIsRefused() {
        assertThatThrownBy(() -> service.createAll(file0, List.of())).isInstanceOf(InvalidPageRangesException.class);
    }

    @Test
    void assigningAnUnknownTypeIsNotFound() {
        UUID document = service.createAll(file0, List.of(new PageRange(1, 6)))
                .getFirst()
                .id();

        assertThatThrownBy(() -> service.assignType(document, UUID.randomUUID()))
                .isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void movingAFinishedDocumentBackClearsItsCompletionTime() {
        UUID document = service.createAll(file0, List.of(new PageRange(1, 6)))
                .getFirst()
                .id();
        service.updateStatus(document, DocumentStatus.FAILED, "timeout");

        service.updateStatus(document, DocumentStatus.CLASSIFYING, null);

        assertThat(service.get(document).completedAt()).isNull();
    }
}
