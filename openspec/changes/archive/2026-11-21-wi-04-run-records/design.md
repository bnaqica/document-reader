# Design

## Context

After WI-03, the main schema has `batch`, `source_file`, `source_file_page` and `document` (V5), with CRUD services,
`RecordQueryService` for ownership, and read-only controllers under `/records`. JSON columns use `JSON`, not `JSONB`,
so key order survives (WI-02). Flyway already creates the `document_reader_integration` schema, which is still empty.
The design's §9, §11, §12, §15 and §16 define the tables this change adds. See `proposal.md` for why, and the three
specs for behaviour.

## Goals / Non-Goals

**Goals:**
- The services later work items need, with the exact names in `docs/workitems/WI-04-run-and-result-records-plus-the-openai-call-log.md`.
- Integrity enforced by the database where it can be: foreign keys, unique run numbers, one result per document, and
  range checks.
- A call log that can't hold prompt or response bodies, because no column exists for them.

**Non-Goals:**
- Voting, consolidation, schema validation of extracted data, or retry logic. WI-12 to WI-15 own those; this change
  stores what it's given.
- Writing the call log from the vision client (WI-09).
- Cost reporting queries beyond listing a batch's calls.

## Decisions

### 1. Tables (V6, main schema)

Every table gets a `UUID` primary key except the two results, whose key is `document_id`. All foreign keys restrict
deletes; nothing in the pipeline deletes these rows.

| Table | Columns and constraints |
|---|---|
| `separation_run` | `id`, `source_file_id` FK, `run_number INT` (≥ 1), `window_start`, `window_end INT` (check `1 ≤ window_start ≤ window_end`), `succeeded BOOLEAN`, `boundaries JSON` (null when not succeeded, check), `created_at`; **unique** `(source_file_id, window_start, run_number)` |
| `classification_run` | `id`, `document_id` FK, `round` (check: `INITIAL`, `TIEBREAK`), `run_number` (≥ 1), `raw_answer TEXT`, `document_type_id` FK (nullable), `confidence NUMERIC(4,3)` (0–1, nullable), `reasoning TEXT`, `succeeded`, `created_at`; **unique** `(document_id, round, run_number)` |
| `classification_result` | **pk** `document_id` FK, `document_type_id` FK, `votes JSON`, `agreement NUMERIC(4,3)` (0–1), `tie_broken BOOLEAN`, `decided_by` (check: `VOTE`, `TIEBREAK`, `CONFIDENCE`), `decided_at` |
| `extraction_run` | `id`, `document_id` FK, `stage` (check: `INITIAL`, `CONSOLIDATED`, `FINAL`), `pass_number` (≥ 1), `data JSON`, `summary TEXT`, `created_at`; **unique** `(document_id, stage, pass_number)` |
| `extraction_result` | **pk** `document_id` FK, `document_type_id` FK, `schema_version INT`, `data JSON`, `summary TEXT`, `created_at` |
| `webhook_delivery_attempt` | `id`, `batch_id` FK, `attempt INT` (≥ 1), `http_status INT` (nullable, 100–599), `error TEXT`, `attempted_at`; **unique** `(batch_id, attempt)` |

- **Indexes:** the unique constraints already lead with the parent id, so they serve every `findBy…` query. Add
  `classification_run(document_type_id)`, `classification_result(document_type_id)` and
  `extraction_result(document_type_id)` so a catalog delete's foreign key check doesn't scan.
- **Named type foreign keys:** `fk_classification_run_type_in_use`, `fk_classification_result_type_in_use` and
  `fk_extraction_result_type_in_use`. Decision 5 depends on these names.
- **`JSON`, not `JSONB`,** for `boundaries`, `votes` and `data`: extracted data must come back in schema key order,
  and one JSON pattern across the schema is simpler than two. Entities keep the WI-02 pattern:
  `@ColumnTransformer(write = "?::json")` on a `String` column, mapped to and from objects with the Jackson
  `JsonMapper` in the service.
- **Window within the file's pages:** the database checks `window_start ≤ window_end`; the service checks
  `window_end ≤ page_count` through `SourceFileService.get`, the same way documents check their ranges. A trigger
  would duplicate that rule.

### 2. The call log (V7, integration schema)

`document_reader_integration.openai_call`: `id`, `batch_id`, `source_file_id`, `document_id` (nullable), `purpose`
(check: `SEPARATE`, `CLASSIFY`, `TIEBREAK`, `CONFIRM_TYPE`, `DRAFT_SCHEMA`, `EXTRACT`, `CONSOLIDATE`, `FINAL`),
`model VARCHAR(255)`, `latency_ms BIGINT` (≥ 0), `prompt_tokens`, `completion_tokens INT` (nullable, ≥ 0),
`http_status` (nullable), `error TEXT`, `created_at`. Index `(batch_id, created_at)`.

- **No foreign keys to the main schema.** The integration schema records what was sent outside; it shouldn't depend on
  the main schema's tables, and logging a call must never fail because of a row elsewhere. *Alternative:* foreign keys
  to `batch`, `source_file` and `document` would catch a wrong id, but every caller passes ids it just read from those
  tables, so the check buys little.
- **No body columns,** asserted by a test that reads `information_schema.columns`.
- The entity uses `@Table(schema = "document_reader_integration")`, overriding Hibernate's default schema.
- **Record outside a transaction.** `OpenAiCallService.record` and `WebhookDeliveryAttemptService.record` use the
  default propagation, so they join a caller's transaction. Model calls and webhook POSTs never run inside one (§13),
  so WI-09 and WI-15 record right after the call, outside any transaction; a row recorded inside a step transaction
  would vanish if that step rolled back. *(Added after code review; `REQUIRES_NEW` was the alternative, but it would
  commit rows that tests then have to clean up, for a case no caller has.)*
- `http_status` is checked to be 100–599 on both tables. A value outside that range is a bug in the caller, not a
  response, so refusing it is deliberate.
- `OpenAiCallPurpose` stores the names in upper case; the spec's lower-case names (`confirm-type`) are how the design
  writes them.

### 3. Entities and enums

Entities `SeparationRun`, `ClassificationRun`, `ClassificationResult`, `ExtractionRun`, `ExtractionResult`,
`WebhookDeliveryAttempt` and `OpenAiCall`, and enums `ClassificationRound`, `DecidedBy`, `ExtractionStage` and
`OpenAiCallPurpose`, all in `entity`.
- Every row is written once and never changed, so there's no `@Version` and no setters. Each entity implements
  `Persistable` with an `isNew` flag cleared after persist (the `SourceFilePage` pattern), so `save` inserts without
  a SELECT first, and a duplicate key fails on insert instead of turning into an update.
- Times come from the injected `Clock`.

### 4. Services (CRUD, one per table)

| Service | Methods |
|---|---|
| `SeparationRunService(Impl)` | `save(UUID sourceFileId, NewSeparationRun)`, `findBySourceFile(UUID)` (window start, then run number) |
| `ClassificationRunService(Impl)` | `save(UUID documentId, NewClassificationRun)`, `findByDocument(UUID)` (round, then run number) |
| `ClassificationResultService(Impl)` | `save(UUID documentId, NewClassificationResult)`, `find(UUID documentId) → Optional` |
| `ExtractionRunService(Impl)` | `save(UUID documentId, NewExtractionRun)`, `findByDocument(UUID)`, `findByDocumentAndStage(UUID, ExtractionStage)` (pass order) |
| `ExtractionResultService(Impl)` | `save(UUID documentId, NewExtractionResult)`, `find(UUID documentId) → Optional` |
| `WebhookDeliveryAttemptService(Impl)` | `record(UUID batchId, int attempt, Integer httpStatus, String error)`, `findByBatch(UUID)` (attempt order) |
| `OpenAiCallService(Impl)` | `record(OpenAiCallRecord)`, `findByBatch(UUID)` (oldest first) |

- **Inputs** at the `dto` root: `NewSeparationRun(runNumber, windowStart, windowEnd, succeeded,
  List<SeparationBoundary>)`, `SeparationBoundary(startPage, label)`, `NewClassificationRun(round, runNumber,
  rawAnswer, documentTypeId, confidence, reasoning, succeeded)`, `NewClassificationResult(documentTypeId,
  Map<UUID, Integer> votes, agreement, tieBroken, decidedBy)`, `NewExtractionRun(stage, passNumber, JsonNode data,
  summary)`, `NewExtractionResult(documentTypeId, schemaVersion, JsonNode data, summary)` and `OpenAiCallRecord`.
- **Records** in `dto.response`, built with builders: `SeparationRunRecord`, `ClassificationRunRecord`,
  `ClassificationResultRecord`, `ExtractionRunRecord`, `ExtractionResultRecord`, `WebhookAttemptRecord` and
  `OpenAiCallLogRecord`. *(`OpenAiCallRecord` is the input name WI-04 fixes, so the response takes another.)*
- **Duplicates:** the save does `saveAndFlush`, so a unique or primary key violation surfaces inside the call as
  `DataIntegrityViolationException`. Callers (WI-12 to WI-15) read existing runs first and treat a duplicate as a
  bug, so no custom exception is added.
- **Null JSON is refused:** `data` and `votes` are checked with `Objects.requireNonNull`, because Jackson writes a null
  as the text `null`, which `NOT NULL` would accept. *(Added after code review.)*
- **Range checks** (confidence, agreement, numbers ≥ 1, latency ≥ 0) are database checks; `boundaries` present only
  when succeeded is a database check too. The services don't repeat them.
- **Dependencies:** `SeparationRunService` depends on `SourceFileService` (page count). The others depend on no other
  service; foreign keys refuse an unknown parent. `ClassificationResultService` doesn't check that the type exists:
  the foreign key does.
- **Votes** are stored as an object keyed by type id (`{"<uuid>": 4}`), in descending vote order as given.

### 5. A type in use, widened

`DocumentTypeServiceImpl.delete` already maps `fk_document_type_in_use` to `DocumentTypeInUseException`. It now maps
any of the four named constraints, held in one set constant. Every other integrity error is still rethrown.
`extraction_run` has no type column, so it isn't one of them.

### 6. Reading runs, and who may

`RunRecordQueryService(Impl)` is an action service, kept apart from `RecordQueryService` so neither interface grows
past what its controllers use. Each method takes a `Caller`:
- it first checks access through `RecordQueryService.sourceFile`, `.document` or `.batch`, which throws
  `ResourceNotFoundException` for a missing or someone else's record, so the ownership rule stays in one place;
- it then reads through the CRUD service. An empty `Optional` result throws `ResourceNotFoundException`.

One read-only controller per table, each mapping only GETs:

| Controller | Path |
|---|---|
| `SeparationRunRecordController` | `GET /records/source-files/{id}/separation-runs` |
| `ClassificationRunRecordController` | `GET /records/documents/{id}/classification-runs` |
| `ClassificationResultRecordController` | `GET /records/documents/{id}/classification-result` |
| `ExtractionRunRecordController` | `GET /records/documents/{id}/extraction-runs?stage=` (optional; all stages, stage then pass order, when absent) |
| `ExtractionResultRecordController` | `GET /records/documents/{id}/extraction-result` |
| `WebhookAttemptRecordController` | `GET /records/batches/{id}/webhook-attempts` |
| `OpenAiCallRecordController` | `GET /records/batches/{id}/openai-calls` |

They take `@CurrentSecurityContext(expression = "authentication") Authentication`, as WI-03's do. An unknown `stage`
fails enum conversion with `MethodArgumentTypeMismatchException`, which the `ResponseEntityExceptionHandler` base of
`GlobalExceptionHandler` already renders as a 400 problem detail; the controller test pins that.

### 7. Tests

| Test | What it covers |
|---|---|
| `repository/RunRecordsSchemaTests` | duplicate run, pass and attempt numbers, a second result, confidence and agreement out of range, a reversed window, boundaries on a failed run |
| `service/SeparationRunServiceTests` | save and read back, window then run order, window past the page count |
| `service/ClassificationRunServiceTests` | save and read back, round then run order, a failed run, a duplicate |
| `service/ClassificationResultServiceTests` | save and find, votes round-trip, second result refused, none found |
| `service/ExtractionRunServiceTests` | stage filter and pass order, key order kept |
| `service/ExtractionResultServiceTests` | save and find, key order, second refused, none found |
| `service/WebhookDeliveryAttemptServiceTests` | record and list, duplicate attempt |
| `service/OpenAiCallServiceTests` | record and read back, `openAiCallIsInIntegrationSchema()`, `openAiCallHasNoBodyColumns()`, negative latency |
| `service/RunRecordQueryServiceTests` | owner, another caller (404), admin, missing result (404) |
| `api/*RecordControllerTests` (`@WebMvcTest`) | 200, 404, 400 for an unknown stage, 405 for writes as an admin, 401 without a token |
| `service/DocumentTypeServiceTests` (extended) | a type only a classification run or an extraction result uses gives `DocumentTypeInUseException` and survives |

## Risks / Trade-offs

- [A duplicate save gives a raw `DataIntegrityViolationException`] → It only happens through a pipeline bug, and the
  database refuses it either way. A custom exception can come with the first caller that needs to recover from it.
- [No foreign keys on the call log, so a wrong id goes unnoticed] → Callers pass ids they just read; the log is for
  reporting, not integrity.
- [`raw_answer` and `reasoning` may quote borrower data] → They're stored, like extracted data, but never logged
  (§18). Only the call log is body-free.
- [Owners can read their batch's call log, including the model and token counts] → Nothing there is sensitive, and
  it helps a caller see why a batch was slow.

## Migration Plan

- Flyway applies V6 and V7 on startup.
- Before production, rolling back means dropping the seven tables and deleting V6 and V7 from the Flyway history.
