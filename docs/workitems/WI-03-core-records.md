# WI-03: Core records: batches, source files, pages and documents

| | |
|---|---|
| **Status** | Built, awaiting developer review |
| **Depends on** | [WI-01](WI-01-foundation.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §5, §16 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 4 |

**Goal:** Create the batch, source file, page and document tables with their CRUD services and read-only controllers.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `V5__batches_files_documents.sql`; the `Batch`, `SourceFile`, `SourceFilePage` and `Document` entities and their enums, repositories, CRUD services and read-only controllers
- Test: `service/BatchServiceTests.java`, `service/SourceFileServiceTests.java`, `service/DocumentServiceTests.java`, `api/BatchCrudControllerTests.java`

**Interfaces:**
- Produces:
  - `BatchService`:
    - `BatchRecord create(NewBatch)`
    - `BatchRecord get(UUID)`
    - `void updateStatus(UUID id, BatchStatus status, String failureReason)`
    - `BatchRecord lockForUpdate(UUID id)` (`SELECT … FOR UPDATE`, which must run inside the caller's transaction)
    - `void complete(UUID id, BatchStatus finalStatus, String failureReason, Instant completedAt)`: the only way to a final status; `updateStatus` refuses final statuses and completed batches
    - `void updateWebhookStatus(UUID id, WebhookStatus status)`
  - `SourceFileService`:
    - `List<SourceFileRecord> createAll(UUID batchId, List<NewSourceFile>)`: `file_index` follows list order.
    - `SourceFileRecord get(UUID)`
    - `List<SourceFileRecord> findByBatch(UUID)`, ordered by `file_index`
    - `void markStored(UUID id, String s3Key)`
    - `void updateStatus(UUID id, SourceFileStatus status, String failureReason)`
  - `SourceFilePageService`: `void saveAll(UUID sourceFileId, List<PageImage>)` and `List<PageRecord> findBySourceFile(UUID)`, ordered.
  - `DocumentService`:
    - `List<DocumentRecord> createAll(UUID sourceFileId, List<PageRange>)`: the batch comes from the file
    - `DocumentRecord get(UUID)`
    - `List<DocumentRecord> findByBatch(UUID)`, ordered by file index and then sequence
    - `List<DocumentRecord> findBySourceFile(UUID)`
    - `void updateStatus(UUID id, DocumentStatus status, String failureReason)`
    - `void assignType(UUID id, UUID documentTypeId)`
  - Enums:
    - `BatchStatus { RECEIVED, PROCESSING, COMPLETED, PARTIALLY_COMPLETED, FAILED }`
    - `SourceFileStatus { RECEIVED, STORED, SEPARATING, SEPARATED, FAILED }`
    - `DocumentStatus { PENDING, CLASSIFYING, CLASSIFIED, EXTRACTING, COMPLETED, FAILED }`
    - `WebhookStatus { NOT_APPLICABLE, PENDING, DELIVERED, FAILED }`
  - Records:
    - `NewSourceFile(int index, String originalFilename, String contentType, long sizeBytes, String sha256, int pageCount)`
    - `PageRange(int start, int end)`
    - `PageImage(int pageNumber, String s3Key, int width, int height)`

- [x] **Step 1: Write the failing tests.**
  - `createBatchStartsReceived()`
  - `createAllFilesKeepsIndexOrder()`: three files give `file_index` 0, 1 and 2, all `RECEIVED`.
  - `markStoredSetsStoredAndKey()`
  - `createAllDocumentsAssignsSequenceInOrder()`: three ranges give sequences 1, 2 and 3 with `PENDING`, and each document has both `batch_id` and `source_file_id`.
  - `findByBatchOrdersByFileThenSequence()`: documents from file 1 come after all documents from file 0.
  - `lockForUpdateBlocksSecondTransaction()`: two threads, where the second waits until the first commits.
  - Controller: GET returns only the caller's own rows unless the caller is admin. POST, PUT and DELETE return 405 for non-admins.
- [x] **Step 2: Run** the tests. Expected: FAIL.
- [x] **Step 3: Write the V5 migration** for the `batch`, `source_file`, `source_file_page` and `document` columns in §16. Include foreign keys, `version` columns, a unique `(batch_id, file_index)`, and indexes on `source_file(batch_id)`, `document(batch_id)` and `document(source_file_id)`.
- [x] **Step 4: Implement** the entities, repositories, CRUD services and controllers.
- [x] **Step 5: Run** the tests and the build. Expected: PASS.

---

## Done when

- [x] Every step above is ticked.
- [x] `./gradlew spotlessApply build` passes, with all tests green.
- [ ] `git status` shows only the files this work item names, plus their tests and fixtures.
- [x] A code review (`superpowers:requesting-code-review`, using the `code-reviewer` agent) has no open findings.
- [ ] The developer has reviewed the diff.

## Commit

Claude never commits in this repo (`.claude/rules/git-workflow.md`). The developer commits this work item as one
commit, with this suggested message:

```text
feat: add batch, source file and document records
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
