package com.bnaqica.documentreader.service;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Creating a document type or alias commits in its own transaction, so a test's rollback doesn't undo it. This
 * removes everything except the seeded catalog, in a transaction of its own that commits.
 */
final class CatalogCleanup {

    private CatalogCleanup() {}

    static void removeAllButSeeded(PlatformTransactionManager transactionManager, JdbcClient jdbc) {
        TransactionTemplate committed = new TransactionTemplate(transactionManager);
        committed.setPropagationBehavior(TransactionDefinition.PROPAGATION_REQUIRES_NEW);
        committed.executeWithoutResult(status -> {
            jdbc.sql("DELETE FROM document_reader.document_type_alias").update();
            jdbc.sql("DELETE FROM document_reader.document_type WHERE origin <> 'SEEDED'")
                    .update();
        });
    }
}
