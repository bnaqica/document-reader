package com.bnaqica.documentreader.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.bnaqica.documentreader.TestcontainersConfiguration;
import com.bnaqica.documentreader.config.ClockConfig;
import com.bnaqica.documentreader.dto.NewBatch;
import com.bnaqica.documentreader.dto.response.BatchRecord;
import com.bnaqica.documentreader.entity.BatchStatus;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.IllegalTransactionStateException;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Completion (WI-10) locks the batch so exactly one transaction completes it. These run outside the test transaction,
 * because the two lockers need real, separate transactions. They remove their own rows.
 */
@DataJpaTest
@Transactional(propagation = Propagation.NOT_SUPPORTED)
@Import({TestcontainersConfiguration.class, ClockConfig.class, BatchServiceImpl.class, LoanPurposeServiceImpl.class})
class BatchLockTests {

    @Autowired
    private BatchService service;

    @Autowired
    private PlatformTransactionManager transactionManager;

    @Autowired
    private JdbcClient jdbc;

    @AfterEach
    void removeBatches() {
        jdbc.sql("DELETE FROM document_reader.batch").update();
    }

    @Test
    void secondLockerWaitsForTheFirstAndSeesItsChange() throws Exception {
        UUID id = service.create(new NewBatch("CAR_REFINANCE", false, null, "user-1", 1))
                .id();
        TransactionTemplate transaction = new TransactionTemplate(transactionManager);
        CountDownLatch locked = new CountDownLatch(1);

        try (ExecutorService first = Executors.newSingleThreadExecutor()) {
            Future<?> holder = first.submit(() -> transaction.executeWithoutResult(status -> {
                service.lockForUpdate(id);
                service.updateStatus(id, BatchStatus.PROCESSING, null);
                locked.countDown();
                sleep(Duration.ofSeconds(1));
            }));
            locked.await();

            long start = System.nanoTime();
            BatchRecord seen = transaction.execute(status -> service.lockForUpdate(id));
            Duration waited = Duration.ofNanos(System.nanoTime() - start);
            holder.get();

            assertThat(waited).isGreaterThanOrEqualTo(Duration.ofMillis(900));
            assertThat(seen.status()).isEqualTo(BatchStatus.PROCESSING);
        }
    }

    @Test
    void lockingOutsideATransactionIsRefused() {
        UUID id = service.create(new NewBatch("CAR_REFINANCE", false, null, "user-1", 1))
                .id();

        assertThatThrownBy(() -> service.lockForUpdate(id)).isInstanceOf(IllegalTransactionStateException.class);
    }

    private static void sleep(Duration duration) {
        try {
            Thread.sleep(duration);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}
