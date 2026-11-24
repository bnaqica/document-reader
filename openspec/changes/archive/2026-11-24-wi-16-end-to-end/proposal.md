# Proposal

## Why

WI-01 to WI-15 each proved their own step in isolation, with neighbouring steps stubbed or skipped. Nothing yet
proves that a real batch flows from `POST /batches` through separation, classification, extraction and completion to
its result and webhook, or that several instances share the work and recover from a crashed one (§20 of
`docs/design/2026-11-20-document-reader-design.md`). There is also no way to measure how well the real model separates
and classifies before the run counts are tuned. This is WI-16, the last item in `docs/workitems/`.

## What Changes

- **End-to-end pipeline tests** (`PipelineEndToEndTests`) run the real application against Testcontainers Postgres and
  LocalStack S3, with every worker running. Only OpenAI is replaced, by a stub `ChatModel`:
  - **A sync batch** of two files (a three-document packet PDF and a one-page licence photo) returns 200 with four
    documents in file order, then page order. They are Pay stubs (pages 1–2), W-2 forms (page 3), Bank statements
    (pages 4–6) and Driver's license or government photo ID (file 1). Each has schema-valid `data` and a summary.
  - **The same batch sent async** gets exactly one webhook, carrying all four documents.
  - **Two application instances** on one database process 10 batches: all 10 complete, and no run row is duplicated.
  - **An instance that crashes mid-extraction** leaves its message leased. A second instance reclaims it once the lease
    expires, and finishes the batch.
- **A stub `ChatModel`** (`PipelineChatModel`) reads each call's purpose from its prompt. It works out which document
  it is looking at from a coloured marker band that each fixture page carries, and answers each purpose. Separation
  returns where the marker colour changes, classification names the marker's type, and extraction returns
  schema-valid data. So the real `SpringAiVisionClient`, prompts and answer schemas all run.
- **Two committed fixtures:** `src/test/resources/fixtures/packet-paystub-w2-bank.pdf` (six pages) and
  `src/test/resources/fixtures/license.jpg`. They are synthetic, readable documents with a marker band, made by a
  checked-in generator (`FixtureGenerator`) that can recreate them.
- **The golden suite** (`GoldenDocumentTests`, tag `golden`) runs only through `./gradlew goldenTest` and only when
  `OPENAI_API_KEY` is set. It sends the same synthetic fixtures to the real model, then prints:
  - per-type classification accuracy;
  - separation accuracy;
  - the tokens per batch, from `openai_call`.

  `./gradlew test` and `build` exclude it.
- **Integration gaps** the end-to-end tests expose are fixed in the code of the work item that owns them, each with a
  test.
- **Docs:** `README.md` covers running the service, its endpoints, and the golden suite. `docs/README.md` links the
  design, the plan, the work items and `openspec/`.

## Capabilities

### New Capabilities

- `document-pipeline`: the guarantees a batch gets from the whole pipeline. That covers its result and webhook, work
  shared between instances, recovery from a crashed instance, and the on-demand measurement against the real model.

### Modified Capabilities

None. Each step's own capability already holds its behaviour. A gap found by the end-to-end tests is fixed against
the requirement already there, or recorded as a delta if it changes one.

## Impact

- **New test code:**
  - `PipelineEndToEndTests`, `PipelineChatModel`, `FixtureGenerator`, `golden/GoldenDocumentTests`;
  - the two fixture files and a golden expectations file.
- **Build:** `build.gradle` excludes the `golden` tag from `test` and adds a `goldenTest` task. There are no new
  dependencies, since Testcontainers LocalStack is already a test dependency.
- **Main code:** only fixes for gaps the tests expose.
- **Docs:** `README.md` and `docs/README.md`.
