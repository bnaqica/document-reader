package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.StepClock;
import com.bnaqica.documentreader.StepClockConfig;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.NewClassificationResult;
import com.bnaqica.documentreader.entity.DecidedBy;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
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

@DataJpaTest
@Import({TestcontainersConfiguration.class, StepClockConfig.class, ClassificationResultServiceImpl.class})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class ClassificationResultServiceTests {

    @Autowired
    private ClassificationResultService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StepClock clock;

    private ProcessingFixtures fixtures;
    private UUID document;
    private UUID payStubs;
    private UUID w2;

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
        w2 = fixtures.type("W-2 forms");
    }

    private NewClassificationResult byVote(UUID type) {
        Map<UUID, Integer> votes = new LinkedHashMap<>();
        votes.put(payStubs, 4);
        votes.put(w2, 1);
        return new NewClassificationResult(type, votes, new BigDecimal("0.8"), false, DecidedBy.VOTE);
    }

    @Test
    void resultReadsBackWithItsVotes() {
        service.save(document, byVote(payStubs));

        reread();

        assertThat(service.find(document)).hasValueSatisfying(result -> {
            assertThat(result.documentId()).isEqualTo(document);
            assertThat(result.documentTypeId()).isEqualTo(payStubs);
            assertThat(result.votes()).containsExactly(Map.entry(payStubs, 4), Map.entry(w2, 1));
            assertThat(result.agreement()).isEqualByComparingTo("0.8");
            assertThat(result.tieBroken()).isFalse();
            assertThat(result.decidedBy()).isEqualTo(DecidedBy.VOTE);
            assertThat(result.decidedAt()).isEqualTo(StepClock.START);
        });
    }

    /** Commits for real: the refused insert aborts its transaction, so the check afterwards needs a fresh one. */
    @Test
    @Transactional(propagation = Propagation.NOT_SUPPORTED)
    void secondResultIsRefusedAndTheFirstKept() {
        try {
            service.save(document, byVote(payStubs));

            assertThatThrownBy(() -> service.save(document, byVote(w2)))
                    .isInstanceOf(DataIntegrityViolationException.class);
            assertThat(service.find(document))
                    .hasValueSatisfying(r -> assertThat(r.documentTypeId()).isEqualTo(payStubs));
        } finally {
            fixtures.deleteBatch(fixtures.batchOf(document));
        }
    }

    @Test
    void resultWithoutVotesIsRefused() {
        assertThatThrownBy(() -> service.save(
                        document, new NewClassificationResult(payStubs, null, BigDecimal.ONE, false, DecidedBy.VOTE)))
                .isInstanceOf(NullPointerException.class);
    }

    @Test
    void unclassifiedDocumentHasNoResult() {
        assertThat(service.find(document)).isEmpty();
    }
}
