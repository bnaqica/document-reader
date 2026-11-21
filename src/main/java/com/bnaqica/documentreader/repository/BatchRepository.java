package com.bnaqica.documentreader.repository;

import com.bnaqica.documentreader.entity.Batch;
import jakarta.persistence.LockModeType;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.data.domain.Limit;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface BatchRepository extends JpaRepository<Batch, UUID> {

    /** Newest first; batches received at the same moment by id, so a limit always cuts the same ones. */
    List<Batch> findByRequestedByOrderByReceivedAtDescIdDesc(String requestedBy, Limit limit);

    List<Batch> findAllByOrderByReceivedAtDescIdDesc(Limit limit);

    /** {@code SELECT … FOR UPDATE}: a second locker waits until this transaction ends. */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT b FROM Batch b WHERE b.id = :id")
    Optional<Batch> findByIdForUpdate(@Param("id") UUID id);

    /** Postgres delivers a NOTIFY only when the transaction commits, and drops it on rollback. */
    @Query(value = "SELECT 1 FROM (SELECT pg_notify('batch_completed', CAST(:id AS text))) AS sent", nativeQuery = true)
    int notifyCompleted(@Param("id") UUID id);
}
