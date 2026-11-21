# WI-04: Run and result records, plus the OpenAI call log

| | |
|---|---|
| **Status** | Built, awaiting developer review |
| **Depends on** | [WI-03](WI-03-core-records.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §9, §11, §12, §16 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 5 |

**Goal:** Create the tables that keep every separation, classification and extraction run, the final results, webhook attempts and the OpenAI call log.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `V6__runs_and_results.sql`, `V7__integration_openai_call.sql`; the entities, repositories, CRUD services and read-only controllers for `separation_run`, `classification_run`, `classification_result`, `extraction_run`, `extraction_result`, `webhook_delivery_attempt` and `document_reader_integration.openai_call`
- Test: one `…ServiceTests` per table under `service/`

**Interfaces:**
- Produces: `save…` and `findBy…` methods per table. The ones later tasks rely on:
  - `SeparationRunService.findBySourceFile(UUID)`
  - `ClassificationRunService.findByDocument(UUID)`
  - `ClassificationResultService.find(UUID documentId) → Optional`
  - `ExtractionRunService.findByDocumentAndStage(UUID, ExtractionStage)`
  - `ExtractionResultService.find(UUID) → Optional`
  - `WebhookDeliveryAttemptService.record(UUID batchId, int attempt, Integer httpStatus, String error)`
  - `OpenAiCallService.record(OpenAiCallRecord)`, where `OpenAiCallRecord` carries `batchId`, `sourceFileId`, a nullable `documentId`, and `purpose`, `model`, `latencyMs`, tokens, `httpStatus` and `error`.
  - Enums: `ClassificationRound { INITIAL, TIEBREAK }`, `DecidedBy { VOTE, TIEBREAK, CONFIDENCE }`, `ExtractionStage { INITIAL, CONSOLIDATED, FINAL }`.

- [x] **Step 1: Write the failing tests.** Each service saves a row and reads it back. `openAiCallIsInIntegrationSchema()` asserts that the table resolves to `document_reader_integration.openai_call`. `openAiCallHasNoBodyColumns()` reads the table's columns from the information schema and asserts that none is named `prompt`, `response` or `body`.
- [x] **Step 2: Run** the tests. Expected: FAIL.
- [x] **Step 3: Write the V6 and V7 migrations** with the columns in §16, and implement the entities, repositories, services and controllers.
- [x] **Step 4: Run** the tests and the build. Expected: PASS.

---

## Done when

- [x] Every step above is ticked.
- [x] `./gradlew spotlessApply build` passes, with all tests green.
- [ ] `git status` shows only the files this work item names, plus their tests and fixtures.
- [x] A code review (`superpowers:requesting-code-review`, using the `code-reviewer` agent) has no open findings.
- [ ] The developer has reviewed the diff.

## Commit

Claude never commits in this repo (`.claude/rules/git-workflow.md`). The developer commits this work item as one
commit, with this suggested message:

```text
feat: add run and result records and OpenAI call log
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
