package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.NewSourceFile;
import com.bnaqica.documentreader.dto.response.SourceFileRecord;
import com.bnaqica.documentreader.entity.SourceFileStatus;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

@DataJpaTest
@Import({TestcontainersConfiguration.class, SourceFileServiceImpl.class})
class SourceFileServiceTests {

    @Autowired
    private SourceFileService service;

    @Autowired
    private JdbcClient jdbc;

    private UUID batch() {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.batch
                            (id, loan_purpose_code, async, status, webhook_status, file_count, requested_by, received_at)
                        VALUES (?, 'CAR_REFINANCE', false, 'RECEIVED', 'NOT_APPLICABLE', 3, 'user-1', now())
                        """).param(id).update();
        return id;
    }

    private static NewSourceFile file(String name, int pages) {
        return new NewSourceFile(name, "application/pdf", 1024, "a".repeat(64), pages);
    }

    @Test
    void filesTakeTheirIndexFromUploadOrder() {
        UUID batch = batch();

        List<SourceFileRecord> created =
                service.createAll(batch, List.of(file("paystub.pdf", 2), file("w2.pdf", 1), file("bank.pdf", 6)));

        assertThat(created).extracting(SourceFileRecord::fileIndex).containsExactly(0, 1, 2);
        assertThat(created)
                .extracting(SourceFileRecord::originalFilename)
                .containsExactly("paystub.pdf", "w2.pdf", "bank.pdf");
        assertThat(created).allSatisfy(f -> {
            assertThat(f.batchId()).isEqualTo(batch);
            assertThat(f.status()).isEqualTo(SourceFileStatus.RECEIVED);
            assertThat(f.s3Key()).isNull();
        });
        assertThat(service.findByBatch(batch))
                .extracting(SourceFileRecord::originalFilename)
                .containsExactly("paystub.pdf", "w2.pdf", "bank.pdf");
    }

    @Test
    void lockForUpdateReturnsTheFileAsItIsNow() {
        UUID id = service.createAll(batch(), List.of(file("scan.pdf", 3)))
                .getFirst()
                .id();

        assertThat(service.lockForUpdate(id).status()).isEqualTo(SourceFileStatus.RECEIVED);
    }

    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void lockForUpdateOutsideATransactionIsRefused() {
        assertThatThrownBy(() -> service.lockForUpdate(UUID.randomUUID()))
                .isInstanceOf(IllegalTransactionStateException.class);
    }

    @Test
    void storedFileRecordsItsLocation() {
        UUID id = service.createAll(batch(), List.of(file("scan.pdf", 3)))
                .getFirst()
                .id();

        service.markStored(id, "docs", "files/" + id + "/original.pdf");

        SourceFileRecord stored = service.get(id);
        assertThat(stored.status()).isEqualTo(SourceFileStatus.STORED);
        assertThat(stored.s3Bucket()).isEqualTo("docs");
        assertThat(stored.s3Key()).isEqualTo("files/" + id + "/original.pdf");
    }

    @Test
    void failureReasonIsKeptOnlyWhileFailed() {
        UUID id = service.createAll(batch(), List.of(file("scan.pdf", 3)))
                .getFirst()
                .id();

        service.updateStatus(id, SourceFileStatus.FAILED, "corrupt page 2");
        assertThat(service.get(id).failureReason()).isEqualTo("corrupt page 2");

        service.updateStatus(id, SourceFileStatus.SEPARATING, null);
        assertThat(service.get(id).status()).isEqualTo(SourceFileStatus.SEPARATING);
        assertThat(service.get(id).failureReason()).isNull();
    }

    @Test
    void unknownFileIsNotFound() {
        assertThatThrownBy(() -> service.get(UUID.randomUUID())).isInstanceOf(ResourceNotFoundException.class);
    }
}
