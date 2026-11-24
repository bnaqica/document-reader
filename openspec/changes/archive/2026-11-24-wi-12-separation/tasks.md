# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-12 as one commit.

## 1. Configuration

- [x] 1.1 Add the overlap cases to `config/DocumentReaderPropertiesValidationTests` first (design decision 6):
  - overlap 15 with window size 15 fails to start, naming the overlap setting;
  - overlap 0 fails the same way;
  - the default 15 and 2 starts.

  Watch the failing cases fail, then add the `@AssertTrue` to `DocumentReaderProperties.Separation`.

## 2. Voting

- [x] 2.1 Write `processors/BoundaryVoteProcessorTests` for `sanitize`:
  - `sanitizeDropsOutOfRangeDuplicatesAndOne()`: `[0, 3, 3, 9, 2]` over 5 pages gives `{2, 3}`;
  - `sanitizeTreatsNullAsEmpty()`.

  Watch them fail, then implement `sanitize` (design decision 1).
- [x] 2.2 Add the `windows` tests first:
  - `windowsOverlap()`: `windows(40, 15, 2)` gives `[1–15, 14–28, 27–40]`;
  - `windowsWithinOneWindow()`: `windows(6, 15, 2)` gives `[1–6]`;
  - `windowsRefuseBadOverlap()`.

  Then implement `windows`.
- [x] 2.3 Add the `vote` tests first:
  - `majorityOfThreeDecidesEachGap()`: `{1,3}`, `{1,3,5}` and `{1}` over 6 pages give `[1–2, 3–6]`;
  - `pageOneAlwaysStarts()`;
  - `emptyRunMeansOneDocument()`;
  - `gapInTwoWindowsUsesMoreCentralWindow()`: windows 1–10 and 6–15, where the gap 8|9 is decided by 6–15;
  - `gapOnBothEdgesUsesEarlierWindow()`: windows 1–15 and 14–28, gap 14|15.

  Then implement both `vote` forms.

## 3. Conversion page by page

- [x] 3.1 Add `forEachPngPageHandsOverPagesInOrder()` to `processors/ImageConversionProcessorTests` first: a 3-page PDF
  hands over pages 1, 2 and 3, each a PNG. Watch it fail, add `forEachPngPage` (design decision 3), and make
  `toPngPages` collect through it. Every existing conversion test must stay green.

## 4. The separation step

- [x] 4.1 Add `dto/event/ClassifyDocumentPayload(UUID documentId)` and the `service/SeparationService` interface
  (`separate(UUID sourceFileId)`). These are checked by compiling.
- [x] 4.2 Write `service/SeparationServiceTests` (design decision 7 has the setup):
  - `singlePageSkipsModel()`: no vision call, one document covering 1–1, the file `SEPARATED`, and one
    `CLASSIFY_DOCUMENT` row;
  - `savesRunsCreatesDocumentsMarksSeparatedAndPublishesOneClassifyPerDocument()`: 6 pages with the three scripted
    answers above give 3 saved runs, documents 1–2 and 3–6 with sequences 1 and 2, the file `SEPARATED`, and two
    `CLASSIFY_DOCUMENT` rows with the documents' ids;
  - `callsRunOutsideTransactions()`.

  Watch them fail. Implement `SeparationServiceImpl` with steps 1 to 4 and 6 of design decision 2, and watch them
  pass.
- [x] 4.3 Add these tests first, then the code for them:
  - `convertsOnceAndReusesPagesOnRetry()`: a first attempt fails on its runs, and the second doesn't convert again
    (spy);
  - `retryReusesSuccessfulRuns()`: with two successful saved runs, exactly one more call is made;
  - `lessThanMajorityOfRunsSucceedingThrows()`: two of three calls fail, so the step throws, no document is created,
    and the file stays `SEPARATING`.
- [x] 4.4 Add `longFileIsSentInWindows()` first: a 20-page file with window 15 and overlap 2 sends pages 1–15 and
  14–20, with answers read in window numbering (design decision 2, step 5). Then the code for it.
- [x] 4.5 Add these tests first, then the code for them:
  - `filesOfOneBatchSeparateIndependently()`;
  - `idempotentWhenDocumentsAlreadyExist()`: a second `separate` on a `SEPARATED` file makes no call and adds no
    document or message.

## 5. Inbox handler and failure

- [x] 5.1 Add these tests first:
  - `handleSeparatesAndMarksMessageDone()`: `type()` is `SEPARATE_FILE`, and `handle` separates the message's file
    and marks the message done;
  - `exhaustedFailsFileAndRunsCompletionCheck()`: the file becomes `FAILED` with "Separation failed", and
    `onFileFailed` is called for it;
  - `exhaustedLeavesSeparatedFileAlone()`.

  Then implement the `InboxMessageHandler` side (design decision 4).
- [x] 5.2 Update `prompts/separate.st` (design decision 5), and check that `config/SpringAiVisionClientTests` still
  passes.

## 6. Docs and verification

- [x] 6.1 Update `README.md` with how a file is separated: the windows, the runs, the vote, the single-page shortcut,
  the failure path, and the overlap rule.
- [x] 6.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the WI-01
  to WI-11 tests.
- [x] 6.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with
  `openspec/changes/wi-12-separation/` as the requirements. Fix every Critical and Important finding, and verify the
  build again.
- [x] 6.4 Tick the boxes in `docs/workitems/WI-12-separation.md` (except the developer review), set its status, and
  update its row in `docs/workitems/README.md`.

## Workflow follow-up

- The developer reviews the diff and commits WI-12 as one commit.
- Then archive `wi-12-separation`.
