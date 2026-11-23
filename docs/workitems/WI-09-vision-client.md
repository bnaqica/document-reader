# WI-09: Vision client (Spring AI)

| | |
|---|---|
| **Status** | Built, awaiting developer review |
| **Depends on** | [WI-04](WI-04-run-and-result-records-plus-the-openai-call-log.md), [WI-07](WI-07-s3-storage-client.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §9–§12, §16 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 10 |

**Goal:** Put every OpenAI call (separate, classify, tie-break, confirm type, draft schema, extract, consolidate, final) behind one client that records each call.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `client/VisionClient`, `config/AiConfig`, `config/SpringAiVisionClient`, prompt templates in `src/main/resources/prompts/*.st`, the response records in `dto/` (`SeparationAnswer`, `ClassificationAnswer`, `TypeMatchAnswer`, `ExtractionAnswer`, `ConsolidatedAnswer`), and `exception/VisionException`
- Test: `config/SpringAiVisionClientTests` with a stub `ChatModel`

**Interfaces:**
- Consumes: `OpenAiCallService.record` (WI-04). Record each call right after it returns, outside any database transaction, so a rolled-back step never loses its log row.
- Produces: `VisionClient`. Every method takes a `CallContext(UUID batchId, UUID sourceFileId, UUID documentId /* nullable */, String loanPurposeDescription)`.
  - `SeparationAnswer separate(CallContext, List<PageImageRef> pages, List<String> catalogNames)`, where `SeparationAnswer(List<Integer> startPages, List<String> labels)`
  - `ClassificationAnswer classify(CallContext, List<PageImageRef> pages, List<CatalogEntry> catalog)`, where `ClassificationAnswer(String documentType, double confidence, String reasoning)`
  - `ClassificationAnswer tiebreak(CallContext, List<PageImageRef> pages, List<CatalogEntry> candidates)`
  - `TypeMatchAnswer confirmNewType(CallContext, List<PageImageRef> pages, String proposedName, List<CatalogEntry> catalog)`, where `TypeMatchAnswer(boolean matchesExisting, String existingName)`
  - `JsonNode draftSchema(CallContext, List<PageImageRef> pages, String typeName)`
  - `ExtractionAnswer extract(CallContext, List<PageImageRef> pages, JsonNode schema)`, where `ExtractionAnswer(JsonNode data, String summary)`
  - `ConsolidatedAnswer consolidate(CallContext, JsonNode schema, List<ExtractionAnswer> passes)`, where `ConsolidatedAnswer(JsonNode data, String summary, List<String> disagreements)`
  - `ExtractionAnswer finalizeExtraction(CallContext, List<PageImageRef> pages, JsonNode schema, ConsolidatedAnswer draft, List<String> validationErrors)`
  - `PageImageRef(int pageNumber, String s3Key)`. The client loads the bytes through `DocumentStorageClient` and sends PNG media at high detail.

- [x] **Step 1: Write the failing tests.**
  - `classifySendsImagesCatalogAndPurpose()`: the stub captures the prompt, which contains N image media, every catalog name and the purpose description.
  - `classifyUsesClassificationTemperature()`
  - `extractRequestsStructuredOutputWithSchema()`
  - `everyCallRecordsOpenAiCall()`: `batchId`, `sourceFileId`, `purpose`, `model` and tokens are recorded, and no body is stored.
  - `httpErrorBecomesVisionException()`
  - `invalidJsonBecomesVisionException()`
  - `consolidateSendsNoImages()`
- [x] **Step 2: Run** the tests. Expected: FAIL.
- [x] **Step 3: Implement** with `ChatClient` and `.entity(Class)`, or a JSON-schema response format. Load prompts from `.st` templates; their wording is the builder's call (§21). Set the temperature per call from `DocumentReaderProperties.OpenAi`. Time each call and record it in `finally`.
- [x] **Step 4: Run** the tests and the build. Expected: PASS.

## Notes from the WI-01 review

The WI-01 code review raised these. Handle each in this work item, test-first.

- There are two model settings: `app.document-reader.open-ai.model` and Spring AI's own `spring.ai.openai.chat.options.model`, which has a different default. Either set `spring.ai.openai.chat.options.model: ${app.document-reader.open-ai.model}`, or always pass `OpenAiChatOptions` with our model on every call. Add a test either way.
- Spring AI's retry auto-configuration (`spring.ai.retry.*`) wraps every call. Combined with 5 classification runs and 3 tie-break runs, retries multiply latency. Set `spring.ai.retry.max-attempts` deliberately, because the inbox already retries whole steps.
- The OpenAI starter also auto-configures embedding, image, audio and moderation models. Turn off what isn't used, for example `spring.ai.model.embedding=none`.
- `openai-java-core` puts Jackson 2 (`com.fasterxml.jackson.core:jackson-databind`) on the classpath. Keep importing `tools.jackson.*`, and never inject a Jackson 2 `ObjectMapper` by accident.
- `OpenAi.model` isn't checked for blanks. Add `@NotBlank`, with a test.

---

## Done when

- [x] Every step above is ticked.
- [x] `./gradlew spotlessApply build` passes, with all tests green.
- [x] `git status` shows only the files this work item names, plus their tests and fixtures.
- [x] A code review (`superpowers:requesting-code-review`, using the `code-reviewer` agent) has no open findings.
- [ ] The developer has reviewed the diff.

## Commit

Claude never commits in this repo (`.claude/rules/git-workflow.md`). The developer commits this work item as one
commit, with this suggested message:

```text
feat: add Spring AI vision client
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
