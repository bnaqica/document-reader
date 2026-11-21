# Proposal

## Why

Every later step of the pipeline records its progress somewhere:
- intake (WI-11) stores the batch and its files;
- separation (WI-12) splits each file into documents;
- classification and extraction (WI-13, WI-14) move each document through its statuses;
- batch completion (WI-10) locks the batch to finish it exactly once.

None of those tables exist yet. This is WI-03 of `docs/workitems/`. It needs only WI-01 and the WI-02 catalog, so it
comes next.

## What Changes

- **Tables:** add the four tables that track a batch through the pipeline, as designed in §16 of
  `docs/design/2026-11-20-document-reader-design.md`:
  - `batch`: one request;
  - `source_file`: one uploaded file in a batch;
  - `source_file_page`: one rendered page image of a file;
  - `document`: one logical document inside a file.
- **Services:** add CRUD services that later work items use to create these rows, move them through their statuses,
  and lock a batch for completion.
- **Read endpoints:** add read-only endpoints under `/records` for each table, as the repo rule requires a controller
  per table. Callers see only their own batches, and admins see all of them.
  - They are read-only for everyone, because rows are created and changed only by the pipeline.
  - They sit under `/records` because WI-11 owns `POST /batches` and `GET /batches/{id}` for intake and the result
    envelope.
- **Foreign keys:** link documents to their document type and to the type a document caused to be created.
- **Catalog behaviour change:** a document type that documents refer to can no longer be removed. That attempt now
  gets 409 instead of a database error.

## Capabilities

### New Capabilities

- `processing-records`: the stored record of each batch, source file, page and document. It covers what each record
  holds, the statuses it moves through, how the pipeline creates and updates records, and who can read them.

### Modified Capabilities

- `document-type-catalog`: adds the requirement that a type documents refer to can't be removed. This is written as
  an ADDED requirement, because `wi-02-reference-data`, which creates this capability, isn't archived yet. It doesn't
  change any requirement WI-02 wrote.

## Impact

- **Database:** migration V5 adds the four tables with foreign keys to `loan_purpose` and `document_type`, a foreign
  key from `document_type.created_by_document_id` to `document`, and indexes.
- **API:** new read-only endpoints under `/records/batches`, `/records/source-files` and `/records/documents`. A
  `DELETE /document-types/{id}` for a type in use now returns 409.
- **Code:**
  - entities and enums for the four tables;
  - repositories;
  - CRUD services: `BatchService`, `SourceFileService`, `SourceFilePageService` and `DocumentService`;
  - three read-only controllers and their response records;
  - a new `DocumentTypeInUseException`.
- **Later work items:** WI-10, WI-11, WI-12, WI-13 and WI-14 use these services.
