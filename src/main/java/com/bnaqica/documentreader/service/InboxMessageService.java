package com.bnaqica.documentreader.service;

import com.bnaqica.documentreader.dto.response.InboxMessageRecord;
import com.bnaqica.documentreader.dto.response.OutboxMessageRecord;
import com.bnaqica.documentreader.entity.InboxStatus;
import com.bnaqica.documentreader.entity.MessageType;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** Inbox rows: inserted by the dispatcher, claimed by workers under a lease, then done, retried or failed. */
public interface InboxMessageService {

    /**
     * Copies outbox messages into the inbox as {@code PENDING}, due at {@code dueAt}, skipping ids already there, and
     * queues {@code NOTIFY inbox_ready} once per type inserted. Runs in the caller's transaction.
     *
     * @return the distinct types that were inserted
     */
    List<MessageType> insertIfAbsent(List<OutboxMessageRecord> messages, Instant dueAt);

    /**
     * Claims up to {@code limit} due messages of one type in its own transaction, which commits before this returns,
     * so no work runs inside it.
     */
    List<InboxMessageRecord> claimDue(MessageType type, int limit, String instanceId, Duration lease);

    /**
     * Must run inside the handler's final transaction. Does nothing when the message is already done, and never
     * revives a failed one.
     */
    void markDone(UUID id);

    /*
     * The methods below end or annotate one claim: they apply only while the message is still PROCESSING under that
     * claim (same holder, same attempt), and return false otherwise, for example when the handler already marked the
     * message done or another instance reclaimed it after the lease expired.
     */

    /** Back to {@code PENDING}, due at {@code nextAttemptAt}, with the error recorded. */
    boolean markRetry(InboxMessageRecord claim, String error, Instant nextAttemptAt);

    /** {@code FAILED} for good, with the error recorded. */
    boolean markFailed(InboxMessageRecord claim, String error);

    /** Back to {@code PENDING}, due now, without counting the attempt: for work cut off by shutdown. */
    boolean release(InboxMessageRecord claim);

    /** Records the error and keeps the claim. */
    boolean recordError(InboxMessageRecord claim, String error);

    InboxMessageRecord get(UUID id);

    /** Newest first; a null type or status matches any. */
    List<InboxMessageRecord> find(MessageType type, InboxStatus status, int limit);
}
