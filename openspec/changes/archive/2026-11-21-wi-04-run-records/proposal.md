# Proposal

## Why

Separation, classification and extraction each make several model calls and vote or consolidate over the answers.
Those steps (WI-12, WI-13, WI-14) need somewhere to keep every run, so a retried step can skip the runs already done,
and so a result can be traced back to the answers that produced it. Webhook delivery (WI-15) needs a record of every
attempt, and every OpenAI call needs a log row for cost and latency reporting. This is WI-04 of `docs/workitems/`. It
needs only the WI-03 records, so it comes next.

## What Changes

- **Run and result tables** in the main schema, as designed in §16 of
  `docs/design/2026-11-20-document-reader-design.md`:
  - `separation_run`: one separation answer for a file, or for one window of a long file;
  - `classification_run`: one classification answer for a document, in the initial or tie-break round;
  - `classification_result`: the winning type for a document, with the votes and how it was decided;
  - `extraction_run`: one extraction answer for a document at the initial, consolidated or final stage;
  - `extraction_result`: a document's final data and summary;
  - `webhook_delivery_attempt`: one attempt to deliver a batch's webhook.
- **OpenAI call log** in the integration schema: `document_reader_integration.openai_call`, one row per call, with
  purpose, model, latency, tokens and outcome. It holds no prompt or response bodies, because those carry borrower
  data (§18).
- **Services:** CRUD services that later work items use to save these rows and read them back.
- **Read endpoints:** read-only endpoints under `/records` for each table, with the same ownership rule as WI-03:
  callers see only their own batches' rows, and admins see all.
- **Catalog behaviour change:** a document type that a classification run, classification result or extraction
  result refers to can't be removed either. That attempt gets 409, like a type a document refers to.

## Capabilities

### New Capabilities

- `processing-runs`: the stored record of every separation, classification and extraction run, the classification
  and extraction results, and every webhook attempt. It covers what each record holds, the rules it must satisfy, and
  who can read it.
- `openai-call-log`: the per-call log of OpenAI usage, what it records and what it must never hold.

### Modified Capabilities

- `document-type-catalog`: adds that a type referred to by a run or result can't be removed. It's written as an ADDED
  requirement next to the one WI-03 added for documents, which it doesn't change.

## Impact

- **Database:** migration V6 adds the six run and result tables with foreign keys to `source_file`, `document`,
  `batch` and `document_type`. Migration V7 adds `document_reader_integration.openai_call`.
- **API:** new read-only endpoints under `/records/source-files/{id}`, `/records/documents/{id}` and
  `/records/batches/{id}`. `DELETE /document-types/{id}` now also returns 409 for a type a run or result refers to.
- **Code:** entities, enums, repositories, CRUD services and read-only controllers for the seven tables; a
  `RunRecordQueryService` for ownership; a wider in-use check in `DocumentTypeServiceImpl.delete`.
- **Later work items:** WI-09 (call log), WI-12, WI-13, WI-14 and WI-15 use these services.
