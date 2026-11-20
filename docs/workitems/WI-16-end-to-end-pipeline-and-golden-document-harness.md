# WI-16: End-to-end pipeline and golden-document harness

| | |
|---|---|
| **Status** | Not started |
| **Depends on** | All of WI-01 to WI-15, each committed first |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §20 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 16 |

**Goal:** Prove the whole pipeline end to end, including two instances sharing the work, and add the on-demand golden suite against real OpenAI.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

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
test: add end-to-end pipeline tests and golden harness
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
