# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-10 as one commit.

## 1. Contract

- [x] 1.1 Add `service/BatchCompletionService` with `onDocumentFinished(UUID documentId)`, `onFileFailed(UUID sourceFileId)` and `onBatchFailed(UUID batchId, String reason)`, each documented as running inside the caller's transaction, and `dto/event/DeliverWebhookPayload(UUID batchId)`. Delete `dto/event/package-info.java` once the package has a class. No behaviour; checked by compiling.

## 2. The completion check

- [x] 2.1 Write `service/BatchCompletionServiceTests` (design decision 5): `doesNotCompleteWhileAFileIsStillSeparating()`, `notLastDocumentDoesNothing()` (no notice, no outbox row) and `lastDocumentCompletesBatch()` (`COMPLETED` with a completion time). Verify they fail, add `BatchCompletionServiceImpl` with `onDocumentFinished`, the lock and the check (design decisions 1 and 2), then verify they pass.
- [x] 2.2 Add the status cases, test-first: `failedFileWithOtherDocumentsCompletedIsPartiallyCompleted()` (through `onFileFailed`), `failedDocumentBesideCompletedOnesIsPartiallyCompleted()`, `allFailedIsFailed()` (reason "No document completed") and `everyFileFailedIsFailed()` (no documents at all).
- [x] 2.3 Add what completion does, test-first (design decision 3): `asyncBatchPublishesOneWebhook()` (one `DELIVER_WEBHOOK` outbox row for the batch, `webhookStatus` `PENDING`, one `batch_completed` notice after commit), `syncBatchPublishesNoWebhook()` (a notice, no outbox row, `webhookStatus` unchanged) and `rolledBackStepCompletesNothing()` (no notice, no outbox row, batch still `PROCESSING`).

## 3. Exactly once

- [x] 3.1 Add `lastDocumentsCompleteBatchOnce()` (design decision 4): two documents from different files finish in two threads at once, giving exactly one `batch_completed` notice and one `DELIVER_WEBHOOK` row. Add `alreadyCompletedBatchIsLeftAlone()` (a check on a `COMPLETED` batch changes nothing and sends nothing). Verify `lastDocumentsCompleteBatchOnce()` fails if the batch lock is replaced by a plain read, and `alreadyCompletedBatchIsLeftAlone()` fails if the early return for a final batch is removed, then that they pass.
- [x] 3.2 Add `calledOutsideTransactionThrows()` for all three methods (`IllegalTransactionStateException`, batch unchanged), relying on `Propagation.MANDATORY`.

## 4. Failing a whole batch

- [x] 4.1 Add `onBatchFailedSetsFailedAndNotifies()` (an async `RECEIVED` batch becomes `FAILED` with the given reason, one notice, one webhook row), `onBatchFailedLeavesCompletedBatchAlone()` and `onBatchFailedNeedsAReason()` (a blank reason gives `IllegalArgumentException`). Verify they fail, implement `onBatchFailed`, then verify they pass.

## 5. Docs and verification

- [x] 5.1 Update `README.md` with how a batch completes: when, the three final statuses, the notice, and the webhook message for async batches (delivered from WI-15).
- [x] 5.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the WI-01 to WI-09 tests.
- [x] 5.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with `openspec/changes/wi-10-batch-completion/` as the requirements. Fix every Critical and Important finding, and verify the build again.
- [x] 5.4 Tick the boxes in `docs/workitems/WI-10-batch-completion.md` (except the developer review and commit), set its status, and update its row in `docs/workitems/README.md`.

## Workflow follow-up

- The developer reviews the diff and commits WI-10 as one commit.
- Then archive `wi-10-batch-completion`.
