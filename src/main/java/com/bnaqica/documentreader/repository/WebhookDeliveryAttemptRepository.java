package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.WebhookDeliveryAttempt;
import java.util.List;
import java.util.UUID;
import org.springframework.data.jpa.repository.JpaRepository;

public interface WebhookDeliveryAttemptRepository extends JpaRepository<WebhookDeliveryAttempt, UUID> {

    List<WebhookDeliveryAttempt> findByBatchIdOrderByAttemptAsc(UUID batchId);
}
