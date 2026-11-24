# Work items

The document-reader build, split into work items that are developed and committed **one at a time, in order**.
Each work item leaves `./gradlew build` green, so every commit is a working state.

- **Design:** `docs/design/2026-11-20-document-reader-design.md`
- **Overall plan:** `docs/superpowers/plans/2026-11-20-document-reader.md`

The work items carry the plan's tasks, with two changes:

- Plan tasks 2 and 3 are combined into WI-02, because the normalizer is small and the type catalog needs it.
- Batch completion moved from the end of plan task 14 to its own work item, **WI-10**. Separation, classification
  and extraction all call it, so building it first lets each of them be committed with working failure paths.

## How to work an item

1. Pick the next work item whose dependencies are all `Done`.
2. Follow its steps test-first (`superpowers:test-driven-development`).
3. Before finishing, run a code review (`superpowers:requesting-code-review`, using the `code-reviewer` agent).
4. Check every box under **Done when**.
5. The developer reviews and commits it as one commit, then marks it `Done` here and in the file.

Claude never commits in this repo; `.claude/hooks/block-git-commit.sh` enforces that.

Listeners follow `listener -> @Service to CRUD @Service interface -> ...` (`.claude/rules/service-architecture.md`). `InboxWorker` and `PgNotificationListener` only hand work to a service. The inbox logic lives in `InboxProcessingService`, and listeners wake each other only through `WorkerSignalService`.

## Order

| # | Work item | Depends on | Status |
|---|---|---|---|
| WI-01 | [Foundation: dependencies, configuration and local S3](WI-01-foundation.md) | — | Not started |
| WI-02 | [Reference data: type-name normalizer, loan purposes and document types](WI-02-reference-data.md) | WI-01 | Not started |
| WI-03 | [Core records: batches, source files, pages and documents](WI-03-core-records.md) | WI-01 | Not started |
| WI-04 | [Run and result records, plus the OpenAI call log](WI-04-run-and-result-records-plus-the-openai-call-log.md) | WI-03 | Built, awaiting developer review |
| WI-05 | [Inbox/outbox engine](WI-05-inbox-outbox-engine.md) | WI-01 | Built, awaiting developer review |
| WI-06 | [Notifications and wait mode](WI-06-notifications-and-wait-mode.md) | WI-03, WI-05 | Built, awaiting developer review |
| WI-07 | [S3 storage client](WI-07-s3-storage-client.md) | WI-01 | Built, awaiting developer review |
| WI-08 | [Content detection and conversion to PNG](WI-08-content-detection-and-conversion-to-png.md) | WI-01 | Built, awaiting developer review |
| WI-09 | [Vision client (Spring AI)](WI-09-vision-client.md) | WI-04, WI-07 | Done |
| WI-10 | [Batch completion](WI-10-batch-completion.md) | WI-03, WI-05, WI-06 | Done |
| WI-11 | [Batch intake API, validation, audit and result query](WI-11-batch-intake-api-validation-audit-and-result-query.md) | WI-02, WI-03, WI-05, WI-06, WI-07, WI-08, WI-10 | Done |
| WI-12 | [Separation (process 1, per file)](WI-12-separation.md) | WI-03, WI-04, WI-05, WI-07, WI-08, WI-09, WI-10 | Done |
| WI-13 | [Catalog resolution and classification (process 2)](WI-13-catalog-resolution-and-classification.md) | WI-02, WI-03, WI-04, WI-05, WI-09, WI-10 | Built, awaiting developer review |
| WI-14 | [Extraction (process 3)](WI-14-extraction.md) | WI-02, WI-03, WI-04, WI-05, WI-09, WI-10 | Not started |
| WI-15 | [Webhook delivery (process 4)](WI-15-webhook-delivery.md) | WI-03, WI-04, WI-05, WI-10, WI-11 | Not started |
| WI-16 | [End-to-end pipeline and golden-document harness](WI-16-end-to-end-pipeline-and-golden-document-harness.md) | All of WI-01 to WI-15 | Not started |

WI-02, WI-03, WI-05, WI-07 and WI-08 depend only on WI-01, so after WI-01 they can be done in any order. The table
order is the recommended one.

Until a later work item lands, the messages it handles wait in the inbox unprocessed. For example, the
`SEPARATE_FILE` messages written by WI-11 are not handled until WI-12. That is expected, and each work item's tests
account for it.

## Global constraints

- Base package `com.bnaqica.documentreader`; follow `.claude/rules/` (package layout, CRUD versus action services, no logic in controllers, listeners or clients, mapping with `@Builder`).
- Main schema `document_reader`, integration schema `document_reader_integration`, audit schema `audit`. Never use `public`.
- Every table except `audit.api_audit` gets a CRUD controller. Pipeline tables are read-only except for the admin role (§7.4). `loan_purpose` and `document_type` are writable by admins only.
- Schema changes are new Flyway migrations. Never edit `V1__create_audit_table.sql`.
- Versions come from the Boot BOM. Spring AI, the AWS SDK, TwelveMonkeys, the JSON Schema validator, Tika and Testcontainers LocalStack are pinned or imported as BOMs, each with a comment saying why.
- OpenAI calls never run inside an open DB transaction (§13).
- Logs carry ids, statuses and counts only. Never log extracted values, summaries, file names or webhook bodies (§18).
- All configuration sits under `app.document-reader.*` with the defaults in §17.
- Tests use Testcontainers Postgres (and LocalStack for S3). OpenAI is always a stub `ChatModel` in CI.
- Terms (§5): a **batch** is one request, a **source file** is one uploaded file, and a **document** is one logical document inside a file. One payload per document.
- **Proof of concept** (§2a): synthetic or sample data only, and no webhook security. Do not build signing, secrets or SSRF checks; those are deferred (§22).
- **Never commit.** The repo's hook blocks commits and the developer reviews every file. Each work item ends with `./gradlew spotlessApply build` passing, and the work is left uncommitted.

## Review focus

The inputs the design implies but no single feature covers. Each is pinned to a test in the work item that owns it.

1. **One bad file in an otherwise valid batch** (corrupt or password-protected PDF, or an unsupported type): the whole request is rejected with an error naming that file. Nothing is written to Postgres or S3, so no batch exists that later fails at separation. Pinned in WI-08 and WI-11.
2. **Separation answer with out-of-range, duplicate or unsorted page numbers:** the run is cleaned (sorted, de-duplicated, 1 and anything above the page count dropped) instead of crashing or producing empty documents. Pinned in WI-12.
3. **Model type name that normalizes to an empty key** (for example "—" or "N/A"): it counts as a failed run, not as a new type with an empty name. Pinned in WI-02 and WI-13.
4. **Caller disconnects during wait mode:** processing continues, the waiter is removed from the registry, and nothing is logged at error level. Pinned in WI-06.
5. **Sideways phone photo (EXIF orientation 6 or 8, including HEIC):** it becomes an upright PNG before any model call. Pinned in WI-08.
