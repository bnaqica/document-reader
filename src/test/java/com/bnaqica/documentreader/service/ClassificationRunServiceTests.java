package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.StepClock;
import com.bnaqica.documentreader.StepClockConfig;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.NewClassificationRun;
import com.bnaqica.documentreader.entity.ClassificationRound;
import jakarta.persistence.EntityManager;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@DataJpaTest
@Import({TestcontainersConfiguration.class, StepClockConfig.class, ClassificationRunServiceImpl.class})
class ClassificationRunServiceTests {

    @Autowired
    private ClassificationRunService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StepClock clock;

    private ProcessingFixtures fixtures;
    private UUID document;

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
    }

    private static NewClassificationRun run(ClassificationRound round, int number) {
        return new NewClassificationRun(round, number, "Pay stub", null, new BigDecimal("0.5"), "guess", true);
    }

    @Test
    void resolvedRunReadsBackWithItsAnswer() {
        UUID payStubs = fixtures.type("Pay stubs");

        service.save(
                document,
                new NewClassificationRun(
                        ClassificationRound.INITIAL,
                        1,
                        "Pay stub",
                        payStubs,
                        new BigDecimal("0.92"),
                        "Shows a pay period and net pay",
                        true));

        reread();

        assertThat(service.findByDocument(document)).singleElement().satisfies(run -> {
            assertThat(run.documentId()).isEqualTo(document);
            assertThat(run.round()).isEqualTo(ClassificationRound.INITIAL);
            assertThat(run.runNumber()).isEqualTo(1);
            assertThat(run.rawAnswer()).isEqualTo("Pay stub");
            assertThat(run.documentTypeId()).isEqualTo(payStubs);
            assertThat(run.confidence()).isEqualByComparingTo("0.92");
            assertThat(run.reasoning()).isEqualTo("Shows a pay period and net pay");
            assertThat(run.succeeded()).isTrue();
            assertThat(run.createdAt()).isEqualTo(StepClock.START);
        });
    }

    @Test
    void failedRunHasNoType() {
        service.save(document, new NewClassificationRun(ClassificationRound.INITIAL, 2, null, null, null, null, false));

        reread();

        assertThat(service.findByDocument(document)).singleElement().satisfies(run -> {
            assertThat(run.succeeded()).isFalse();
            assertThat(run.documentTypeId()).isNull();
            assertThat(run.confidence()).isNull();
        });
    }

    @Test
    void runsListByRoundThenRunNumber() {
        service.save(document, run(ClassificationRound.TIEBREAK, 2));
        service.save(document, run(ClassificationRound.INITIAL, 4));
        service.save(document, run(ClassificationRound.INITIAL, 1));
        service.save(document, run(ClassificationRound.TIEBREAK, 1));
        service.save(document, run(ClassificationRound.INITIAL, 5));
        service.save(document, run(ClassificationRound.INITIAL, 3));
        service.save(document, run(ClassificationRound.TIEBREAK, 3));
        service.save(document, run(ClassificationRound.INITIAL, 2));

        assertThat(service.findByDocument(document))
                .extracting(r -> r.round() + ":" + r.runNumber())
                .containsExactly(
                        "INITIAL:1",
                        "INITIAL:2",
                        "INITIAL:3",
                        "INITIAL:4",
                        "INITIAL:5",
                        "TIEBREAK:1",
                        "TIEBREAK:2",
                        "TIEBREAK:3");
    }

    @Test
    void duplicateRunIsRefused() {
        service.save(document, run(ClassificationRound.INITIAL, 3));

        assertThatThrownBy(() -> service.save(document, run(ClassificationRound.INITIAL, 3)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
