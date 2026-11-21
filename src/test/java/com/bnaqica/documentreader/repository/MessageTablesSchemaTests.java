package com.bnaqica.documentreader.repository;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;

/** What the database itself refuses for the outbox and inbox. Plain SQL. */
@DataJpaTest
@Import(TestcontainersConfiguration.class)
class MessageTablesSchemaTests {

    @Autowired
    private JdbcClient jdbc;

    private void outbox(String type) {
        jdbc.sql("""
                        INSERT INTO document_reader.outbox_message (id, message_type, aggregate_id, payload, created_at)
                        VALUES (?, ?, ?, '{}'::jsonb, now())
                        """).params(UUID.randomUUID(), type, UUID.randomUUID()).update();
    }

    private void inbox(String type, String status, int attempts) {
        jdbc.sql("""
                        INSERT INTO document_reader.inbox_message
                            (id, message_type, aggregate_id, payload, status, attempts, next_attempt_at, created_at)
                        VALUES (?, ?, ?, '{}'::jsonb, ?, ?, now(), now())
                        """)
                .params(UUID.randomUUID(), type, UUID.randomUUID(), status, attempts)
                .update();
    }

    @Test
    void outboxTypeMustBeOneOfTheFour() {
        outbox("SEPARATE_FILE");

        assertThatThrownBy(() -> outbox("PRINT_FILE")).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inboxTypeMustBeOneOfTheFour() {
        inbox("DELIVER_WEBHOOK", "PENDING", 0);

        assertThatThrownBy(() -> inbox("PRINT_FILE", "PENDING", 0)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void inboxStatusMustBeOneOfTheFour() {
        assertThatThrownBy(() -> inbox("SEPARATE_FILE", "PAUSED", 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void attemptsCannotBeNegative() {
        assertThatThrownBy(() -> inbox("SEPARATE_FILE", "PENDING", -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }
}
