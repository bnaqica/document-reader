package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.PageImage;
import com.bnaqica.documentreader.dto.response.PageRecord;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@DataJpaTest
@Import({TestcontainersConfiguration.class, SourceFilePageServiceImpl.class})
class SourceFilePageServiceTests {

    @Autowired
    private SourceFilePageService service;

    @Autowired
    private JdbcClient jdbc;

    private UUID sourceFile() {
        UUID batch = UUID.randomUUID();
        UUID file = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.batch
                            (id, loan_purpose_code, async, status, webhook_status, file_count, requested_by, received_at)
                        VALUES (?, 'CAR_REFINANCE', false, 'RECEIVED', 'NOT_APPLICABLE', 1, 'user-1', now())
                        """).param(batch).update();
        jdbc.sql("""
                        INSERT INTO document_reader.source_file
                            (id, batch_id, file_index, original_filename, content_type, size_bytes, sha256, page_count, status)
                        VALUES (?, ?, 0, 'scan.pdf', 'application/pdf', 100, repeat('a', 64), 3, 'SEPARATING')
                        """).params(file, batch).update();
        return file;
    }

    private static PageImage page(int number) {
        return new PageImage(number, "files/x/pages/000" + number + ".png", 1700, 2200);
    }

    @Test
    void pagesListInPageOrder() {
        UUID file = sourceFile();

        service.saveAll(file, List.of(page(2), page(1), page(3)));

        assertThat(service.findBySourceFile(file))
                .extracting(PageRecord::pageNumber)
                .containsExactly(1, 2, 3);
        assertThat(service.findBySourceFile(file).getFirst())
                .isEqualTo(new PageRecord(file, 1, "files/x/pages/0001.png", 1700, 2200));
    }

    @Test
    void savingAgainReplacesTheFilesPages() {
        UUID file = sourceFile();
        service.saveAll(file, List.of(page(1), page(2), page(3)));

        service.saveAll(file, List.of(page(1), page(2)));

        assertThat(service.findBySourceFile(file))
                .extracting(PageRecord::pageNumber)
                .containsExactly(1, 2);
    }
}
