# Design

## Context

Everything separation needs already exists:
- **Records (WI-03):**
  - `SourceFileService`: `get`, `updateStatus`, and the `FAILED` reason.
  - `SourceFilePageService.saveAll` replaces a file's pages, and `findBySourceFile` reads them in page order.
  - `DocumentService.createAll(fileId, ranges)` sorts the ranges and numbers them 1, 2… It refuses overlapping or
    out-of-range pages. `uq_document_sequence (source_file_id, sequence)` refuses a second set of documents for a file.
- **Runs (WI-04):**
  - `SeparationRunService.save(fileId, NewSeparationRun)` and `findBySourceFile`.
  - `uq_separation_run_number (source_file_id, window_start, run_number)`.
  - `boundaries` is null exactly when the run failed.
- **Messaging (WI-05):**
  - An `InboxMessageHandler` gets `handle(message)` with no transaction open, and `onExhausted(message, lastError)`
    inside the transaction that marks the message `FAILED`.
  - `InboxMessageService.markDone`, and `OutboxPublisher.publish` (MANDATORY).
- **Storage and conversion (WI-07, WI-08):**
  - `DocumentStorageClient.get` and `put`, and `S3Keys.page(fileId, n)`.
  - `ImageConversionProcessor.toPngPages(bytes, type)` builds a list of every page. That's about 2 MB per page, so
    200 MB for a 100-page file, times `inbox.concurrency` (4) per worker.
- **Vision (WI-09):**
  - `VisionClient.separate(CallContext, List<PageImageRef>, List<String> catalogNames)` returns
    `SeparationAnswer(startPages, labels)`.
  - It throws `VisionException`, and `IllegalStateException` inside a transaction.
  - It sends only the images, with no page numbers. So the model numbers a window's pages 1…n.
- **Completion (WI-10):** `BatchCompletionService.onFileFailed` runs inside the caller's transaction. The caller sets
  the file's status first, and must not load the batch or its other files first.
- **Config:** `DocumentReaderProperties.Separation(runs, windowSize, windowOverlap)` checks that `runs` is odd. Nothing
  checks the overlap.
- **The type:** a stored file's detected type is its `content_type`. Its extension is in the original's S3 key, and
  `DetectedType` maps the MIME type back.

## Goals / Non-Goals

**Goals:**
- The `document-separation` behaviour in the delta spec.
- At most one rendered page in memory at a time.
- Idempotent on retry and on redelivery.

**Non-Goals:**
- Classification (WI-13).
- Dropping truly blank pages (§9 lets the builder; not needed now).
- Separation run parallelism settings.

## Decisions

### 1. `BoundaryVoteProcessor` is pure functions

`processors/BoundaryVoteProcessor` is a stateless `@Component`, with no Spring or I/O dependencies:

- **`sanitize(List<Integer> startPages, int pageCount) → SortedSet<Integer>`:** keeps the pages in `2..pageCount`,
  without duplicates, sorted. A null list is treated as empty.
- **`windows(int pageCount, int size, int overlap) → List<PageRange>`:**
  - It starts at 1, and each next window starts `size − overlap` pages later. A window ends at
    `min(start + size − 1, pageCount)`, and the list stops at the window that reaches `pageCount`.
  - `windows(40, 15, 2)` gives 1–15, 14–28 and 27–40.
  - It throws `IllegalArgumentException` unless `1 ≤ overlap < size`. The configuration already guarantees that
    (decision 6), so this only guards direct callers.
- **`voteWindows(List<WindowRuns> windows, int pageCount) → List<PageRange>`** (not a `vote` overload, because both
  parameter lists erase to `List`):
  - `WindowRuns(PageRange window, List<SortedSet<Integer>> runs)` holds the window's successful runs, as absolute
    page numbers.
  - For each p in `2..pageCount`, it picks the window that holds p−1 and p, and has the largest
    `min((p − 1) − start, end − p)`. On a tie, it picks the earlier window.
  - p is a boundary when `2 × (runs containing p) > runs.size()`.
  - The boundaries, plus page 1, become the ranges.
- **`vote(List<SortedSet<Integer>> runs, int pageCount)`:** the work item's single-window form. It calls
  `voteWindows` with one window covering 1–pageCount.

**Why "furthest from an edge" is measured that way:** a gap at a window's edge has only one page of context on that
side. With the default overlap of 2, the one gap two windows share sits on an edge of both, so the tie rule picks the
earlier window.

### 2. The step's order (`SeparationServiceImpl.separate`)

The step does all its work in `separate(sourceFileId)`. `handle(message)` calls it with `message.aggregateId()` and
then lets `InboxProcessingService` mark the message done. The final transaction also marks it done (`markDone` is
idempotent), so the documents and the done mark commit together.

1. **Check the file.** It reads the file. If the file is `SEPARATED` or `FAILED`, it logs at `debug` level and
   returns (idempotency). Otherwise, it sets the file to `SEPARATING` in a short transaction.
2. **Get the pages.** If `findBySourceFile` already holds `pageCount` pages, it reuses them. Otherwise it:
   1. reads the original from S3, and maps `content_type` back to a `DetectedType`;
   2. converts page by page (decision 3), putting each PNG to `S3Keys.page` as it's rendered and keeping only its
      number, key and size;
   3. then calls `SourceFilePageService.saveAll` in one transaction.

   A crash halfway leaves some objects without rows. The next attempt converts again, and overwrites them.
3. **A single page** gives one range, 1–1, with no call.
4. **Otherwise, for each window from `windows(...)`:**
   1. It reads that window's saved runs.
   2. It makes `runs − successful` new calls, with run numbers after the highest saved one.
   3. The calls run concurrently on virtual threads, at most `separation.runs` at a time, with no transaction open.
   4. Each call's result is saved straight away in its own transaction. A `VisionException` saves a failed run.
   5. If fewer than `runs / 2 + 1` successful runs exist for the window, it throws
      `IllegalStateException("Too few successful separation runs")`, and the inbox retries.
5. **Store runs in absolute numbers.** Each answer's start pages are shifted by `windowStart − 1` before they are
   saved as `SeparationBoundary(startPage, label)`, with labels matched by index (null when missing). The vote
   re-derives each run's set as `sanitize(stored − windowStart + 1, windowLength)`, shifted back. So saved runs and
   fresh runs are handled the same way.
6. **The final transaction:**
   - `DocumentService.createAll(fileId, ranges)`;
   - `SourceFileService.updateStatus(fileId, SEPARATED, null)`;
   - one `publish(CLASSIFY_DOCUMENT, documentId, new ClassifyDocumentPayload(documentId))` per document;
   - `InboxMessageService.markDone(messageId)` (only when called from `handle`).

   If another delivery got there first, `uq_document_sequence` rolls this transaction back. The retry then finds the
   file `SEPARATED`.

**Prompt inputs:**
- `CallContext(batchId, fileId, null, loanPurposeDescription)`, where the description comes from
  `LoanPurposeService.get(batch.loanPurposeCode())`. The batch is read once at the start; this isn't the completion
  path, so the WI-10 ordering rule doesn't apply.
- The catalog names come from `DocumentTypeService.catalog()`.

**Logging:** file and batch ids, page, window and run counts, and the number of documents. Never labels or file names.

### 3. Conversion goes page by page

`ImageConversionProcessor.forEachPngPage(bytes, type, Consumer<ConvertedPage>)`:
- renders and hands over one page at a time, using the same per-type code;
- `toPngPages` becomes a collector over it, so WI-08's behaviour and tests stay as they are.

A 100-page file then holds one rendered page, about 2 MB, at a time, not 200 MB. A HEIC file is a single page either
way.

### 4. The exhausted path

`onExhausted(message, lastError)` runs inside the inbox's `FAILED` transaction:
- It locks the file with the new `SourceFileService.lockForUpdate` (`SELECT … FOR UPDATE`, MANDATORY), the same lock
  the final transaction takes first. If the file is `SEPARATED`, because a late attempt committed first, it returns.
- Otherwise it calls `SourceFileService.updateStatus(fileId, FAILED, "Separation failed")`, then
  `BatchCompletionService.onFileFailed(fileId)`.

**Assumption:** `lastError` isn't copied into the reason. It can hold an exception message, and the file's reason is
shown to callers in the result envelope.

**The opposite order (from the code review).** An attempt whose lease expired can still be calling the model when
another attempt is exhausted, fails the file and completes the batch. So the final transaction (step 6 of decision 2)
starts by locking the file and goes on only while it is still `SEPARATING`; otherwise it creates nothing. Whichever of
the two commits first wins, in either order.

**Page count (from the code review).** If conversion yields a different number of pages than intake counted, the step
throws, naming both counts, instead of failing later on a window that runs past the pages.

### 5. The prompt reads correctly for a window

`prompts/separate.st` says: "The images are consecutive pages of one uploaded file, in order, numbered from 1 at the
first image." The rule "Page 1 always starts a document" stays, and the code drops page 1 anyway. The rest of the
prompt is unchanged.

### 6. The overlap check

`Separation` gets `@AssertTrue(message = "window-overlap must be at least 1 and less than window-size")
isWindowOverlapValid()`. The WI-01 note asked for `overlap < size`; an overlap of 0 is also refused, because:
- with an overlap of 0, the gap between two windows lies in neither window, so it could never be decided;
- the existing `@PositiveOrZero` stays, and the new check is stricter.

### 7. Tests

- **`processors/BoundaryVoteProcessorTests`:** plain unit tests.
  - `majorityOfThreeDecidesEachGap`, `pageOneAlwaysStarts`, `sanitizeDropsOutOfRangeDuplicatesAndOne`,
    `emptyRunMeansOneDocument`;
  - `windowsOverlap`, `windowsWithinOneWindow` and `windowsRefuseBadOverlap`;
  - `gapInTwoWindowsUsesMoreCentralWindow` (the 1–10 / 6–15 case) and `gapOnBothEdgesUsesEarlierWindow`.
- **`service/SeparationServiceTests`:**
  - `@DataJpaTest` with real CRUD services, the outbox publisher, real `ImageConversionProcessor` (which
    `@MockitoSpyBean` checks for conversions), and the `RecordingStorage` fake from WI-11;
  - `VisionClient` is mocked with scripted answers, and its calls record whether a transaction is active;
  - `BatchCompletionService` is mocked for the exhausted test; `@Transactional(NOT_SUPPORTED)`.
  - Files are created stored, with real PDF bytes in the fake.
  - The tests: `singlePageSkipsModel`, `convertsOnceAndReusesPagesOnRetry`,
    `savesRunsCreatesDocumentsMarksSeparatedAndPublishesOneClassifyPerDocument`,
    `filesOfOneBatchSeparateIndependently`, `longFileIsSentInWindows`, `retryReusesSuccessfulRuns`,
    `lessThanMajorityOfRunsSucceedingThrows`, `idempotentWhenDocumentsAlreadyExist`,
    `exhaustedFailsFileAndRunsCompletionCheck`, `exhaustedLeavesSeparatedFileAlone` and
    `callsRunOutsideTransactions`.
- **`config/DocumentReaderPropertiesValidationTests`** gets the overlap cases.
- **`processors/ImageConversionProcessorTests`** gets `forEachPngPageHandsOverPagesInOrder`.

### 8. The test JVM gets 1 GB

The full test suite ran out of the test JVM's default 512 MB heap once this change added its test context. Spring
caches every test configuration's context for the whole run, each with its own connection pool and Hibernate model.
`build.gradle` sets `maxHeapSize = '1g'` on the test task (found while applying). Production memory is unaffected.

## Risks / Trade-offs

- **[Lease]** A 100-page file has 8 windows. With each window's 3 calls made concurrently at up to 120 s each, the
  worst case is about 16 minutes against a 10-minute lease. → Typical calls take 10–30 s, so a few minutes. If a
  lease does expire, the reclaiming attempt reuses every saved run. The cost is time, never a duplicate.
- **[Orphaned page objects]** An attempt that crashes between the puts and `saveAll` leaves S3 objects that the next
  attempt overwrites. They are never orphaned for good.
- **[Overlapping attempts pick the same run numbers]** Both take `max(saved) + 1`, so the slower one's saves hit
  `uq_separation_run_number` and that attempt fails, wasting its calls. → It can't produce a duplicate run or document,
  and the file lock settles which attempt's outcome stands. This only happens after a lease expires.
- **[A run that fails on content]** A `VisionException` for an invalid answer is saved as a failed run and counts
  against the majority, the same as a timeout.

## Open Questions

None. The work item leaves every choice above to the builder (§9 [builder], §21).
