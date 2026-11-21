# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-04 as one commit.

## 1. Tables

- [x] 1.1 Write `repository/RunRecordsSchemaTests` (`@DataJpaTest` plus Testcontainers, using `JdbcClient`). It asserts that each of these inserts fails: a second `separation_run` with the same `(source_file_id, window_start, run_number)`; a window 4–3; a failed separation run with boundaries; a second `classification_run` with the same `(document_id, round, run_number)`; confidence 1.2; a second `classification_result` for a document; agreement -0.1; a second `extraction_run` with the same `(document_id, stage, pass_number)`; pass number 0; a second `webhook_delivery_attempt` with the same `(batch_id, attempt)`. It also asserts that initial and tie-break run 1 for one document both insert. Verify it fails because the tables don't exist.
- [x] 1.2 Write `V6__runs_and_results.sql` (design decision 1), with the three named `…_type_in_use` foreign keys. Verify 1.1 passes, and that `DocumentReaderApplicationTests` and every WI-02 and WI-03 test still pass.

## 2. Separation runs

- [x] 2.1 Write `service/SeparationRunServiceTests`:
  - a run over pages 1–6 with boundaries at 1 "Paystub" and 3 "W-2" reads back with both boundaries in order and the `Clock` time;
  - a failed run reads back with no boundaries;
  - runs over 1–15 and 14–20, saved in mixed order, list by window start then run number;
  - a window 5–7 on a 6-page file gives `IllegalArgumentException` and stores nothing.

  Verify it fails. Implement `SeparationRun`, its repository, `SeparationRunService(Impl)`, `NewSeparationRun`, `SeparationBoundary` and `SeparationRunRecord`, then verify it passes.

## 3. Classification

- [x] 3.1 Write `service/ClassificationRunServiceTests`: a resolved run reads back with its answer, type, confidence and reasoning; a failed run has no type; initial runs 1–5 and tie-break runs 1–3 saved in mixed order list initial first, each in run order; a duplicate initial run 3 gives `DataIntegrityViolationException`. Verify it fails. Implement `ClassificationRun`, `ClassificationRound`, its repository, `ClassificationRunService(Impl)`, `NewClassificationRun` and `ClassificationRunRecord`, then verify it passes.
- [x] 3.2 Write `service/ClassificationResultServiceTests`: a result decided by `VOTE` with votes `{"Pay stubs" id: 4, "W-2" id: 1}` and agreement 0.8 reads back equal through `find`; a second result gives `DataIntegrityViolationException` and `find` still returns the first; `find` for an unclassified document is empty. Verify it fails. Implement `ClassificationResult`, `DecidedBy`, its repository, `ClassificationResultService(Impl)`, `NewClassificationResult` and `ClassificationResultRecord`, then verify it passes.

## 4. Extraction

- [x] 4.1 Write `service/ExtractionRunServiceTests`: initial passes 1–3 and a consolidated pass 1; `findByDocumentAndStage(id, INITIAL)` returns passes 1, 2, 3 only; `findByDocument` returns all four in stage then pass order; data saved with keys `employerName`, `payDate`, `grossPay` reads back with that key order. Verify it fails. Implement `ExtractionRun`, `ExtractionStage`, its repository, `ExtractionRunService(Impl)`, `NewExtractionRun` and `ExtractionRunRecord`, then verify it passes.
- [x] 4.2 Write `service/ExtractionResultServiceTests`: a result for a "Pay stubs" document at schema version 1 reads back with its data (key order kept) and summary; a second result gives `DataIntegrityViolationException`; `find` for an unextracted document is empty. Verify it fails. Implement `ExtractionResult`, its repository, `ExtractionResultService(Impl)`, `NewExtractionResult` and `ExtractionResultRecord`, then verify it passes.

## 5. Webhook attempts and the call log

- [x] 5.1 Write `service/WebhookDeliveryAttemptServiceTests`: `record(batch, 1, null, "timeout")` then `record(batch, 2, 200, null)` list in attempt order with the `Clock` time; recording attempt 1 again gives `DataIntegrityViolationException`. Verify it fails. Implement `WebhookDeliveryAttempt`, its repository, `WebhookDeliveryAttemptService(Impl)` and `WebhookAttemptRecord`, then verify it passes.
- [x] 5.2 Write `service/OpenAiCallServiceTests`:
  - a classify call with latency, tokens and status 200 reads back through `findByBatch`;
  - a separate call with no document, status or tokens and error "timeout" reads back;
  - `openAiCallIsInIntegrationSchema()`: `information_schema.tables` has `document_reader_integration.openai_call` and no `openai_call` in `document_reader`;
  - `openAiCallHasNoBodyColumns()`: no column is named `prompt`, `response` or `body`;
  - latency -5 is refused.

  Verify it fails. Write `V7__integration_openai_call.sql` (design decision 2) and implement `OpenAiCall`, `OpenAiCallPurpose`, its repository, `OpenAiCallService(Impl)`, `OpenAiCallRecord` and `OpenAiCallLogRecord`, then verify it passes.

## 6. Catalog

- [x] 6.1 Extend `service/DocumentTypeServiceTests`: deleting a type that only a classification run resolved to gives `DocumentTypeInUseException` and the type still resolves; the same for a type only an extraction result refers to. Verify both fail with a rethrown integrity error, implement design decision 5, then verify they pass along with the existing in-use and unused-type tests.

## 7. Reading runs

- [x] 7.1 Write `service/RunRecordQueryServiceTests` (real Postgres): the owner reads each of the seven lists or results; another caller gets `ResourceNotFoundException` for each; an admin reads them; a missing classification or extraction result gives `ResourceNotFoundException`. Verify it fails, implement `RunRecordQueryService(Impl)` (design decision 6), then verify it passes.
- [x] 7.2 Write `api/RunRecordControllersTests`, one `@WebMvcTest` over the seven controllers in design decision 6, parameterized by path because they share one contract (`@Import(SecurityConfig.class)`, mocked `RunRecordQueryService`), reusing `RecordControllerTestSupport`. It checks:
  - each GET returns 200, passing `Caller("user-1", false)` for a plain JWT and `admin=true` with `ROLE_ADMIN`;
  - `extraction-runs?stage=INITIAL` passes the stage, no `stage` passes none, and `stage=DRAFT` gives 400 as a problem detail;
  - `ResourceNotFoundException` gives 404 as a problem detail;
  - POST, PUT, PATCH and DELETE give 405 for an admin, without calling the service;
  - no token gives 401.

  Verify they fail, implement the seven controllers, then verify they pass.
- [x] 7.3 Extend `config/LocalProfileAdminTests`: `GET /records/batches/{id}/openai-calls` for a missing batch gives 404, not 500, without a token in the `local` profile. Verify it passes (the controllers reuse WI-03's security context fix).

## 8. Verification

- [x] 8.1 Update `README.md`'s records section with the new endpoints. Verify it matches design decision 6.
- [x] 8.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the WI-01 to WI-03 tests.
- [x] 8.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with `openspec/changes/wi-04-run-records/` as the requirements. Fix every Critical and Important finding, and verify the build again.
- [x] 8.4 Tick the boxes in `docs/workitems/WI-04-run-and-result-records-plus-the-openai-call-log.md` (except the developer review and commit), and set its status.

## Workflow follow-up

- The developer reviews the diff and commits WI-04 as one commit.
- Then archive `wi-04-run-records`.
