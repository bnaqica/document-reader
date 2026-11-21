package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.StepClock;
import com.bnaqica.documentreader.StepClockConfig;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.NewSeparationRun;
import com.bnaqica.documentreader.dto.SeparationBoundary;
import jakarta.persistence.EntityManager;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;

@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    StepClockConfig.class,
    SeparationRunServiceImpl.class,
    SourceFileServiceImpl.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
class SeparationRunServiceTests {

    @Autowired
    private SeparationRunService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StepClock clock;

    private ProcessingFixtures fixtures;

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
    }

    private UUID file(int pages) {
        return fixtures.sourceFile(fixtures.batch("user-1"), pages);
    }

    private static NewSeparationRun run(int number, int start, int end) {
        return new NewSeparationRun(number, start, end, true, List.of(new SeparationBoundary(start, "Paystub")));
    }

    @Test
    void successfulRunReadsBackWithItsBoundaries() {
        UUID file = file(6);
        List<SeparationBoundary> boundaries =
                List.of(new SeparationBoundary(1, "Paystub"), new SeparationBoundary(3, "W-2"));

        service.save(file, new NewSeparationRun(1, 1, 6, true, boundaries));

        reread();

        assertThat(service.findBySourceFile(file)).singleElement().satisfies(run -> {
            assertThat(run.sourceFileId()).isEqualTo(file);
            assertThat(run.runNumber()).isEqualTo(1);
            assertThat(run.windowStart()).isEqualTo(1);
            assertThat(run.windowEnd()).isEqualTo(6);
            assertThat(run.succeeded()).isTrue();
            assertThat(run.boundaries()).containsExactlyElementsOf(boundaries);
            assertThat(run.createdAt()).isEqualTo(StepClock.START);
        });
    }

    @Test
    void failedRunHasNoBoundaries() {
        UUID file = file(6);

        service.save(file, new NewSeparationRun(2, 1, 6, false, null));

        reread();

        assertThat(service.findBySourceFile(file)).singleElement().satisfies(run -> {
            assertThat(run.succeeded()).isFalse();
            assertThat(run.boundaries()).isNull();
        });
    }

    @Test
    void runsListByWindowThenRunNumber() {
        UUID file = file(20);
        service.save(file, run(2, 14, 20));
        service.save(file, run(3, 1, 15));
        service.save(file, run(1, 14, 20));
        service.save(file, run(1, 1, 15));
        service.save(file, run(3, 14, 20));
        service.save(file, run(2, 1, 15));

        assertThat(service.findBySourceFile(file))
                .extracting(r -> r.windowStart() + ":" + r.runNumber())
                .containsExactly("1:1", "1:2", "1:3", "14:1", "14:2", "14:3");
    }

    @Test
    void windowPastTheLastPageIsRefused() {
        UUID file = file(6);

        assertThatThrownBy(() -> service.save(file, run(1, 5, 7))).isInstanceOf(IllegalArgumentException.class);
        assertThat(service.findBySourceFile(file)).isEmpty();
    }
}
