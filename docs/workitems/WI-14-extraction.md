# WI-14: Extraction (process 3)

| | |
|---|---|
| **Status** | Built, awaiting developer review |
| **Depends on** | [WI-02](WI-02-reference-data.md), [WI-03](WI-03-core-records.md), [WI-04](WI-04-run-and-result-records-plus-the-openai-call-log.md), [WI-05](WI-05-inbox-outbox-engine.md), [WI-09](WI-09-vision-client.md), [WI-10](WI-10-batch-completion.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §12 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 14 (the extraction half) |

**Goal:** Extract each document's data and summary in three stages (first passes, consolidation, final determination) and validate the result against the type's schema.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `processors/DataSchemaValidator`, `service/ExtractionService(Impl)`
- Test: `processors/DataSchemaValidatorTests`, `service/ExtractionServiceTests`

**Interfaces:**
- Consumes: Work items WI-02, WI-03, WI-04, WI-05 and WI-09, and `BatchCompletionService.onDocumentFinished` (WI-10).
- Produces:
  - `DataSchemaValidator.validate(JsonNode schema, JsonNode data) → List<String>`: error messages, empty when valid.
  - `ExtractionService.extract(UUID documentId)`

The service implementation also implements `InboxMessageHandler` (WI-05) for its message type: `handle` calls the step, and `onExhausted` runs the failure path. Listeners follow `listener -> @Service to CRUD @Service interface -> ...` (`.claude/rules/service-architecture.md`): the inbox listener calls `InboxProcessingService`, which calls this service, which changes data only through CRUD services.

- [x] **Step 1: Write the failing tests.**
  - `completedDocumentRunsCompletionCheck()`: after the final stage, `BatchCompletionService.onDocumentFinished` is called once, inside the same transaction that saves the result.
  - `exhaustedFailsDocumentAndRunsCompletionCheck()`
  - `threeInitialPassesThenConsolidateThenFinal()`: `extraction_run` rows are 3 `INITIAL`, 1 `CONSOLIDATED` and 1 `FINAL`, in that order.
  - `finalReceivesDraftAndImages()`
  - `invalidFinalRetriedOnceWithErrors()`: the second `finalizeExtraction` call receives a non-empty `validationErrors`.
  - `invalidTwiceThrows()`
  - `longDocumentTruncatedAt20Pages()`: 25 pages means 20 are sent and `truncated=true`.
  - `idempotentWhenResultExists()`
- [x] **Step 2: Run** the tests. Expected: FAIL.
- [x] **Step 3: Implement** §12. Make the model calls outside a transaction. In the final transaction, save `extraction_result`, set the document `COMPLETED` and call `onDocumentFinished`.
- [x] **Step 4: Run** the tests and the build. Expected: PASS.

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
feat: add three-stage document extraction
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
