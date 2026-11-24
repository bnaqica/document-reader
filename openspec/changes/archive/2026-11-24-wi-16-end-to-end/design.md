# Design

## Context

What already exists:
- **Pipeline steps:** every step has a handler: `SEPARATE_FILE` (WI-12), `CLASSIFY_DOCUMENT` (WI-13), `EXTRACT_DOCUMENT`
  (WI-14) and `DELIVER_WEBHOOK` (WI-15). Intake (WI-11) and completion (WI-10) are in place.
- **Workers (WI-05):**
  - `WorkerConfig` runs the outbox dispatcher, the inbox worker loops and the `LISTEN` connection when
    `app.document-reader.workers.enabled` is true. The test config turns them off by default.
  - Each instance has its own instance id, used for `locked_by`.
  - `InboxProcessingServiceImpl.processSafely` catches even an `Error` from a handler and leaves the message
    `PROCESSING` until its lease expires. That is how a crashed attempt looks to the other instances.
- **OpenAI (WI-09):**
  - `SpringAiVisionClient` sends each purpose's prompt (`prompts/*.st`), with the page images as media, to the
    `ChatModel` bean.
  - The prompts are told apart by their fixed text. For example, classify says "Decide what type of document it is",
    and extract says "Read the data the schema below asks for".
  - The extract, consolidate and final prompts include the type's schema as text.
- **Storage:** `S3DocumentStorageClientTests` already starts LocalStack 4.4 through Testcontainers.
- **Seeded types:** "Pay stubs", "W-2 forms", "Bank statements" and "Driver's license or government photo ID". Every
  top-level property of each is nullable, so data with every required field null (plus any filled field) is valid.
- **Conversion:** pages are rendered to PNG, at most 2048 px on the longest side. A solid colour band survives
  rendering, and survives JPEG compression to within a small distance.

## Goals / Non-Goals

**Goals:**
- The `document-pipeline` delta spec, proved against the real application wiring.
- The only stand-in is OpenAI, and the webhook receiver is a local HTTP server.

**Non-Goals:**
- Tuning run counts or prompts. The golden suite gives the numbers, and tuning is a later, manual step.
- Sending page images with `detail: high` (WI-09's note). The golden suite's printout is how to judge whether it's
  needed.

## Decisions

### 1. Fixtures carry a colour marker; a generator makes them

`src/test/java/.../FixtureGenerator` (a `main` method, run by hand) writes the two committed fixtures. The tests don't
regenerate them; they read the committed files.

- **`packet-paystub-w2-bank.pdf`:** six letter pages. Pages 1–2 are a pay stub, page 3 a W-2, and pages 4–6 a bank
  statement, each with readable synthetic text (employer, amounts, periods) for the golden suite. Across the top
  10 % of each page runs a solid band in its document's colour: pay stub red, W-2 green, bank statement blue.
- **`license.jpg`:** a landscape card image with a yellow band and synthetic licence text.

The colours are far apart, so JPEG and resampling can't confuse them. The marker carries no meaning for the real
model, which reads the text.

### 2. `PipelineChatModel`: a stub that answers by purpose and marker

`src/test/java/.../PipelineChatModel` implements `ChatModel`.

**Telling the purpose:** it reads the user message text and matches each prompt's fixed sentence. That covers
separate, classify, tiebreak, confirm, draft-schema, extract, consolidate and final. An unknown prompt throws, so a
changed template fails loudly.

**Telling the document:** it decodes each attached image (`ImageIO`) and reads the colour of the pixel at 50 % width,
5 % height. The colour is matched to the nearest of the four marker colours.

**Answers**, each as the JSON the purpose's answer schema expects:
- **separate:** the window-numbered pages where the marker colour differs from the page before, plus page 1, with a
  label per segment.
- **classify / tiebreak:** the marker's type name, with confidence 0.9.
- **confirm:** no match. **draft-schema:** never expected; it throws.
- **extract / final:** the schema is parsed out of the prompt text. The data sets every required property to null,
  except one string property (the first one), which gets a value made from the type. The summary is "Synthetic <type>".
- **consolidate:** the same data and summary, with no disagreements.

**Usage:** each response carries usage (100 prompt and 10 completion tokens), so `openai_call` rows are realistic.

**Hooks for the crash test:** a `Predicate<String> crashOn` (purpose) and a `CountDownLatch`. When set, the matching
call throws a `SimulatedCrash extends Error`.

### 3. `PipelineEndToEndTests`: the real application with workers on

**Setup:**
- `@SpringBootTest(webEnvironment = RANDOM_PORT)`, with `workers.enabled=true` and a short poll interval
  (`outbox.poll-interval=200ms`).
- `inbox.lease=3s` for the crash test, and `inbox.backoff-initial=200ms`.
- Test configuration supplies:
  - Postgres from `TestcontainersConfiguration`;
  - a LocalStack container, with `s3.endpoint` and credentials set through `@DynamicPropertySource` and the bucket
    created before the context starts;
  - a `PipelineChatModel` as the `@Primary` `ChatModel`;
  - a mock `JwtDecoder` that accepts any token, as in `UploadLimitTests`.
- Requests go through a real `HttpClient` as multipart, the same as `ApiAuditFilterTests`.
- The webhook receiver is a JDK `HttpServer`, as in WI-15.

**The tests:**
- **`syncBatchReturnsOnePayloadPerDocument`:**
  - It sends the packet and the licence, sync, with `wait.timeout` long enough (60 s).
  - It asserts the 200 envelope as the spec scenario describes.
  - It validates each document's data against its type's schema with `DataSchemaValidator`.
- **`asyncBatchDeliversOneWebhook`:** 202, then it waits up to 60 s for one POST. It asserts the body, waits a further
  2 s, and checks that no second POST arrived.
- **`twoAppInstancesShareWorkWithoutDuplicates`:**
  - A second context starts with `SpringApplicationBuilder` on the same database and LocalStack, with workers on and
    no web server.
  - Ten async batches are sent. The test waits until all ten are final.
  - It asserts that each is `COMPLETED`, that `separation_run`, `classification_run` and `extraction_run` hold the
    exact expected counts (no duplicates), and that both instances' ids appear in `inbox_message.locked_by`, so the
    work really was shared.
- **`instanceKilledMidExtractionIsRecovered`:**
  - Instance A's stub crashes on its first extract call. A is then closed.
  - Instance B, already running, reclaims the message after the 3 s lease and finishes.
  - The test asserts the batch is `COMPLETED`, and that the reclaimed message is `DONE` with `attempts` of 2.

The second context is started and closed inside its test, so it doesn't leak into the cached contexts.

### 4. The golden suite

**`golden/GoldenDocumentTests`:**
- It is tagged `golden` and runs only when `OPENAI_API_KEY` is set (`@EnabledIfEnvironmentVariable`).
- It uses the same application setup as decision 3, but with the real `ChatModel`.
- It reads `src/test/resources/golden/expectations.json`, which lists each batch's files and its expected documents
  (file, page range, type).

**What it does:**
- Runs each batch sync.
- Compares the result to the expectations: separation accuracy is correct boundaries over expected boundaries, and
  classification accuracy is per type.
- Prints a table, with tokens per batch summed from `openai_call`.
- Fails only if the pipeline errors. Accuracy is reported, not asserted, because the suite exists to measure.

**`build.gradle`:**
- `test` excludes the `golden` tag.
- `tasks.register('goldenTest', Test)` includes only `golden`, uses the same classpath, and is never wired into
  `check`.

### 5. Gaps found on the way

Anything the end-to-end tests show is broken is fixed in the owning step's code, with a test in that step's own test
class. Each fix is added to tasks 2.x as it is found, and listed in the review summary. If a fix changes a requirement
of an existing spec, the change gets a delta for it.

### 6. Docs

**`README.md` gains:**
- "Running the service": the prerequisites, the local profile, sending a batch with `curl`, and reading the result.
- An overview of the endpoints.
- "Golden suite": what it measures, how to run it (`OPENAI_API_KEY=… ./gradlew goldenTest`), and that it never uses
  real borrower data.

**`docs/README.md`:**
- It now says `openspec/` is in use, with the current specs under `openspec/specs`.
- It links the archived changes.

## Changes made while building

- **Both instances use their own container setup, not `TestcontainersConfiguration`.** That configuration gives each
  context its own Postgres, so two instances couldn't share one. `PipelineTestSupport` starts one Postgres and one
  LocalStack for the JVM and gives both instances the same properties.
- **The second instance gets its properties as command-line arguments.** `SpringApplicationBuilder.properties` sets
  only defaults, which `application.yml`'s `${DB_URL}` outranks.
- **The second instance runs a web server on a random port, and leaves the test sources out of its scan.** A plain
  `SpringApplication` has no `@SpringBootTest` exclude filter, so it would pick up other tests' nested configurations
  and controllers. A small `TypeExcludeFilter` leaves out every class compiled from the test sources.
- **Sharing is shown by each stub's call count, not by `locked_by`.** `markDone` clears `locked_by`, so a finished
  message no longer shows who handled it. Each instance's `PipelineChatModel` counts the calls it answered, and the
  test checks that both counts went up.
- **The crash happens on the final reading, not the first extract call.** The extract passes run on an executor that
  wraps an `Error` in an `IllegalStateException`, which turns a crash into an ordinary failure and a retry after the
  backoff. The final reading runs on the handler's own thread, so its `Error` reaches the inbox, which leaves the
  message claimed until its lease expires. The test pauses this instance's `InboxWorker` while instance A claims and
  crashes, so that A is the one to claim it.
- **The golden suite sits in the root test package**, next to the pipeline tests, since the package rules allow no
  new top-level packages.

## Fixes from the code review

- **Only crash instance A has the 3 s lease.** With the short lease on every instance, a round of four separations
  could outlast it. The other instance then handled the same file again, and the test saw 31 separation runs instead
  of 30. Leases and retries now keep the application's defaults, and only A, which claims the message and dies, gets
  `inbox.lease=3s` (`startInstance(configuration, extra)`). The two-instance test also checks that every inbox
  message of its ten batches is `DONE` after exactly one attempt.
- **Golden separation is recall and precision over boundaries after page 1.** Page 1 always starts a document, and
  counting only the expected starts that were found would give a model that splits every page 100 %.
- **The golden suite reports every batch before it fails,** and fails only for a batch that doesn't reach a final
  status. A `FAILED` batch is reported like any other. With no retry settings of its own, it uses the application's
  backoff (10 s), not fast test retries against the paid API.
- **`PipelineEndToEndTests` is `@DirtiesContext`,** so its workers stop polling the shared database once the class
  is done, rather than at JVM exit.
- **The scan filter compares against the test classes' own location,** so it works with any build tool's output folder.
- **The webhook receiver shuts its executor down on close.**

## Risks / Trade-offs

- **[Slow tests]** Two contexts and LocalStack add about 30–60 s to `./gradlew build`. That's acceptable for the only
  full-pipeline proof. The tests use short poll intervals.
- **[Timing]** Every wait polls the database with a deadline (60 s) instead of sleeping a fixed time. The second-POST
  check waits 2 s on purpose.
- **[The stub ties tests to prompt text]** A template edit means a one-line stub change, and it fails loudly rather
  than silently.
- **[Heap]** Another cached context. The test JVM has 1 GB since WI-12. If it runs out, the WI-16 contexts are marked
  `@DirtiesContext`.

## Open Questions

None. §20 and the work item settle the scope.
