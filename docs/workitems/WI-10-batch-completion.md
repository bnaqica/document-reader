# WI-10: Batch completion

| | |
|---|---|
| **Status** | Built, awaiting developer review |
| **Depends on** | [WI-03](WI-03-core-records.md), [WI-05](WI-05-inbox-outbox-engine.md), [WI-06](WI-06-notifications-and-wait-mode.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §7.3, §12 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 14 (the completion half, moved earlier) |

**Goal:** Build the check that completes a batch exactly once, when all of its files and documents have finished, so every later step can rely on it.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `service/BatchCompletionService(Impl)`
- Test: `service/BatchCompletionServiceTests`

**Interfaces:**
- Consumes:
  - from WI-03: `BatchService.lockForUpdate`, `BatchService.complete`, `BatchService.updateWebhookStatus`, `SourceFileService.findByBatch` and `DocumentService.findByBatch`
  - from WI-05: `OutboxPublisher.publish`
  - from WI-06: `CompletionWaitService.notifyBatchCompleted`
- Produces:
  - `BatchCompletionService.onDocumentFinished(UUID documentId)` and `onFileFailed(UUID sourceFileId)`. Both must run inside the caller's transaction, and both run the completion check:
    - lock the batch;
    - if every source file is `SEPARATED` or `FAILED` and every document is `COMPLETED` or `FAILED`, set the final status. All files separated and all documents completed → `COMPLETED`. No document completed → `FAILED`. Otherwise → `PARTIALLY_COMPLETED`.
    - then set `completedAt`, call `notifyBatchCompleted`, and publish `DELIVER_WEBHOOK` if the batch is async (setting `webhookStatus=PENDING`).
  - `BatchCompletionService.onBatchFailed(UUID batchId, String reason)`. WI-11 uses this when an S3 upload fails: it sets the batch `FAILED`, notifies, and publishes the webhook if the batch is async.

The completion check (design §12) is built here, ahead of the steps that call it. Separation (WI-12), classification (WI-13) and extraction (WI-14) can then each be committed with working failure paths. Until WI-15 lands, `DELIVER_WEBHOOK` messages stay in the inbox unprocessed, which is expected.

- [x] **Step 1: Write the failing tests.** Set up rows directly through the WI-03 services; no pipeline step is needed.
  - `lastDocumentsCompleteBatchOnce()`: two documents from different files finish concurrently in two threads, giving exactly one `batch_completed` notification and one webhook outbox row.
  - `doesNotCompleteWhileAFileIsStillSeparating()`: every document of file 0 is done, but file 1 is `SEPARATING`, so the batch stays `PROCESSING`.
  - `failedFileWithOtherDocumentsCompletedIsPartiallyCompleted()`
  - `allFailedIsFailed()`
  - `syncBatchPublishesNoWebhook()`
  - `notLastDocumentDoesNothing()`
  - `onBatchFailedSetsFailedAndNotifies()`
  - `calledOutsideTransactionThrows()`
- [x] **Step 2: Run** `./gradlew test --tests '*BatchCompletionServiceTests'`. Expected: FAIL.
- [x] **Step 3: Implement** the check as described in Interfaces. Use `Propagation.MANDATORY` so a caller outside a transaction fails fast.
- [x] **Step 4: Run** the tests, then `./gradlew spotlessApply build`. Expected: PASS.

---

## Done when

- [x] Every step above is ticked.
- [x] `./gradlew spotlessApply build` passes, with all tests green.
- [x] `git status` shows only the files this work item names, plus their tests and fixtures.
- [x] A code review (`superpowers:requesting-code-review`, using the `code-reviewer` agent) has no open findings.
- [ ] The developer has reviewed the diff.

## Commit

Claude never commits in this repo (`.claude/rules/git-workflow.md`). The developer commits this work item as one
commit, with this suggested message:

```text
feat: add batch completion check
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
