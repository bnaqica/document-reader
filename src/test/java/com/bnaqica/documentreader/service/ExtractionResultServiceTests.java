package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.StepClock;
import com.bnaqica.documentreader.StepClockConfig;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.NewExtractionResult;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.databind.json.JsonMapper;

@DataJpaTest
@Import({TestcontainersConfiguration.class, StepClockConfig.class, ExtractionResultServiceImpl.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class ExtractionResultServiceTests {

    @Autowired
    private ExtractionResultService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private JsonMapper jsonMapper;

    @Autowired
    private StepClock clock;

    private ProcessingFixtures fixtures;
    private UUID document;
    private UUID payStubs;

    @Autowired
    private EntityManager entityManager;

    /** Writes pending changes and forgets every entity, so the next read comes from Postgres, not Hibernate's cache. */
    private void reread() {
        entityManager.flush();
        entityManager.clear();
    }

    @BeforeEach
    void setUp() {
        clock.reset();
        fixtures = new ProcessingFixtures(jdbc);
        document = fixtures.document();
        payStubs = fixtures.type("Pay stubs");
    }

    private NewExtractionResult result(String summary) {
        return new NewExtractionResult(
                payStubs, 1, jsonMapper.readTree("{\"employerName\":\"Acme\",\"payDate\":\"2026-10-31\"}"), summary);
    }

    @Test
    void resultReadsBackWithKeysInSavedOrder() {
        service.save(document, result("October pay stub"));

        reread();

        assertThat(service.find(document)).hasValueSatisfying(found -> {
            assertThat(found.documentId()).isEqualTo(document);
            assertThat(found.documentTypeId()).isEqualTo(payStubs);
            assertThat(found.schemaVersion()).isEqualTo(1);
            assertThat(found.data().propertyNames()).containsExactly("employerName", "payDate");
            assertThat(found.summary()).isEqualTo("October pay stub");
            assertThat(found.createdAt()).isEqualTo(StepClock.START);
        });
    }

    /** Commits for real: the refused insert aborts its transaction, so the check afterwards needs a fresh one. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void secondResultIsRefusedAndTheFirstKept() {
        try {
            service.save(document, result("first"));

            assertThatThrownBy(() -> service.save(document, result("second")))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(service.find(document))
                    .hasValueSatisfying(r -> assertThat(r.summary()).isEqualTo("first"));
        } finally {
            fixtures.deleteBatch(fixtures.batchOf(document));
        }
    }

    @Test
    void resultWithoutDataIsRefused() {
        assertThatThrownBy(() -> service.save(document, new NewExtractionResult(payStubs, 1, null, "s")))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void unextractedDocumentHasNoResult() {
        assertThat(service.find(document)).isEmpty();
    }
}
