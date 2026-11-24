# document-reader

Accepts multipart document uploads for lending workflows, records each request in PostgreSQL and stores the file in S3 under the request's UUID. Using Spring AI with OpenAI vision, it classifies each document by voting over a configurable number of classification calls (lender document types, extendable by the model), then extracts the document's information and a summary by consolidating several extraction passes into a final determination. Processing runs asynchronously through the inbox/outbox pattern on PostgreSQL.

| | |
|---|---|
| Port | 8084 |
| Stack | Java 25, Spring Boot 4.1, Spring AI (OpenAI), AWS S3, Gradle 9.9 (wrapper), PostgreSQL, Flyway, JUnit, Testcontainers, Spring Security (JWT), springdoc, Spotless, JaCoCo, Lombok, SLF4J (Logback), Jib |

## Prerequisites

- A JDK 17+ to run Gradle. The Java 25 toolchain is downloaded automatically by Gradle (foojay resolver)
  if you don't have it.
- In your IDE, the Lombok plugin with annotation processing enabled.
- For the feature workflow in Claude Code: Node.js 22.12+ and OpenSpec (`npm install -g @fission-ai/openspec@latest`).
- Docker running, for the local database and the Testcontainers tests.

## Run locally

```bash
./gradlew bootRun
```

This uses the `local` profile, which turns off authentication. Swagger UI is at
<http://localhost:8084/swagger-ui.html>.

The `local` profile still needs `OPENAI_API_KEY` (see below). It stores files in the LocalStack S3 from the sibling
`local-docker` repo, at `http://localhost:4566`. Start that LocalStack, then create the bucket once:

```bash
awslocal s3 mb s3://document-reader-local
```

No AWS variables are needed locally: with an S3 endpoint override the service uses placeholder credentials, which
LocalStack accepts, and encrypts with `AES256`, since LocalStack has no KMS. Elsewhere, credentials come from the AWS
default chain (an IAM role in production) and objects are encrypted with `aws:kms`.

Objects are stored at `files/{sourceFileId}/original.{ext}` and `files/{sourceFileId}/pages/0001.png` onwards. Keys
hold only ids, page numbers and the extension, never a file name.

The service's AWS role needs `s3:GetObject`, `s3:PutObject` and `s3:ListBucket` on the bucket, plus
`kms:GenerateDataKey` and `kms:Decrypt` for SSE-KMS. Without `s3:ListBucket`, S3 answers a missing key with 403, so
checking whether pages already exist fails instead of answering no.

## Environment variables

| Variable | Purpose | In the `local` profile |
|---|---|---|
| `DB_URL` | JDBC URL of the database | Not needed (`compose.yaml`) |
| `DB_USERNAME` | Database user | Not needed |
| `DB_PASSWORD` | Database password | Not needed |
| `JWT_ISSUER_URI` | Issuer whose JWTs this service accepts | Not needed (authentication is off) |
| `OPENAI_API_KEY` | OpenAI API key, used for vision calls | **Needed** |
| `OPENAI_MODEL` | OpenAI vision model; defaults to `gpt-4o` | Optional |
| `S3_BUCKET` | S3 bucket for uploaded files and page images | Not needed (`document-reader-local`) |
| `S3_SERVER_SIDE_ENCRYPTION` | S3 server-side encryption for every object: `aws:kms` (default) or `AES256` | Not needed (`AES256`, as LocalStack has no KMS) |
| `APP_DOCUMENTREADER_INBOX_CONCURRENCY` | Inbox messages one worker handles at once (`app.document-reader.inbox.concurrency`); defaults to 4 | Optional |
| `APP_DOCUMENTREADER_WORKERS_ENABLED` | Runs the background dispatcher and inbox workers (`app.document-reader.workers.enabled`); defaults to `true` | Optional |

Processing settings (limits, run counts, timeouts and retries) are under `app.document-reader` in
`src/main/resources/application.yml`, with their defaults. The service refuses to start if one can't work, such as an
even vote count or a zero limit.

## Batch API

`POST /batches` (multipart) sends a batch, and `GET /batches/{id}` reads its result at any stage. Both need a bearer
token. A caller reads only the batches they sent (by token subject), and an admin reads every batch. Another
caller's batch gets 404, the same as one that doesn't exist.

| Part | Rule |
|---|---|
| `files` (repeated) | 1 to `batch.max-files` (20) files, each up to `file.max-size` (50 MB) and `file.max-pages` (100), and up to `batch.max-total-size` (200 MB) in all |
| `loanPurpose` | Required; an active code from `GET /loan-purposes` |
| `async` | Optional, `true` or `false` (default `false`) |
| `webhookUrl` | Required when `async` is true, and refused otherwise; an absolute `http` or `https` URL (no other checks for the proof of concept) |

The whole batch is checked before anything is stored, and one bad file refuses it all:
- **400:** a request field. The checks run in the order above, and only the first failure is reported. The detail
  names the problem, and for the loan purpose it lists the valid codes.
- **413:** the whole batch is over its size limit.
- **415, 413 or 422:** a failing file, for an unsupported type, a file over the size limit, or a file that is
  unreadable (password-protected or corrupt) or over the page limit. The problem's `errors` lists every failing
  file with its `index`, `fileName`, `status` and `detail`. The status is the first of 415, 413 and 422 that any file
  failed with.

A real upload with a file over 50 MB is refused while it is being received, so its 413 doesn't name the file.

**What happens to an accepted batch:**
1. The batch and its files are recorded.
2. Each original goes to S3 under `{prefix}{fileId}/original.{ext}`. The extension comes from the detected type.
3. One `SEPARATE_FILE` message is queued per file.

If S3 fails, the batch and its files become `FAILED` ("Storing the files failed"), and the caller hears of it the
usual way.

**The answer:**
- **An async batch** gets 202 `{ batchId, status, statusUrl }` straight away. Its webhook follows once it completes
  (WI-15).
- **A sync batch** waits up to `wait.timeout` (300 s). It gets 200 with the result if it finishes in time, and 202
  otherwise, while processing carries on.

The result (also returned by `GET /batches/{id}`):

```json
{
  "batchId": "4b0e…", "status": "COMPLETED", "loanPurpose": "CAR_REFINANCE",
  "receivedAt": "2026-11-20T16:00:00Z", "completedAt": "2026-11-20T16:01:12Z", "error": null,
  "files": [
    { "id": "a1…", "index": 0, "fileName": "packet.pdf", "pageCount": 6, "status": "SEPARATED", "documentCount": 3, "error": null }
  ],
  "documents": [
    {
      "id": "9c1d…", "sourceFileId": "a1…", "pages": { "start": 1, "end": 2 }, "status": "COMPLETED",
      "documentType": { "name": "Pay stubs", "newType": false },
      "classification": { "runs": 5, "votes": { "Pay stubs": 4, "W-2 forms": 1 }, "agreement": 0.8, "tieBreak": null },
      "summary": "Bi-weekly pay stub …", "data": { "employerName": "Acme Corp" }, "truncated": false, "error": null
    }
  ]
}
```

**Reading the result:**
- Files are in upload order, and documents are in file order, then page order.
- `documentType` and `classification` are null until a document is classified, and `summary` and `data` are null
  until it is extracted.
- `newType` is true for a type the model created.
- `tieBreak` is filled in only when the first vote tied. Then it holds that round's `runs`, `votes` and `decidedBy`.

The audit row for an upload records the form fields and, for each file, its name, declared type, size and SHA-256,
never its bytes. An upload refused for its size is audited with a null payload. `server.tomcat.max-swallow-size`
follows `batch.max-total-size`, so a client sending an oversized file still gets its 413, rather than a reset
connection.

## Reference data API

Every call needs a bearer token. Reads are open to any valid token. Creating, changing or removing reference data
needs the Keycloak realm role `admin`; without it the call returns 403. In the `local` profile, every request acts
as an admin.

| Method and path | What it does | Needs `admin` |
|---|---|---|
| `GET /loan-purposes` | Lists active loan purposes, by code | No |
| `GET /loan-purposes/{code}` | One loan purpose, active or not | No |
| `POST /loan-purposes` | Adds one: `{code, name, description}` | Yes |
| `PUT /loan-purposes/{code}` | Replaces `{name, description, active}` | Yes |
| `DELETE /loan-purposes/{code}` | Deactivates it (batches keep referring to the code) | Yes |
| `GET /document-types` | Lists document types, by name | No |
| `GET /document-types/{id}` | One document type | No |
| `POST /document-types` | Adds one: `{name, description, dataSchema}` | Yes |
| `PUT /document-types/{id}` | Replaces `{name, description, dataSchema}`; a changed schema raises its version | Yes |
| `DELETE /document-types/{id}` | Removes it and its aliases; 409 while a document, classification run or result, or extraction result refers to it | Yes |
| `GET /document-types/{id}/aliases` | Lists a type's other names | No |
| `POST /document-types/{id}/aliases` | Adds one: `{alias}` | Yes |
| `DELETE /document-types/{id}/aliases/{aliasId}` | Removes one | Yes |

Names are compared by a key that ignores case, punctuation and plurals, so "Pay stubs", "Paystub" and "pay-stub" are
the same name. A name or alias whose key is already taken gets 409, naming the type that owns it. A `dataSchema` must
be strict JSON Schema: every object lists all its properties as required (allowing `null` for absent values) and
sets `"additionalProperties": false`. It may use only the keywords strict mode accepts (`type`, `properties`,
`required`, `additionalProperties`, `items`, `anyOf`, `enum`, `const`, `description`, `format`, `pattern`, number bounds,
`minItems` and `maxItems`), and objects may nest at most 9 deep.

## Processing records API

Read-only views of what the pipeline has stored. A caller sees only the batches they sent (by token subject);
admins see every batch. A record of someone else's batch returns 404, the same as one that doesn't exist. Any method
other than GET returns 405, for admins too: only the pipeline creates or changes records.

| Path | Returns |
|---|---|
| `GET /records/batches?limit=50` | The caller's batches, newest first; `limit` is 1–200 |
| `GET /records/batches/{id}` | One batch |
| `GET /records/batches/{id}/source-files` | The batch's uploaded files, in upload order |
| `GET /records/batches/{id}/documents` | The batch's documents, by file then page order |
| `GET /records/source-files/{id}` | One uploaded file |
| `GET /records/source-files/{id}/pages` | The file's rendered pages, in page order |
| `GET /records/documents/{id}` | One document |
| `GET /records/batches/{id}/webhook-attempts` | The batch's webhook delivery attempts, in attempt order |
| `GET /records/batches/{id}/openai-calls` | The batch's OpenAI calls, oldest first (model, latency, tokens, status; never prompts or responses) |
| `GET /records/source-files/{id}/separation-runs` | The file's separation runs, by window then run number |
| `GET /records/documents/{id}/classification-runs` | The document's classification runs, initial round then tie-break |
| `GET /records/documents/{id}/classification-result` | The decided type, with votes; 404 until classified |
| `GET /records/documents/{id}/extraction-runs?stage=` | The document's extraction runs, by stage then pass; `stage` (`INITIAL`, `CONSOLIDATED` or `FINAL`) is optional |
| `GET /records/documents/{id}/extraction-result` | The final data and summary; 404 until extracted |
| `GET /records/outbox-messages?limit=50` | **Admins only.** Messages steps have published, newest first |
| `GET /records/inbox-messages?type=&status=&limit=50` | **Admins only.** Inbox messages, newest first, optionally one type and status |
| `GET /records/inbox-messages/{id}` | **Admins only.** One inbox message, with its attempts and last error |

## Messages between steps

The pipeline's steps hand work to each other through a Postgres outbox and inbox:

1. **Publish.** A step writes its next message to `outbox_message` in the same transaction as its own data, so a
   rollback drops both. Postgres sends `NOTIFY outbox_ready` when it commits.
2. **Dispatch.** A dispatcher on every instance copies undispatched rows into `inbox_message` (same id, so never
   twice) and sends `NOTIFY inbox_ready`.
3. **Claim.** A worker per message type claims due messages with `FOR UPDATE SKIP LOCKED` under a lease
   (`inbox.lease`), counting one attempt each time, and commits before any work starts.
4. **Finish.** Success marks the message `DONE`. A failure retries after `backoff-initial × 2^(attempts − 1)`, up to
   `backoff-max`. On the last attempt (`inbox.max-attempts`, or `webhook.max-attempts` for webhooks) the message is
   `FAILED` and its step is told, so it can fail its file or document.

A message whose lease expires (its instance crashed) is claimed again.

Workers wake on a signal from the same instance, on a Postgres notification from another, or every
`outbox.poll-interval`. Each instance holds one connection outside the pool that `LISTEN`s on `outbox_ready`,
`inbox_ready` and `batch_completed`; it shows in `pg_stat_activity` as `document-reader-listener`, and reconnects with
backoff if it drops. `app.document-reader.workers.enabled=false` turns the loops and the listener off.

## Separating a file

Each stored file's `SEPARATE_FILE` message splits it into documents:

1. **Conversion.** The file becomes `SEPARATING`. Its pages are converted to PNG one at a time, stored under
   `{prefix}{fileId}/pages/{nnnn}.png` and recorded. A retry reuses recorded pages.
2. **A single page** is one document, with no model call.
3. **Windows and runs.**
   - A longer file is sent in windows of `separation.window-size` pages (15), each overlapping the one before by
     `separation.window-overlap` (2). For example, 40 pages are sent as 1–15, 14–28 and 27–40.
   - Each window gets `separation.runs` separation calls (3, and it must be odd), made at once. Each run is saved as it
     finishes, and a retry makes only the calls still missing.
   - A window with fewer than a majority of successful runs fails the attempt, and the inbox retries it.
4. **The vote.**
   - Each answer is cleaned: page 1, pages outside the window and duplicates are dropped.
   - A gap between two pages is a document boundary when more than half of the successful runs of one window start a
     document there. That window is the one where the gap sits furthest from an edge, or the earlier on a tie.
   - Page 1 always starts a document.
5. **Finishing.** One transaction creates the documents (numbered from 1 within the file), marks the file `SEPARATED`,
   and queues one `CLASSIFY_DOCUMENT` per document.

A file whose message is exhausted becomes `FAILED` ("Separation failed"), and the batch completion check runs, so the
batch's other files carry on. A message for a file already separated or failed does nothing.
`separation.window-overlap` must be at least 1 and less than `separation.window-size`, or the service won't start.

## Classifying a document

Each document's `CLASSIFY_DOCUMENT` message decides its type:

1. **Runs.**
   - The document becomes `CLASSIFYING`.
   - Its first `classification.max-pages` pages (3), the loan purpose and the catalog go to `classification.runs`
     classification calls (5), at most `classification.parallelism` at a time (5).
   - Each run is saved as it finishes, and a retry makes only the calls still missing.
   - Fewer than `classification.min-successful-runs` successful runs (a majority) fails the attempt, and the inbox
     retries it.
2. **Resolving.** Every answer is resolved to the catalog by its name's key before it is counted, so "Paystub" and
   "Pay stubs" are votes for the same type.
   - A name with no usable key ("N/A") is a failed run.
   - A name that resolves to no type counts as a vote for that new name.
3. **The vote.**
   - The most votes wins (`VOTE`).
   - On a tie, `classification.tiebreak-runs` (3) calls may choose only among the tied candidates (`TIEBREAK`).
   - If that ties too, the highest average confidence over both rounds wins (`CONFIDENCE`).
4. **A winning name that isn't in the catalog.** One call asks whether it is an existing type under another name.
   - If it is, the name becomes that type's alias.
   - If not, one call drafts its data schema and a `MODEL` type is created. Its description is "not yet reviewed".
   - Two documents creating the same type at once end up with one.
5. **Finishing.** One transaction saves the result, assigns the type, sets the document to `CLASSIFIED` and queues
   `EXTRACT_DOCUMENT`.
   - The result records the votes, the agreement (winner votes ÷ the first-round runs counted), whether a tie-break
     ran, and how the type was decided.

A document whose message is exhausted becomes `FAILED` ("Classification failed"), and the batch completion check runs.
A message for a document that is already classified, further along or failed does nothing.

## Completing a batch

Every step that finishes a document (completed or failed) or fails a file runs the completion check in its own
transaction. The check locks the batch row, so only one step can complete it, and completes the batch once every file
is `SEPARATED` or `FAILED` and every document is `COMPLETED` or `FAILED`:
- `COMPLETED`: every file was separated and every document completed;
- `FAILED` ("No document completed"): no document completed, including when every file failed;
- `PARTIALLY_COMPLETED`: anything in between.

Completing records the time, sends `batch_completed` on commit, and for an async batch sets `webhookStatus` to
`PENDING` and writes one `DELIVER_WEBHOOK` message. Intake can also fail a whole batch with a reason, with the same
notice and webhook. A check on a batch that is already complete does nothing, so retried steps are safe.
`DELIVER_WEBHOOK` messages stay `PENDING` in the inbox until webhook delivery (WI-15) is built.

## Waiting for a batch

A sync request waits for its batch to reach a final status. A `batch_completed` notification from whichever instance
finished it ends the wait at once; the status is also re-read every `wait.poll-interval`, so a missed notification
only delays it. At `wait.timeout` the wait gives up and leaves the batch processing. A wait runs outside any
transaction, and a notification only prompts a re-read: the status decides.

## Vision calls

Every OpenAI call goes through one vision client: separation, classification, tie-break, new-type confirmation,
schema drafting, extraction, consolidation and the final extraction. Each sends the page images (read from S3; none for
consolidation), the loan purpose and the step's inputs, and asks for a JSON answer in a fixed shape. Extraction asks for
the document type's own schema. Prompts live in `src/main/resources/prompts/`.

- **Model and temperature:** every call names `open-ai.model`, with the separation, classification or extraction
  temperature for its step. A blank model stops startup. Only the OpenAI chat model is set up.
- **Retries and timeout:** the OpenAI SDK retries a timeout, 429 or 5xx twice (`spring.ai.openai.chat.max-retries`),
  and a call times out after 120 seconds. The inbox retries the whole step after that.
- **Failures:** an HTTP error, no answer, invalid JSON or an answer missing a required value raises a vision error
  naming the step, never the content.
- **Call log:** each call is logged in `document_reader_integration.openai_call` with its ids, purpose, model,
  latency, tokens, HTTP status and a short error, never the prompt or the answer. If the row can't be written, the
  call's answer is still used.
- **No open transaction:** a call made inside a database transaction is refused, so no call holds a connection and a
  rolled-back step keeps its log row.

## Test and format

```bash
./gradlew build
```

The build runs the tests, writes a coverage report to `build/reports/jacoco/test/html/index.html`, and fails if
the code isn't formatted. To format it:

```bash
./gradlew spotlessApply
```

## Build a container (Jib)

The image is built on a base image with ImageMagick and libheif, which HEIC uploads need. Build the base once:

```bash
docker build -t document-reader-base:1 src/main/docker/base
```

Then build the service image:

```bash
./gradlew jibDockerBuild
```

Jib reads the base from the local Docker daemon (`docker://document-reader-base:1`). The base build fails unless a
test HEIC really round-trips, so a missing HEVC decoder is caught there.

To push to a registry instead, push the base there too and name it:

```bash
IMAGE_REGISTRY=ghcr.io/my-org ./gradlew jib -PbaseImage=ghcr.io/my-org/document-reader-base:1
```

## Uploads and pages

An upload's type comes from its bytes, never its name: PDF, PNG, JPEG, HEIC/HEIF, TIFF and WEBP are supported, and
anything else is refused as unsupported. A password-protected or corrupt file, or an image over 100 million pixels, is
refused as unreadable. Every page becomes an upright RGB PNG (EXIF orientation applied, HEIC rotation honoured, no
alpha), at most `conversion.max-dimension` (2048) on its longest side; PDFs render at `conversion.dpi` (200), or less
where that would exceed the cap.

HEIC sequences (bursts) are refused as unsupported; a multi-image HEIC uses its first image. HEIC needs ImageMagick
with libheif's HEVC decoder on the `PATH` (`magick`, or `convert` for ImageMagick 6). Without it, HEIC uploads are refused
as unreadable, and the HEIC tests are skipped; CI installs it. On macOS: `brew install imagemagick`; on Ubuntu:
`apt-get install imagemagick libheif1 libheif-plugin-libde265`.

## Contributing

Package layout and conventions are in `CLAUDE.md` and `.claude/rules/`.
