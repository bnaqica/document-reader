# Design

## Context

What already exists:
- **Catalog (WI-02):**
  - `TypeNameNormalizer.key(name)` gives an empty result for a name with no usable key, such as "N/A" or "—".
  - `DocumentTypeService.resolve(name)` matches by the type's key or an alias's key.
  - `catalog()` lists names and descriptions.
  - `create(request)` runs in `REQUIRES_NEW`. It creates only `MANUAL` types, and throws
    `DocumentTypeKeyConflictException` for a taken key (also after a lost insert race).
  - `DocumentTypeAliasService.add` runs in `REQUIRES_NEW` and throws the same exception.
  - `document_type.created_by_document_id` exists, but nothing sets it yet.
- **Records (WI-03, WI-04):**
  - `DocumentService.get`, `updateStatus` and `assignType`, and `SourceFilePageService.findBySourceFile`.
  - `ClassificationRunService.save` and `findByDocument`; runs are insert-only, unique per
    `(document_id, round, run_number)`.
  - `ClassificationResultService.save` and `find`; `document_id` is the primary key.
  - `NewClassificationResult(documentTypeId, votes Map<UUID,Integer>, agreement, tieBroken, decidedBy)`.
- **Messaging (WI-05):** `InboxMessageHandler`, `InboxMessageService.markDone` (MANDATORY), and `OutboxPublisher`.
- **Vision (WI-09):**
  - `classify(ctx, pages, catalog)` and `tiebreak(ctx, pages, candidates)` return
    `ClassificationAnswer(documentType, confidence, reasoning)`.
  - `confirmNewType(ctx, pages, name, catalog)` returns `TypeMatchAnswer(matchesExisting, existingName)`.
  - `draftSchema(ctx, pages, name)` returns a schema that already passed `DataSchemaRules`, or throws.
  - Every method throws `VisionException`, and `IllegalStateException` inside a transaction. Spring AI already retries
    timeouts and 5xx twice, so a `VisionException` means the run failed.
- **Completion (WI-10):** `BatchCompletionService.onDocumentFinished` runs in the caller's transaction. The document's
  status is set first.
- **The result envelope (WI-11):** it counts votes from successful runs by `documentTypeId`.
- **WI-12's review lesson:** an attempt whose lease expired can finish after the message was exhausted, so the final
  write must lock and re-check.

## Goals / Non-Goals

**Goals:** the delta specs, an idempotent step, exactly one type per new name, and no model call inside a transaction.

**Non-Goals:**
- extraction (WI-14);
- reviewing or editing model-created types (admins can, through the existing catalog API);
- tuning the temperature.

## Decisions

### 1. Votes are tallied by candidate key, not type id

The work item sketches `tally(List<ResolvedVote>) → VoteOutcome(Optional<UUID> winner, …)`. A name the catalog doesn't
have has no id until it wins, and creating a type for every losing name would fill the catalog with them.

So a candidate is a **name key**:
- the resolved type's `normalizedKey`;
- or, for an unresolved name, its own key.

The processor's types:
- `ResolvedVote(String candidate, double confidence)`
- `VoteOutcome(Optional<String> winner, List<String> tied, Map<String, Long> counts)`
- `tally(List<ResolvedVote>)`
- `byConfidence(List<ResolvedVote> all, List<String> tied) → String`: the highest average, with ties broken by the
  smaller key.

`TypeVoteProcessor` is a pure `@Component`. The service keeps a `Candidate(key, typeId or null, name)` per key, using
the first spelling seen for a new name.

### 2. The step (`ClassificationServiceImpl.classify` and `handle`)

**1. Check the document.**
- If it is past `CLASSIFYING` (`CLASSIFIED`, `EXTRACTING`, `COMPLETED`, `FAILED`), or a result exists, it logs at
  `debug` level and returns.
- Otherwise a `PENDING` document becomes `CLASSIFYING`.

**2. Pages.** Its file's recorded pages from `startPage` to `min(endPage, startPage + maxPages − 1)`, from WI-12.

**3. Context.**
- `CallContext(batchId, fileId, documentId, purposeDescription)`.
- The catalog is read once per attempt, and passed to classify and confirm.

**4. The first round.**
- It reads the saved `INITIAL` runs and makes `runs − successful` calls. Run numbers come after the highest saved one.
- The calls run on virtual threads, limited by a `Semaphore(parallelism)`.
- Each answer is resolved right away: no key means a failed run; a resolved name records its type id; an unresolved
  name gives a successful run with a null type id. The run is saved in its own transaction. A `VisionException` saves
  a failed run (`rawAnswer` null).
- `rawAnswer` is the answer's `documentType` text. It is the only part kept besides confidence and reasoning, as the
  table defines.

**5. The minimum.**
- The vote uses the successful `INITIAL` runs (at most `runs`, the lowest run numbers), each re-resolved by its saved
  `rawAnswer`.
- Re-resolving means a name another document has since added counts as that type.
- Fewer than `minSuccessfulRuns` throws `IllegalStateException("Too few successful classification runs …")`.

**6. The tally.**
- A single winner means `VOTE`.
- On a tie, it makes the `TIEBREAK` runs the same way, offering `CatalogEntry`s for the tied candidates only. An
  unresolved candidate is offered with the description "A kind of document not in the catalog yet".
- An answer whose key isn't one of the tied keys is a failed run.
- A single tie-break winner means `TIEBREAK`. Otherwise (tied, or no successful runs), `byConfidence` over every
  counted run of both rounds means `CONFIDENCE`.

**7. Winner to type.**
- A resolved winner is its type.
- An unresolved winner goes through `DocumentTypeCatalogService.createOrMatch` (decision 3), outside any transaction.

**8. The final transaction:**
1. `DocumentService.lockForUpdate(documentId)` (new, `SELECT … FOR UPDATE`, MANDATORY). Unless the document is still
   `CLASSIFYING`, it logs and saves nothing.
2. `ClassificationResultService.save`:
   - votes are the first-round counts by type id; a losing new name has no id and is left out;
   - agreement is winner votes ÷ counted first-round runs, as `BigDecimal` with scale 3 (the column is `NUMERIC(4, 3)`), `HALF_UP`;
   - `tieBroken` and `decidedBy` as decided.
3. `assignType`, and `updateStatus(CLASSIFIED)`.
4. `publish(EXTRACT_DOCUMENT, documentId, new ExtractDocumentPayload(documentId))`.
5. `markDone(messageId)`, when called from `handle`.

The result's primary key also refuses a duplicate.

**Assumption:** agreement divides by the runs counted (the successful ones), not by `classification.runs`. Five
successful runs voting 4 to 1 give 0.8, as the work item expects. With one failed run, 3 of 4 gives 0.75, and the
failure doesn't make the answer look weaker.

### 3. `DocumentTypeCatalogService.createOrMatch(ctx, pages, proposedName)`

An action service over the catalog CRUD services. It runs with no transaction open, because it calls the model.

1. **Re-resolve first.** If another document added the name meanwhile, it returns that type with no call.
2. **Confirm.** `confirmNewType(ctx, pages, name, catalog)`. If the answer matches an existing name that resolves,
   it calls `DocumentTypeAliasService.add(type, name)` and returns the type.
   - On `DocumentTypeKeyConflictException` (the name was taken meanwhile), it returns `resolve(name)` if that's
     present, otherwise the matched type.
   - A match naming a type that doesn't resolve is treated as "no match".
3. **Create.** `draftSchema(ctx, pages, name)`, then the new
   `DocumentTypeService.createFromModel(new DocumentTypeRequest(name, DESCRIPTION, schema), ctx.documentId())`.
   - It runs in `REQUIRES_NEW`, with origin `MODEL`, `createdByDocumentId` set and schema version 1.
   - `DESCRIPTION` is "Named by the model during classification; not yet reviewed", because the reasoning text can
     quote borrower data.
   - On `DocumentTypeKeyConflictException` (another document won the insert), it returns
     `resolve(name).orElseThrow()`. The race goes to the database's unique key (§10.2.5).

A `VisionException` from confirm or draft propagates, so the inbox retries. The saved runs make that retry cheap.

### 4. The exhausted path

`onExhausted` runs inside the inbox's `FAILED` transaction:
- It calls `DocumentService.lockForUpdate`. If the document is past `CLASSIFYING` (`CLASSIFIED` or later, or already
  `FAILED`), it returns.
- Otherwise it calls `updateStatus(FAILED, "Classification failed")`, then
  `BatchCompletionService.onDocumentFinished(documentId)`.

The lock is the first read of the document in that transaction, which follows WI-10's rule.

### 5. The envelope counts votes for new names

`BatchQueryServiceImpl.votes` names a successful run with a null `documentTypeId` by
`DocumentTypeService.resolve(rawAnswer)`. That's the type's name if it now resolves, for example as the type the
model created or as an alias, and otherwise the trimmed `rawAnswer`. A null `rawAnswer` is skipped. Resolving happens
once per distinct answer in a query.

### 6. Tests

- **`processors/TypeVoteProcessorTests`:** `tallyPicksPlurality`, `tallyReportsTie`,
  `byConfidencePicksHighestAverage`, `byConfidenceTieUsesSmallerKey`.
- **`service/DocumentTypeCatalogServiceTests`:**
  - `@DataJpaTest`, non-transactional, with real catalog services and `VisionClient` mocked; `CatalogCleanup`
    afterwards.
  - The tests: `newNameConfirmedAsExistingBecomesAlias`, `newNameRejectedCreatesTypeWithDraftedSchema` (`MODEL`,
    `created_by_document_id`), `nameAddedMeanwhileSkipsTheCalls`, and
    `concurrentCreateOfSameNewTypeYieldsOneType` (two threads, a `CyclicBarrier` in the draft mock, one row, the same
    id for both).
- **`service/ClassificationServiceTests`:**
  - `@DataJpaTest`, non-transactional, with real CRUD, catalog and outbox services; `VisionClient` scripted;
    `BatchCompletionService` mocked.
  - The tests: `variantNamesCountAsOneType`, `emptyKeyAnswerIsFailedRun`, `belowMinSuccessfulRunsThrows`,
    `tiebreakRestrictedToTiedTypes`, `tiebreakWinnerWins`, `stillTiedUsesHighestAverageConfidence`,
    `savesResultWithAgreementAndPublishesExtract`, `newWinningNameIsCreatedAndAssigned`,
    `retryReusesSuccessfulRuns`, `sendsFirstMaxPagesOfTheDocument`, `callsRunOutsideTransactions`,
    `idempotentWhenResultExists`, `documentFailedWhileClassifyingGetsNoResult`, `handleClassifiesAndMarksMessageDone`,
    `exhaustedFailsDocumentAndRunsCompletionCheck` and `exhaustedLeavesClassifiedDocumentAlone`.
- **New cases in existing tests:**
  - `DocumentTypeServiceTests`: `createFromModelRecordsOriginAndDocument`;
  - `DocumentServiceTests`: `lockForUpdate…`;
  - `BatchQueryServiceTests`: `votesForANewNameCountUnderItsTypeOrName`.

### 7. Fixes from the code review

- **Agreement uses the merged count.** A winning new name that the model confirms as an existing type is merged with
  that type's own votes, and agreement is computed from the merged count. Otherwise "Earnings statement" 3 plus Pay
  stubs 2 would be stored as Pay stubs at 0.6.
- **Over-long names are failed runs.** `document_type.name` and `alias` are `VARCHAR(200)`, so an answer longer than
  200 characters could never be saved. Without this, it would retry to exhaustion, paying for confirm and draft calls
  each time.
- **Saved runs keep their type.** A run saved with a type id is counted as that type on a retry (`documentTypes.get`),
  even if its answer no longer resolves after an admin renamed the type. Only runs saved with no type id are
  re-resolved.
- **Starting is locked too.** `PENDING` to `CLASSIFYING` runs under `lockForUpdate`, so an attempt that read the
  document before the message was exhausted can't overwrite the failure.
- **Not changed:**
  - a tie-break retry whose tied set changed between attempts (rare; the result still comes from saved runs);
  - one `resolve` query per distinct new-name answer in the envelope.

## Risks / Trade-offs

- **[Lease]** 5 + 3 calls plus confirm and draft take well under the 10-minute lease. A reclaim reuses the saved runs.
- **[Runs collide when attempts overlap]** As in WI-12, the lock settles the outcome, and a duplicate run number only
  fails the slower attempt.
- **[Model-created types are unreviewed]** They are flagged `newType` in the envelope, and admins can edit them
  through the catalog API.
- **[A drafted schema is refused]** `draftSchema` throws, so the attempt is retried, up to exhaustion. The document
  then fails. That's the right outcome for a type the model can't describe.

## Open Questions

None. The choices above are left to the builder by §11 [builder] and §21, or follow the work item.
