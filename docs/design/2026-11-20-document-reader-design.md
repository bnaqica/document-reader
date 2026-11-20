# Document Reader — Design

- **Date:** 2026-11-20
- **Status:** Approved. Revised the same day so that every request is a batch. Design only: no feature code and no
  OpenSpec specs yet.
- **Service:** `document-reader` (`com.bnaqica.documentreader`, port 8084, PostgreSQL, integration schema)

Decisions are tagged so they can be checked:

- **[decided]** — the product owner decided this in the design conversation.
- **[proposed]** — the designer filled this in; it is open to correction.
- **[builder]** — deliberately left to whoever implements it.

---

## 1. Intent

Lenders collect a long list of supporting documents for a loan: pay stubs, bank statements, W-2s, titles,
insurance declarations and more. They arrive as several files, and a single file is often several documents
scanned together. This service takes one request (a **batch** of one or more files, plus what the loan is for) and
works through four steps:

1. Split each file into the separate documents it contains.
2. Decide what kind of document each one is.
3. Extract each document's information and write a short summary of it.
4. Return one payload per document.

Vision models are not deterministic, so every judgement (where one document ends and the next begins, what type a
document is, what it says) is made by asking OpenAI several times and reconciling the answers, never by trusting a
single call.

## 2. Goals, non-goals, anti-goals

**Goals**
- Accept a batch of one or more files with a required loan purpose, and return one payload per document. **[decided]**
- Record the batch in PostgreSQL before doing anything else. **[decided]**
- Store each original file in AWS S3, keyed by the UUID of its database record. **[decided]**
- Convert every accepted format (PDF, HEIC, TIFF and the like) into the image format that suits OpenAI vision best. **[decided]**
- When a file holds several documents, separate them first. **[decided]**
- Classify each document by running OpenAI vision a configurable number of times and voting, with a tie-break round. **[decided]**
- Extract each document's data and summary in three stages: several first passes, a consolidation of those results,
  then a final determination. **[decided]**
- Run the processes asynchronously through an inbox/outbox on PostgreSQL. **[decided]**
- Let each caller choose between waiting for the result and receiving it by webhook. **[decided]**
- Use the loan purpose as context that helps the model interpret the documents. **[decided]**
- Keep the set of document types stable. Near-duplicate names map to the first type saved, and the model may add
  genuinely new types. **[decided]**

**Non-goals (v1)**
- Using the loan purpose for a required-documents checklist or a "what's missing" report. The purpose is context
  only. **[decided]**
- Merging pages across files. A file boundary is always a document boundary. **[proposed]**
- Accepting part of a batch. If any file is invalid, the whole request is rejected (§7.1). **[proposed]**
- Human review screens. Every intermediate result is kept so a review tool can be built later. **[proposed]**
- Fraud or tamper detection. **[proposed]**
- Returning an earlier result when the same file is uploaded again. A re-upload is processed again. **[proposed]**
- A retention and deletion policy. Data is kept until one is decided. **[proposed]**

**Anti-goals** (outcomes that count as failure even if the code works) **[proposed]**
- Type sprawl: the catalog filling up with "Paystub", "Pay stubs" and "Pay-stub" as separate types.
- Lost work: a batch that was accepted but never finished, or a webhook that silently never arrives.
- Leaking borrower data: extracted SSNs, account numbers or document bytes ending up in logs, the audit table,
  another caller's response or an attacker-controlled webhook URL.
- Processing a step twice for one message because of a retry or a second instance.

## 3. Constraints

- The stack comes from the service template: Java 25, Spring Boot 4, Gradle, Flyway, JPA, Spring Security with
  Keycloak JWT, Testcontainers, and the package layout in `.claude/rules/`. No entity reaches a controller, the
  CRUD service is the only caller of its repository, and every table has a CRUD controller except `audit.api_audit`.
- The LLM is **Spring AI with OpenAI** (vision input plus structured output). **[decided]** Spring AI and the AWS SDK
  v2 are not managed by the Spring Boot BOM. Import their own BOMs and add a comment on why each one is pinned.
- OpenAI vision accepts PNG, JPEG, WEBP and non-animated GIF. It does not accept PDF pages as vision images, and it
  does not accept HEIC.
- Java has no reliable pure-Java HEIC decoder. Converting HEIC needs a native tool (libheif or ImageMagick) in the
  container image, which means a custom Jib base image or layer. **[builder]** chooses the tool; it must run inside
  the Jib image.
- Infrastructure is AWS S3 for files and PostgreSQL for everything else, including messaging. There is no Kafka or
  SQS. **[decided]** Locally, `local-docker/localstack` provides S3.

## 4. Approaches considered (asynchronous processing)

| | Approach | Trade-off |
|---|---|---|
| **A (chosen)** | Our own `outbox` and `inbox` tables. Workers claim rows with `FOR UPDATE SKIP LOCKED`, and Postgres `LISTEN/NOTIFY` wakes workers and waiting API calls. | We own and maintain the most code. In return it is exactly the pattern asked for, the de-duplication is real, several instances work safely, and every part can be tested. |
| B | Spring Modulith event publication registry | Much less code. It has no real inbox or duplicate check, retries mostly mean replay after a restart, and it does not share work across instances, so we would end up rebuilding parts of A. |
| C | A Postgres job library (JobRunr, db-scheduler) | Good retries and a dashboard. It is a job queue rather than inbox/outbox, adds a dependency outside the BOM, and makes it awkward to save a step's data and its next message in one transaction. |

**Chosen: A** **[decided]**.

## 5. Terms

- **Batch:** one API call. It carries one or more files and the shared `loanPurpose`, `async` and `webhookUrl`.
  It produces one result and, in async mode, one webhook. **[decided]**
- **Source file:** one uploaded file in a batch. It is stored in S3 under its own UUID and has its own pages.
- **Document:** one logical document found inside a source file, such as a pay stub covering pages 3–4 of
  `scan.pdf`. It has its own UUID, a page range, a type and extracted data. Each document is one payload in the
  response.

So a batch holds one or more source files, and each source file holds one or more documents.

## 6. End-to-end flow

```
Caller ──POST /batches (files[], loanPurpose, async, webhookUrl)──▶ API
  1. validate the request and every file                       (4xx names the file; nothing stored)
  2. tx: insert batch + one source_file per file (RECEIVED)       → UUIDs
  3. upload each original to S3: files/{fileId}/original.{ext}
  4. tx: files STORED + batch PROCESSING + outbox SEPARATE_FILE per file
  5a. async=false → wait for the batch (up to timeout) → 200 with result | 202 on timeout
  5b. async=true  → 202 { batchId, status }

Process 1 — SEPARATE_FILE (one per file, run in parallel)
  convert every page to PNG → S3 files/{fileId}/pages/0001.png …
  single page → one document; else M separation runs → vote on each page gap
  tx: create documents (page ranges) + file SEPARATED + outbox CLASSIFY_DOCUMENT per document

Process 2 — CLASSIFY_DOCUMENT (one per document, run in parallel)
  N classification runs → normalize each answer to the catalog → vote
  tie → narrow to the tied types → 3 more runs → winner
  new type? confirm it is not a near-duplicate → create it and its data format
  tx: save classification + outbox EXTRACT_DOCUMENT

Process 3 — EXTRACT_DOCUMENT (one per document, run in parallel)
  X first passes → consolidation call → final determination call
  tx: save extraction; lock the batch row; if every file is separated or failed
      and every document is finished:
      complete the batch + NOTIFY batch_completed + outbox DELIVER_WEBHOOK if async

Process 4 — DELIVER_WEBHOOK
  one signed POST of the batch result to webhookUrl, retried with backoff
```

The user decided on separation first, then classification, then extraction, with one batch result and one webhook
per batch. **[decided]** Fanning out per file and per document, fanning back in on the batch, and webhook delivery
as its own outbox step are **[proposed]**.

## 7. API

### 7.1 `POST /batches` (multipart)

| Part | Type | Rule |
|---|---|---|
| `files` | file, repeated | Required, 1 to `batch.max-files` (default 20). **[decided: several files]** **[proposed: limit]** The allowed types are PDF, PNG, JPEG, HEIC/HEIF, TIFF and WEBP, checked by content sniffing as well as the declared type. Each file can be up to 50 MB and 100 pages, and the whole batch up to 200 MB. All limits are configurable. **[proposed]** |
| `loanPurpose` | string | Required, and applies to every file. **[decided]** Must match a code in the `loan_purpose` table. **[proposed]** |
| `async` | boolean | Optional, default `false`. **[decided]** |
| `webhookUrl` | string | Required when `async=true`. **[decided]** It is rejected when `async=false`. **[proposed]** |

A single file is simply a batch of one. There is no separate single-file endpoint. **[proposed]**

**Loan purposes**
- They live in a `loan_purpose` table seeded by Flyway, so adding one is a data change rather than a code change.
  **[proposed]**
- The seed is `CAR_PURCHASE`, `CAR_REFINANCE`, `HOME_PURCHASE` and `MORTGAGE_REFINANCE` **[decided]**, plus
  `BOAT_PURCHASE`, `BOAT_REFINANCE`, `RV_PURCHASE` and `RV_REFINANCE`. Those last four are **[proposed]** because the
  document list includes hull numbers, marine surveys and RV titles.
- Each purpose has a short description, which is what the prompts receive.

**Validation is all-or-nothing.** **[proposed]** Every file is checked (type, size, readability, page count) before
anything is written. If any check fails, the request is rejected and nothing is stored. Each `ProblemDetail` names the
failing file by its index and file name, in an `errors` array. The responses:
- A missing or unknown `loanPurpose` returns **400**, and the message lists the valid codes. **[decided: required]**
- `async=true` with no `webhookUrl` returns **400**, "webhookUrl is required when async is true". **[decided]**
- A `webhookUrl` with `async` false or missing returns **400**, "webhookUrl is only used when async is true". **[proposed]**
- A `webhookUrl` that is not https, or that resolves to a private, loopback or link-local address, returns **400**.
  The `local` profile allows http and localhost. **[proposed]**
- No files, or more than `batch.max-files`, returns **400**.
- An unsupported type returns **415**. A file over 50 MB, or a batch over 200 MB, returns **413**. A file that is
  unreadable (corrupt or password-protected), or over 100 pages, returns **422**. **[proposed]**

**Responses**
- Wait mode, finished in time: **200** with the batch result (7.3). **[decided]**
- Wait mode, over `wait.timeout` (default 300 s): **202** with `{ batchId, status, statusUrl }`. Processing
  continues, and the result is available from `GET /batches/{id}`. **[proposed]**
- Webhook mode: **202** with `{ batchId, status, statusUrl }` straight away. **[decided]**
- Processing failures come back as the result envelope with the failure statuses and `error` filled in, through the
  same channel a success would use (200 in wait mode, webhook in async mode). Callers then handle a single shape.
  **[proposed]**

### 7.2 `GET /batches/{id}`

Returns the batch result at any stage. A caller can read only its own batches; an admin role can read all of them.
**[proposed]**

### 7.3 Result envelope

The response carries one payload per document. **[decided]** Every payload has the same outer shape, and only `data`
varies by document type. **[decided]** Documents are listed in file order, then page order. `files` gives a short
status for each uploaded file, so a file that produced no documents (because it failed) is still visible.
**[proposed]**

```json
{
  "batchId": "4b0e…",
  "status": "RECEIVED | PROCESSING | COMPLETED | PARTIALLY_COMPLETED | FAILED",
  "loanPurpose": "CAR_REFINANCE",
  "receivedAt": "2026-11-20T16:00:00Z",
  "completedAt": "2026-11-20T16:01:12Z",
  "error": null,
  "files": [
    { "id": "a1…", "index": 0, "fileName": "packet.pdf", "pageCount": 6, "status": "SEPARATED", "documentCount": 3, "error": null },
    { "id": "b2…", "index": 1, "fileName": "license.heic", "pageCount": 1, "status": "SEPARATED", "documentCount": 1, "error": null }
  ],
  "documents": [
    {
      "id": "9c1d…",
      "sourceFileId": "a1…",
      "pages": { "start": 1, "end": 2 },
      "status": "COMPLETED",
      "documentType": { "name": "Pay stubs", "newType": false },
      "classification": {
        "runs": 5,
        "votes": { "Pay stubs": 4, "W-2 forms": 1 },
        "agreement": 0.8,
        "tieBreak": null
      },
      "summary": "Bi-weekly pay stub from Acme Corp for the period ending 2026-10-31 …",
      "data": { "employerName": "Acme Corp", "payPeriodEnd": "2026-10-31", "grossPay": 4210.00, "...": "..." },
      "truncated": false,
      "error": null
    }
  ]
}
```

The batch status **[proposed]**:
- `COMPLETED`: every file was separated and every document completed.
- `PARTIALLY_COMPLETED`: at least one document completed, but some file or document failed. Each one carries its
  own `status` and `error`.
- `FAILED`: no document completed.

### 7.4 CRUD controllers

The template requires a CRUD controller for every table. For the pipeline tables (files, documents, runs, inbox,
outbox), those controllers are read-only for everyone except an admin role. Callers never create a run or a message
directly. `loan_purpose` and `document_type` are writable by admins only. **[proposed]**

## 8. Conversion to images

- **Target format: PNG.** **[decided: best format for vision]** **[proposed: PNG specifically]** It is lossless, so
  small print and digits stay sharp, which matters more for documents than file size. JPEG artifacts blur numbers.
- **PDF:** render each page with PDFBox at 200 DPI. **[proposed]**
- **HEIC/HEIF:** decode with a native tool, then apply EXIF orientation. **[builder]** chooses the tool (see section 3).
- **TIFF and multi-frame TIFF:** each frame becomes a page.
- **Images:** apply EXIF rotation and drop alpha and colour profiles.
- **Resize** so the longest side is at most 2048 px, because OpenAI scales larger images down anyway. Very tall pages
  can be split into overlapping tiles. **[builder]** decides whether tiling is needed after testing on real statements.
- **Storage:** pages go to S3 at `files/{fileId}/pages/{nnnn}.png` and are recorded in `source_file_page`.
  Conversion happens once per file, at the start of process 1, and every later process reuses the pages. A retry
  finds the pages already there and skips conversion. **[proposed]**
- **Page caps** (configurable) **[proposed]**:
  - `file.max-pages`, default 100 per file. Checked at intake, where the page count is read, and rejected with 422.
  - `classification.max-pages`, default 3. Classification sees only the first pages of each document.
  - `extraction.max-pages`, default 20 per document. A longer document is extracted from its first 20 pages and
    marked `truncated: true`.

## 9. Separation (process 1, per file) **[decided: separate first]** **[proposed: mechanism]**

1. **Claim** the inbox message (section 13) and set the file status to `SEPARATING`. Convert the pages (section 8).
2. **Single page:** create one document covering page 1, and make no separation calls.
3. **Separation runs.** Make `separation.runs` calls (default 3, must be odd). Each call receives the file's page
   images in order, the loan purpose, and the document type catalog as hints. It returns structured output: the page
   numbers where a new document starts, plus a tentative label for each segment. The label helps the model think but
   is **not** used as the type; classification decides the type.
4. **Long files** are sent in overlapping windows (default 15 pages, overlapping by 2). **[builder]** chooses how to
   merge answers for a gap that appears in two windows. Keeping the decision from the window where the gap sits
   furthest from an edge is a reasonable rule.
5. **Vote on each gap.** For every gap between page *i* and page *i+1*, count the runs that start a new document at
   *i+1*. A majority means a boundary. Because the run count is odd, there are no ties. Page 1 always starts a document.
6. **Save** `separation_run` rows and the boundaries. In one transaction, create a `document` row for each segment
   with its page range, set the file to `SEPARATED`, and write one `CLASSIFY_DOCUMENT` outbox message per document.

Blank pages and separator sheets stay attached to the document before them. **[builder]** may drop pages that are
truly blank.

A file whose separation message ends up `FAILED` is marked `FAILED` and runs the batch completion check (section 12),
so one bad file never leaves the batch hanging. The batch's other files carry on. **[proposed]**

## 10. Document type catalog

### 10.1 Storage

- `document_type`: the canonical name, a unique `normalized_key`, a description, the JSON Schema for `data`, the
  origin (`SEEDED` or `MODEL`), and the document that created it.
- `document_type_alias`: other names that point to a type, each with its own unique `normalized_key`.
- A Flyway migration seeds the 41 lender document types. **[decided: the starting list]** For each seeded type, the
  builder has the model draft the JSON Schema and a person reviews it before it goes into the migration. **[proposed]**
  The schema should cover the fields lenders actually use. For a pay stub that is employer, employee, pay period,
  pay date, gross, net, YTD gross, deductions and hours.

### 10.2 Keeping near-duplicates together **[decided: rule]** **[proposed: mechanism]**

The first name saved wins. Later variants become aliases of it and never become new types.

1. **The prompt includes the catalog.** Every classification call lists the current canonical names with their
   descriptions, and the model must choose one unless nothing fits.
2. **Every answer is normalized to a key**, both on each run and when a new type is created:
   lowercase → replace punctuation and hyphens with spaces → singularize each word → remove all spaces.
   So "Pay stubs", "Paystub", "pay-stub" and "PAY STUBS" all become `paystub`, and "W-2 forms" and "W2 form" both
   become `w2form`. A key that matches a type or alias resolves to that type.
3. **A confirmation call for genuinely new names.** When the winning answer matches no key, one extra call shows the
   model the new name, the page images and the catalog, and asks whether the name is the same as an existing type.
   If it is, the name is saved as an alias. If it is not, a new type is created.
4. **New types get their data format once.** When a type is created, one call drafts its JSON Schema from the pages.
   That schema is stored and reused for every later document of that type. **[proposed]**
5. **Race safety.** The unique constraint on `normalized_key` decides between two documents creating the same new
   type at once. The loser of the insert reads the winner's row and uses it.

Answers are normalized and resolved to a type **before** voting, so "Paystub" and "Pay stubs" count as one vote each
for the same type.

## 11. Classification (process 2, per document)

1. **Claim** the message and set the document status to `CLASSIFYING`.
2. **Initial runs.** Make `classification.runs` calls (default 5), up to `classification.parallelism` at a time.
   Each call receives the document's first pages, the loan purpose and the catalog. It returns structured output:
   `{ documentType, confidence (0–1), reasoning }`. Each run is saved to `classification_run`, along with which type
   it resolved to.
3. **Vote.** Count resolved types. If one type has the most votes, it wins. **[decided]**
4. **Tie-break.** **[decided]** When the top count is shared, run `classification.tiebreak-runs` more calls
   (default 3, must be odd). These calls may choose only among the tied types, and they are saved with
   `round = TIEBREAK`. The type with the most tie-break votes wins. With two tied types, three runs always give a
   winner.
5. **Still tied** (possible with three or more tied types, or when a run fails): the winner is the type with the
   highest average confidence across all runs. **[proposed]**
6. **Save** `classification_result` (type, vote counts, agreement = winning votes ÷ initial runs, whether a
   tie-break happened), set the document status to `CLASSIFIED` and write outbox `EXTRACT_DOCUMENT`, all in one
   transaction.

Repeated calls only help if the model can vary, so the classification temperature must be above zero.
**[builder]** tunes it.

A run that errors (timeout, refusal, invalid JSON) is retried a few times. If it still fails, it is left out of the
vote as long as at least `classification.min-successful-runs` runs succeeded (default: a majority of `runs`).
Otherwise the message fails and the inbox retries it (section 13). **[proposed]**

## 12. Extraction (process 3, per document) and batch completion

The three stages follow the user's description: first results, then a summary of them, then a final determination.
**[decided]** What each stage receives is **[proposed]**. Every call also receives the loan purpose as context.

1. **First passes.** Make `extraction.passes` calls (default 3). Each one sends the document's page images and its
   type's JSON Schema, and returns structured output `{ data: <type schema>, summary }`. Each result is saved to
   `extraction_run` as stage `INITIAL`.
2. **Consolidation.** One text-only call receives the X results and produces a merged draft of `data` and `summary`.
   For each field it notes where the passes disagreed. The draft is saved as stage `CONSOLIDATED`.
3. **Final determination.** One call receives the page images and the consolidated draft. It resolves each
   disagreement by looking at the document again and returns the final `{ data, summary }`, saved as stage `FINAL`.
4. **Complete the document.** In one transaction, save `extraction_result`, set the document to `COMPLETED`, and run
   the batch completion check.

The final `data` must validate against the type's schema. If it doesn't, the final stage is retried once with the
validation errors included. A second failure fails the message. **[proposed]**

**Batch completion check** **[proposed]**. It runs inside the transaction that finishes a document (completed or
failed) or fails a file:
- Lock the batch row (`SELECT … FOR UPDATE`).
- If every source file is `SEPARATED` or `FAILED`, and every document is `COMPLETED` or `FAILED`:
  - set the batch's final status (§7.3) and `completed_at`;
  - run `NOTIFY batch_completed '<uuid>'`;
  - write outbox `DELIVER_WEBHOOK` if the batch is async.

The row lock makes sure exactly one transaction completes the batch. Checking the files as well as the documents
stops a batch from completing while one of its files is still being separated.

## 13. Inbox/outbox mechanics (approach A)

### Tables (main schema `document_reader`)

- **`outbox_message`**: `id` (uuid), `message_type`, `aggregate_id` (the batch, file or document id), `payload`
  (jsonb), `created_at`, `dispatched_at`.
- **`inbox_message`**: `id` (the same uuid as the outbox row, primary key, which de-duplicates), `message_type`,
  `aggregate_id`, `payload`, `status` (`PENDING`, `PROCESSING`, `DONE`, `FAILED`), `attempts`, `next_attempt_at`,
  `locked_by`, `locked_until`, `last_error`, `created_at`, `completed_at`.

Message types are `SEPARATE_FILE`, `CLASSIFY_DOCUMENT`, `EXTRACT_DOCUMENT` and `DELIVER_WEBHOOK`.

### Flow

1. **Write.** A step writes its next messages to `outbox_message` in the same transaction as its own data. If the
   transaction rolls back, the messages disappear with it.
2. **Dispatch.** A dispatcher on every instance moves undispatched outbox rows into `inbox_message` (insert, and do
   nothing on conflict), marks them dispatched, and sends `NOTIFY inbox_ready`. It claims rows with
   `FOR UPDATE SKIP LOCKED`, so instances never move the same row twice. It wakes on a schedule
   (`outbox.poll-interval`, default 1 s) and on `NOTIFY outbox_ready`, which is sent when an outbox row is written.
3. **Claim.** Workers claim due `PENDING` rows with `SKIP LOCKED`. They set `PROCESSING`, `locked_by` (the instance
   id) and `locked_until` (a lease, default 10 min), then commit straight away. **OpenAI calls never run inside an
   open database transaction.**
4. **Work.** The handler runs. Intermediate results (pages, runs) are saved as it goes, so a retry can skip work
   that is already done.
5. **Finish.** One transaction saves the result, marks the inbox row `DONE` and writes the next outbox messages.
6. **Failure.** The worker increments `attempts`, records `last_error` and sets
   `next_attempt_at = now + backoff(attempts)` (exponential, configurable). At `inbox.max-attempts` (default 5) the
   row becomes `FAILED`. A failed `SEPARATE_FILE` fails that file, and a failed `CLASSIFY_DOCUMENT` or
   `EXTRACT_DOCUMENT` fails that document. Either way the batch completion check runs (section 12).
7. **Stale leases.** A row whose `locked_until` has passed (for example, its instance crashed) is claimable again.
8. **Idempotency.** Each handler first checks the state of its file or document. If the step's result already
   exists, it marks the message `DONE` and writes the next messages if those are still missing. A second instance or
   a retry therefore cannot separate, classify or extract twice.

Worker concurrency per message type is configurable. Extraction is the slowest step. **[builder]** sizes the pools;
virtual threads suit them well.

## 14. Wait mode

- The API thread runs on a virtual thread (`spring.threads.virtual.enabled=true`), so blocking is cheap. **[proposed]**
- Each instance holds one dedicated Postgres connection that runs `LISTEN batch_completed`, plus an in-memory map
  from batch id to waiting future. A notification completes the matching future. This works when another instance
  did the processing.
- As a safety net in case a notification is missed, the waiting thread also re-reads the batch status every
  `wait.poll-interval` (default 2 s).
- When the wait reaches `wait.timeout`, the API returns 202 (section 7.1). The default is 300 s, because a batch
  holds several files. Callers sending large batches should use webhook mode.
- **[builder]** chooses between a virtual-thread blocking wait and `DeferredResult`; the behaviour must be the same
  either way.

## 15. Webhook delivery (process 4) **[decided: one per batch]** **[proposed: mechanism]**

- One webhook is sent per batch. It is a POST of the batch result (section 7.3) as JSON to `webhookUrl`, for every
  final status.
- Headers: `X-Document-Reader-Batch` (batch id), `X-Document-Reader-Delivery` (inbox message id, for the receiver's
  de-duplication), `X-Document-Reader-Timestamp`, and `X-Document-Reader-Signature`, an HMAC-SHA256 of
  `timestamp + "." + body` using one shared secret from configuration. A secret per caller is deferred.
- Any 2xx response is success. Anything else, or a timeout (10 s), retries through the inbox backoff, with
  `webhook.max-attempts` defaulting to 8. Every attempt is recorded in `webhook_delivery_attempt`.
- The SSRF check runs again just before each send, because DNS can change between intake and delivery. Redirects
  are not followed.
- After the final failed attempt, the batch keeps its result and records the webhook status as `FAILED`. The caller
  can still fetch the result with `GET`.

## 16. Data model

**`document_reader` schema (main)**

| Table | Purpose and key columns |
|---|---|
| `loan_purpose` | `code` (pk), `name`, `description`, `active` |
| `batch` | `id`, `loan_purpose_code`, `async`, `webhook_url`, `status` (`RECEIVED`, `PROCESSING`, `COMPLETED`, `PARTIALLY_COMPLETED`, `FAILED`), `webhook_status`, `failure_reason`, `file_count`, `requested_by` (token subject), `received_at`, `completed_at`, `version` |
| `source_file` | `id`, `batch_id`, `file_index`, `original_filename`, `content_type`, `size_bytes`, `sha256`, `s3_bucket`, `s3_key`, `page_count`, `status` (`RECEIVED`, `STORED`, `SEPARATING`, `SEPARATED`, `FAILED`), `failure_reason`, `version` |
| `source_file_page` | `source_file_id`, `page_number`, `s3_key`, `width`, `height` |
| `separation_run` | `source_file_id`, `run_number`, `window_start`, `window_end`, `boundaries` (jsonb), `succeeded`, `created_at` |
| `document` | `id`, `batch_id`, `source_file_id`, `sequence` (within the file), `start_page`, `end_page`, `status` (`PENDING`, `CLASSIFYING`, `CLASSIFIED`, `EXTRACTING`, `COMPLETED`, `FAILED`), `document_type_id`, `truncated`, `failure_reason`, `completed_at`, `version` |
| `document_type` | `id`, `name`, `normalized_key` (unique), `description`, `data_schema` (jsonb), `schema_version`, `origin`, `created_by_document_id`, `created_at` |
| `document_type_alias` | `document_type_id`, `alias`, `normalized_key` (unique) |
| `classification_run` | `document_id`, `round` (`INITIAL` or `TIEBREAK`), `run_number`, `raw_answer`, `document_type_id` (resolved), `confidence`, `reasoning`, `succeeded`, `created_at` |
| `classification_result` | `document_id` (pk), `document_type_id`, `votes` (jsonb), `agreement`, `tie_broken`, `decided_by` (`VOTE`, `TIEBREAK` or `CONFIDENCE`), `decided_at` |
| `extraction_run` | `document_id`, `stage` (`INITIAL`, `CONSOLIDATED` or `FINAL`), `pass_number`, `data` (jsonb), `summary`, `created_at` |
| `extraction_result` | `document_id` (pk), `document_type_id`, `schema_version`, `data` (jsonb), `summary` |
| `webhook_delivery_attempt` | `batch_id`, `attempt`, `http_status`, `error`, `attempted_at` |
| `outbox_message`, `inbox_message` | see section 13 |

`document.batch_id` is stored alongside `source_file_id` so the completion check and the result query read one batch
without joining through files. **[proposed]**

**`document_reader_integration` schema**

| Table | Purpose |
|---|---|
| `openai_call` | One row per OpenAI call: `batch_id`, `source_file_id`, `document_id` (null for separation), `purpose` (separate, classify, tiebreak, confirm-type, draft-schema, extract, consolidate, final), `model`, `latency_ms`, `prompt_tokens`, `completion_tokens`, `http_status`, `error`, `created_at`. It holds no prompt or response bodies, because those contain borrower data. Cost and latency reporting per batch come from this table. |

**`audit` schema**: the existing `audit.api_audit`. For multipart requests, the audited payload records only the
form fields and each file's metadata (name, type, size, sha256), never the file bytes. **[proposed]**

## 17. Configuration (`app.document-reader.*`) **[proposed defaults]**

| Key | Default |
|---|---|
| `batch.max-files` / `batch.max-total-size` | 20 / 200MB |
| `file.max-size` / `file.max-pages` | 50MB / 100 |
| `conversion.dpi` / `conversion.max-dimension` | 200 / 2048 |
| `separation.runs` / `separation.window-size` / `separation.window-overlap` | 3 / 15 / 2 |
| `classification.runs` / `tiebreak-runs` / `max-pages` / `parallelism` / `min-successful-runs` | 5 / 3 / 3 / 5 / majority |
| `extraction.passes` / `max-pages` | 3 / 20 |
| `openai.model` (vision), temperatures for separation / classification / extraction | **[builder]** picks the current vision model, temperatures about 0.5 / 0.7 / 0.2 |
| `wait.timeout` / `wait.poll-interval` | 300s / 2s |
| `outbox.poll-interval` | 1s |
| `inbox.max-attempts` / `inbox.backoff-initial` / `inbox.backoff-max` / `inbox.lease` | 5 / 10s / 10m / 10m |
| `webhook.max-attempts` / `webhook.timeout` / `webhook.secret` | 8 / 10s / env `WEBHOOK_SECRET` |
| `s3.bucket` / `s3.prefix` | env `S3_BUCKET` / `files/` |

Spring's multipart limits (`spring.servlet.multipart.max-file-size` and `max-request-size`) are set to match
`file.max-size` and `batch.max-total-size`, so an oversized upload is cut off before it is buffered. **[proposed]**

The OpenAI key comes from env `OPENAI_API_KEY`. AWS credentials come from the default provider chain. Locally,
both point at the `local-docker` LocalStack.

## 18. Security and privacy **[proposed]**

- Every endpoint needs a Keycloak JWT except health, per the template.
- A caller can read only its own batches (`requested_by` must equal the token subject). An admin role can read all
  of them.
- S3 objects are private and use SSE-KMS. Downloads are never exposed. Page images stay internal.
- Logs carry ids, statuses and counts only, never extracted values, summaries, file names or webhook bodies.
- The audit payload excludes file bytes (section 16), and `openai_call` holds no prompt or response bodies.

## 19. Where things go (package layout)

| Package | Contents |
|---|---|
| `api` | `BatchController` (upload, get), plus CRUD controllers per table |
| `service` | Action services: `BatchIntakeService`, `SeparationService`, `ClassificationService`, `ExtractionService`, `BatchCompletionService`, `DocumentTypeCatalogService`, `WebhookDeliveryService`, `CompletionWaitService`, `BatchQueryService`. Each sits on CRUD services (`BatchService`, `SourceFileService`, `DocumentService`, `DocumentTypeService`, `LoanPurposeService`, `InboxMessageService` and the rest), which own their repositories |
| `processors` | `ImageConversionProcessor` (PDF, HEIC, TIFF → PNG), `BoundaryVoteProcessor`, `TypeVoteProcessor`, `TypeNameNormalizer` |
| `publisher` | `OutboxPublisher` (writes outbox rows and notifies) |
| `listener` | `PgNotificationListener` (passes `LISTEN` events to services), `InboxWorker` (claims and passes messages to the matching service) |
| `client` | `VisionClient` (the contract over the Spring AI `ChatClient`), `DocumentStorageClient` (the contract over S3), `WebhookClient` |
| `config` | `@ConfigurationProperties`, `ChatClient`, `S3Client`, executor and listener-connection beans |
| `dto.request` / `dto.response` / `dto.event` | Upload form, batch result envelope, and inbox message payloads |
| `exception` | Validation (including the per-file error list), unsupported media, OpenAI and S3 error translation |

## 20. Testing

- **Unit:** the normalization keys (the Paystub / Pay stubs cases), the boundary vote, the type vote and tie-break
  outcomes, backoff maths, the SSRF check and the HMAC signature.
- **Integration:** Postgres through Testcontainers and S3 through `testcontainers-localstack`. OpenAI is replaced with
  a stub `ChatModel` that returns scripted answers. That makes it possible to script split points, ties,
  disagreements, failures and new types.
- **Pipeline:**
  - with competing workers on one database, each message is processed exactly once;
  - stale leases are reclaimed;
  - the fan-in completes the batch exactly once when its last documents finish at the same moment;
  - the batch doesn't complete while one file is still separating;
  - one failed file or document gives `PARTIALLY_COMPLETED`;
  - the wait mode completes when another worker does the processing.
- **Golden documents:** a small set of redacted real samples per common type, plus a few multi-document packets and
  multi-file batches. This suite runs on demand against the real OpenAI and stays out of CI, to measure separation
  and classification accuracy and cost before tuning the run counts.

## 21. Left to the builder

The exact prompts. The OpenAI model and temperatures. The HEIC conversion tool and Jib image changes. Whether to tile
tall pages. How to merge separation windows. Whether to drop blank pages. Worker pool sizes. Indexes. The
blocking-wait mechanism. The wording of `ProblemDetail` messages.

## 22. Deferred decisions

- A retention and deletion policy for files, pages and extracted data.
- Returning an earlier result when the same file (same sha256) is uploaded again. The `sha256` column is already
  stored so this can be added later.
- Webhook signing secrets per caller.
- Using the loan purpose for required-document checklists.
