# WI-13: Catalog resolution and classification (process 2)

| | |
|---|---|
| **Status** | Not started |
| **Depends on** | [WI-02](WI-02-reference-data.md), [WI-03](WI-03-core-records.md), [WI-04](WI-04-run-and-result-records-plus-the-openai-call-log.md), [WI-05](WI-05-inbox-outbox-engine.md), [WI-09](WI-09-vision-client.md), [WI-10](WI-10-batch-completion.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §10, §11 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 13 |

**Goal:** Classify each document by vote with a tie-break round, keeping near-duplicate type names together and creating new types safely.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `processors/TypeVoteProcessor`, `service/DocumentTypeCatalogService(Impl)`, `service/ClassificationService(Impl)`
- Test: `processors/TypeVoteProcessorTests`, `service/DocumentTypeCatalogServiceTests`, `service/ClassificationServiceTests`

**Interfaces:**
- Consumes: Work items WI-02, WI-03, WI-04, WI-05 and WI-09, and `BatchCompletionService.onDocumentFinished` (WI-10).
- Produces:
  - Resolution itself is `DocumentTypeService.resolve(String name)` from WI-02: empty means new or unusable. `DocumentTypeService.create` and `DocumentTypeAliasService.add` run in their own transactions, so after a `DocumentTypeKeyConflictException` the caller can re-resolve inside its own transaction.
  - `DocumentTypeCatalogService.createOrMatch(CallContext, List<PageImageRef>, String proposedName) → DocumentTypeResponse`: runs the confirmation call, then either adds an alias or drafts a schema and creates the type, losing races gracefully.
  - `TypeVoteProcessor.tally(List<ResolvedVote>) → VoteOutcome(Optional<UUID> winner, List<UUID> tied, Map<UUID, Long> counts)`
  - `TypeVoteProcessor.byConfidence(List<ResolvedVote> all, List<UUID> tied) → UUID`
  - `ClassificationService.classify(UUID documentId)`

The service implementation also implements `InboxMessageHandler` (WI-05) for its message type: `handle` calls the step, and `onExhausted` runs the failure path. Listeners follow `listener -> @Service to CRUD @Service interface -> ...` (`.claude/rules/service-architecture.md`): the inbox listener calls `InboxProcessingService`, which calls this service, which changes data only through CRUD services.

- [ ] **Step 1: Write the failing tests.**
  - `tallyPicksPlurality()`: Pay stubs ×4 and W-2 ×1 gives Pay stubs.
  - `tallyReportsTie()`: 2, 2 and 1 gives two tied types.
  - `tiebreakRestrictedToTiedTypes()`: the tiebreak prompt's candidates are exactly the tied types, and three calls are made.
  - `tiebreakWinnerWins()`: `decidedBy=TIEBREAK`.
  - `stillTiedUsesHighestAverageConfidence()`: three tied types and tiebreak votes of 1, 1 and 1 give `decidedBy=CONFIDENCE`.
  - `variantNamesCountAsOneType()`: "Paystub" ×2 and "Pay stubs" ×2 against W-2 ×1 gives Pay stubs with 4 votes.
  - Review Focus #3: `emptyKeyAnswerIsFailedRun()`. "N/A" is saved with `succeeded=false` and is not counted.
  - `belowMinSuccessfulRunsThrows()`
  - `newNameConfirmedAsExistingBecomesAlias()`
  - `newNameRejectedCreatesTypeWithDraftedSchema()`: `origin=MODEL` and `created_by_document_id` are set.
  - `concurrentCreateOfSameNewTypeYieldsOneType()`: two threads give one row, and both documents get the same id.
  - `savesResultWithAgreementAndPublishesExtract()`: an agreement of 0.8 for 4 of 5.
  - `idempotentWhenResultExists()`
  - `exhaustedFailsDocumentAndRunsCompletionCheck()`, which calls `BatchCompletionService.onDocumentFinished`.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** §11. Make the initial runs through a bounded virtual-thread executor (`classification.parallelism`). Resolve every answer before tallying. On `DocumentTypeKeyConflictException`, re-read by key. Save the result, set the status and publish `EXTRACT_DOCUMENT` in one transaction.
- [ ] **Step 4: Run** the tests and the build. Expected: PASS.

---

## Done when

- [ ] Every step above is ticked.
- [ ] `./gradlew spotlessApply build` passes, with all tests green.
- [ ] `git status` shows only the files this work item names, plus their tests and fixtures.
- [ ] A code review (`superpowers:requesting-code-review`, using the `code-reviewer` agent) has no open findings.
- [ ] The developer has reviewed the diff.

## Commit

Claude never commits in this repo (`.claude/rules/git-workflow.md`). The developer commits this work item as one
commit, with this suggested message:

```text
feat: add document classification with voting and tie-break
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
