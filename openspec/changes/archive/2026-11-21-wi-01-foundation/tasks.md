# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail, implement, then run it and watch it pass. Nothing is committed; the developer commits WI-01 as one
commit.

## 1. Libraries

- [x] 1.1 Add the Spring AI 2.0.3 and AWS SDK 2.59.6 BOM imports to `build.gradle`, plus `spring-ai-starter-model-openai`, `software.amazon.awssdk:s3`, `pdfbox` 3.0.8, TwelveMonkeys `imageio-tiff` and `imageio-webp` 3.15.3, `tika-core` 4.2.0, `json-schema-validator` 3.0.9 and test-only `testcontainers-localstack`, each pinned with a "not in Boot BOM" comment. Verify that `./gradlew dependencies --configuration runtimeClasspath` resolves without conflicts, and that the existing `./gradlew build` still passes.

## 2. Processing settings and defaults

- [x] 2.1 Write `DocumentReaderPropertiesTests.bindsDefaults()` asserting every default in the spec's default requirements (batch, file, conversion, run counts, timing and retry, `s3.prefix = files/`, `s3.region = us-east-1`, no S3 endpoint). Verify it fails to compile or fails, because the class doesn't exist.
- [x] 2.2 Add `config/DocumentReaderProperties` (records per design decision 1), enable it with `@ConfigurationPropertiesScan`, and write the defaults under `app.document-reader` in `application.yml`. Verify that `bindsDefaults()` passes.
- [x] 2.3 Write `overriddenLimitKeepsOtherDefaults()` (`batch.max-files=5`) and verify it passes against 2.2.
- [x] 2.4 Write `minSuccessfulRunsDefaultsToMajority()` (5 → 3, 7 → 4) and `explicitMinSuccessfulRunsIsKept()` (5 → 5), and verify they fail. Implement the compact-constructor derivation (design decision 3), then verify they pass.

## 3. Rejecting invalid settings at startup

- [x] 3.1 Write `ApplicationContextRunner` tests: `rejectsEvenSeparationRuns()`, `rejectsEvenTiebreakRuns()`, `rejectsZeroExtractionPasses()` and `rejectsMinSuccessfulRunsAboveRuns()`, each asserting startup fails with a message containing the property name; and `acceptsOddRunCounts()` (separation 5, tie-break 3). Verify the rejection tests fail.
- [x] 3.2 Add `@Validated`, cascading `@Valid`, `@Positive` and the `@AssertTrue` checks (design decision 2), including positive durations and sizes. Verify all the 3.1 tests pass.

## 4. Upload limits, environment, local profile and threads

- [x] 4.1 Write `multipartLimitsFollowSettings()` (defaults give 50MB and 200MB from `MultipartProperties`) and `multipartLimitFollowsChangedFileLimit()` (`file.max-size=10MB` gives a 10MB `max-file-size`), and verify they fail. Wire `spring.servlet.multipart.*` to the settings (design decision 4), then verify they pass.
- [x] 4.2 Write `UploadLimitTests.fileOverLimitIsRefused()`: a test-only multipart endpoint with `file.max-size=10MB` refuses a 10.5MB part with HTTP 413, and accepts a 1MB part. Verify it passes after 4.1. If the response isn't 413, map `MaxUploadSizeExceededException` to a 413 `ProblemDetail` in `GlobalExceptionHandler`.
- [x] 4.3 Write `readsCredentialsAndLocationsFromEnvironment()`: with `OPENAI_API_KEY`, `OPENAI_MODEL` and `S3_BUCKET` set as properties, the bound values match. Verify it fails, then add `spring.ai.openai.api-key: ${OPENAI_API_KEY}`, `open-ai.model: ${OPENAI_MODEL:gpt-4o}` and `s3.bucket: ${S3_BUCKET}` to `application.yml`, with placeholders in the test `config/application.yml`. Verify it passes, and that `DocumentReaderApplicationTests` still starts without real credentials.
- [x] 4.4 Write `LocalProfileTests.localProfileUsesLocalStack()` (endpoint `http://localhost:4566`, path-style on, bucket `document-reader-local`) and `defaultProfileHasNoEndpointOverride()`, and verify they fail. Add the values to `application-local.yml`, then verify they pass.
- [x] 4.5 Write `VirtualThreadTests.requestRunsOnVirtualThread()`, using a test-only controller that returns `Thread.currentThread().isVirtual()`, and verify it fails. Set `spring.threads.virtual.enabled: true`, then verify it passes.
- [x] 4.6 Document the new environment variables (`OPENAI_API_KEY`, `OPENAI_MODEL`, `S3_BUCKET`) and the one-time `awslocal s3 mb s3://document-reader-local` command in `README.md`, and verify the README's local-run steps match `application-local.yml`.

## 5. Verification

- [x] 5.1 Run `./gradlew spotlessApply build` and verify BUILD SUCCESSFUL with every test green, including the existing security and context tests.
- [x] 5.2 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with `openspec/changes/wi-01-foundation/` as the requirements. Fix every Critical and Important finding, and verify the build again.
- [x] 5.3 Tick the boxes in `docs/workitems/WI-01-foundation.md` (except the developer review and commit), and verify that `git status` lists only the files this change names.

## Workflow follow-up

- The developer reviews the diff and commits WI-01 as one commit.
- After the commit, run `/opsx:archive` to merge `processing-configuration` into `openspec/specs/`.
