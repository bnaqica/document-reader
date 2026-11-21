package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.ProcessingFixtures;
import com.bnaqica.documentreader.StepClock;
import com.bnaqica.documentreader.StepClockConfig;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.dto.response.WebhookAttemptRecord;
import jakarta.persistence.EntityManager;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

@DataJpaTest
@Import({TestcontainersConfiguration.class, StepClockConfig.class, WebhookDeliveryAttemptServiceImpl.class})
class WebhookDeliveryAttemptServiceTests {

    @Autowired
    private WebhookDeliveryAttemptService service;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private StepClock clock;

    private UUID batch;

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
        batch = new ProcessingFixtures(jdbc).batch("user-1");
    }

    @Test
    void attemptsListInAttemptOrder() {
        service.record(batch, 2, 200, null);
        service.record(batch, 1, null, "timeout");

        reread();

        assertThat(service.findByBatch(batch))
                .containsExactly(
                        new WebhookAttemptRecord(batch, 1, null, "timeout", StepClock.START.plusSeconds(1)),
                        new WebhookAttemptRecord(batch, 2, 200, null, StepClock.START));
    }

    @Test
    void duplicateAttemptIsRefused() {
        service.record(batch, 1, 500, "server error");

        assertThatThrownBy(() -> service.record(batch, 1, 200, null))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
