# Design

## Context

After WI-02, the database has `loan_purpose`, `document_type` and `document_type_alias` (V2–V4), and the code has the
CRUD service pattern, problem-detail errors and Keycloak roles. The approved design's §5 (terms), §7.3 (statuses) and
§16 (columns) define the four tables this change adds. See `proposal.md` for why, and the two specs for behaviour.

Rules that shape this:
- every table gets a controller;
- only a table's CRUD service touches its repository;
- controllers hold no logic and never see entities;
- exceptions live in `exception`;
- the schema is `document_reader`.

## Goals / Non-Goals

**Goals:**
- The services later work items need, with the exact names in `docs/workitems/WI-03-core-records.md`.
- Integrity enforced by the database where it can be: foreign keys, a unique file index per batch, a unique page
  number per file, and status check constraints.
- One guarded, lockable path to completing a batch, which WI-10 builds on.

**Non-Goals:**
- Which status follows which. Later work items own their transitions; this change records whatever status it's given,
  apart from the completion guard.
- Truncation and extraction results (WI-14), and webhook delivery state beyond the initial value (WI-10, WI-15).
- Paging beyond a simple `limit`.

## Decisions

### 1. Tables (V5)

| Table | Columns and constraints |
|---|---|
| `batch` | `id UUID` pk, `loan_purpose_code` FK → `loan_purpose(code)`, `async BOOLEAN`, `webhook_url VARCHAR(2048)`, `status` (check: the five statuses), `webhook_status` (check: the four), `failure_reason TEXT`, `file_count INT` (> 0), `requested_by VARCHAR(255)`, `received_at`, `completed_at TIMESTAMPTZ`, `version BIGINT` |
| `source_file` | `id UUID` pk, `batch_id` FK → `batch`, `file_index INT` (≥ 0), `original_filename VARCHAR(512)`, `content_type VARCHAR(255)`, `size_bytes BIGINT`, `sha256 CHAR(64)`, `s3_bucket`, `s3_key VARCHAR(1024)` (null until stored), `page_count INT` (> 0), `status` (check), `failure_reason`, `version`; **unique** `(batch_id, file_index)` |
| `source_file_page` | **pk** `(source_file_id, page_number)`, FK → `source_file` ON DELETE CASCADE, `s3_key`, `width INT`, `height INT` |
| `document` | `id UUID` pk, `batch_id` FK → `batch`, `source_file_id` FK → `source_file`, `sequence INT` (> 0), `start_page`, `end_page` (check `start_page ≤ end_page`), `status` (check), `document_type_id` FK → `document_type` (**no cascade**: a type in use can't be deleted), `truncated BOOLEAN DEFAULT false`, `failure_reason`, `completed_at`, `version`; **unique** `(source_file_id, sequence)` |

- **Indexes:** `source_file(batch_id)`, `document(batch_id)`, `document(source_file_id)`, `document(document_type_id)`,
  and `batch(requested_by, received_at DESC)` for listing.
- **Link back to the catalog:** `document_type.created_by_document_id` gains its foreign key to `document`, with
  `ON DELETE SET NULL`. WI-02's comment said it would arrive here.
- **Why `document.batch_id` is stored alongside `source_file_id`:** completion and the result query read a whole
  batch without joining through files (design §16).

### 2. Entities and status methods

`Batch`, `SourceFile`, `SourceFilePage` (with an `@EmbeddedId SourceFilePageId(sourceFileId, pageNumber)`) and
`Document`, plus the enums `BatchStatus`, `SourceFileStatus`, `DocumentStatus` and `WebhookStatus`, all in `entity`.
They follow the existing entity pattern, with `@Version` on the three mutable tables.

The entities change state through small methods, so the rules live in one place:
- `Batch.updateStatus(status, reason)`.
- `Batch.updateStatus` refuses a final status (`IllegalArgumentException`) and a completed batch
  (`IllegalStateException`), so `complete` is the only way to finish. *(Tightened after code review.)*
- `Batch.complete(finalStatus, reason, at)`. It throws `IllegalArgumentException` for a status that isn't final, and
  `IllegalStateException` when the batch already has `completed_at`.
- `SourceFile.markStored(bucket, key)` and `SourceFile.updateStatus(status, reason)`.
- `Document.updateStatus(status, reason, now)`, which records `completed_at` on `COMPLETED` or `FAILED`.
- `Document.assignType(typeId)`.

A reason is kept only with `FAILED` and cleared otherwise. A batch's initial webhook status comes from `async`:
`PENDING` if async, otherwise `NOT_APPLICABLE`.

### 3. Services (CRUD, one per table)

| Service | Methods | Notes |
|---|---|---|
| `BatchService(Impl)` | `create(NewBatch)`, `get`, `updateStatus`, `lockForUpdate`, `complete`, `updateWebhookStatus`, `findRecent(String requestedBy /* null = all */, int limit)` | `lockForUpdate` uses a repository method with `@Lock(PESSIMISTIC_WRITE)`, and is `@Transactional(propagation = MANDATORY)`, so calling it outside a transaction throws `IllegalTransactionStateException` |
| `SourceFileService(Impl)` | `createAll(batchId, List<NewSourceFile>)`, `get`, `findByBatch`, `markStored(id, bucket, key)`, `updateStatus` | Indexes come from list position; `NewSourceFile` carries no index. *(The plan listed `index` in `NewSourceFile`; deriving it from position removes a way to get it wrong.)* |
| `SourceFilePageService(Impl)` | `saveAll(sourceFileId, List<PageImage>)`, `findBySourceFile` | `saveAll` deletes the file's pages, flushes, then inserts, all in one transaction |
| `DocumentService(Impl)` | `createAll(sourceFileId, List<PageRange>)` (the batch comes from the file, and a composite foreign key `document(source_file_id, batch_id) → source_file(id, batch_id)` backs it; an empty list is refused), `get`, `findByBatch`, `findBySourceFile`, `updateStatus`, `assignType` | Validates ranges against the file's page count, from `SourceFileService.get`, and throws `InvalidPageRangesException`. Sorts ranges by start before numbering |

- **Dependency direction:** `DocumentService` depends on `SourceFileService`, never the reverse, and no CRUD service
  calls an action service.
- **Ordering:** `findByBatch` orders by the file's `file_index`, then `sequence`, using a JPQL join to `SourceFile`.
- **Records** (`BatchRecord`, `SourceFileRecord`, `PageRecord` and `DocumentRecord`) are response records in
  `dto.response`, built with builders.
- **Inputs** (`NewBatch(loanPurposeCode, async, webhookUrl, requestedBy, fileCount)`,
  `NewSourceFile(originalFilename, contentType, sizeBytes, sha256, pageCount)`, `PageRange(start, end)` and
  `PageImage(pageNumber, s3Key, width, height)`) are internal records at the `dto` root.
- **Unknown loan purpose:** `create` checks it through `LoanPurposeService.get`, so an unknown code throws
  `ResourceNotFoundException` before any insert. The foreign key backs it.

### 4. Reading records, and who may

Ownership is logic, so it isn't in the controllers. `RecordQueryService(Impl)` is an action service over the four
CRUD services:
- each method takes a `Caller(String subject, boolean admin)`;
- a record belonging to another caller's batch throws the same `ResourceNotFoundException` as a missing one.

`Caller` is a record at the `dto` root, with a static `Caller.of(Authentication)`:
- `admin` is true when the authorities contain `ROLE_ADMIN`;
- `subject` is the JWT subject, or the authentication name otherwise.

In the `local` profile that gives an admin caller.

The controllers `BatchRecordController`, `SourceFileRecordController` and `DocumentRecordController` map only GETs
under `/records`:
- `GET /records/batches?limit=`: `@Min(1) @Max(200)`, default 50;
- `GET /records/batches/{id}`, `/{id}/source-files` and `/{id}/documents`;
- `GET /records/source-files/{id}` and `/{id}/pages`;
- `GET /records/documents/{id}`.

Spring answers any other method on those paths with 405. The existing `ResponseEntityExceptionHandler` base renders
that as a problem detail.

### 5. A type in use can't be removed

`DocumentTypeServiceImpl.delete` deletes and flushes. A `DataIntegrityViolationException` (the restricting foreign
key from `document`) becomes `DocumentTypeInUseException`, which the handler maps to 409.
- The database is the guarantee; there's no count query first.
- *Alternative:* checking through `DocumentService` would make the catalog depend on documents. That's one-way and
  legal, but it races with a document being classified at that moment, so the foreign key was chosen.

### 6. Tests

| Test | What it covers |
|---|---|
| `service/BatchServiceTests` | creation defaults, the unknown purpose, completion guards, `findRecent` filtering and order |
| `service/BatchLockTests` | the two-thread lock wait, and the lock refused outside a transaction; not transactional, with its own cleanup |
| `service/SourceFileServiceTests` | index order, stored, and the failure reason being set and cleared |
| `service/SourceFilePageServiceTests` | page order and replacement |
| `service/DocumentServiceTests` | sequences, every refused range, batch ordering across files, assigning a type, and completion time |
| `service/RecordQueryServiceTests` | the owner, another caller (404), and an admin |
| `api/*RecordControllerTests` (`@WebMvcTest`) | 200, 404, 400 for the limit, and 405 for writes, including as an admin |
| `service/DocumentTypeServiceTests` (extended) | deleting a type in use gives `DocumentTypeInUseException`, and the type survives |

All database tests run against Testcontainers Postgres.

### 7. Changes after code review

- **Record controllers** take `@CurrentSecurityContext(expression = "authentication") Authentication`. A plain
  `Authentication` parameter is null for the `local` profile's anonymous admin, and that caused a 500.
  `Caller.mayRead` is null-safe for a token with no subject.
- **New entities** use a nullable `Long` version, and `SourceFilePage` implements `Persistable`, so saving inserts
  directly instead of merging (one SELECT fewer per row).
- **Batch listing** breaks ties on `id`, so `limit` is stable.
- **Moving a document back** from a finished status clears its completion time.
- **`assignType`** checks the type through `DocumentTypeService.get`.
- **The in-use translation** maps only the named constraint `fk_document_type_in_use`, keeps the cause, and rethrows
  any other integrity error.

## Risks / Trade-offs

- [No status-transition rules: a bug in a later work item could set an odd status] → The completion guard covers the
  one transition that must happen exactly once. Later work items test their own transitions.
- [Deleting all of a file's pages and re-inserting them runs two statements] → Both run in one transaction, and a
  page set is at most 100 rows.
- [`requested_by` in the `local` profile is the anonymous name] → That's acceptable on a developer machine, where
  every caller is an admin anyway.

## Migration Plan

- Flyway applies V5 on startup.
- Before production, rolling back means dropping the four tables, dropping the new `created_by_document_id` foreign
  key, and deleting V5 from the Flyway history.
