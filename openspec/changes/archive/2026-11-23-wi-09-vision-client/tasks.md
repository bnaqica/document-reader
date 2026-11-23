# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-09 as one commit.

## 1. Settings

- [x] 1.1 In `DocumentReaderPropertiesValidationTests`, add `blankModelIsRefused()` (an empty `OPENAI_MODEL` stops startup, naming `app.document-reader.open-ai.model`). In a new `config/OpenAiSettingsTests` (it starts the OpenAI auto-configuration), add `onlyTheChatModelIsSetUp()` (a `ChatModel` bean, and no `EmbeddingModel`, `ImageModel`, `TextToSpeechModel`, `TranscriptionModel` or `ModerationModel` bean), `sdkRetriesTwice()`, `callTimesOutAfterTwoMinutes()` and `chatModelFollowsOurModel()` (`spring.ai.openai.chat.model` resolves to `app.document-reader.open-ai.model`). Verify they fail, add `@NotBlank` to `OpenAi.model` and the settings in design decision 7 to `application.yml`, then verify they pass.

## 2. Contract

- [x] 2.1 Add `client/VisionClient`, the records in design decision 1 (`CallContext`, `PageImageRef` and the five answers) and `exception/VisionException`. No behaviour; checked by compiling.
- [x] 2.2 Add the answer schemas in `src/main/resources/prompts/schemas/` with a test, `config/AnswerSchemasTests`: each schema passes `DataSchemaRules`. Verify it fails (files missing), write the schemas, then verify it passes.

## 3. The client

- [x] 3.1 Write `config/SpringAiVisionClientTests` with the stub `ChatModel` (design decision 8): `classifySendsImagesCatalogAndPurpose()` (N PNG media in page order, every catalog name and description, the purpose), `classifyUsesClassificationTemperature()` (and the configured model), `everyCallNamesTheConfiguredModel()`. Verify they fail, implement `SpringAiVisionClient.classify`, the prompt templates and `config/AiConfig` (design decisions 2, 4 and 5), then verify they pass.
- [x] 3.2 Add the other calls, test-first, one at a time: `separateSendsCatalogNames()` (separation temperature), `tiebreakListsOnlyCandidates()`, `confirmNewTypeSendsProposedName()` (and `matchesExisting` without a name is invalid), `draftSchemaMustPassSchemaRules()`, `extractRequestsStructuredOutputWithSchema()` (strict `JSON_SCHEMA` wrapping the type schema), `consolidateSendsNoImages()` (holds the schema and every pass), `finalizeSendsDraftAndValidationErrors()`.
- [x] 3.3 Add the failure cases: `httpErrorBecomesVisionException()` (the SDK's `RateLimitException`, status 429 on the exception), `invalidJsonBecomesVisionException()`, `emptyAnswerBecomesVisionException()`, `confidenceOutOfRangeIsInvalid()`, `missingPageImageBecomesVisionException()` (a `StorageException`; nothing sent, nothing logged). Verify they fail, implement, then verify they pass.
- [x] 3.4 Add the logging cases: `everyCallRecordsOpenAiCall()` (`batchId`, `sourceFileId`, `documentId`, purpose, model, latency, tokens and status 200; the record type has no body field, and its error is null), `failedCallIsRecordedWithStatusAndError()`, `separationIsRecordedWithoutDocument()`, `recordFailureStillReturnsAnswer()`, `callInsideTransactionIsRefused()` (nothing sent, nothing logged). Verify they fail, implement design decision 6, then verify they pass.

## 4. Docs and verification

- [x] 4.1 Update `README.md`: the OpenAI settings (model, SDK retries, timeout), the call log, and that calls never run inside a transaction. Add the image `detail` note (design, Risks) to `docs/workitems/WI-16-end-to-end-pipeline-and-golden-document-harness.md`.
- [x] 4.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the WI-01 to WI-08 tests.
- [x] 4.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with `openspec/changes/wi-09-vision-client/` as the requirements. Fix every Critical and Important finding, and verify the build again.
- [x] 4.4 Tick the boxes in `docs/workitems/WI-09-vision-client.md` (except the developer review and commit), set its status, and update its row in `docs/workitems/README.md`.

## Workflow follow-up

- The developer reviews the diff and commits WI-09 as one commit.
- Then archive `wi-09-vision-client`.
