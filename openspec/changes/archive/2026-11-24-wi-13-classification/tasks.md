# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-13 as one commit.

## 1. Voting

- [x] 1.1 Write `processors/TypeVoteProcessorTests` (design decision 1):
  - `tallyPicksPlurality()`: Pay stubs ×4 and W-2 ×1 gives Pay stubs;
  - `tallyReportsTie()`: 2, 2 and 1 gives two tied candidates;
  - `byConfidencePicksHighestAverage()`;
  - `byConfidenceTieUsesSmallerKey()`.

  Watch them fail, then implement `TypeVoteProcessor`.

## 2. Record and catalog additions

- [x] 2.1 Add `DocumentServiceTests` cases first: `lockForUpdate` returns the document as it is now, and is refused
  outside a transaction. Then add `DocumentService.lockForUpdate` and its repository query.
- [x] 2.2 Add `createFromModelRecordsOriginAndDocument()` to `DocumentTypeServiceTests` first: the type has origin
  `MODEL`, the creating document, schema version 1, and its own committed transaction. A taken key throws
  `DocumentTypeKeyConflictException`. Then add `DocumentTypeService.createFromModel`.

## 3. New types

- [x] 3.1 Write `service/DocumentTypeCatalogServiceTests` (design decision 6):
  - `newNameConfirmedAsExistingBecomesAlias()`;
  - `newNameRejectedCreatesTypeWithDraftedSchema()`;
  - `nameAddedMeanwhileSkipsTheCalls()`;
  - `concurrentCreateOfSameNewTypeYieldsOneType()`.

  Watch them fail, then implement `DocumentTypeCatalogService` and its implementation (design decision 3).

## 4. The classification step

- [x] 4.1 Add `dto/event/ExtractDocumentPayload(UUID documentId)` and the `service/ClassificationService` interface
  (`classify(UUID documentId)`). These are checked by compiling.
- [x] 4.2 Write the first `service/ClassificationServiceTests`:
  - `savesResultWithAgreementAndPublishesExtract()`: 4 of 5 gives agreement 0.8 and `VOTE`, the document `CLASSIFIED`
    with its type, and one `EXTRACT_DOCUMENT` row;
  - `variantNamesCountAsOneType()`;
  - `emptyKeyAnswerIsFailedRun()` (Review Focus #3);
  - `sendsFirstMaxPagesOfTheDocument()`;
  - `callsRunOutsideTransactions()`.

  Watch them fail. Implement steps 1 to 5, the clear-vote path of step 6, and step 8 of design decision 2. Watch them
  pass.
- [x] 4.3 Add these tests first, then the tie-break and confidence rounds:
  - `tiebreakRestrictedToTiedTypes()`: three calls, each offered exactly the tied candidates;
  - `tiebreakWinnerWins()` (`TIEBREAK`);
  - `stillTiedUsesHighestAverageConfidence()`: three tied, tie-break votes 1, 1 and 1, so `CONFIDENCE`.
- [x] 4.4 Add these tests first, then the code for them:
  - `belowMinSuccessfulRunsThrows()`: the document stays `CLASSIFYING`, with no result;
  - `retryReusesSuccessfulRuns()`;
  - `newWinningNameIsCreatedAndAssigned()`: the new `MODEL` type is assigned, and the result has its id.
- [x] 4.5 Add these tests first, then the code for them:
  - `idempotentWhenResultExists()`: no call, and no second result or message;
  - `documentFailedWhileClassifyingGetsNoResult()`.

## 5. Inbox handler and failure

- [x] 5.1 Add these tests first, then the `InboxMessageHandler` side (design decision 4):
  - `handleClassifiesAndMarksMessageDone()`: `type()` is `CLASSIFY_DOCUMENT`;
  - `exhaustedFailsDocumentAndRunsCompletionCheck()`: `FAILED` with "Classification failed", and `onDocumentFinished`
    called;
  - `exhaustedLeavesClassifiedDocumentAlone()`.

## 6. The result envelope

- [x] 6.1 Add `votesForANewNameCountUnderItsTypeOrName()` to `BatchQueryServiceTests` first: a run with a null type id
  counts under the type its answer now resolves to, or else under its answer text. Then change
  `BatchQueryServiceImpl` (design decision 5).

## 7. Docs and verification

- [x] 7.1 Update `README.md` with how a document is classified: the runs, resolving names, the vote, the tie-break and
  confidence rounds, new types and aliases, and the failure path.
- [x] 7.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the WI-01
  to WI-12 tests.
- [x] 7.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with
  `openspec/changes/wi-13-classification/` as the requirements. Fix every Critical and Important finding, and verify
  the build again.
- [x] 7.4 Tick the boxes in `docs/workitems/WI-13-catalog-resolution-and-classification.md` (except the developer
  review), set its status, and update its row in `docs/workitems/README.md`.

## Workflow follow-up

- The developer reviews the diff and commits WI-13 as one commit.
- Then archive `wi-13-classification`.
