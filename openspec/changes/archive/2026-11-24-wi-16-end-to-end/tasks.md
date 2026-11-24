# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-16 as one commit.

## 1. Fixtures and the stub model

- [x] 1.1 Add `FixtureGenerator` (design decision 1), run it once, and commit its output to
  `src/test/resources/fixtures/`: `packet-paystub-w2-bank.pdf` and `license.jpg`. Check by opening both, and by
  `ImageConversionProcessor.pageCount` giving 6 and 1.
- [x] 1.2 Write `PipelineChatModelTests` first. For each purpose, a prompt built from the real template, with fixture
  page images attached, gets the expected answer:
  - separation starts {1, 3, 4} for the packet;
  - each page's type;
  - schema-valid extract data;
  - an unknown prompt throws.

  Then implement `PipelineChatModel` (design decision 2).

## 2. End-to-end tests

- [x] 2.1 Write `PipelineEndToEndTests` with `syncBatchReturnsOnePayloadPerDocument()` (design decision 3) and run it.
  Fix each integration gap it exposes in its owning step, test-first there (design decision 5), and record each fix
  in this task, until the test passes.
  - No gaps: it passed on the first run. Breaking the stub's separation answer made it fail, so it does check.
- [x] 2.2 Add `asyncBatchDeliversOneWebhook()`, and make it pass the same way.
- [x] 2.3 Add `twoAppInstancesShareWorkWithoutDuplicates()`, and make it pass the same way.
- [x] 2.4 Add the stub's crash hook and `instanceKilledMidExtractionIsRecovered()`, and make it pass the same way.
  - No gaps in 2.2 to 2.4 either. The deviations from the design are listed under "Changes made while building" in
    `design.md`.

## 3. Golden suite

- [x] 3.1 Add `src/test/resources/golden/expectations.json` and `golden/GoldenDocumentTests` (design decision 4).
  Exclude the `golden` tag from `test`, and add the `goldenTest` task. Check that `./gradlew test` doesn't run it, and
  that `./gradlew goldenTest` without a key skips it.

## 4. Docs and verification

- [x] 4.1 Update `README.md` and `docs/README.md` (design decision 6).
- [x] 4.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green and the JaCoCo report
  produced.
- [x] 4.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with
  `openspec/changes/wi-16-end-to-end/` as the requirements. Fix every Critical and Important finding, and verify the
  build again.
- [x] 4.4 Tick the boxes in `docs/workitems/WI-16-end-to-end-pipeline-and-golden-document-harness.md` (except the
  developer review), set its status, and update its row in `docs/workitems/README.md`.

## Workflow follow-up

- The developer reviews the diff and commits WI-16 as one commit.
- The developer runs `./gradlew goldenTest` with a real key before tuning the run counts. That costs real OpenAI
  calls, so it is never run automatically.
- Then archive `wi-16-end-to-end`.
