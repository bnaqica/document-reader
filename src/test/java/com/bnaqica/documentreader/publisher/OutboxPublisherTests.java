package com.bnaqica.documentreader.publisher;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.PgNotifications;
import com.bnaqica.documentreader.StepClockConfig;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.service.OutboxMessageServiceImpl;
import com.bnaqica.documentreader.service.WorkerSignalService;
import com.bnaqica.documentreader.service.WorkerSignalServiceImpl;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/** Commits for real, because NOTIFY is only delivered on commit. Cleans up its rows after each test. */
@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    StepClockConfig.class,
    OutboxPublisher.class,
    OutboxMessageServiceImpl.class,
    WorkerSignalServiceImpl.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxPublisherTests {

    private final UUID aggregate = UUID.randomUUID();

    @Autowired
    private OutboxPublisher publisher;

    @Autowired
    private WorkerSignalService signals;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @AfterEach
    void deleteRows() {
        jdbc.sql("DELETE FROM document_reader.outbox_message WHERE aggregate_id = ?")
                .param(aggregate)
                .update();
    }

    private int rows() {
        return jdbc.sql("SELECT count(*) FROM document_reader.outbox_message WHERE aggregate_id = ?")
                .param(aggregate)
                .query(Integer.class)
                .single();
    }

    private void publish() {
        publisher.publish(MessageType.CLASSIFY_DOCUMENT, aggregate, Map.of("documentId", aggregate.toString()));
    }

    @Test
    void publishRequiresTransaction() {
        assertThatThrownBy(this::publish).isInstanceOf(IllegalTransactionStateException.class);
        assertThat(rows()).isZero();
    }

    @Test
    void publishRollsBackWithCaller() throws Exception {
        try (PgNotifications notifications = new PgNotifications(dataSource, "outbox_ready")) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
                publish();
                status.setRollbackOnly();
            });

            assertThat(rows()).isZero();
            assertThat(notifications.receive(500)).isEmpty();
        }
    }

    @Test
    void commitStoresTheMessageAndNotifies() throws Exception {
        try (PgNotifications notifications = new PgNotifications(dataSource, "outbox_ready")) {
            new TransactionTemplate(transactionManager).executeWithoutResult(status -> publish());

            assertThat(notifications.receive(2000)).hasSize(1);
        }
        Map<String, Object> row = jdbc.sql("""
                        SELECT message_type, payload->>'documentId' AS document, dispatched_at
                        FROM document_reader.outbox_message WHERE aggregate_id = ?
                        """).param(aggregate).query().singleRow();
        assertThat(row)
                .containsEntry("message_type", "CLASSIFY_DOCUMENT")
                .containsEntry("document", aggregate.toString())
                .containsEntry("dispatched_at", null);
    }

    @Test
    void commitWakesTheDispatcher() {
        signals.awaitDispatcherSignal(Duration.ZERO); // drop any signal left by an earlier test

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> publish());

        assertThat(signals.awaitDispatcherSignal(Duration.ofMillis(100))).isTrue();
    }

    @Test
    void rollbackDoesNotWakeTheDispatcher() {
        signals.awaitDispatcherSignal(Duration.ZERO);

        new TransactionTemplate(transactionManager).executeWithoutResult(status -> {
            publish();
            status.setRollbackOnly();
        });

        assertThat(signals.awaitDispatcherSignal(Duration.ofMillis(100))).isFalse();
    }
}
