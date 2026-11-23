# Design

## Context

Everything the check needs already exists:
- **WI-03 `BatchService`:**
  - `lockForUpdate` takes `SELECT … FOR UPDATE` and must be the first read of the batch in its transaction;
  - `complete` records the final status, the reason (kept only with `FAILED`) and the time, and refuses a second
    completion;
  - `updateWebhookStatus`.
- **WI-03 records:** `SourceFileService.findByBatch`, `SourceFileService.get` and `DocumentService.findByBatch` /
  `get` read them. `DocumentStatus.isFinished()` and `BatchStatus.isFinal()` already say "done" for documents and
  batches.
- **WI-05 `OutboxPublisher.publish(type, aggregateId, payload)`:** runs with `Propagation.MANDATORY`, and the payload is
  turned into JSON.
- **WI-06 `CompletionWaitService.notifyBatchCompleted`:** queues `NOTIFY batch_completed` in the caller's transaction
  and wakes local waiters after commit.
- **WI-05 inbox:** a `DELIVER_WEBHOOK` message with no handler stays `PENDING`, which is what we want until WI-15.
- `BatchLockTests` already proves that a second locker waits and then sees the first's changes.

The callers set the document's or file's own status first, in the same transaction, and then call the check (WI-12
to WI-14). The check never changes a file or document. See `proposal.md` for why.

## Goals / Non-Goals

**Goals:**
- `BatchCompletionService` with `onDocumentFinished(documentId)`, `onFileFailed(sourceFileId)` and
  `onBatchFailed(batchId, reason)`, as the work item names them.
- Exactly one completion per batch under concurrency and retries.

**Non-Goals:**
- Setting file or document statuses. The callers do that.
- Moving a batch from `RECEIVED` to `PROCESSING`. WI-11 and WI-12 own that.
- Delivering the webhook or building its body. WI-15 reads the batch when it delivers.
- Cancelling the other files' or documents' work after `onBatchFailed`. Their steps will find the batch already
  complete, and the check then does nothing.

## Decisions

### 1. One service, one private check

- `service/BatchCompletionService` (interface) and `service/BatchCompletionServiceImpl`.
  - The impl is an action service: it changes data only through the CRUD service interfaces and the publisher, and
    touches no repository.
  - It is annotated `@Transactional(propagation = Propagation.MANDATORY)`, so a call outside a transaction throws
    `IllegalTransactionStateException` before anything is read.
- `onDocumentFinished(documentId)` and `onFileFailed(sourceFileId)` look up the batch id, through
  `DocumentService.get(...).batchId()` and `SourceFileService.get(...).batchId()`. Both then run the same private
  `check(batchId)`.
- **What `check` does:**
  1. `lockForUpdate(batchId)`. If the batch is already final, it returns: a retried step, or one that finished after
     `onBatchFailed`.
  2. It reads `findByBatch` for files and for documents.
  3. If any file isn't `SEPARATED` or `FAILED`, or any document isn't finished, it returns.
  4. Otherwise it chooses the status (decision 2) and calls `finish` (decision 3).
- **What `onBatchFailed(batchId, reason)` does:** it locks the batch, returns if it is already final, and otherwise
  calls `finish(batch, FAILED, reason)`. A blank reason is refused with `IllegalArgumentException`, because a `FAILED`
  batch must say why.

The document and file look-ups come before the lock, but they read the document or file, not the batch. So the rule
that the lock is the first read of the batch still holds.

*Alternative:* one public `check(batchId)` that callers use directly. The work item names the two entry points, and
taking a document or file id keeps callers from passing the wrong batch.

### 2. Choosing the status

- **`FAILED`** with the reason "No document completed" when no document is `COMPLETED`. This includes a batch whose
  files all failed before any document was found.
- **`COMPLETED`** when every file is `SEPARATED` and every document is `COMPLETED`.
- **`PARTIALLY_COMPLETED`** otherwise.

The order matters for a batch with no documents: it is `FAILED`, never `COMPLETED`. This is a small, pure function
of the two lists, kept private in the impl.

### 3. Finishing a batch

`finish(batch, status, reason)` does the following in order:
1. `BatchService.complete(id, status, reason, clock.instant())`, using the injected `Clock`;
2. `CompletionWaitService.notifyBatchCompleted(id)`;
3. for an async batch only, `BatchService.updateWebhookStatus(id, PENDING)` and then
   `OutboxPublisher.publish(DELIVER_WEBHOOK, id, new DeliverWebhookPayload(id))`.

It logs at `info` with the batch id, the final status and the file and document counts, and nothing else.

`dto/event/DeliverWebhookPayload(UUID batchId)` is the payload. The batch id is also the aggregate id. WI-15 reads the
batch, its files and its documents when it delivers, so the result is never copied into the outbox.

### 4. Concurrency

The batch row lock serialises every check on a batch. The second check waits for the first transaction to end, and
then sees its committed changes, because every `findByBatch` is a new query under read committed. So when the last two
documents finish together:
- one transaction completes the batch;
- the other finds it final and returns.

**Stale reads:** a `findByBatch` query can hand back managed entities the transaction loaded earlier, without their
committed changes. In practice the caller has loaded only its own document or file, which is current. The check reads
no other document before it takes the lock.

### 5. Tests

`service/BatchCompletionServiceTests` is a `@DataJpaTest` against Testcontainers Postgres, wired like `BatchLockTests`:
- `@Transactional(propagation = NOT_SUPPORTED)`, so each test runs real transactions through a `TransactionTemplate`;
- it imports the real `BatchServiceImpl`, `SourceFileServiceImpl`, `DocumentServiceImpl`,
  `CompletionWaitServiceImpl`, `OutboxMessageServiceImpl`, `OutboxPublisher`, `WorkerSignalServiceImpl` and the
  services those need;
- rows are created through the WI-03 services, and each test removes its own rows.

Notices are counted with `PgNotifications` on `batch_completed`, and webhook messages by counting
`outbox_message` rows of type `DELIVER_WEBHOOK` for the batch.

The concurrency test does the following:
1. it starts two threads, each finishing one document in its own transaction;
2. both threads wait on a latch after updating their document, so both reach the check together;
3. it asserts one notice and one outbox row.

## Risks / Trade-offs

- [The lock is held until the caller's transaction ends] → The callers' transactions are short and make no OpenAI
  calls (§13), so other documents of the batch wait only milliseconds.
- [A step that forgets to call the check leaves the batch `PROCESSING` forever] → WI-12 to WI-14 each pin the call
  with a test. A sweeper for stuck batches is out of scope.
- [`onBatchFailed` while other steps still run] → Their later checks find the batch final and do nothing, so the
  result never changes after it is announced. Their own records still update, so a later read can show a document
  completed in a `FAILED` batch. WI-11 calls `onBatchFailed` only before any message is published, so this doesn't
  happen today.

## Migration Plan

None. No schema change and no new settings.
