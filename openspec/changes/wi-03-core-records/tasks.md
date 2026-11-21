# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-03 as one commit.

## 1. Tables

- [x] 1.1 Write `repository/ProcessingRecordsSchemaTests` (`@DataJpaTest` plus Testcontainers, using `JdbcClient`). It asserts that inserting a second `source_file` with the same `(batch_id, file_index)` fails, a second page with the same `(source_file_id, page_number)` fails, a document with `start_page > end_page` fails, a batch with an unknown `loan_purpose_code` fails, and a batch status outside the five fails. Verify it fails because the tables don't exist.
- [x] 1.2 Write `V5__batches_files_documents.sql` (design decision 1), including the `document_type.created_by_document_id` foreign key. Verify 1.1 passes, and that `DocumentReaderApplicationTests` and every WI-02 test still pass.

## 2. Batches

- [x] 2.1 Write `service/BatchServiceTests`:
  - a sync batch starts `RECEIVED` with `NOT_APPLICABLE`, file count, requester and received time from the fixed `Clock`;
  - an async batch starts `PENDING` with its webhook URL;
  - an unknown purpose gives `ResourceNotFoundException` and stores no row;
  - completing as `PARTIALLY_COMPLETED` records the status and time;
  - completing as `PROCESSING` gives `IllegalArgumentException`;
  - completing twice gives `IllegalStateException`, and the first status stays;
  - `findRecent("user-1", 50)` returns only that caller's batches, newest first, and `findRecent(null, 50)` returns all.

  Verify it fails. Implement the `Batch` entity and enums, `BatchRepository`, `BatchService(Impl)`, `BatchRecord` and `NewBatch`, then verify it passes.
- [x] 2.2 Write `service/BatchLockTests` (not transactional, cleaning up its rows). Thread A locks a batch inside a transaction, sets status `PROCESSING`, waits 1 s and commits. Thread B locks the same batch after A has it, and must observe `PROCESSING` and a wait of at least 900 ms. Also assert that `lockForUpdate` outside a transaction throws `IllegalTransactionStateException`. Verify it fails, add the `@Lock(PESSIMISTIC_WRITE)` repository method and the `MANDATORY` service method, then verify it passes.

## 3. Source files and pages

- [x] 3.1 Write `service/SourceFileServiceTests`:
  - three files get indexes 0, 1 and 2 in list order, all `RECEIVED`;
  - `findByBatch` returns index order;
  - `markStored` records the bucket and key and sets `STORED`;
  - `FAILED` with a reason, then `SEPARATING`, clears the reason.

  Verify it fails. Implement `SourceFile`, its repository, `SourceFileService(Impl)`, `SourceFileRecord` and `NewSourceFile`, then verify it passes.
- [x] 3.2 Write `service/SourceFilePageServiceTests`: pages saved as 2, 1, 3 list as 1, 2, 3; saving pages 1 and 2 again leaves exactly 1 and 2. Verify it fails. Implement `SourceFilePage`, `SourceFilePageId`, its repository, `SourceFilePageService(Impl)`, `PageRecord` and `PageImage`, then verify it passes.

## 4. Documents and the catalog link

- [x] 4.1 Write `service/DocumentServiceTests`:
  - ranges 1–2, 3–3 and 4–6 on a 6-page file give sequences 1, 2 and 3, all `PENDING`, each with both `batch_id` and `source_file_id`;
  - ranges given out of order are numbered by page;
  - 1–4 with 5–7, 3–5 with 4–6, 0–2, and 3–2 each give `InvalidPageRangesException`, with no rows created;
  - `findByBatch` puts file 0's documents before file 1's;
  - `assignType` to "Pay stubs" is recorded;
  - `FAILED` with a reason sets `completed_at` from the `Clock`.

  Verify it fails. Implement `Document`, its repository, `DocumentService(Impl)`, `DocumentRecord`, `PageRange` and `InvalidPageRangesException`, then verify it passes.
- [x] 4.2 Extend `service/DocumentTypeServiceTests`: deleting a type that a document refers to gives `DocumentTypeInUseException`, and the type still resolves afterwards; deleting an unused type still works. Extend `api/DocumentTypeControllerTests`: `DocumentTypeInUseException` gives 409 as a problem detail. Verify both fail, implement design decision 5 and its handler, then verify they pass.

## 5. Reading records

- [x] 5.1 Write `service/RecordQueryServiceTests` (real Postgres): the owner reads its batch, files, pages and documents; another caller gets `ResourceNotFoundException` for each, including a document and a file by their own ids; an admin reads them all; and listing returns only the caller's batches unless the caller is an admin. Verify it fails, implement `Caller` and `RecordQueryService(Impl)` (design decision 4), then verify it passes.
- [x] 5.2 Write `api/BatchRecordControllerTests`, `api/SourceFileRecordControllerTests` and `api/DocumentRecordControllerTests` (`@WebMvcTest`, `@Import(SecurityConfig.class)`, mocked `RecordQueryService`). They check:
  - each GET returns 200, passing `Caller("user-1", false)` for a plain `jwt().jwt(j -> j.subject("user-1"))` and `admin=true` with `ROLE_ADMIN`;
  - `ResourceNotFoundException` gives 404 as a problem detail;
  - `limit=0` and `limit=500` give 400;
  - POST, PUT, PATCH and DELETE on each path give 405 for an admin, without calling the service;
  - no token gives 401.

  Verify they fail, implement the three controllers, then verify they pass.

## 6. Verification

- [x] 6.1 Update `README.md` with the `/records` endpoints and who can read them. Verify it matches design decision 4.
- [x] 6.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the WI-01 and WI-02 tests.
- [x] 6.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with `openspec/changes/wi-03-core-records/` as the requirements. Fix every Critical and Important finding, and verify the build again.
- [x] 6.4 Tick the boxes in `docs/workitems/WI-03-core-records.md` (except the developer review and commit), and set its status.

## Workflow follow-up

- The developer reviews the diff and commits WI-03 as one commit.
- Then archive in order: `wi-01-foundation`, `wi-02-reference-data`, then `wi-03-core-records`. WI-03's
  `document-type-catalog` delta adds to the capability WI-02 creates.
