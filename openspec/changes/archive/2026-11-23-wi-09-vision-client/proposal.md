# Proposal

## Why

Separation, classification and extraction (WI-12 to WI-14) all ask OpenAI vision about page images. They need one
place that sends the pages and the prompt, gets back a typed answer, and logs every call for cost and latency
reporting without keeping borrower data (§9–§12 and §16 of `docs/design/2026-11-20-document-reader-design.md`). The
WI-01 review also left OpenAI settings that need fixing before the first real call. This is WI-09 of
`docs/workitems/`. It needs WI-04 (the call log) and WI-07 (page images in S3).

## What Changes

- **One vision client** for the eight kinds of call: separate, classify, tie-break, confirm a new type, draft a
  schema, extract, consolidate, and final extraction. Each takes the batch, file and document it is for, plus the
  loan purpose, and returns a typed answer.
- **Page images** are read from S3 by key and sent as PNG images in page order. Consolidation sends no images.
- **Structured answers:** every call asks for JSON in a fixed shape. Extraction asks for the document type's own
  schema. An answer that isn't valid JSON in that shape, or a failed call, becomes one vision error.
- **Every call is logged**, whether or not it succeeds: batch, file, document, purpose, model, latency, tokens and
  HTTP status, never the prompt or the answer. Calls are refused inside a database transaction, so a rolled-back step
  never holds a connection open during a call or loses its log row.
- **Model and temperature per call:** every call names the configured model and the temperature for its step, so
  Spring AI's own default model is never used.
- **OpenAI settings:** a blank model is refused at startup. The unused OpenAI models (embedding, image, audio,
  moderation) are switched off. The OpenAI SDK's retries are set deliberately low, because the inbox already retries
  whole steps.
- **Prompts** live in templates under `src/main/resources/prompts/`.

## Capabilities

### New Capabilities

- `vision-calls`: what each OpenAI vision call sends and returns, how failures are reported, and how each call is
  logged and kept out of database transactions.

### Modified Capabilities

- `processing-configuration`: adds that a blank OpenAI model is refused at startup, that only the OpenAI chat model is
  set up, and how many times the SDK retries a call.

## Impact

- **Code:** `client/VisionClient`, `config/AiConfig`, `config/SpringAiVisionClient`, `exception/VisionException`;
  records in `dto/`: `CallContext`, `PageImageRef`, `SeparationAnswer`, `ClassificationAnswer`, `TypeMatchAnswer`,
  `ExtractionAnswer`, `ConsolidatedAnswer`; `config/DocumentReaderProperties.OpenAi` gains `@NotBlank`.
- **Resources:** prompt templates and answer schemas under `src/main/resources/prompts/`; OpenAI settings in
  `application.yml`.
- **Dependencies:** none new (Spring AI 2.0.3 is already in the build).
- **Later work items:** WI-12 (separation), WI-13 (classification) and WI-14 (extraction) call this client.
