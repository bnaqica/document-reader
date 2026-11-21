# Proposal

## Why

WI-05 sends `NOTIFY outbox_ready` and `inbox_ready`, but nothing listens yet, so an instance only notices work
another instance published on its next poll. Sync callers also need a way to wait for their batch: the request blocks
until the batch completes or `wait.timeout` passes (§14 of `docs/design/2026-11-20-document-reader-design.md`), even
when another instance did the processing. This is WI-06 of `docs/workitems/`. It needs WI-03 and WI-05, and WI-10
(completion) and WI-11 (intake) build on it.

## What Changes

- **Listening:** each instance keeps one dedicated Postgres connection that `LISTEN`s on `outbox_ready`,
  `inbox_ready` and `batch_completed`, and reconnects with backoff if the connection drops.
  - `outbox_ready` wakes the dispatcher;
  - `inbox_ready` wakes that message type's worker;
  - `batch_completed` wakes whoever waits on that batch.
- **After a reconnect** every loop is woken once, because notifications sent while disconnected are lost.
- **Waiting for a batch:** `CompletionWaitService.await(batchId, timeout)` returns true as soon as the batch reaches a
  final status, by notification or by re-reading the status every `wait.poll-interval`, and false at the timeout. A
  waiter whose thread is interrupted (the caller went away) is removed quietly and doesn't affect processing.
- **Announcing completion:** `notifyBatchCompleted(batchId)` sends `NOTIFY batch_completed` inside the completing
  transaction, so waiters hear only of committed completions.
- **Switch:** the listener runs only when `app.document-reader.workers.enabled` is true, like the other background
  loops; waits still work by polling when it's off.

## Capabilities

### New Capabilities

- `batch-completion-wait`: waiting for a batch to reach a final status, by notification or polling, with a timeout,
  and announcing a completion.

### Modified Capabilities

- `message-processing`: adds that a notification wakes the dispatcher or a worker on every instance, and that a
  reconnect wakes them all. Written as ADDED; the existing "Workers wake on a signal or a poll" requirement stays.
- `processing-configuration`: "Worker settings have defaults" now also covers the notification listener.

## Impact

- **Database:** no migration. One extra connection per instance, outside the pool.
- **API:** none yet; WI-11 calls `await` from `POST /batches`.
- **Code:**
  - `config/NotificationConnectionConfig`, `listener/PgNotificationListener`;
  - `service/PgNotificationService(Impl)`, which routes each notification;
  - `service/CompletionWaitService(Impl)`;
  - `BatchService.notifyCompleted`, which runs the `pg_notify` through the batch repository;
  - `WorkerSignalService.wakeAll` (from WI-05) is reused after a reconnect.
- **Dependencies:** the Postgres driver moves from `runtimeOnly` to `implementation`, because the listener uses
  `PGConnection`.
