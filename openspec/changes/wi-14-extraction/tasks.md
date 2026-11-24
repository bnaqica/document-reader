# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-14 as one commit.

## 1. Validation and records

- [x] 1.1 Write `processors/DataSchemaValidatorTests` (design decision 5). Watch them fail, then implement
  `DataSchemaValidator` (design decision 1).
- [x] 1.2 Add `markTruncatedSetsTheFlag()` to `DocumentServiceTests` first. Then add `DocumentService.markTruncated` and
  `Document.markTruncated()`.

## 2. The extraction step

- [x] 2.1 Add the `service/ExtractionService` interface (`extract(UUID documentId)`). This is checked by compiling.
- [x] 2.2 Write the first `service/ExtractionServiceTests`:
  - `threeInitialPassesThenConsolidateThenFinal()`: 3 `INITIAL`, 1 `CONSOLIDATED` and 1 `FINAL`, in that order; the
    result saved; the document `COMPLETED`;
  - `finalReceivesDraftAndImages()`;
  - `callsRunOutsideTransactions()`;
  - `completedDocumentRunsCompletionCheck()`: `onDocumentFinished` is called once, inside a transaction where the
    result is already visible.

  Watch them fail. Implement steps 1 to 6 of design decision 2, without the retry of step 5, and watch them pass.
- [x] 2.3 Add these tests first, then the corrective retry:
  - `invalidFinalRetriedOnceWithErrors()`;
  - `invalidTwiceThrows()`: no result, and the document stays `EXTRACTING`.
- [x] 2.4 Add these tests first, then the code for them:
  - `longDocumentTruncatedAt20Pages()`: 25 pages means each call that sends pages gets the first 20, and the document
    is `truncated`;
  - `retryReusesSavedPasses()`: a pass that fails on the first attempt leaves two saved, and the retry makes one
    extraction call.
- [x] 2.5 Add these tests first, then the code for them:
  - `idempotentWhenResultExists()`;
  - `documentFailedWhileExtractingGetsNoResult()`.

## 3. Inbox handler and failure

- [x] 3.1 Add these tests first, then the `InboxMessageHandler` side (design decision 3):
  - `handleExtractsAndMarksMessageDone()`: `type()` is `EXTRACT_DOCUMENT`;
  - `exhaustedFailsDocumentAndRunsCompletionCheck()`: `FAILED` with "Extraction failed", and `onDocumentFinished`
    called;
  - `exhaustedLeavesCompletedDocumentAlone()`.

## 4. Docs and verification

- [x] 4.1 Update `README.md` with how a document is extracted: the three stages, truncation, the corrective retry,
  completion, and the failure path.
- [x] 4.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the WI-01
  to WI-13 tests.
- [x] 4.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with
  `openspec/changes/wi-14-extraction/` as the requirements. Fix every Critical and Important finding, and verify the
  build again.
- [x] 4.4 Tick the boxes in `docs/workitems/WI-14-extraction.md` (except the developer review), set its status, and
  update its row in `docs/workitems/README.md`.

## Workflow follow-up

- The developer reviews the diff and commits WI-14 as one commit.
- Then archive `wi-14-extraction`.
