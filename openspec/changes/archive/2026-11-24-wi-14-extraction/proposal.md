# Proposal

## Why

WI-13 queues one `EXTRACT_DOCUMENT` message per classified document, but nothing handles them yet, so no document is
ever completed, and no batch completes either. Extraction is process 3 of the pipeline (§12 of
`docs/design/2026-11-20-document-reader-design.md`). It reads each document's data and summary in three stages, checks
the final data against the type's schema, completes the document and runs the batch completion check (WI-10), which
finally lets batches finish. This is WI-14 of `docs/workitems/`.

## What Changes

- **An `EXTRACT_DOCUMENT` handler.** It sets the document to `EXTRACTING`, then:
  1. **First passes.** `extraction.passes` (3) extraction calls, run at once and outside any transaction, each with
     the document's pages, its type's data schema and the loan purpose. Each is saved as an `INITIAL` run, and a retry
     reuses the passes already saved.
  2. **Consolidation.** One text-only call merges the passes into a draft and lists where they disagreed. It is saved
     as a `CONSOLIDATED` run.
  3. **Final determination.** One call, with the pages and the draft, settles the disagreements. Its data is
     validated against the type's schema. Invalid data is retried once with the validation errors, and a second
     failure fails the attempt so the inbox retries it. The valid answer is saved as a `FINAL` run.
- **Long documents** are sent only their first `extraction.max-pages` (20) pages, and are marked `truncated`.
- **One transaction then:**
  - saves the extraction result (type, schema version, data, summary);
  - marks the document `COMPLETED`;
  - runs the batch completion check;
  - marks the inbox message done.

  The document is locked first and must still be `EXTRACTING`, the same guard as WI-12 and WI-13.
- **A document whose message is exhausted** becomes `FAILED` ("Extraction failed"), and the batch completion check
  runs.
- **A message for a document already completed or failed, or with a result,** does nothing.
- **A schema validator** (`DataSchemaValidator`) checks data against a JSON Schema and returns the errors, so the
  final stage can send them back to the model. It uses the networknt validator already on the classpath.
- **Logs** never carry extracted values, summaries or validation messages (§18), because those can quote borrower
  data.

## Capabilities

### New Capabilities

- `document-extraction`: how a classified document's data is extracted. This covers the three stages and what each
  receives, page truncation, schema validation with one corrective retry, the result and document completion, and
  retries, repeated messages and exhausted documents.

### Modified Capabilities

None. `batch-completion` already defines what happens when a document finishes, and `batch-results` already shows
`summary`, `data` and `truncated`.

## Impact

- **New code:**
  - `processors/DataSchemaValidator`;
  - `service/ExtractionService` and `service/ExtractionServiceImpl` (also the `EXTRACT_DOCUMENT` `InboxMessageHandler`).
- **Changed code:** `DocumentService` gains `markTruncated`.
- **Tests:**
  - `processors/DataSchemaValidatorTests` and `service/ExtractionServiceTests`;
  - a new case in `DocumentServiceTests`.
- **Dependencies and schema:** none new. `extraction_run`, `extraction_result` and `document.truncated` already exist.
- **Later work items:** WI-15 delivers the webhook that completing an async batch queues.
