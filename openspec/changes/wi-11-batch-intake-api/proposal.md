# Proposal

## Why

Every piece of the pipeline is now in place except the entry point. Records (WI-03), the outbox (WI-05), waiting
(WI-06), S3 storage (WI-07), type detection and page counting (WI-08) and batch completion (WI-10) are all built,
but no caller can send a batch or read its result. This change adds `POST /batches` and `GET /batches/{id}`
(§6, §7, §16 and §18 of `docs/design/2026-11-20-document-reader-design.md`), so separation (WI-12) has real batches
to work on. This is WI-11 of `docs/workitems/`.

## What Changes

- **`POST /batches`** (multipart) takes repeated `files`, `loanPurpose`, `async` and `webhookUrl`.
- **All-or-nothing validation:**
  - The request fields are checked first: the loan purpose, the file count, and how `async` and `webhookUrl` go
    together. Each failure is a 400 with the exact message from §7.1.
  - Then every file is checked: its type from its content (415), its size and the batch's total size (413), and
    whether it is readable and within the page limit (422). Every failing file is listed in an `errors` array with
    its index and file name.
  - A request that fails any check stores nothing in Postgres or S3.
- **Storing a valid batch follows §6:**
  1. The batch and its files are created as `RECEIVED`.
  2. Each original is put to S3.
  3. In one transaction, the files become `STORED`, the batch becomes `PROCESSING`, and one `SEPARATE_FILE` message
     is queued per file.
- **If S3 fails**, the batch is failed through `BatchCompletionService.onBatchFailed`. The caller then gets the
  `FAILED` result through the usual channel: 200 in wait mode, or 202 plus the webhook in async mode.
- **Answers:**
  - An async batch gets 202 with `{ batchId, status, statusUrl }` straight away.
  - A sync batch waits up to `wait.timeout`. It gets 200 with the result envelope if the batch finishes in time,
    and 202 if it doesn't.
- **`GET /batches/{id}`** returns the §7.3 result envelope at any stage. Files are in upload order, and documents
  are in file order, then page order. Another caller's batch is 404, the same as a batch that doesn't exist. An
  admin can read every batch.
- **Auditing a multipart request** records the form fields and, for each file, its name, declared type, size and
  SHA-256, but never the file's bytes. An upload refused for its size (413) still writes an audit row.
- **An oversized upload** gets a 413 problem response, not a connection reset. Today Tomcat stops reading after
  2 MB, so a 60 MB upload is cut off.

## Capabilities

### New Capabilities

- `batch-intake`: what `POST /batches` accepts and refuses, the order it stores a batch in, and how it answers
  (wait or 202).
- `batch-results`: the result envelope that `GET /batches/{id}` returns (and that wait mode returns), and who may
  read it.
- `api-audit`: what the audit trail records for a request, starting with multipart uploads and refused uploads.
  Auditing was built in WI-01, before OpenSpec, so it has no spec yet. Only the behaviour this change touches is
  specified.

### Modified Capabilities

- `processing-configuration`: "Upload size limits follow the file and batch limits" gains the rule that a refused
  upload gets a 413 problem response, rather than a dropped connection.

## Impact

- **New code:**
  - `api/BatchController`;
  - `dto/request/BatchUploadRequest`;
  - `dto/response/BatchResultResponse`, `FileResultResponse`, `DocumentResultResponse` and `BatchAcceptedResponse`;
  - `service/BatchIntakeService(Impl)` and `service/BatchQueryService(Impl)`;
  - `processors/WebhookUrlValidator`;
  - `exception/InvalidWebhookUrlException`, `exception/BatchValidationException` and its `FileError`.
- **Changed code:**
  - `exception/GlobalExceptionHandler` gets the 400, 413, 415 and 422 mappings and the `errors` array;
  - `config/ApiAuditFilter` writes the multipart payload, and records an audit row for a refused upload;
  - `application.yml` sets `server.tomcat.max-swallow-size`.
- **API:** two new endpoints. Existing endpoints don't change.
- **Dependencies and schema:** none new. All the tables already exist.
- **Later work items:** WI-12 handles the `SEPARATE_FILE` messages. Until then, a batch stays `PROCESSING`, and a sync
  call returns 202 after `wait.timeout`. WI-15 delivers webhooks.
