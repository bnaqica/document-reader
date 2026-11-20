# WI-01: Foundation: dependencies, configuration and local S3

| | |
|---|---|
| **Status** | Not started |
| **Depends on** | none |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §3, §17 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 1 |

**Goal:** Add every library the service needs and bind all `app.document-reader.*` settings with their defaults, so later work items only add code.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

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
feat: add dependencies and document-reader configuration
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
