# Proposal

## Why

A batch fans out into one separation step per file and one classification and extraction step per document, which
run in parallel on any instance. Something has to notice when the last of them finishes, set the batch's final status
exactly once, wake any synchronous caller, and queue the webhook for an async one (§7.3 and §12 of
`docs/design/2026-11-20-document-reader-design.md`). Building it now, before separation, classification and extraction
(WI-12 to WI-14), lets each of those steps be committed with working failure paths. This is WI-10 of
`docs/workitems/`. It needs WI-03 (the records), WI-05 (the outbox) and WI-06 (the completion notice).

## What Changes

- **One completion check**, run by the step that finishes a document (completed or failed) or fails a file, inside
  that step's own transaction. It locks the batch, and once every file is separated or failed and every document is
  completed or failed, it completes the batch.
- **The final status** follows §7.3: `COMPLETED` when every file was separated and every document completed,
  `FAILED` when no document completed, and `PARTIALLY_COMPLETED` otherwise.
- **On completion**, the batch records its completion time, a `batch_completed` notice is sent on commit, and an
  async batch gets one `DELIVER_WEBHOOK` message with its webhook marked pending. A sync batch gets no webhook.
- **Failing a whole batch** (WI-11 uses this when a file can't be stored in S3) completes it as `FAILED` with a
  reason, with the same notice and webhook.
- **Exactly once**: two steps finishing the last documents at the same time complete the batch once. A check on a
  batch that is already complete does nothing, so a retried step is safe.
- The check refuses to run outside a transaction, so its lock and its changes always commit together.

## Capabilities

### New Capabilities

- `batch-completion`: when a batch is complete, which final status it gets, and what happens once it completes
  (notice, webhook), exactly once.

### Modified Capabilities

None. `processing-records` already covers completing a batch once and locking it, and `batch-completion-wait`
covers the notice. This change uses both without changing them.

## Impact

- **Code:** `service/BatchCompletionService` and `service/BatchCompletionServiceImpl`; the webhook message payload
  `dto/event/DeliverWebhookPayload`.
- **Tests:** `service/BatchCompletionServiceTests`, against Testcontainers Postgres.
- **Dependencies, schema, settings:** none new.
- **Later work items:** WI-11 calls `onBatchFailed`, WI-12 calls `onFileFailed`, WI-13 and WI-14 call
  `onDocumentFinished`. WI-15 handles the `DELIVER_WEBHOOK` messages; until then they stay `PENDING` in the inbox.
