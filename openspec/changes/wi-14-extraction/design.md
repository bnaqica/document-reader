# Design

## Context

What already exists:
- **Records:**
  - `ExtractionRunService.save(documentId, NewExtractionRun(stage, passNumber, data, summary))`,
    `findByDocument` and `findByDocumentAndStage`. Runs are insert-only, unique per `(document_id, stage, pass_number)`,
    and `data` can't be null.
  - `ExtractionResultService.save` and `find`; `document_id` is the primary key.
  - `NewExtractionResult(documentTypeId, schemaVersion, data, summary)`.
  - `DocumentService`: `get`, `lockForUpdate` (WI-13), `updateStatus`. The `truncated` column exists, but nothing sets
    it.
- **The type:** `DocumentTypeService.get(id)` gives the `dataSchema` and `schemaVersion`. The type comes from the
  document's `documentTypeId`, which WI-13 sets.
- **Vision (WI-09):**
  - `extract(ctx, pages, schema)` and `finalizeExtraction(ctx, pages, schema, draft, validationErrors)` return
    `ExtractionAnswer(data, summary)`.
  - `consolidate(ctx, schema, passes)` returns `ConsolidatedAnswer(data, summary, disagreements)`. It sends no images.
  - Each throws `VisionException`, and `IllegalStateException` inside a transaction.
- **Pages:** `SourceFilePageService.findBySourceFile` gives the page keys (WI-12).
- **Validation library:** `com.networknt:json-schema-validator` 3.0.9 is a dependency already: `SchemaRegistry`,
  `Schema` and `SpecificationVersion`, on Jackson 3. Nothing uses it yet.
- **Completion (WI-10):** `BatchCompletionService.onDocumentFinished` runs inside the caller's transaction, after the
  status is set. The caller must not load the batch or its other documents first.

## Goals / Non-Goals

**Goals:**
- the delta spec;
- an idempotent, lock-guarded final write;
- no model call inside a transaction;
- no extracted value, summary or validation message in a log.

**Non-Goals:**
- webhook delivery (WI-15);
- re-extracting when a type's schema changes later.

## Decisions

### 1. `DataSchemaValidator`

`processors/DataSchemaValidator` is a `@Component`:
- `validate(JsonNode schema, JsonNode data) → List<String>` is empty when the data is valid.
- It builds a `Schema` from a `SchemaRegistry` for draft 2020-12, the dialect the catalog's strict schemas are written
  for, and returns each error message (location and message) in order.
- Null data gives one error: "data is missing".
- The schema is compiled on every call. Types are few and calls are rare, so YAGNI says no cache.

### 2. The step (`ExtractionServiceImpl.extract` and `handle`)

**1. Check the document.**
- If it is `COMPLETED` or `FAILED`, or a result exists, it logs at `debug` level and returns.
- A `CLASSIFIED` document moves to `EXTRACTING` under `lockForUpdate`, in a short transaction. It returns if the
  document is no longer `CLASSIFIED` or `EXTRACTING`, which is WI-13's review lesson.
- A document with no type (it shouldn't happen) throws `IllegalStateException`.

**2. Inputs.**
- The type's `dataSchema` and `schemaVersion`.
- The pages from `startPage` to `min(endPage, startPage + maxPages − 1)`. It is truncated when the document is longer.
- `CallContext(batchId, fileId, documentId, purpose)`.

**3. First passes.**
- It reads the saved `INITIAL` runs and makes `passes − saved` calls, concurrently on virtual threads. Pass numbers
  come after the highest saved one.
- Each answer is saved straight away as `INITIAL` (`data`, `summary`).
- A `VisionException` in any pass propagates once the others are done, so the inbox retries. The saved passes are
  reused next time. A pass only counts once its answer is saved.
- The first `passes` saved runs, by pass number, are the ones consolidated.

**4. Consolidation.**
- `consolidate(ctx, schema, passes)`, saved as `CONSOLIDATED` with the next pass number for that stage.
- A retry consolidates again rather than reusing a saved draft, because the run table can't hold the disagreements
  the final stage needs. It's a text-only call, so it's cheap.

**5. Final determination.**
- `finalizeExtraction(ctx, pages, schema, draft, List.of())`, then validate.
- If the data is invalid, it calls `finalizeExtraction` again with the errors, and validates again.
- If that is still invalid, it throws `IllegalStateException("Final extraction data failed validation twice …")`. The
  message names the document and the error count, never the errors themselves.
- Only a valid answer is saved, as `FINAL` with the next pass number.

**6. The final transaction:**
1. `lockForUpdate`. It goes on only while the document is `EXTRACTING`; otherwise it logs and saves nothing.
2. `ExtractionResultService.save(documentId, new NewExtractionResult(typeId, schemaVersion, data, summary))`.
3. `DocumentService.markTruncated(documentId)` when truncated. This is a new CRUD method; `Document` gets
   `markTruncated()`.
4. `updateStatus(COMPLETED)`.
5. `BatchCompletionService.onDocumentFinished(documentId)`.
6. `markDone(messageId)`, when called from `handle`.

The result's primary key also refuses a duplicate. The document lock is the first read of that document in the
transaction, and nothing else in the batch is loaded before the completion check, as WI-10 requires.

**Why the FINAL row is saved before the transaction:** it records what the model answered even if the final
transaction loses the race. A later retry then sees the result, or a `FAILED` document, and stops.

### 3. The exhausted path

`onExhausted` runs inside the inbox's `FAILED` transaction:
- It calls `lockForUpdate`. If the document is `COMPLETED` or `FAILED`, it returns.
- Otherwise it calls `updateStatus(FAILED, "Extraction failed")`, then `onDocumentFinished`.

The inbox's last error isn't copied into the reason.

### 4. Logging

Logs carry only ids, stage and pass numbers, page counts and error counts.
- `info`: passes saved, document completed.
- `warn`: invalid final data.
- `debug`: skipped work.

### 5. Tests

- **`processors/DataSchemaValidatorTests`:** valid data gives no errors. A missing required field, a wrong type and an
  extra property under `additionalProperties: false` each give an error naming the field. Null data gives an error.
- **`service/ExtractionServiceTests`:**
  - `@DataJpaTest`, non-transactional, with real CRUD services, the catalog, `DataSchemaValidator` and the outbox;
    `VisionClient` scripted; `BatchCompletionService` mocked, recording whether a transaction is active and whether
    the result is visible inside it.
  - The documents are `CLASSIFIED` as the seeded Pay stubs type, so the tests use its real schema. Answers are built
    to match it, or to miss a field.
  - The tests: `threeInitialPassesThenConsolidateThenFinal`, `finalReceivesDraftAndImages`,
    `invalidFinalRetriedOnceWithErrors`, `invalidTwiceThrows`, `longDocumentTruncatedAt20Pages`,
    `retryReusesSavedPasses`, `callsRunOutsideTransactions`, `completedDocumentRunsCompletionCheck`,
    `idempotentWhenResultExists`, `documentFailedWhileExtractingGetsNoResult`, `handleExtractsAndMarksMessageDone`,
    `exhaustedFailsDocumentAndRunsCompletionCheck` and `exhaustedLeavesCompletedDocumentAlone`.
- **`DocumentServiceTests`:** `markTruncatedSetsTheFlag`.

### 6. Fixes from the code review

- When several passes fail, the later exceptions are added as suppressed exceptions to the first, rather than
  dropped. The inbox's `last_error` still records only the first.
- The consolidation test now checks that all three saved passes are sent, and a new test covers the exhausted path
  on a document that has already failed.
- **Not changed:**
  - no `parallelism` limit for passes (harmless at the default of 3);
  - no `$` prefix on root-level validation errors (they go only to the model);
  - `callContext`, `waitFor` and page selection are still copied across the three steps (a refactor for later).

## Risks / Trade-offs

- **[A schema that the model can never satisfy]** The message retries to exhaustion, and the document fails. That's
  the right outcome. The passes are reused, so each retry costs one consolidate call and up to two final calls.
- **[Overlapping attempts]** Duplicate pass numbers make the slower attempt fail, and the lock settles the outcome.
  This is the same as WI-12 and WI-13.
- **[Lease]** Three passes over 20 pages, plus two text and image calls, fit well inside 10 minutes.

## Open Questions

None. The work item and §12 settle every choice here, or leave it to the builder.
