# Design

## Context

Intake and the result query reuse what earlier work items built. Nothing in the schema changes.

- **Records (WI-03):**
  - `BatchService.create(NewBatch)` starts a batch as `RECEIVED`, and throws not-found for an unknown loan purpose.
  - `BatchService.updateStatus` moves a batch between statuses that aren't final.
  - `SourceFileService.createAll` gives the files indexes 0, 1, 2… in list order. It also provides `markStored`,
    `updateStatus` and `findByBatch`.
  - `DocumentService.findByBatch` already returns documents in file order, then page order.
- **Results and catalog:**
  - `ClassificationRunService.findByDocument`, `ClassificationResultService.find`, `ExtractionResultService.find`.
  - `DocumentTypeService.findAll`, which carries each type's `origin`.
- **Callers:**
  - `LoanPurposeService.findActive` and `findAllActive`.
  - `dto/Caller` (subject and admin flag, `mayRead`), which the record controllers already use.
  - `RecordQueryServiceImpl` already answers 404 for another caller's batch.
- **Detection and storage:**
  - `ContentTypeDetector.detect(bytes)` gives a `DetectedType` with its MIME type and extension, or throws
    `UnsupportedDocumentException`.
  - `ImageConversionProcessor.pageCount(bytes, type)` throws `UnreadableDocumentException` for a password-protected,
    corrupt or oversized file.
  - `DocumentStorageClient.put` throws `StorageException`, and `S3Keys.original(fileId, ext)` builds the key.
  - `DocumentReaderProperties.s3().bucket()` gives the bucket.
- **Messaging and waiting:**
  - `OutboxPublisher.publish` requires a transaction (`MANDATORY`).
  - `BatchCompletionService.onBatchFailed` (WI-10) also runs inside the caller's transaction.
  - `CompletionWaitService.await` refuses to run inside a transaction.
- **Existing gaps:**
  - The audit filter reads the raw body. For a refused multipart upload that throws `Stream closed`, and no audit row
    is written (the WI-01 review).
  - Tomcat's `max-swallow-size` is still the 2 MB default.
  - `ResponseEntityExceptionHandler` already turns `MaxUploadSizeExceededException` into a 413 problem.

## Goals / Non-Goals

**Goals:**
- `POST /batches` and `GET /batches/{id}`, as the delta specs describe.
- Nothing stored for a refused batch, and exactly one `SEPARATE_FILE` per file for an accepted one.
- An audit payload holding metadata only, and an audit row for every refused upload.

**Non-Goals:**
- Processing `SEPARATE_FILE` (WI-12) and delivering webhooks (WI-15).
- Webhook security: signing, SSRF and DNS checks are deferred (§22).
- Cleaning up S3 objects left behind when a later upload in the batch fails.
- Releasing in-flight waits at shutdown.

## Decisions

### 1. The controller maps; `BatchIntakeService` decides

- **The controller:**
  - binds the parts into `BatchUploadRequest(List<MultipartFile> files, String loanPurpose, String async, String
    webhookUrl)`. `async` stays a string, so a value that isn't a boolean gets intake's own 400 ("async must be true or
    false"), not Spring's conversion message (from the code review);
  - builds the `Caller`, the same way the record controllers do (`@CurrentSecurityContext`);
  - calls `BatchIntakeService.submit(request, caller)`.
- **There are no bean-validation annotations.** The §7.1 checks need exact messages in a fixed order, and they
  stop at the first failure. Annotations report every failing field, in no fixed order.
- **`submit` returns a sealed `IntakeOutcome`:**
  - `Finished(BatchResultResponse)` becomes a 200;
  - `Accepted(batchId, status)` becomes a 202 `BatchAcceptedResponse`;
  - the controller adds `statusUrl` with `ServletUriComponentsBuilder.fromCurrentContextPath()`, because only it
    knows the request URL.

  Choosing between 200 and 202 is a pattern match on the type, so the controller holds no logic.
- **Alternative considered:** returning `ResponseEntity` from the service. It was rejected, because it would pull
  HTTP types into the service.

### 2. Validation order, and two exceptions

`BatchIntakeServiceImpl.validate` runs these checks in order. Before any of them, a caller whose token has no
subject is refused with `AccessDeniedException` (403), because `batch.requested_by` can't be null and no one could
read the batch (from the code review).

1. **The loan purpose.**
   - Missing, or not an active code → 400 "loanPurpose must be one of: <active codes, comma-separated>".
2. **The file count.**
   - No files, or more than `batch.max-files` → 400 "Send between 1 and <max> files".
3. **`async` and `webhookUrl`.**
   - The two §7.1 messages.
   - Then `WebhookUrlValidator.validate`, which throws `InvalidWebhookUrlException`. A URL passes only when
     `URI.create` parses it as absolute, with an `http` or `https` scheme and a non-blank host. No DNS lookup
     is made.
4. **The total size.**
   - The sum of `MultipartFile.getSize()` over `batch.max-total-size` → 413 "The files total <n> MB; the limit is
     <max> MB".
   - This is checked before any file is read, so a too-large batch is never parsed.
5. **Each file, in index order**, stopping at that file's first failure:
   1. `detect` → 415 (the `UnsupportedDocumentException` message);
   2. the file's size over `file.max-size` → 413 "The file is larger than <max> MB";
   3. `pageCount` → 422 (the `UnreadableDocumentException` message);
   4. pages over `file.max-pages` → 422 "The file has <n> pages; the limit is <max>".

**Exceptions and the response:**
- Steps 1, 2, 4 and 5 throw `BatchValidationException(HttpStatus status, String detail, List<FileError> errors)`,
  where `FileError` is `(int index, String fileName, int status, String detail)`. Steps 1, 2 and 4 have an empty
  list.
- Step 5 collects every file's error before it throws.
  - The response status is the first of 415, 413 and 422 found among the errors. That's how this design reads the
    work item's "validation order".
  - The detail is "<n> file(s) can't be accepted".
- `GlobalExceptionHandler` maps:
  - `BatchValidationException` to its status and detail, with the `errors` array when the list isn't empty;
  - `InvalidWebhookUrlException` to a 400.
- `fileName` comes back in the problem only to the caller who sent it. It is never logged.

**Assumptions:**
- **The declared type** is kept only for the audit. The detected type decides, and it is what `source_file.content_type`
  stores. A phone that declares `application/octet-stream` still has its PNG accepted.
- **The validation pass** keeps each file's detected type, size, SHA-256 and page count, and drops its bytes before
  reading the next file. The storing pass reads the bytes again. That way, at most one file's bytes are in memory at a
  time, not the whole batch. Spring keeps uploaded parts on disk.

### 3. Three transactions, with the wait outside them

`BatchIntakeServiceImpl` isn't `@Transactional`. It uses `TransactionTemplate`:

- **Transaction 1:**
  - `BatchService.create(new NewBatch(code, async, url, caller.subject(), n))`;
  - `SourceFileService.createAll`.
- **The S3 puts**, with no transaction open:
  - each file goes to `S3Keys.original(fileId, detectedType.extension())`;
  - the detected extension always passes `S3Keys`, so the key can't be refused. The original file name never reaches
    a key.
- **Transaction 2:**
  - `markStored(fileId, properties.s3().bucket(), key)` for each file;
  - `BatchService.updateStatus(batchId, PROCESSING, null)`;
  - one `OutboxPublisher.publish(SEPARATE_FILE, fileId, new SeparateFilePayload(fileId))` per file.
  - `dto/event/SeparateFilePayload(UUID sourceFileId)` is new here; WI-12 reads it.
- **If S3 fails**, or anything else fails while storing (such as re-reading a part), a failure transaction runs
  instead of transaction 2. Catching every `RuntimeException`, not just `StorageException`, keeps a batch from staying
  `RECEIVED` for good (from the code review):
  - `SourceFileService.updateStatus(fileId, FAILED, "Storing the files failed")` for each file;
  - then `BatchCompletionService.onBatchFailed(batchId, "Storing the files failed")`, which sends the notice and,
    for an async batch, queues the webhook.
  - The `StorageException` is logged at `error` level with the batch id and isn't rethrown. The caller gets the
    `FAILED` envelope (200) or a 202, the same way as any other final status (§7.1).
  - So `GlobalExceptionHandler` needs no `StorageException` mapping. That answers the WI-07 review's question.
- **Afterwards:**
  - an async batch returns `Accepted`;
  - a sync one calls `CompletionWaitService.await(batchId, wait.timeout)` with no transaction open, then returns
    `Finished(query.getResult(...))` or `Accepted`.

**Alternative considered:** answering the S3 failure with a 503. It was rejected, because it gives the caller a second
shape for a processing failure, and an async caller would still get the webhook.

### 4. The wait blocks a virtual thread

`spring.threads.virtual.enabled` is on, so a blocking `await` costs no platform thread. `DeferredResult` would let a
client disconnect end the wait sooner, but the only cost of not doing so is a waiter kept until `wait.timeout`.
Processing carries on either way, and WI-06 already removes waiters cleanly (Review Focus #4). Graceful shutdown
already caps how long in-flight requests delay a stop (`spring.lifecycle.timeout-per-shutdown-phase`, 30 s by
default). So waits aren't released separately.

### 5. `BatchQueryService` builds the envelope

`BatchQueryServiceImpl.getResult(UUID id, Caller caller)` is an action service, running
`@Transactional(readOnly = true)`.

**Who can read:**
- It reads the batch, and answers `ResourceNotFoundException("Batch", id)` unless `caller.mayRead(requestedBy)`.
- That is the same rule and message as `RecordQueryServiceImpl`, so an unknown id and another caller's batch look
  alike.
- It takes `Caller` rather than the work item's `Jwt`. That way the `local` profile's anonymous admin works the same
  way it does for the record endpoints.

**Files:**
- From `findByBatch`, already in index order.
- `documentCount` is counted from the batch's documents.

**Documents:**
- From `DocumentService.findByBatch`, already in file order, then page order.
- For each document, it reads the classification runs and result, and the extraction result.
- Type names come from one `DocumentTypeService.findAll` per query.

**Field rules:**
- **`votes`:** counted from the successful runs of each round, grouped by their resolved type's name. Taking them from
  the runs, rather than from `classification_result.votes`, keeps the envelope independent of how WI-13 fills that
  column.
- **`runs`:** the number of those successful runs.
- **`agreement` and `decidedBy`:** come from the result.
- **`tieBreak`:** null unless `tieBroken`. When it's set, it holds `{ runs, votes, decidedBy }` for the `TIEBREAK`
  round.
- **`newType`:** `origin == MODEL`. A type the model created stays flagged as new for every document classified as
  it, which tells the caller the type wasn't in the curated catalog. Tracking "created by this document" would need
  `createdByDocumentId` added to `DocumentTypeResponse`, for no clear gain.
- **`documentType` and `classification`:** null until the document has a result.
- **`summary` and `data`:** null until the document has an extraction result.
- **`status`:** the batch's status is returned as it is. Each `RECEIVED` and `PROCESSING` value maps to itself.

The query reads per document (N+1). A batch has at most 20 files of 100 pages each, and it is read rarely, so this
is fine for the proof of concept.

### 6. The audit filter reads parts, not the body, for multipart

`ApiAuditFilter.payload` branches on a `multipart/` content type:

- **For multipart, it calls `request.getParts()` before the chain.** Spring deletes the parts' temporary files once
  the request is handled, so reading them afterwards fails with `NoSuchFileException` (found while applying). Tomcat
  parses the parts once and keeps them, or the parse failure, for the request, so the handler sees the same parts and
  Spring still answers an oversized upload with its 413.
- **Each part is recorded as follows:**
  - a part without a file name gives `field → value`, capped at its first 2,048 bytes, because a field part can be as
    large as a file part (from the code review);
  - each file part adds `{ name, contentType, size, sha256 }` under `files`;
  - the SHA-256 is computed by streaming the part.
- **If `getParts()` throws** (an upload over the limits, which Tomcat refused while parsing), the payload is null. The
  row is still written with the response status.
- **The raw input stream is never read for multipart.** That is what threw `Stream closed` before.
- **Other requests** keep today's body capture.

**Shared helpers and JSON:**
- One `processors/Sha256` helper (`hex(InputStream)`) computes the digest for both intake and the filter.
- The JSON is built with the Jackson 3 `JsonMapper`.

### 7. Tomcat swallows up to the batch limit

`server.tomcat.max-swallow-size: ${app.document-reader.batch.max-total-size}`, with a comment.

- After Tomcat refuses an oversized upload, it reads and discards up to this much of the body before it answers. So a
  60 MB file against the 50 MB limit gets its 413 problem.
- Anything bigger than a whole batch may still be cut off. That's acceptable for a request far beyond every limit.
- `-1` (unlimited) was rejected, because it would let any client make the server read without limit.

### 8. Tests

- **`processors/WebhookUrlValidatorTests`** is a plain unit test.
- **`service/BatchIntakeServiceTests`:**
  - `@DataJpaTest` with real CRUD services, the outbox publisher, `ContentTypeDetector`, `ImageConversionProcessor`
    and `BatchCompletionServiceImpl`;
  - an in-memory `DocumentStorageClient` fake that records each put, checks the database state at the moment of the
    put (for `intakeOrdersWrites`), and can be told to fail;
  - `CompletionWaitService` is mocked (`await` returns true or false), with `@Transactional(NOT_SUPPORTED)`.
  - It covers every validation rule, nothing stored on refusal, write order, the S3 failure, the async and sync
    outcomes, and the detected type and key.
  - Fixtures come from `ConversionFixtures`: `pdf(n)`, `passwordProtectedPdf()` and `png(…)`.
  - The size limits are lowered through properties (for example, `file.max-size=1MB`), so no test builds 50 MB.
- **`service/BatchQueryServiceTests`:**
  - `@DataJpaTest`, with rows set up through the CRUD services;
  - it covers ordering, counts, votes, tie-break, `newType`, the summary and data, the nulls before each stage, 404
    for another caller, and admin access.
- **`api/BatchControllerTests`:**
  - `@WebMvcTest(BatchController.class)` with `SecurityConfig`, and the intake and query services mocked;
  - it checks that the controller and `GlobalExceptionHandler` map each outcome and exception to the right status
    and body: 200 for `Finished`, 202 for `Accepted` with `statusUrl`, each exception status, the `errors` array,
    401 without a token, and 404 from the query.
- **`config/ApiAuditFilterTests`:**
  - `@SpringBootTest(RANDOM_PORT)` with a real `HttpClient`, because MockMvc skips Tomcat's multipart parsing;
  - `DocumentStorageClient` is a test bean, and `workers.enabled=false`;
  - it covers the metadata payload, a refused (415) upload's payload, and a 413 upload's row.
- **`config/UploadLimitTests`** gains `largeRefusedUploadGets413Problem()`. It sends a real 60 MB file, and asserts the
  413 status and the problem's JSON body.

## Risks / Trade-offs

- **[Orphaned objects]** If the second of three puts fails, the first object stays in S3 with a `FAILED` file. →
  Accepted for the proof of concept, and the key holds only ids. The design doesn't call for a cleanup job.
- **[Container 413s don't name the file]** Spring refuses a file over `file.max-size` while parsing the upload, before
  intake runs. So a real oversized file gets a 413 with no `errors` entry. Intake's own size checks apply only when the
  container's limits are configured higher than the processing limits. → See Open Questions.
- **[Status precedence]** "first failing file's status (validation order: 400, 415, 413, 422)" can also be read as
  "the status of the lowest-index failing file". This design picks precedence by check order. Both readings list
  every file.
- **[Transaction 2 fails after the puts]** The batch stays `RECEIVED` with its objects in S3, and the caller gets a
  500. → Rare, because it means Postgres failed between two transactions. It is visible through `/records/batches`.
- **[N+1 reads]** These are bounded by the batch limits; see decision 5.

## Open Questions

None left.
- **Resolved (developer, while proposing):** the container limits stay as §17 sets them, so a real file over 50 MB
  gets a plain 413 without the file named. Raising `spring.servlet.multipart.max-file-size` to
  `batch.max-total-size`, so that intake could name the file, was rejected.
