# Design

## Context

The build already has Spring AI 2.0.3 (`spring-ai-starter-model-openai`), which wraps the official `openai-java` SDK
4.49. `OpenAiCallService.record` (WI-04) writes the call log in its own transaction. `DocumentStorageClient.get`
(WI-07) reads page PNGs by key. `DataSchemaRules` (WI-02) already checks that a data schema suits OpenAI strict
structured output, and every seeded type's schema passes it. `DocumentReaderProperties.OpenAi` holds the model and
three temperatures, with no checks. See `proposal.md` for why.

What Spring AI 2.0.3 does differently from the work item's notes:
- The chat model is set with `spring.ai.openai.chat.model`, not `spring.ai.openai.chat.options.model`.
- `OpenAiChatModel` doesn't use Spring AI's `RetryTemplate`, so `spring.ai.retry.*` has no effect on it. Retries come
  from the SDK, set with `spring.ai.openai.chat.max-retries` (default 3).
- Each optional model has its own switch: `spring.ai.model.embedding`, `image`, `audio.speech`,
  `audio.transcription` and `moderation`, each set to `none` to turn it off.
- It sends images without OpenAI's `detail` field, so OpenAI uses `auto` (see Risks).

Rules that shape this:
- `client` holds the contract only; the implementation and wiring go in `config`, like `S3DocumentStorageClient`;
- exceptions go in `exception`; records that aren't entities or API bodies go in `dto`;
- logs carry ids, statuses and counts only;
- Jackson 3 (`tools.jackson.*`) only. `openai-java` brings Jackson 2, which must never be injected by mistake.

## Goals / Non-Goals

**Goals:**
- The `VisionClient` interface in `docs/workitems/WI-09-vision-client.md`, which WI-12 to WI-14 call.
- The five WI-01 review notes on OpenAI settings, each pinned by a test.

**Non-Goals:**
- Voting, tie-break rules, retries of single runs, separation windows, or cleaning separation answers: WI-12 to
  WI-14 own those. The client makes one call and returns one answer.
- Checking extracted data against the type's schema: WI-14 does that, and passes the errors to
  `finalizeExtraction`.
- Running calls in parallel: callers decide (`classification.parallelism`).
- A real OpenAI call in tests. CI always uses a stub `ChatModel`.

## Decisions

### 1. Contract and records

- `client/VisionClient` with the eight methods from the work item. Each takes
  `dto/CallContext(UUID batchId, UUID sourceFileId, UUID documentId, String loanPurposeDescription)`, where
  `documentId` is null for separation.
- `dto/PageImageRef(int pageNumber, String s3Key)`.
- The answer records in `dto/`: `SeparationAnswer(List<Integer> startPages, List<String> labels)`,
  `ClassificationAnswer(String documentType, double confidence, String reasoning)`,
  `TypeMatchAnswer(boolean matchesExisting, String existingName)`, `ExtractionAnswer(JsonNode data, String summary)`
  and `ConsolidatedAnswer(JsonNode data, String summary, List<String> disagreements)`.
- `exception/VisionException(OpenAiCallPurpose purpose, String reason[, Integer httpStatus][, Throwable cause])`.
  Its message names the purpose, the reason and the status, never content. Callers read `purpose()` and
  `httpStatus()`.

*Alternative:* reuse the existing `dto/PageImage`, which also carries the width and height. The work item names
`PageImageRef`, and the client only needs the key.

### 2. One call path: `ChatModel`, a JSON schema response format, and our own parsing

`config/SpringAiVisionClient` builds each request as a `Prompt` of one system message (the step's instructions) and
one user message (the step's inputs, plus the page images as `Media` with `image/png`). The request carries
`OpenAiChatOptions` with:
- the configured model, on every call, so Spring AI's own default model is never used;
- the step's temperature;
- `ResponseFormat` of type `JSON_SCHEMA`, strict, with the answer's schema.

It calls `chatModel.call(prompt)` and reads the answer text and the token usage from the one `ChatResponse`. It parses
the text with the application's Jackson 3 `JsonMapper` into the answer record.

*Alternative:* `ChatClient` with `.entity(Class)`. It is shorter, but it parses the answer itself, so the token usage
and the difference between "the call failed" and "the answer wasn't JSON" are harder to get at. It also adds format
instructions to the prompt that we don't want to test around. One `ChatModel` call gives the usage, the HTTP error and
the raw text in one place.

### 3. Answer schemas

- The fixed answers (separation, classification, type match, consolidation) use hand-written strict JSON Schemas in
  `src/main/resources/prompts/schemas/*.json`. A test runs each through `DataSchemaRules`, so they meet the same
  strict rules as the catalog's schemas.
- Extraction and final extraction wrap the type's schema: `{data: <type schema>, summary: string}`, strict. Type
  schemas already pass `DataSchemaRules`, so the wrapper is strict too.
- Consolidation's `data` follows the type's schema in the same way.
- Schema drafting can't use a fixed schema (the answer *is* a schema), so it asks for `JSON_OBJECT`. The answer must
  pass `DataSchemaRules`, or the call raises a `VisionException` ("drafted schema breaks the schema rules"). The
  violations name the drafted properties, so they aren't attached to it.
- `DataSchemaRules` gains two checks strict mode needs: only the keywords strict mode accepts (`type`, `properties`,
  `required`, `additionalProperties`, `items`, `anyOf`, `enum`, `const`, `description`, `format`, `pattern`, the number
  bounds and `minItems`/`maxItems`), and objects nested at most `MAX_DEPTH` (9) deep, leaving one level for the
  extraction wrapper within OpenAI's limit of 10 (arrays don't count as a level). It also refuses `anyOf` or `items`
  at the root, and a property, item or branch that isn't a schema object (such as `true`). This also applies to
  schemas set through the catalog API, so the change carries a delta for `document-type-catalog`.
- After parsing, required values are checked: a non-blank type name, a confidence from 0 to 1, non-null lists, and
  an `existingName` when `matchesExisting` is true. A failed check raises a `VisionException` with reason "invalid
  answer".

### 4. Prompts

- One template per step in `src/main/resources/prompts/`: `separate.st`, `classify.st`, `tiebreak.st`,
  `confirm-type.st`, `draft-schema.st`, `extract.st`, `consolidate.st`, `final.st`, plus a shared
  `system.st`. They are rendered with Spring AI's `StTemplateRenderer` (`<name>` delimiters, so JSON braces in the text
  need no escaping).
- The catalog, the candidates, the schema, the passes and the draft go in as pre-rendered text: a numbered list of
  `name — description`, and pretty-printed JSON.
- The wording is the builder's call (§21). Tests check what each prompt holds, not its wording.

### 5. Temperatures

| Purpose | Temperature |
|---|---|
| separate | `separation-temperature` (0.5) |
| classify, tiebreak | `classification-temperature` (0.7) |
| confirm-type, draft-schema, extract, consolidate, final | `extraction-temperature` (0.2) |

Classification and tie-break runs are voted on, so they need variety. Confirmation and schema drafting are single
decisions, so they get the low temperature too.

### 6. Logging each call, and transactions

- Before anything else, a call checks `TransactionSynchronizationManager.isActualTransactionActive()` and throws an
  `IllegalStateException` if a transaction is open, as `CompletionWaitService` does. Nothing is sent or logged.
- The page images are read from S3 next. A `StorageException` becomes a `VisionException` ("page image unavailable")
  and is not logged, because OpenAI was never called.
- The call is timed with `System.nanoTime()`. In `finally`, one `OpenAiCallRecord` is written with the context's ids,
  the purpose, the model, the latency, the tokens from `ChatResponse.getMetadata().getUsage()` (null when missing),
  the HTTP status (200 on success, or `statusCode()` of an `OpenAIServiceException` found in the cause chain, or
  null when no response came) and a short error: `HTTP 429`, `timeout`, `OpenAI unreachable` (an I/O failure),
  `request failed` (a failure before anything was sent), `no answer`, `invalid JSON`, `invalid answer`. A refusal or
  empty answer counts as `no answer`. Token counts are null when the response reports no usage.
- A `VisionException` never carries a cause that quotes the answer (Jackson's parse errors do), so logging the
  exception can't leak borrower data.
- Because no transaction is open, `record` commits on its own, so a step that later rolls back keeps its log row.
- If `record` throws, the failure is logged at `error` with the batch id and purpose, and the call still returns its
  answer (or raises its own error). The OpenAI call has already been paid for, and failing the step would only repeat
  it.
- Logs carry the purpose, the ids, the latency and the status only.

### 7. Settings (`application.yml` and `AiConfig`)

- `spring.ai.model.embedding`, `image`, `audio.speech`, `audio.transcription` and `moderation` set to `none`.
- `spring.ai.openai.chat.model: ${app.document-reader.open-ai.model}`, so Spring AI's default agrees with ours even
  though every call names the model anyway.
- `spring.ai.openai.chat.max-retries: 2`: the SDK retries timeouts, 429s and 5xx twice, with its own backoff. More
  would multiply latency across 5 classification and 3 tie-break runs, while the inbox retries the whole step.
- `spring.ai.openai.chat.timeout: 120s`, so one call can't use the 10-minute inbox lease alone.
- `DocumentReaderProperties.OpenAi.model` gains `@NotBlank`.
- `config/AiConfig` builds the `SpringAiVisionClient` from the `ChatModel`, `DocumentStorageClient`,
  `OpenAiCallService`, the Jackson 3 `JsonMapper` and the `OpenAi` properties.

### 8. Tests

`config/SpringAiVisionClientTests` is a unit test: a stub `ChatModel` records each `Prompt` and returns a canned
`ChatResponse` with usage, or throws. It uses a Mockito `DocumentStorageClient` returning tiny PNGs, and a Mockito
`OpenAiCallService`. The HTTP error case throws the SDK's own `RateLimitException`. The transaction case runs inside a
`TransactionTemplate` with a stub transaction manager. The blank-model test goes in the
existing `DocumentReaderPropertiesValidationTests`. The OpenAI auto-configuration tests go in a new
`config/OpenAiSettingsTests`, because they need the Spring AI auto-configuration that the properties tests don't start.

## Risks / Trade-offs

- [Spring AI 2.0.3 can't set OpenAI's image `detail`, so OpenAI uses `auto` instead of the `high` the work item asks
  for] → With pages capped at 2048 px, `auto` normally gives the high-detail reading. WI-16's golden documents will show
  if small print is missed. If so, a later work item can send the request through the SDK directly. The gap is noted
  in WI-16's work item.
- [Strict structured output rejects a type schema that breaks its rules] → Every schema in the catalog passes
  `DataSchemaRules`, which checks the same rules, and drafted schemas are checked before they are returned.
- [Prompt wording affects accuracy, and tests can't judge it] → Tests check what each prompt contains. WI-16's golden
  documents measure the results.
- [Jackson 2 from `openai-java` on the classpath] → The client takes the Jackson 3 `JsonMapper` by type, and the
  imports are `tools.jackson.*` only.

## Migration Plan

None. No schema change. Deploying needs `OPENAI_API_KEY`, as before.
