# WI-12: Separation (process 1, per file)

| | |
|---|---|
| **Status** | Not started |
| **Depends on** | [WI-03](WI-03-core-records.md), [WI-04](WI-04-run-and-result-records-plus-the-openai-call-log.md), [WI-05](WI-05-inbox-outbox-engine.md), [WI-07](WI-07-s3-storage-client.md), [WI-08](WI-08-content-detection-and-conversion-to-png.md), [WI-09](WI-09-vision-client.md), [WI-10](WI-10-batch-completion.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §9 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 12 |

**Goal:** Split each file into documents by voting on page boundaries, then queue classification for each document.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `service/SeparationService(Impl)`, `processors/BoundaryVoteProcessor`
- Test: `processors/BoundaryVoteProcessorTests`, `service/SeparationServiceTests`

**Interfaces:**
- Consumes: Work items WI-03, WI-04, WI-05, WI-07, WI-08 and WI-09, and `BatchCompletionService.onFileFailed` (WI-10).
- **Pages (from WI-08's review):** `ImageConversionProcessor.toPngPages` returns every page at once, about 2 MB per PNG, so up to 200 MB for a 100-page file. If that is too much for the worker heap, add a per-page variant (for example a `Consumer<ConvertedPage>`) and store each page in S3 before rendering the next.
- Produces:
  - `BoundaryVoteProcessor.sanitize(List<Integer> startPages, int pageCount) → SortedSet<Integer>`
  - `BoundaryVoteProcessor.vote(List<SortedSet<Integer>> runs, int pageCount) → List<PageRange>`
  - `BoundaryVoteProcessor.windows(int pageCount, int size, int overlap) → List<PageRange>`
  - `SeparationService.separate(UUID sourceFileId)`

The service implementation also implements `InboxMessageHandler` (WI-05) for its message type: `handle` calls the step, and `onExhausted` runs the failure path. Listeners follow `listener -> @Service to CRUD @Service interface -> ...` (`.claude/rules/service-architecture.md`): the inbox listener calls `InboxProcessingService`, which calls this service, which changes data only through CRUD services.

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

## Notes from the WI-01 review

The WI-01 code review raised these. Handle each in this work item, test-first.

- `separation.window-overlap` isn't checked against `separation.window-size`. An overlap equal to the window size gives a step of 0, and the windowing loops forever. Add `@AssertTrue` for `windowOverlap < windowSize` to `DocumentReaderProperties`, with a test, before writing `windows(...)`.

---

## Done when

- [ ] Every step above is ticked.
- [ ] `./gradlew spotlessApply build` passes, with all tests green.
- [ ] `git status` shows only the files this work item names, plus their tests and fixtures.
- [ ] A code review (`superpowers:requesting-code-review`, using the `code-reviewer` agent) has no open findings.
- [ ] The developer has reviewed the diff.

## Commit

Claude never commits in this repo (`.claude/rules/git-workflow.md`). The developer commits this work item as one
commit, with this suggested message:

```text
feat: add per-file document separation
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
