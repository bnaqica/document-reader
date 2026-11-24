# WI-11: Batch intake API, validation, audit and result query

| | |
|---|---|
| **Status** | Done |
| **Depends on** | [WI-02](WI-02-reference-data.md), [WI-03](WI-03-core-records.md), [WI-05](WI-05-inbox-outbox-engine.md), [WI-06](WI-06-notifications-and-wait-mode.md), [WI-07](WI-07-s3-storage-client.md), [WI-08](WI-08-content-detection-and-conversion-to-png.md), [WI-10](WI-10-batch-completion.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §6, §7, §16, §18 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 11 |

**Goal:** Expose `POST /batches` and `GET /batches/{id}`: validate the whole batch, store it, queue separation, and wait or return 202.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `api/BatchController`, `dto/request/BatchUploadRequest`, `dto/response/BatchResultResponse`, `FileResultResponse`, `DocumentResultResponse`, `BatchAcceptedResponse`, `service/BatchIntakeService(Impl)`, `service/BatchQueryService(Impl)`, `processors/WebhookUrlValidator`, `exception/InvalidWebhookUrlException`, `exception/BatchValidationException` (which carries a list of `FileError(int index, String fileName, int status, String detail)`)
- Modify: `exception/GlobalExceptionHandler` (415, 413, 422 and 400 mappings, plus the `errors` array), `config/ApiAuditFilter` (payload for multipart)
- Test: `api/BatchControllerTests`, `service/BatchIntakeServiceTests`, `processors/WebhookUrlValidatorTests`, `config/ApiAuditFilterTests`

**Interfaces:**
- Consumes: Work items WI-02, WI-03, WI-05, WI-06, WI-07 and WI-08, and `BatchCompletionService.onBatchFailed` (WI-10) for the S3 failure path.
- **Storage (from WI-07's review):** take `source_file.s3_bucket` from `DocumentReaderProperties.s3().bucket()`, because the storage contract doesn't expose it. `S3Keys.original` refuses an extension that isn't 1–10 letters or digits, so derive the extension from the detected content type, or turn the refusal into the per-file 422. `GlobalExceptionHandler` has no handler for `StorageException` yet; choose its status (503 is likely) rather than letting it become a generic 500.
- **Waiting (from WI-06's review):** call `CompletionWaitService.await` outside any transaction (it refuses inside one), after the batch and its files are committed. Tomcat doesn't interrupt a request thread when the client disconnects, so a blocking wait runs to `wait.timeout`; use `DeferredResult` (cancelling on `onError` and `onTimeout`) if a disconnect must end it sooner. In-flight waits also delay graceful shutdown until they time out; decide whether to release them on shutdown.
- Produces:
  - `POST /batches` (multipart: repeated `files`, `loanPurpose`, `async`, `webhookUrl`)
  - `GET /batches/{id}`
  - `BatchQueryService.getResult(UUID id, Jwt caller) → BatchResultResponse`. It returns 404 for another caller's batch; the 404 avoids revealing that the batch exists. Admins can read all.
  - `WebhookUrlValidator.validate(String url)`, which throws `InvalidWebhookUrlException`.
  - The envelope shape is exactly §7.3: `files[]` ordered by index, and `documents[]` ordered by file index and then page. The public batch status maps `RECEIVED → RECEIVED`, and `PROCESSING → PROCESSING`.
  - When a batch has several file errors, the response status is the first failing file's status (validation order: 400, 415, 413, 422), and every failing file is listed in `errors`.

- [x] **Step 1: Write the failing tests.**
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
- [x] **Step 2: Run** the tests. Expected: FAIL.
- [x] **Step 3: Implement the intake** in the order of §6:
  1. validate the request fields;
  2. for every file, sniff the type, check the size and count pages, collecting all errors before throwing `BatchValidationException`;
  3. transaction 1: create the batch and its source files;
  4. put each original to S3;
  5. transaction 2: `markStored` for each file, set the batch to `PROCESSING`, and publish one `SEPARATE_FILE` per file;
  6. if the request is not async, call `await(batchId, wait.timeout)`.

  The webhook check only confirms an absolute URL with an `http` or `https` scheme (§7.1). It does no DNS lookups.
- [x] **Step 4: Run** the tests and the build. Expected: PASS.

## Notes from the WI-01 review

The WI-01 code review raised these. Handle each in this work item, test-first.

- Tomcat's `server.tomcat.max-swallow-size` defaults to 2 MB. A real 60 MB upload against the 50 MB limit is cut off after 2 MB, and many clients then see a connection reset, not the 413 `ProblemDetail`. Set it to at least `batch.max-total-size` (or `-1`), with a comment and a test that sends well over the limit.
- A refused upload (413) currently writes no audit row: `ApiAuditFilter` hits `IOException: Stream closed` reading the payload. If that hasn't already been fixed separately, it must be fixed before this work item ships, with a test that a 413 upload produces an audit row with `success=false` and status 413.

---

## Done when

- [x] Every step above is ticked.
- [x] `./gradlew spotlessApply build` passes, with all tests green.
- [x] `git status` shows only the files this work item names, plus their tests and fixtures.
- [x] A code review (`superpowers:requesting-code-review`, using the `code-reviewer` agent) has no open findings.
- [x] The developer has reviewed the diff.

## Commit

Claude never commits in this repo (`.claude/rules/git-workflow.md`). The developer commits this work item as one
commit, with this suggested message:

```text
feat: add batch intake and result API
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
