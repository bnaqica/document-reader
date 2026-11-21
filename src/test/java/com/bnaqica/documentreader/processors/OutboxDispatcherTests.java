package com.bnaqica.documentreader.processors;

import static org.assertj.core.api.Assertions.assertThat;

import com.bnaqica.documentreader.MutableClockConfig;
import com.bnaqica.documentreader.PgNotifications;
import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.entity.MessageType;
import com.bnaqica.documentreader.service.InboxMessageServiceImpl;
import com.bnaqica.documentreader.service.OutboxMessageServiceImpl;
import com.bnaqica.documentreader.service.WorkerSignalService;
import com.bnaqica.documentreader.service.WorkerSignalServiceImpl;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import javax.sql.DataSource;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.ImportAutoConfiguration;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jackson.autoconfigure.JacksonAutoConfiguration;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

/** Commits for real: dispatch must hold across concurrent transactions. Every queue row is removed around each test. */
@DataJpaTest
@Import({
    TestcontainersConfiguration.class,
    MutableClockConfig.class,
    OutboxDispatcher.class,
    OutboxMessageServiceImpl.class,
    InboxMessageServiceImpl.class,
    WorkerSignalServiceImpl.class
})
@ImportAutoConfiguration(JacksonAutoConfiguration.class)
@Transactional(propagation = Propagation.NOT_SUPPORTED)
class OutboxDispatcherTests {

    @Autowired
    private OutboxDispatcher dispatcher;

    @Autowired
    private WorkerSignalService signals;

    @Autowired
    private JdbcClient jdbc;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    @AfterEach
    void emptyQueues() {
        jdbc.sql("DELETE FROM document_reader.inbox_message").update();
        jdbc.sql("DELETE FROM document_reader.outbox_message").update();
    }

    private UUID outbox(MessageType type) {
        UUID id = UUID.randomUUID();
        jdbc.sql("""
                        INSERT INTO document_reader.outbox_message (id, message_type, aggregate_id, payload, created_at)
                        VALUES (?, ?, ?, '{"documentId":"d1"}'::jsonb, now())
                        """).params(id, type.name(), UUID.randomUUID()).update();
        return id;
    }

    private int count(String sql) {
        return jdbc.sql(sql).query(Integer.class).single();
    }

    @Test
    void dispatcherMovesOnceAcrossTwoDispatchers() throws Exception {
        for (int i = 0; i < 100; i++) {
            outbox(MessageType.CLASSIFY_DOCUMENT);
        }
        CountDownLatch start = new CountDownLatch(1);
        Callable<Integer> dispatch = () -> {
            start.await();
            return dispatcher.dispatchPending();
        };

        try (ExecutorService threads = Executors.newFixedThreadPool(2)) {
            Future<Integer> first = threads.submit(dispatch);
            Future<Integer> second = threads.submit(dispatch);
            start.countDown();

            assertThat(first.get() + second.get()).isEqualTo(100);
        }
        assertThat(count("SELECT count(*) FROM document_reader.inbox_message")).isEqualTo(100);
        assertThat(count("SELECT count(*) FROM document_reader.outbox_message WHERE dispatched_at IS NULL"))
                .isZero();
    }

    @Test
    void dispatcherIgnoresDuplicate() {
        UUID id = outbox(MessageType.SEPARATE_FILE);
        jdbc.sql("""
                        INSERT INTO document_reader.inbox_message
                            (id, message_type, aggregate_id, payload, status, attempts, next_attempt_at, created_at)
                        VALUES (?, 'SEPARATE_FILE', ?, '{}'::jsonb, 'DONE', 1, now(), now())
                        """).params(id, UUID.randomUUID()).update();

        dispatcher.dispatchPending();

        assertThat(count("SELECT count(*) FROM document_reader.inbox_message")).isEqualTo(1);
        assertThat(jdbc.sql("SELECT status FROM document_reader.inbox_message")
                        .query(String.class)
                        .single())
                .isEqualTo("DONE");
        assertThat(count("SELECT count(*) FROM document_reader.outbox_message WHERE dispatched_at IS NULL"))
                .isZero();
    }

    @Test
    void dispatchNotifiesAndSignalsTheType() throws Exception {
        outbox(MessageType.EXTRACT_DOCUMENT);
        signals.awaitSignal(MessageType.EXTRACT_DOCUMENT, Duration.ZERO); // drop a signal left by an earlier test

        try (PgNotifications notifications = new PgNotifications(dataSource, "inbox_ready")) {
            assertThat(dispatcher.dispatchPending()).isEqualTo(1);

            assertThat(notifications.receive(2000)).containsExactly("EXTRACT_DOCUMENT");
        }
        assertThat(signals.awaitSignal(MessageType.EXTRACT_DOCUMENT, Duration.ofMillis(100)))
                .isTrue();
        assertThat(signals.awaitSignal(MessageType.CLASSIFY_DOCUMENT, Duration.ofMillis(50)))
                .isFalse();
    }

    @Test
    void nothingToDispatchIsZero() {
        assertThat(dispatcher.dispatchPending()).isZero();
    }
}
