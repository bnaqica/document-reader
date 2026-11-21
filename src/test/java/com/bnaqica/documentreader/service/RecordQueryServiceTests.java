package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.config.ClockConfig;
import com.bnaqica.documentreader.dto.Caller;
import com.bnaqica.documentreader.dto.NewBatch;
import com.bnaqica.documentreader.dto.NewSourceFile;
import com.bnaqica.documentreader.dto.PageImage;
import com.bnaqica.documentreader.dto.PageRange;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.exception.ResourceNotFoundException;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;

@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    ClockConfig.class,
    RecordQueryServiceImpl.class,
    BatchServiceImpl.class,
    LoanPurposeServiceImpl.class,
    SourceFileServiceImpl.class,
    SourceFilePageServiceImpl.class,
    DocumentServiceImpl.class,
    DocumentTypeServiceImpl.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class RecordQueryServiceTests {

    private static final Caller OWNER = new Caller("user-1", false);
    private static final Caller OTHER = new Caller("user-2", false);
    private static final Caller ADMIN = new Caller("admin-1", true);

    @Autowired
    private RecordQueryService records;

    @Autowired
    private BatchService batches;

    @Autowired
    private SourceFileService sourceFiles;

    @Autowired
    private SourceFilePageService pages;

    @Autowired
    private DocumentService documents;

    private UUID batch;
    private UUID file;
    private UUID document;

    @BeforeEach
    void batchOfUser1WithOneFileAndDocument() {
        batch = batches.create(new NewBatch("CAR_REFINANCE", false, null, "user-1", 1))
                .id();
        file = sourceFiles
                .createAll(batch, List.of(new NewSourceFile("scan.pdf", "application/pdf", 100, "a".repeat(64), 2)))
                .getFirst()
                .id();
        pages.saveAll(file, List.of(new PageImage(1, "p1.png", 10, 10), new PageImage(2, "p2.png", 10, 10)));
        document = documents
                .createAll(file, List.of(new PageRange(1, 2)))
                .getFirst()
                .id();
    }

    @Test
    void ownerReadsEveryRecordOfTheirBatch() {
        assertThat(records.batch(batch, OWNER).id()).isEqualTo(batch);
        assertThat(records.sourceFiles(batch, OWNER)).hasSize(1);
        assertThat(records.documents(batch, OWNER)).hasSize(1);
        assertThat(records.sourceFile(file, OWNER).id()).isEqualTo(file);
        assertThat(records.pages(file, OWNER)).hasSize(2);
        assertThat(records.document(document, OWNER).id()).isEqualTo(document);
    }

    @Test
    void anotherCallerFindsNothing() {
        assertThatThrownBy(() -> records.batch(batch, OTHER)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> records.sourceFiles(batch, OTHER)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> records.documents(batch, OTHER)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> records.sourceFile(file, OTHER)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> records.pages(file, OTHER)).isInstanceOf(ResourceNotFoundException.class);
        assertThatThrownBy(() -> records.document(document, OTHER)).isInstanceOf(ResourceNotFoundException.class);
    }

    @Test
    void adminReadsAnyBatch() {
        assertThat(records.batch(batch, ADMIN).id()).isEqualTo(batch);
        assertThat(records.document(document, ADMIN).id()).isEqualTo(document);
        assertThat(records.pages(file, ADMIN)).hasSize(2);
    }

    @Test
    void listingShowsOnlyTheCallersBatchesUnlessAdmin() {
        UUID others = batches.create(new NewBatch("CAR_REFINANCE", false, null, "user-2", 1))
                .id();

        assertThat(records.batches(OWNER, 50)).extracting(BatchRecord::id).containsExactly(batch);
        assertThat(records.batches(OTHER, 50)).extracting(BatchRecord::id).containsExactly(others);
        assertThat(records.batches(ADMIN, 50)).extracting(BatchRecord::id).contains(batch, others);
    }
}
