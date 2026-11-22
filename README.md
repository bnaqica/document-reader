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
sets `"additionalProperties": false`.

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

## Waiting for a batch

A sync request waits for its batch to reach a final status. A `batch_completed` notification from whichever instance
finished it ends the wait at once; the status is also re-read every `wait.poll-interval`, so a missed notification
only delays it. At `wait.timeout` the wait gives up and leaves the batch processing. A wait runs outside any
transaction, and a notification only prompts a re-read: the status decides.

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

```bash
./gradlew jibDockerBuild
```

To push to a registry instead:

```bash
IMAGE_REGISTRY=ghcr.io/my-org ./gradlew jib
```

## Contributing

Package layout and conventions are in `CLAUDE.md` and `.claude/rules/`.
