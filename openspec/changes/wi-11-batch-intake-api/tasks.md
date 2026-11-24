# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-11 as one commit.

## 1. Contracts

- [x] 1.1 Add the types with no behaviour, checked by compiling:
  - `dto/request/BatchUploadRequest`;
  - `dto/response/BatchResultResponse`, `FileResultResponse`, `DocumentResultResponse` and `BatchAcceptedResponse`,
    with `@Builder` records shaped as in §7.3 and design decision 5;
  - `dto/event/SeparateFilePayload(UUID sourceFileId)`;
  - `exception/BatchValidationException` with its `FileError(int index, String fileName, int status, String detail)`,
    and `exception/InvalidWebhookUrlException`;
  - the `service/BatchIntakeService` interface (`submit(BatchUploadRequest, Caller)`, returning a sealed
    `IntakeOutcome` of `Finished` and `Accepted`), and the `service/BatchQueryService` interface
    (`getResult(UUID, Caller)`).

## 2. Small pieces

- [x] 2.1 Write `processors/WebhookUrlValidatorTests`:
  - relative, `ftp://`, no host, blank and unparseable URLs are refused with "webhookUrl must be an absolute http or
    https URL";
  - `https://example.com/hook` and `http://localhost:9000/hook` are accepted.

  Watch the tests fail, implement `processors/WebhookUrlValidator` (design decision 2), and watch them pass.
- [x] 2.2 Write a test for `processors/Sha256.hex(InputStream)` that checks a known digest. Watch it fail, then
  implement it.

## 3. Validation (no writes)

- [x] 3.1 Write `service/BatchIntakeServiceTests` for the request fields (design decision 8 has the setup):
  - `missingLoanPurposeIs400ListingValidCodes()` and `unknownLoanPurposeIs400()`;
  - `noFilesIs400()` and `twentyOneFilesIs400()`;
  - `asyncTrueWithoutUrlIs400()`, `urlWithoutAsyncIs400()` and `relativeWebhookUrlIs400()`, each with its exact
    detail;
  - `httpAndHttpsWebhookUrlsAreAccepted()`.

  Watch them fail. Add `BatchIntakeServiceImpl` with the field checks in design decision 2's order, and watch them
  pass.
- [x] 3.2 Add the file checks, test-first:
  - `batchOver200MbIs413()`, with lowered limits;
  - `docxIs415NamingTheFile()`, whose entry has the index and the file name;
  - `fileOver50MbIs413()` and `fileOver100PagesIs422()`;
  - `severalFailuresUseFirstStatusInCheckOrder()`: file 0 has too many pages and file 1 is a Word document, so the
    answer is 415 and both are listed in index order;
  - `oneEncryptedPdfAmongValidFilesIs422AndStoresNothing()` (Review Focus #1): the batch, file and outbox counts
    are unchanged, and the storage fake is empty.

## 4. Storing and queueing

- [x] 4.1 Add these tests first:
  - `intakeOrdersWrites()`: the storage fake sees the batch and three files `RECEIVED` at the first put. Afterwards
    the files are `STORED` with the configured bucket and their keys, the batch is `PROCESSING`, and there are
    exactly three `SEPARATE_FILE` outbox rows, one per file;
  - `detectedTypeIsStoredAndNamesTheKey()`: a PNG declared as `application/octet-stream` records `image/png`, and a
    JPEG named `scan.final.JPEG` gets `/original.jpg`;
  - `batchRecordsCallerAndFileDetails()`: the batch records the subject, and each file records its SHA-256 and
    page count.

  Then implement transactions 1 and 2 and the puts (design decision 3).
- [x] 4.2 Add `s3FailureMarksBatchFailed()` first: the batch and every file are `FAILED` with "Storing the files
  failed", there are no `SEPARATE_FILE` rows, and the batch has one `batch_completed` notice. For a sync request the
  outcome is `Finished` with status `FAILED`. Then implement the failure transaction.
- [x] 4.3 Add `asyncReturnsAcceptedAtOnce()` first: `await` is never called. Then add
  `syncReturnsFinishedWhenCompletedInTime()` (`await` mocked to true) and `syncReturnsAcceptedOnTimeout()` (`await`
  mocked to false), each also verifying that `await` runs with no transaction active. Then implement the outcomes.

## 5. Result query

- [x] 5.1 Write `service/BatchQueryServiceTests`:
  - `resultListsDocumentsInFileThenPageOrder()`;
  - `failedFileIsListedWithNoDocuments()`;
  - `unclassifiedDocumentHasNullTypeAndClassification()`;
  - `clearVoteShowsRunsVotesAndAgreement()`;
  - `tieBreakShowsItsRound()`;
  - `modelCreatedTypeIsNew()`;
  - `extractedDocumentShowsSummaryAndData()`;
  - `getOtherCallersBatchIs404()`, which also covers an unknown id with the same message;
  - `adminReadsAnyBatch()`.

  Watch them fail, implement `BatchQueryServiceImpl` (design decision 5), and watch them pass.

## 6. HTTP

- [x] 6.1 Write `api/BatchControllerTests` (`@WebMvcTest`, services mocked):
  - `asyncReturns202WithStatusUrl()`;
  - `syncReturns200WhenCompletedInTime()`;
  - `syncReturns202OnTimeout()`;
  - `validationErrorKeepsItsStatusAndErrors()`, parameterized over 400, 413, 415 and 422, checking the detail and the
    `errors` entries;
  - `invalidWebhookUrlIs400()`;
  - `getReturnsTheEnvelope()`;
  - `getOtherCallersBatchIs404()`;
  - `requestsWithoutTokenAre401()`.

  Watch them fail. Implement `api/BatchController` and the `GlobalExceptionHandler` mappings, and watch them pass.

## 7. Audit and upload limits

- [x] 7.1 Write `config/ApiAuditFilterTests` (real server, design decision 8):
  - `auditPayloadExcludesFileBytes()`: the payload holds `loanPurpose` and each file's name, type, size and
    SHA-256, and none of the file's bytes;
  - `refusedUploadKeepsMetadataPayload()`: a 415 request whose payload still holds the metadata;
  - `oversizedUploadIsAuditedAs413()`: a row with status 413, `success=false` and a null payload.

  Watch them fail (the last one writes no row today), change `ApiAuditFilter` (design decision 6), and watch them
  pass.
- [x] 7.2 Add `largeRefusedUploadGets413Problem()` to `config/UploadLimitTests` first: a real 60 MB file against the
  default limits receives a 413 with a problem JSON body. Watch it fail with a reset connection, set
  `server.tomcat.max-swallow-size` (design decision 7), and watch it pass.

## 8. Docs and verification

- [x] 8.1 Update `README.md`: describe `POST /batches` and `GET /batches/{id}`, with the parts, the validation
  statuses, wait mode and 202, an example result envelope, and the swallow-size setting.
- [x] 8.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the
  WI-01 to WI-10 tests.
- [x] 8.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with
  `openspec/changes/wi-11-batch-intake-api/` as the requirements. Fix every Critical and Important finding, and verify
  the build again.
- [x] 8.4 Tick the boxes in `docs/workitems/WI-11-batch-intake-api-validation-audit-and-result-query.md` (except the
  developer review), set its status, and update its row in `docs/workitems/README.md`.

## Workflow follow-up

- The developer reviews the diff and commits WI-11 as one commit.
- Then archive `wi-11-batch-intake-api`.
