# Document Reader Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or
> superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build the document-reader pipeline. It accepts a batch of one or more files with a loan purpose and stores
it in Postgres and S3. It separates each file into documents, classifies each document by vote, and extracts each
document's data and summary in three stages. It returns one payload per document, by waiting or by webhook. Every
step runs through a Postgres inbox/outbox.

**Architecture:** Each pipeline step is an inbox message handled by an action service. A handler saves its result
and writes the next outbox messages in one transaction. Steps fan out per file and then per document, and fan back
in on the batch row. OpenAI calls run outside transactions, through a `VisionClient` contract over the Spring AI
`ChatClient`. Postgres `LISTEN/NOTIFY` wakes the workers and the API calls waiting for a result.

**Tech Stack:** Java 25, Spring Boot 4.1, Spring AI (OpenAI), AWS SDK v2 S3, PDFBox, TwelveMonkeys ImageIO, ImageMagick
(HEIC), a JSON Schema validator, Flyway, PostgreSQL 18, Testcontainers (Postgres, LocalStack), JUnit 5.

**Spec:** `docs/design/2026-11-20-document-reader-design.md`. Section numbers below (§n) refer to it.

**Execution:** build this plan through the work items in `docs/workitems/`, one at a time, with one commit each.
They carry these tasks in a dependency-safe order: tasks 2 and 3 are combined into WI-02, and batch completion
moves out of task 14 into WI-10, ahead of tasks 12 and 13, which call it.

## Global Constraints

- Base package `com.bnaqica.documentreader`; follow `.claude/rules/` (package layout, CRUD versus action services, no logic in controllers, listeners or clients, mapping with `@Builder`).
- Main schema `document_reader`, integration schema `document_reader_integration`, audit schema `audit`. Never use `public`.
- Every table except `audit.api_audit` gets a CRUD controller. Pipeline tables are read-only except for the admin role (§7.4). `loan_purpose` and `document_type` are writable by admins only.
- Schema changes are new Flyway migrations. Never edit `V1__create_audit_table.sql`.
- Versions come from the Boot BOM. Spring AI, the AWS SDK, TwelveMonkeys, the JSON Schema validator, Tika and Testcontainers LocalStack are pinned or imported as BOMs, each with a comment saying why.
- OpenAI calls never run inside an open DB transaction (§13).
- Logs carry ids, statuses and counts only. Never log extracted values, summaries, file names or webhook bodies (§18).
- All configuration sits under `app.document-reader.*` with the defaults in §17.
- Tests use Testcontainers Postgres (and LocalStack for S3). OpenAI is always a stub `ChatModel` in CI.
- Listeners follow `listener -> @Service to CRUD @Service interface -> ...` (`.claude/rules/service-architecture.md`). `InboxWorker` and `PgNotificationListener` only hand work to services. The inbox logic lives in `InboxProcessingService`, wake-ups go through `WorkerSignalService`, and each step's action service implements `InboxMessageHandler` itself. `docs/workitems/` has the full detail.
- Terms (§5): a **batch** is one request, a **source file** is one uploaded file, and a **document** is one logical document inside a file. One payload per document.
- **Proof of concept** (§2a): synthetic or sample data only, and no webhook security. Do not build signing, secrets or SSRF checks; those are deferred (§22).
- **Never commit.** The repo's hook blocks commits and the developer reviews every file. Each task ends with `./gradlew spotlessApply build` passing, and the work is left uncommitted.

## Review Focus

1. **One bad file in an otherwise valid batch** (corrupt or password-protected PDF, or an unsupported type): the whole request is rejected with an error naming that file. Nothing is written to Postgres or S3, so no batch exists that later fails at separation. Pinned in Task 9 and Task 11.
2. **Separation answer with out-of-range, duplicate or unsorted page numbers:** the run is cleaned (sorted, de-duplicated, 1 and anything above the page count dropped) instead of crashing or producing empty documents. Pinned in Task 12.
3. **Model type name that normalizes to an empty key** (for example "—" or "N/A"): it counts as a failed run, not as a new type with an empty name. Pinned in Task 2 and Task 13.
4. **Caller disconnects during wait mode:** processing continues, the waiter is removed from the registry, and nothing is logged at error level. Pinned in Task 7.
5. **Sideways phone photo (EXIF orientation 6 or 8, including HEIC):** it becomes an upright PNG before any model call. Pinned in Task 9.

---

## File structure

| Area | Files (under `src/main/java/com/bnaqica/documentreader/`) |
|---|---|
| Config | `config/DocumentReaderProperties.java`, `config/AiConfig.java`, `config/S3Config.java`, `config/WorkerConfig.java`, `config/NotificationConnectionConfig.java` |
| Reference data | `entity/LoanPurpose.java`, `entity/DocumentType.java`, `entity/DocumentTypeAlias.java`, `entity/DocumentTypeOrigin.java`, repositories, `service/LoanPurposeService(Impl).java`, `service/DocumentTypeService(Impl).java`, `api/LoanPurposeController.java`, `api/DocumentTypeController.java` |
| Core records | `entity/Batch`, `BatchStatus`, `WebhookStatus`, `SourceFile`, `SourceFileStatus`, `SourceFilePage`, `Document`, `DocumentStatus`; CRUD services and controllers |
| Run records | `entity/SeparationRun`, `ClassificationRun`, `ClassificationRound`, `ClassificationResult`, `DecidedBy`, `ExtractionRun`, `ExtractionStage`, `ExtractionResult`, `WebhookDeliveryAttempt`, `OpenAiCall` (integration schema); CRUD services and controllers |
| Messaging | `entity/OutboxMessage`, `InboxMessage`, `MessageType`, `InboxStatus`; `publisher/OutboxPublisher`; `processors/OutboxDispatcher`; `listener/InboxWorker`; `service/InboxMessageHandler` (interface); `service/InboxProcessingService(Impl)`; `service/WorkerSignalService(Impl)`; `listener/PgNotificationListener`; `service/CompletionWaitService(Impl)` |
| Processing | `processors/TypeNameNormalizer`, `ImageConversionProcessor`, `ContentTypeDetector`, `BoundaryVoteProcessor`, `TypeVoteProcessor`, `DataSchemaValidator`, `WebhookUrlValidator` |
| Clients | `client/VisionClient` (+ `config/SpringAiVisionClient` wiring), `client/DocumentStorageClient` (+ S3 implementation in `config`), `client/WebhookClient` |
| Actions | `service/BatchIntakeService(Impl)`, `SeparationService(Impl)`, `ClassificationService(Impl)`, `DocumentTypeCatalogService(Impl)`, `ExtractionService(Impl)`, `BatchCompletionService(Impl)`, `WebhookDeliveryService(Impl)`, `BatchQueryService(Impl)` |
| API | `api/BatchController`, `dto/request/BatchUploadRequest`, `dto/response/BatchResultResponse`, `FileResultResponse`, `DocumentResultResponse`, `BatchAcceptedResponse` |
| Migrations | `src/main/resources/db/migration/V2__reference_data.sql`, `V3__seed_loan_purposes.sql`, `V4__seed_document_types.sql`, `V5__batches_files_documents.sql`, `V6__runs_and_results.sql`, `V7__integration_openai_call.sql`, `V8__inbox_outbox.sql` |

Tests mirror these packages under `src/test/java/com/bnaqica/documentreader/`.

---

### Task 1: Dependencies, configuration properties and local S3

**Files:**
- Modify: `build.gradle`, `src/main/resources/application.yml`, `src/main/resources/application-local.yml`, `src/test/resources/config/application.yml`, `compose.yaml`
- Create: `config/DocumentReaderProperties.java`
- Test: `config/DocumentReaderPropertiesTests.java`

**Interfaces:**
- Produces: `DocumentReaderProperties` record with nested records `Batch(maxFiles, maxTotalSize)`, `File(maxSize, maxPages)`, `Conversion(dpi, maxDimension)`, `Separation(runs, windowSize, windowOverlap)`, `Classification(runs, tiebreakRuns, maxPages, parallelism, minSuccessfulRuns)`, `Extraction(passes, maxPages)`, `OpenAi(model, separationTemperature, classificationTemperature, extractionTemperature)`, `Wait(timeout, pollInterval)`, `Outbox(pollInterval)`, `Inbox(maxAttempts, backoffInitial, backoffMax, lease)`, `Webhook(maxAttempts, timeout)`, `S3(bucket, prefix)`. Durations use `Duration` and sizes use `DataSize`.

- [ ] **Step 1: Write the failing test.** `bindsDefaults()` loads the context and asserts every default from §17, including: `batch = 20 / 200MB`, `file = 50MB / 100`, `conversion = 200 / 2048`, `separation = 3 / 15 / 2`, `classification = 5 / 3 / 3 / 5`, `minSuccessfulRuns = 3`, `extraction = 3 / 20`, `wait = 300s / 2s`, `outbox.pollInterval = 1s`, `inbox = 5 / 10s / 10m / 10m`, `webhook = 8 / 10s`, `s3.prefix = "files/"`. `multipartLimitsMatchProperties()` asserts that `spring.servlet.multipart.max-file-size = 50MB` and `max-request-size = 200MB`. `rejectsEvenSeparationRuns()` and `rejectsEvenTiebreakRuns()` set a value of 4 and expect the context to fail to start.
- [ ] **Step 2: Run** `./gradlew test --tests '*DocumentReaderPropertiesTests'`. Expected: FAIL, the class does not exist.
- [ ] **Step 3: Add dependencies and properties.** Add the Spring AI BOM with the OpenAI starter, the AWS SDK v2 BOM with `s3`, `pdfbox`, TwelveMonkeys `imageio-tiff` and `imageio-webp`, `tika-core`, a JSON Schema validator, and test-only `testcontainers-localstack`, with a "not in Boot BOM" comment on each. Turn on `spring.threads.virtual.enabled: true`. Set the multipart limits. Add `DocumentReaderProperties` with `@Validated` and a constraint for odd run counts. Set `OPENAI_API_KEY` and `S3_BUCKET` from the environment, with placeholders in the test config. In the local profile, point S3 at LocalStack: add the `localstack` service to `compose.yaml`, or document `local-docker/localstack`.
- [ ] **Step 4: Run** the test again. Expected: PASS. Then run `./gradlew spotlessApply build`. Expected: BUILD SUCCESSFUL.

### Task 2: Type-name normalizer

**Files:**
- Create: `processors/TypeNameNormalizer.java`
- Test: `processors/TypeNameNormalizerTests.java`

**Interfaces:**
- Produces: `Optional<String> TypeNameNormalizer.key(String name)`. It returns empty when nothing usable is left.

- [ ] **Step 1: Write the failing tests.**
  - `keyOf("Pay stubs") = keyOf("Paystub") = keyOf("pay-stub") = keyOf("PAY STUBS") = "paystub"`
  - `keyOf("W-2 forms") = keyOf("W2 form") = "w2form"`
  - `keyOf("1099 forms or K-1 forms") = "1099formork1form"`
  - `keyOf("Bank statements") = "bankstatement"`
  - `keyOf("Homeowners association documents") = "homeownersassociationdocument"`. Words ending in "ss", "us" and "is" are kept as they are, so "address" stays "address".
  - Review Focus #3: `key("—")`, `key("N/A")`, `key("  ")` and `key(null)` are all empty.
- [ ] **Step 2: Run** `./gradlew test --tests '*TypeNameNormalizerTests'`. Expected: FAIL.
- [ ] **Step 3: Implement** the normalization in §10.2: lowercase, replace punctuation and hyphens with spaces, singularize each word, then remove all spaces. Singularize with these rules in order: `ies→y`, `sses→ss`, then a trailing `s` is removed unless the word ends in `ss`, `us` or `is`. Treat `n/a`, `na`, `none` and `unknown` as empty.
- [ ] **Step 4: Run** the tests. Expected: PASS.

### Task 3: Reference data (loan purposes and document types)

**Files:**
- Create: `V2__reference_data.sql`, `V3__seed_loan_purposes.sql`, `V4__seed_document_types.sql`, the entities, repositories, CRUD services and controllers listed in the file structure
- Test: `repository/ReferenceDataSeedTests.java`, `api/LoanPurposeControllerTests.java`, `api/DocumentTypeControllerTests.java`

**Interfaces:**
- Consumes: `TypeNameNormalizer.key` (Task 2).
- Produces:
  - `LoanPurposeService`: `Optional<LoanPurposeResponse> findActive(String code)`, `List<LoanPurposeResponse> findAllActive()`, plus CRUD.
  - `DocumentTypeService`: `List<DocumentTypeResponse> findAll()`, `Optional<DocumentTypeResponse> findByKey(String normalizedKey)` (it checks types and aliases), `DocumentTypeResponse create(NewDocumentType)` (throws `DuplicateDocumentTypeException` on a unique-key conflict), `void addAlias(UUID typeId, String alias)`, plus CRUD.
  - `DocumentTypeResponse(id, name, normalizedKey, description, dataSchema (JsonNode), schemaVersion, origin)`.

- [ ] **Step 1: Write the failing tests.**
  - `seedsEightLoanPurposes()`: the codes are exactly `CAR_PURCHASE`, `CAR_REFINANCE`, `HOME_PURCHASE`, `MORTGAGE_REFINANCE`, `BOAT_PURCHASE`, `BOAT_REFINANCE`, `RV_PURCHASE` and `RV_REFINANCE`, all active, each with a description.
  - `seedsFortyOneDocumentTypes()`: the count is 41. Every `normalized_key` is unique and equals `TypeNameNormalizer.key(name)`. Every `data_schema` is a valid JSON Schema object with at least one property. `origin` is `SEEDED`.
  - `findByKeyResolvesAlias()`: after `addAlias(payStubsId, "Paystub")`, `findByKey("paystub")` returns Pay stubs.
  - `createRejectsDuplicateKey()`: creating "Paystubs" throws `DuplicateDocumentTypeException`.
  - Controllers: a GET with a `jwt()` returns 200. A POST or PUT without the admin role returns 403, and with it returns 201 or 200. A request with no token returns 401.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Write the V2 migration** with the `loan_purpose`, `document_type` and `document_type_alias` columns in §16, a unique `normalized_key` on both type tables, and `data_schema JSONB NOT NULL`.
- [ ] **Step 4: Write the V3 seed** for the eight purposes, with one-sentence descriptions that prompts can use as context.
- [ ] **Step 5: Write the V4 seed.** For each of the 41 types in the design conversation's list, use the model to draft the JSON Schema and **have a person review it** before it goes into the file. The schemas cover the fields lenders use (§10.1). `name` is the list wording, for example "Pay stubs" and "W-2 forms, typically the past two years" becoming "W-2 forms". Record the short-name choices in a comment at the top of V4.
- [ ] **Step 6: Implement** the entities (`@Getter`, a protected no-args constructor, `@Builder`, and no `@Data`), the repositories, the CRUD services that map to response records, and the controllers with `@PreAuthorize("hasRole('ADMIN')")` on writes.
- [ ] **Step 7: Run** the tests, then `./gradlew spotlessApply build`. Expected: PASS.

### Task 4: Batches, source files, pages and documents

**Files:**
- Create: `V5__batches_files_documents.sql`; the `Batch`, `SourceFile`, `SourceFilePage` and `Document` entities and their enums, repositories, CRUD services and read-only controllers
- Test: `service/BatchServiceTests.java`, `service/SourceFileServiceTests.java`, `service/DocumentServiceTests.java`, `api/BatchCrudControllerTests.java`

**Interfaces:**
- Produces:
  - `BatchService`:
    - `BatchRecord create(NewBatch)`
    - `BatchRecord get(UUID)`
    - `void updateStatus(UUID id, BatchStatus status, String failureReason)`
    - `BatchRecord lockForUpdate(UUID id)` (`SELECT … FOR UPDATE`, which must run inside the caller's transaction)
    - `void complete(UUID id, BatchStatus finalStatus, Instant completedAt)`
    - `void updateWebhookStatus(UUID id, WebhookStatus status)`
  - `SourceFileService`:
    - `List<SourceFileRecord> createAll(UUID batchId, List<NewSourceFile>)`: `file_index` follows list order.
    - `SourceFileRecord get(UUID)`
    - `List<SourceFileRecord> findByBatch(UUID)`, ordered by `file_index`
    - `void markStored(UUID id, String s3Key)`
    - `void updateStatus(UUID id, SourceFileStatus status, String failureReason)`
  - `SourceFilePageService`: `void saveAll(UUID sourceFileId, List<PageImage>)` and `List<PageRecord> findBySourceFile(UUID)`, ordered.
  - `DocumentService`:
    - `List<DocumentRecord> createAll(UUID batchId, UUID sourceFileId, List<PageRange>)`
    - `DocumentRecord get(UUID)`
    - `List<DocumentRecord> findByBatch(UUID)`, ordered by file index and then sequence
    - `List<DocumentRecord> findBySourceFile(UUID)`
    - `void updateStatus(UUID id, DocumentStatus status, String failureReason)`
    - `void assignType(UUID id, UUID documentTypeId)`
  - Enums:
    - `BatchStatus { RECEIVED, PROCESSING, COMPLETED, PARTIALLY_COMPLETED, FAILED }`
    - `SourceFileStatus { RECEIVED, STORED, SEPARATING, SEPARATED, FAILED }`
    - `DocumentStatus { PENDING, CLASSIFYING, CLASSIFIED, EXTRACTING, COMPLETED, FAILED }`
    - `WebhookStatus { NOT_APPLICABLE, PENDING, DELIVERED, FAILED }`
  - Records:
    - `NewSourceFile(int index, String originalFilename, String contentType, long sizeBytes, String sha256, int pageCount)`
    - `PageRange(int start, int end)`
    - `PageImage(int pageNumber, String s3Key, int width, int height)`

- [ ] **Step 1: Write the failing tests.**
  - `createBatchStartsReceived()`
  - `createAllFilesKeepsIndexOrder()`: three files give `file_index` 0, 1 and 2, all `RECEIVED`.
  - `markStoredSetsStoredAndKey()`
  - `createAllDocumentsAssignsSequenceInOrder()`: three ranges give sequences 1, 2 and 3 with `PENDING`, and each document has both `batch_id` and `source_file_id`.
  - `findByBatchOrdersByFileThenSequence()`: documents from file 1 come after all documents from file 0.
  - `lockForUpdateBlocksSecondTransaction()`: two threads, where the second waits until the first commits.
  - Controller: GET returns only the caller's own rows unless the caller is admin. POST, PUT and DELETE return 405 for non-admins.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Write the V5 migration** for the `batch`, `source_file`, `source_file_page` and `document` columns in §16. Include foreign keys, `version` columns, a unique `(batch_id, file_index)`, and indexes on `source_file(batch_id)`, `document(batch_id)` and `document(source_file_id)`.
- [ ] **Step 4: Implement** the entities, repositories, CRUD services and controllers.
- [ ] **Step 5: Run** the tests and the build. Expected: PASS.

### Task 5: Run and result records, plus OpenAI call log

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

- [ ] **Step 1: Write the failing tests.** Each service saves a row and reads it back. `openAiCallIsInIntegrationSchema()` asserts that the table resolves to `document_reader_integration.openai_call`. `openAiCallHasNoBodyColumns()` reads the table's columns from the information schema and asserts that none is named `prompt`, `response` or `body`.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Write the V6 and V7 migrations** with the columns in §16, and implement the entities, repositories, services and controllers.
- [ ] **Step 4: Run** the tests and the build. Expected: PASS.

### Task 6: Inbox/outbox engine

**Files:**
- Create: `V8__inbox_outbox.sql`, `entity/OutboxMessage`, `InboxMessage`, `MessageType`, `InboxStatus`, their repositories, `service/OutboxMessageService(Impl)`, `InboxMessageService(Impl)`, `publisher/OutboxPublisher`, `processors/OutboxDispatcher`, `listener/InboxWorker`, `service/InboxMessageHandler`, `config/WorkerConfig`, and read-only controllers
- Test: `publisher/OutboxPublisherTests`, `processors/OutboxDispatcherTests`, `listener/InboxWorkerTests`

**Interfaces:**
- Produces:
  - `MessageType { SEPARATE_FILE, CLASSIFY_DOCUMENT, EXTRACT_DOCUMENT, DELIVER_WEBHOOK }`
  - `OutboxPublisher.publish(MessageType type, UUID aggregateId, Object payload)`: it must be called inside the caller's transaction (`Propagation.MANDATORY`) and sends `NOTIFY outbox_ready` after the commit.
  - `interface InboxMessageHandler { MessageType type(); void handle(InboxMessageRecord message); void onExhausted(InboxMessageRecord message, String lastError); }`
  - `InboxMessageService`:
    - `List<InboxMessageRecord> claimDue(MessageType, int limit, String instanceId, Duration lease)`
    - `void markDone(UUID id)`: it must be called inside the handler's final transaction.
    - `void markRetry(UUID id, String error, Instant nextAttemptAt)`
    - `void markFailed(UUID id, String error)`
  - Backoff: `nextAttempt = now + min(backoffInitial × 2^(attempts−1), backoffMax)`.
  - `int maxAttempts(MessageType)`: returns `inbox.maxAttempts` for every type except `DELIVER_WEBHOOK`, which uses `webhook.maxAttempts`.

- [ ] **Step 1: Write the failing tests.**
  - `publishRequiresTransaction()`: throws when called outside a transaction.
  - `publishRollsBackWithCaller()`
  - `dispatcherMovesOnceAcrossTwoDispatchers()`: two dispatchers run in parallel over 100 rows, giving 100 inbox rows and every outbox row dispatched.
  - `dispatcherIgnoresDuplicate()`: inserting the same id twice gives one inbox row.
  - `twoWorkersProcessEachMessageExactlyOnce()`: two workers, 50 messages, and the handler is invoked 50 times with distinct ids.
  - `failedHandleSchedulesRetryWithBackoff()`: `attempts=1` sets `nextAttemptAt = now+10s`, and `attempts=3` sets `now+40s`, using a fixed `Clock`.
  - `backoffCapsAtMax()`
  - `exhaustedAfterMaxAttemptsCallsOnExhausted()`: on the 5th failure the status is `FAILED` and `onExhausted` is called once.
  - `webhookUsesItsOwnMaxAttempts()`: a `DELIVER_WEBHOOK` message is exhausted on the 8th failure.
  - `staleLeaseIsReclaimed()`: a row left `PROCESSING` with `locked_until` in the past is claimed again.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Write the V8 migration** for the tables in §13, with an index on `inbox_message(message_type, status, next_attempt_at)` and a partial index on `outbox_message(dispatched_at) WHERE dispatched_at IS NULL`.
- [ ] **Step 4: Implement the claim** as one short transaction: `SELECT … WHERE status='PENDING' AND next_attempt_at <= now OR (status='PROCESSING' AND locked_until < now) … FOR UPDATE SKIP LOCKED LIMIT n`, then update to `PROCESSING` and commit. `InboxWorker` runs one loop per message type on virtual threads. It wakes on `inbox_ready` (Task 7) or on `outbox.pollInterval`, and dispatches to the handler bean whose `type()` matches.
- [ ] **Step 5: Run** the tests and the build. Expected: PASS.

### Task 7: Notifications and wait mode

**Files:**
- Create: `config/NotificationConnectionConfig`, `listener/PgNotificationListener`, `service/CompletionWaitService(Impl)`
- Test: `listener/PgNotificationListenerTests`, `service/CompletionWaitServiceTests`

**Interfaces:**
- Consumes: `BatchService.get` (Task 4), and `WorkerSignalService.signal` and `signalDispatcher` (Task 6).
- Produces:
  - `CompletionWaitService.await(UUID batchId, Duration timeout) → boolean`: true when the batch reached a final status.
  - `void notifyBatchCompleted(UUID batchId)`: sends `NOTIFY batch_completed`, and must be called inside the completing transaction.
  - `PgNotificationListener` listens on the `outbox_ready`, `inbox_ready` and `batch_completed` channels using one dedicated connection that reconnects with backoff.

- [ ] **Step 1: Write the failing tests.**
  - `awaitReturnsWhenNotified()`: a notification is sent from another connection, and `await` returns true in under 1 s.
  - `awaitFallsBackToPolling()`: with notifications suppressed, the batch status is set to `COMPLETED` and `await` returns true within `pollInterval + 1s`.
  - `awaitTimesOut()`: returns false after the timeout, set to 3 s for the test.
  - Review Focus #4: `waiterRemovedWhenCallerInterrupted()`. The waiting thread is interrupted, the registry size returns to 0, processing is not affected, and no ERROR log is written.
  - `listenerReconnectsAfterConnectionDrop()`
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** using the PgJDBC `PGConnection.getNotifications(timeoutMs)` loop on a virtual thread, and a `ConcurrentHashMap<UUID, CompletableFuture<Void>>` registry. `await` completes on whichever comes first: the future, or a status poll every `wait.pollInterval`.
- [ ] **Step 4: Run** the tests and the build. Expected: PASS.

### Task 8: S3 storage client

**Files:**
- Create: `client/DocumentStorageClient`, `config/S3Config`, `config/S3DocumentStorageClient`, `exception/StorageException`
- Test: `config/S3DocumentStorageClientTests` (Testcontainers LocalStack)

**Interfaces:**
- Produces: `DocumentStorageClient`:
  - `void put(String key, byte[] bytes, String contentType)`
  - `byte[] get(String key)`
  - `boolean exists(String key)`
  - Key helpers on `S3Keys`: `original(UUID sourceFileId, String ext)` returns `files/{id}/original.{ext}`, and `page(UUID sourceFileId, int page)` returns `files/{id}/pages/{nnnn}.png`.
  - Every SDK error is translated to `StorageException`.

- [ ] **Step 1: Write the failing tests.**
  - `putThenGetRoundTrips()`
  - `putUsesServerSideEncryption()`: the head-object response shows SSE is set.
  - `pageKeyIsZeroPadded()`: `page(id, 7)` ends with `/pages/0007.png`.
  - `originalKeyUsesSourceFileId()`: `original(id, "pdf")` equals `files/{id}/original.pdf`.
  - `missingKeyThrowsStorageException()`
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** with the AWS SDK v2 `S3Client`, an endpoint override from configuration for LocalStack, and `ServerSideEncryption.AWS_KMS`. In the local profile, where LocalStack lacks KMS, use `AES256`.
- [ ] **Step 4: Run** the tests and the build. Expected: PASS.

### Task 9: Content detection and conversion to PNG

**Files:**
- Create: `processors/ContentTypeDetector`, `processors/ImageConversionProcessor`, `exception/UnsupportedDocumentException`, `exception/UnreadableDocumentException`, and a Jib base image with ImageMagick and libheif (`src/main/jib/` or a custom `from.image`)
- Test: `processors/ContentTypeDetectorTests`, `processors/ImageConversionProcessorTests`, with fixtures in `src/test/resources/fixtures/`: a 3-page PDF, an encrypted PDF, a corrupt PDF, a multi-frame TIFF, a HEIC with EXIF 6, a JPEG with EXIF 8, a 4000×6000 PNG, and an RGBA PNG

**Interfaces:**
- Produces:
  - `ContentTypeDetector.detect(byte[]) → DetectedType`, one of `PDF`, `PNG`, `JPEG`, `HEIC`, `TIFF` or `WEBP`. It throws `UnsupportedDocumentException`, which maps to 415.
  - `ImageConversionProcessor.pageCount(byte[], DetectedType) → int`, which throws `UnreadableDocumentException` (422).
  - `ImageConversionProcessor.toPngPages(byte[], DetectedType) → List<ConvertedPage(int pageNumber, byte[] png, int width, int height)>`.

- [ ] **Step 1: Write the failing tests.**
  - `detectsBySniffingNotExtension()`: a PDF named `.png` is detected as `PDF`.
  - `rejectsDocx()` throws `UnsupportedDocumentException`.
  - `pdfRendersOnePngPerPage()`: the 3-page PDF gives 3 pages at 200 DPI, each with its longest side at most 2048.
  - `tiffFramesBecomePages()`
  - `resizesToMaxDimension()`: 4000×6000 becomes 1365×2048.
  - `dropsAlpha()`: the output PNG has no alpha channel.
  - Review Focus #5: `heicWithExif6IsUpright()` and `jpegWithExif8IsUpright()`. The output width and height are swapped relative to the raw pixel grid, and a marker pixel in the fixture lands in the expected corner.
  - Review Focus #1: `encryptedPdfIsUnreadable()` and `corruptPdfIsUnreadable()` both throw `UnreadableDocumentException` from `pageCount`.
- [ ] **Step 2: Run** the tests. Expected: FAIL. Mark the HEIC tests with `@EnabledIf` on ImageMagick being present, and make sure CI's runner installs it.
- [ ] **Step 3: Implement.** Use Tika `Detector` for sniffing. Render PDFs with PDFBox `PDFRenderer.renderImageWithDPI(page, dpi, ImageType.RGB)`. Read TIFF and WEBP through TwelveMonkeys. Convert HEIC with ImageMagick (`magick input.heic -auto-orient png:-`) through `ProcessBuilder`, using temp files in a private temp directory. Apply EXIF orientation for JPEG and WEBP. Resize with bicubic scaling.
- [ ] **Step 4: Update the Jib configuration** with a base image that contains ImageMagick and libheif, keeping `configurationName = 'productionRuntimeClasspath'`. Add the ImageMagick install to `.github/workflows/build.yml`.
- [ ] **Step 5: Run** the tests and the build. Expected: PASS.

### Task 10: Vision client (Spring AI)

**Files:**
- Create: `client/VisionClient`, `config/AiConfig`, `config/SpringAiVisionClient`, prompt templates in `src/main/resources/prompts/*.st`, the response records in `dto/` (`SeparationAnswer`, `ClassificationAnswer`, `TypeMatchAnswer`, `ExtractionAnswer`, `ConsolidatedAnswer`), and `exception/VisionException`
- Test: `config/SpringAiVisionClientTests` with a stub `ChatModel`

**Interfaces:**
- Consumes: `OpenAiCallService.record` (Task 5).
- Produces: `VisionClient`. Every method takes a `CallContext(UUID batchId, UUID sourceFileId, UUID documentId /* nullable */, String loanPurposeDescription)`.
  - `SeparationAnswer separate(CallContext, List<PageImageRef> pages, List<String> catalogNames)`, where `SeparationAnswer(List<Integer> startPages, List<String> labels)`
  - `ClassificationAnswer classify(CallContext, List<PageImageRef> pages, List<CatalogEntry> catalog)`, where `ClassificationAnswer(String documentType, double confidence, String reasoning)`
  - `ClassificationAnswer tiebreak(CallContext, List<PageImageRef> pages, List<CatalogEntry> candidates)`
  - `TypeMatchAnswer confirmNewType(CallContext, List<PageImageRef> pages, String proposedName, List<CatalogEntry> catalog)`, where `TypeMatchAnswer(boolean matchesExisting, String existingName)`
  - `JsonNode draftSchema(CallContext, List<PageImageRef> pages, String typeName)`
  - `ExtractionAnswer extract(CallContext, List<PageImageRef> pages, JsonNode schema)`, where `ExtractionAnswer(JsonNode data, String summary)`
  - `ConsolidatedAnswer consolidate(CallContext, JsonNode schema, List<ExtractionAnswer> passes)`, where `ConsolidatedAnswer(JsonNode data, String summary, List<String> disagreements)`
  - `ExtractionAnswer finalizeExtraction(CallContext, List<PageImageRef> pages, JsonNode schema, ConsolidatedAnswer draft, List<String> validationErrors)`
  - `PageImageRef(int pageNumber, String s3Key)`. The client loads the bytes through `DocumentStorageClient` and sends PNG media at high detail.

- [ ] **Step 1: Write the failing tests.**
  - `classifySendsImagesCatalogAndPurpose()`: the stub captures the prompt, which contains N image media, every catalog name and the purpose description.
  - `classifyUsesClassificationTemperature()`
  - `extractRequestsStructuredOutputWithSchema()`
  - `everyCallRecordsOpenAiCall()`: `batchId`, `sourceFileId`, `purpose`, `model` and tokens are recorded, and no body is stored.
  - `httpErrorBecomesVisionException()`
  - `invalidJsonBecomesVisionException()`
  - `consolidateSendsNoImages()`
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** with `ChatClient` and `.entity(Class)`, or a JSON-schema response format. Load prompts from `.st` templates; their wording is the builder's call (§21). Set the temperature per call from `DocumentReaderProperties.OpenAi`. Time each call and record it in `finally`.
- [ ] **Step 4: Run** the tests and the build. Expected: PASS.

### Task 11: Batch intake API, validation, audit and result query

**Files:**
- Create: `api/BatchController`, `dto/request/BatchUploadRequest`, `dto/response/BatchResultResponse`, `FileResultResponse`, `DocumentResultResponse`, `BatchAcceptedResponse`, `service/BatchIntakeService(Impl)`, `service/BatchQueryService(Impl)`, `processors/WebhookUrlValidator`, `exception/InvalidWebhookUrlException`, `exception/BatchValidationException` (which carries a list of `FileError(int index, String fileName, int status, String detail)`)
- Modify: `exception/GlobalExceptionHandler` (415, 413, 422 and 400 mappings, plus the `errors` array), `config/ApiAuditFilter` (payload for multipart)
- Test: `api/BatchControllerTests`, `service/BatchIntakeServiceTests`, `processors/WebhookUrlValidatorTests`, `config/ApiAuditFilterTests`

**Interfaces:**
- Consumes: Tasks 3, 4, 6, 7, 8 and 9.
- Produces:
  - `POST /batches` (multipart: repeated `files`, `loanPurpose`, `async`, `webhookUrl`)
  - `GET /batches/{id}`
  - `BatchQueryService.getResult(UUID id, Jwt caller) → BatchResultResponse`. It returns 404 for another caller's batch; the 404 avoids revealing that the batch exists. Admins can read all.
  - `WebhookUrlValidator.validate(String url)`, which throws `InvalidWebhookUrlException`.
  - The envelope shape is exactly §7.3: `files[]` ordered by index, and `documents[]` ordered by file index and then page. The public batch status maps `RECEIVED → RECEIVED`, and `PROCESSING → PROCESSING`.
  - When a batch has several file errors, the response status is the first failing file's status (validation order: 400, 415, 413, 422), and every failing file is listed in `errors`.

- [ ] **Step 1: Write the failing tests.**
  - `missingLoanPurposeIs400ListingValidCodes()` and `unknownLoanPurposeIs400()`
  - `noFilesIs400()` and `twentyOneFilesIs400()`
  - `asyncTrueWithoutUrlIs400()`, with the detail exactly "webhookUrl is required when async is true"
  - `urlWithoutAsyncIs400()`, with the detail exactly "webhookUrl is only used when async is true"
  - `relativeWebhookUrlIs400()` and `ftpWebhookUrlIs400()`, with the detail exactly "webhookUrl must be an absolute http or https URL"
  - `httpAndHttpsWebhookUrlsAreAccepted()`, including `http://localhost:9000/hook`
  - `docxIs415NamingTheFile()`: the `errors` entry has the index and file name.
  - `fileOver50MbIs413()`, `batchOver200MbIs413()` and `fileOver100PagesIs422()`
  - Review Focus #1: `oneEncryptedPdfAmongValidFilesIs422AndStoresNothing()`. The batch, file and outbox counts are unchanged, and S3 is empty.
  - `asyncReturns202WithStatusUrl()`, `syncReturns200WhenCompletedInTime()` (wait mocked to true) and `syncReturns202OnTimeout()` (wait mocked to false)
  - `intakeOrdersWrites()`: the batch and three files exist as `RECEIVED` before any S3 put. Afterwards the files are `STORED`, the batch is `PROCESSING`, and there are exactly three `SEPARATE_FILE` outbox rows.
  - `s3FailureMarksBatchFailed()`
  - `getOtherCallersBatchIs404()`
  - `resultListsDocumentsInFileThenPageOrder()`
  - `auditPayloadExcludesFileBytes()`: the audit `request_payload` contains `loanPurpose` and each file's name, size and sha256, but no file content.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement the intake** in the order of §6:
  1. validate the request fields;
  2. for every file, sniff the type, check the size and count pages, collecting all errors before throwing `BatchValidationException`;
  3. transaction 1: create the batch and its source files;
  4. put each original to S3;
  5. transaction 2: `markStored` for each file, set the batch to `PROCESSING`, and publish one `SEPARATE_FILE` per file;
  6. if the request is not async, call `await(batchId, wait.timeout)`.

  The webhook check only confirms an absolute URL with an `http` or `https` scheme (§7.1). It does no DNS lookups.
- [ ] **Step 4: Run** the tests and the build. Expected: PASS.

### Task 12: Separation (process 1, per file)

**Files:**
- Create: `service/SeparationService(Impl)`, `processors/BoundaryVoteProcessor`
- Test: `processors/BoundaryVoteProcessorTests`, `service/SeparationServiceTests`

**Interfaces:**
- Consumes: Tasks 4, 5, 6, 8, 9 and 10, and `BatchCompletionService.onFileFailed` (Task 14).
- Produces:
  - `BoundaryVoteProcessor.sanitize(List<Integer> startPages, int pageCount) → SortedSet<Integer>`
  - `BoundaryVoteProcessor.vote(List<SortedSet<Integer>> runs, int pageCount) → List<PageRange>`
  - `BoundaryVoteProcessor.windows(int pageCount, int size, int overlap) → List<PageRange>`
  - `SeparationService.separate(UUID sourceFileId)`

- [ ] **Step 1: Write the failing tests.**
  - `majorityOfThreeDecidesEachGap()`: the runs `{1,3}`, `{1,3,5}` and `{1}` over 6 pages give `[1–2, 3–6]`.
  - `pageOneAlwaysStarts()`
  - Review Focus #2: `sanitizeDropsOutOfRangeDuplicatesAndOne()`, where `[0, 3, 3, 9, 2]` with `pageCount=5` becomes `{2, 3}`, and `emptyRunMeansOneDocument()`.
  - `windowsOverlap()`: `windows(40, 15, 2)` gives `[1–15, 14–28, 27–40]`.
  - `gapInTwoWindowsUsesMoreCentralWindow()`
  - `singlePageSkipsModel()`: the vision stub is never called, and one document covers 1–1.
  - `convertsOnceAndReusesPagesOnRetry()`: the second run finds the pages in S3 and does not call conversion.
  - `savesRunsCreatesDocumentsMarksSeparatedAndPublishesOneClassifyPerDocument()`
  - `filesOfOneBatchSeparateIndependently()`: two files in a batch produce documents with their own `source_file_id`, and their sequences each start at 1.
  - `exhaustedFailsFileAndRunsCompletionCheck()`: `onExhausted` sets the file to `FAILED` and calls `BatchCompletionService.onFileFailed`.
  - `idempotentWhenDocumentsAlreadyExist()`
  - `lessThanMajorityOfRunsSucceedingThrows()`, so the inbox retries.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement.** For a gap at page *p*, count the runs (across windows that cover both *p−1* and *p*, keeping the window where *p* is furthest from an edge) whose sanitized set contains *p*. More than half means a boundary. Make the model calls outside a transaction. Then, in one transaction, create the documents, mark the file `SEPARATED` and publish.
- [ ] **Step 4: Run** the tests and the build. Expected: PASS.

### Task 13: Catalog resolution and classification (process 2)

**Files:**
- Create: `processors/TypeVoteProcessor`, `service/DocumentTypeCatalogService(Impl)`, `service/ClassificationService(Impl)`
- Test: `processors/TypeVoteProcessorTests`, `service/DocumentTypeCatalogServiceTests`, `service/ClassificationServiceTests`

**Interfaces:**
- Consumes: Tasks 2, 3, 4, 5, 6 and 10, and `BatchCompletionService.onDocumentFinished` (Task 14).
- Produces:
  - `DocumentTypeCatalogService.resolve(String rawName) → Optional<DocumentTypeResponse>`: matches by key against types and aliases; empty means new or unusable.
  - `DocumentTypeCatalogService.createOrMatch(CallContext, List<PageImageRef>, String proposedName) → DocumentTypeResponse`: runs the confirmation call, then either adds an alias or drafts a schema and creates the type, losing races gracefully.
  - `TypeVoteProcessor.tally(List<ResolvedVote>) → VoteOutcome(Optional<UUID> winner, List<UUID> tied, Map<UUID, Long> counts)`
  - `TypeVoteProcessor.byConfidence(List<ResolvedVote> all, List<UUID> tied) → UUID`
  - `ClassificationService.classify(UUID documentId)`

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
- [ ] **Step 3: Implement** §11. Make the initial runs through a bounded virtual-thread executor (`classification.parallelism`). Resolve every answer before tallying. On `DuplicateDocumentTypeException`, re-read by key. Save the result, set the status and publish `EXTRACT_DOCUMENT` in one transaction.
- [ ] **Step 4: Run** the tests and the build. Expected: PASS.

### Task 14: Extraction (process 3) and batch completion

**Files:**
- Create: `processors/DataSchemaValidator`, `service/ExtractionService(Impl)`, `service/BatchCompletionService(Impl)`
- Test: `processors/DataSchemaValidatorTests`, `service/ExtractionServiceTests`, `service/BatchCompletionServiceTests`

**Interfaces:**
- Consumes: Tasks 3, 4, 5, 6, 7 and 10.
- Produces:
  - `DataSchemaValidator.validate(JsonNode schema, JsonNode data) → List<String>`: error messages, empty when valid.
  - `ExtractionService.extract(UUID documentId)`
  - `BatchCompletionService.onDocumentFinished(UUID documentId)` and `onFileFailed(UUID sourceFileId)`. Both must run inside the caller's transaction, and both run the completion check:
    - lock the batch;
    - if every source file is `SEPARATED` or `FAILED` and every document is `COMPLETED` or `FAILED`, set the final status. All files separated and all documents completed → `COMPLETED`. No document completed → `FAILED`. Otherwise → `PARTIALLY_COMPLETED`.
    - then set `completedAt`, call `notifyBatchCompleted`, and publish `DELIVER_WEBHOOK` if the batch is async (setting `webhookStatus=PENDING`).
  - `BatchCompletionService.onBatchFailed(UUID batchId, String reason)`, used by intake when S3 fails.

- [ ] **Step 1: Write the failing tests.**
  - `threeInitialPassesThenConsolidateThenFinal()`: `extraction_run` rows are 3 `INITIAL`, 1 `CONSOLIDATED` and 1 `FINAL`, in that order.
  - `finalReceivesDraftAndImages()`
  - `invalidFinalRetriedOnceWithErrors()`: the second `finalizeExtraction` call receives a non-empty `validationErrors`.
  - `invalidTwiceThrows()`
  - `longDocumentTruncatedAt20Pages()`: 25 pages means 20 are sent and `truncated=true`.
  - `idempotentWhenResultExists()`
  - Completion:
    - `lastDocumentsCompleteBatchOnce()`: two documents from different files finish concurrently in two threads, giving exactly one `batch_completed` notification and one webhook outbox row.
    - `doesNotCompleteWhileAFileIsStillSeparating()`: every document of file 0 is done, but file 1 is `SEPARATING`, so the batch stays `PROCESSING`.
    - `failedFileWithOtherDocumentsCompletedIsPartiallyCompleted()`
    - `allFailedIsFailed()`
    - `syncBatchPublishesNoWebhook()`
    - `notLastDocumentDoesNothing()`
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** §12. Make the model calls outside a transaction. In the final transaction, save `extraction_result`, set the document `COMPLETED` and call `onDocumentFinished`.
- [ ] **Step 4: Run** the tests and the build. Expected: PASS.

### Task 15: Webhook delivery (process 4)

**Files:**
- Create: `client/WebhookClient`, `config/WebhookClientConfig`, `service/WebhookDeliveryService(Impl)`
- Test: `service/WebhookDeliveryServiceTests` (with a local HTTP server stub)

**Interfaces:**
- Consumes: Tasks 4, 5, 6, 11 (`BatchQueryService`) and 14.
- Produces: `WebhookDeliveryService.deliver(UUID batchId, UUID deliveryId)`. There is no signing in the proof of concept (§15).

- [ ] **Step 1: Write the failing tests.**
  - `postsBatchEnvelopeWithTwoHeaders()`: `X-Document-Reader-Batch` and `X-Document-Reader-Delivery`, and no signature header, with a body containing every document payload.
  - `2xxMarksDelivered()`
  - `500SchedulesRetry()` and `timeoutAfter10sSchedulesRetry()`
  - `redirectIsNotFollowedAndCountsAsFailure()`
  - `everyAttemptRecorded()`
  - `exhaustedMarksWebhookFailedButKeepsResult()`: `webhookStatus=FAILED`, the batch status is unchanged, and `GET` still returns the result.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** with `RestClient` built on a JDK `HttpClient` with `Redirect.NEVER` and a 10 s timeout. The body is the `BatchResultResponse` serialized with `JsonMapper`.
- [ ] **Step 4: Run** the tests and the build. Expected: PASS.

### Task 16: End-to-end pipeline and golden-document harness

**Files:**
- Create: `src/test/java/com/bnaqica/documentreader/PipelineEndToEndTests.java`, `src/test/java/com/bnaqica/documentreader/golden/GoldenDocumentTests.java` (tag `golden`, excluded from `test`), `src/test/resources/fixtures/packet-paystub-w2-bank.pdf`, `src/test/resources/fixtures/license.jpg`, and an `./gradlew goldenTest` task in `build.gradle`
- Modify: `README.md` (how to run the service, the endpoints and the golden suite), `docs/README.md` (links to the design and plan)

**Interfaces:**
- Consumes: everything above. The stub `ChatModel` is scripted per purpose.

- [ ] **Step 1: Write the failing tests.**
  - `syncBatchReturnsOnePayloadPerDocument()`: a batch of two files (a 3-document PDF and a single-page license photo) with `CAR_REFINANCE` returns 200 with four documents in order:
    - Pay stubs (file 0, pages 1–2)
    - W-2 forms (file 0, page 3)
    - Bank statements (file 0, pages 4–6)
    - Driver's license (file 1, page 1)

    Each has `data` that validates against its schema and a non-empty `summary`. `files[]` shows document counts of 3 and 1.
  - `asyncBatchDeliversOneWebhook()`: exactly one webhook, containing all four payloads.
  - `twoAppInstancesShareWorkWithoutDuplicates()`: two Spring contexts on one database and 10 batches give exactly 10 completed batches and no duplicate run rows.
  - `instanceKilledMidExtractionIsRecovered()`: the lease expires and a second instance finishes.
  - `goldenSuite()` runs only when `OPENAI_API_KEY` is set and the `golden` tag is selected. It runs on synthetic or sample documents only, and prints per-type accuracy, separation accuracy, and per-batch token totals from `openai_call`.
- [ ] **Step 2: Run** `./gradlew test --tests '*PipelineEndToEndTests'`. Expected: FAIL.
- [ ] **Step 3: Fix any integration gaps** the end-to-end tests expose, in the task that owns the code.
- [ ] **Step 4: Run** `./gradlew spotlessApply build`. Expected: BUILD SUCCESSFUL, with the JaCoCo report produced. Run `./gradlew goldenTest` manually with a real key before tuning the run counts.
