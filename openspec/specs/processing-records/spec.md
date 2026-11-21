# processing-records Specification

## Purpose
Defines the stored record of everything the service processes:
- a batch, which is one request;
- the source files uploaded in it;
- each file's rendered pages;
- the documents found in each file.

It covers what each record holds, the statuses it moves through, how the pipeline creates and changes records, and
who can read them.

## Requirements

### Requirement: A batch records its request
A batch SHALL record its loan purpose, whether it is async, its webhook URL, the caller who sent it (the token's
subject), how many files it holds, and when it was received. A new batch SHALL start with status `RECEIVED`. A batch
SHALL refer to an existing loan purpose, and creating one with an unknown code SHALL be refused.

#### Scenario: New synchronous batch
- **WHEN** the pipeline creates a batch for `CAR_REFINANCE` with 2 files, not async, from caller `user-1`
- **THEN** the batch has status `RECEIVED`, webhook status `NOT_APPLICABLE`, file count 2, requester `user-1`, and a
  received time

#### Scenario: New async batch
- **WHEN** the pipeline creates an async batch with webhook URL `https://example.com/hook`
- **THEN** the batch has webhook status `PENDING` and that webhook URL

#### Scenario: Unknown loan purpose
- **WHEN** the pipeline creates a batch for loan purpose `SPACESHIP_PURCHASE`
- **THEN** creation is refused and no batch is stored

### Requirement: A batch is completed exactly once
Completing a batch SHALL record a final status (`COMPLETED`, `PARTIALLY_COMPLETED` or `FAILED`), the completion time,
and with `FAILED` a reason. Completing is the only way to a final status. Completing with a status that isn't final,
completing twice, or changing a completed batch's status SHALL be refused and change nothing.

#### Scenario: Complete
- **GIVEN** a batch with status `PROCESSING`
- **WHEN** it is completed as `PARTIALLY_COMPLETED`
- **THEN** its status is `PARTIALLY_COMPLETED` and it has a completion time

#### Scenario: Not a final status
- **WHEN** a batch is completed as `PROCESSING`
- **THEN** completion is refused and the batch is unchanged

#### Scenario: Final status without completing
- **WHEN** a batch's status is set to `COMPLETED` without completing it
- **THEN** the change is refused and the batch is unchanged

#### Scenario: Completed batch can't be reopened
- **GIVEN** a batch completed as `COMPLETED`
- **WHEN** its status is set to `PROCESSING`
- **THEN** the change is refused and the batch stays `COMPLETED`

#### Scenario: Already complete
- **GIVEN** a batch already completed as `COMPLETED`
- **WHEN** it is completed again as `FAILED`
- **THEN** completion is refused and the batch stays `COMPLETED`

### Requirement: A batch can be locked for completion
The pipeline SHALL be able to lock a batch for the rest of its transaction. A second transaction locking the same
batch SHALL wait until the first ends. Locking SHALL be refused outside a transaction.

#### Scenario: Second locker waits
- **GIVEN** one transaction has locked a batch
- **WHEN** a second transaction locks the same batch
- **THEN** the second waits until the first commits, and then sees the first's changes

#### Scenario: No transaction
- **WHEN** a batch is locked outside any transaction
- **THEN** the lock is refused

### Requirement: Source files keep their upload order
Creating a batch's source files SHALL give them indexes 0, 1, 2 and so on, in the order they were uploaded. Each
SHALL record its original file name, content type, size, SHA-256 hash and page count, and SHALL start as
`RECEIVED`. No two files in a batch SHALL share an index.

#### Scenario: Three files
- **WHEN** three files are created for a batch
- **THEN** they have indexes 0, 1 and 2, in upload order, all `RECEIVED`
- **AND** listing the batch's files returns them in index order

### Requirement: A stored file records where it is
Marking a source file stored SHALL record its S3 bucket and key and set its status to `STORED`.

#### Scenario: Stored
- **WHEN** a file is marked stored in bucket `docs` under key `files/{id}/original.pdf`
- **THEN** its status is `STORED` with that bucket and key

### Requirement: Statuses record why something failed
Setting a source file or document to `FAILED` SHALL record a failure reason. Setting any other status SHALL clear it.
A document SHALL record its completion time while it is `COMPLETED` or `FAILED`, and clear it when moved back.

#### Scenario: Failure reason
- **WHEN** a document is set to `FAILED` with the reason "extraction gave invalid data twice"
- **THEN** its status is `FAILED`, that reason is recorded, and it has a completion time

#### Scenario: Retry clears the reason
- **GIVEN** a source file set to `FAILED` with a reason
- **WHEN** it is set to `SEPARATING`
- **THEN** it has no failure reason

### Requirement: A file's pages are kept in order
Saving a source file's rendered pages SHALL record each page's number, S3 key, width and height, replacing any pages
the file already had. Listing them SHALL return them in page order.

#### Scenario: Save and list
- **WHEN** pages 2, 1 and 3 of a file are saved
- **THEN** listing the file's pages returns pages 1, 2 and 3

#### Scenario: Saving again replaces
- **GIVEN** a file has pages 1 to 3
- **WHEN** pages 1 and 2 are saved for it again
- **THEN** the file has exactly pages 1 and 2

### Requirement: Documents cover a file's pages in order
Creating a file's documents from page ranges SHALL number them 1, 2, 3 and so on, in page order, and each SHALL start
as `PENDING`, linked to its file and to that file's batch, never another. The ranges SHALL start at page 1 or later, end at or before the
file's page count, have a start no later than their end, and not overlap. Ranges that break these rules SHALL be
refused, and no documents SHALL be created.

#### Scenario: Three documents
- **WHEN** documents are created for a 6-page file from ranges 1–2, 3–3 and 4–6
- **THEN** they have sequences 1, 2 and 3, all `PENDING`, each linked to the batch and the file

#### Scenario: Range past the last page
- **WHEN** documents are created for a 6-page file from ranges 1–4 and 5–7
- **THEN** creation is refused and the file has no documents

#### Scenario: No ranges
- **WHEN** documents are created for a file from no ranges
- **THEN** creation is refused

#### Scenario: Overlapping ranges
- **WHEN** documents are created from ranges 1–3 and 3–5
- **THEN** creation is refused and the file has no documents

### Requirement: A batch's documents list in file then page order
Listing a batch's documents SHALL return every document of the first file in sequence order, then those of the next
file, and so on.

#### Scenario: Two files
- **GIVEN** file 0 has documents 1 and 2, and file 1 has document 1
- **WHEN** the batch's documents are listed
- **THEN** they come back as file 0 document 1, file 0 document 2, then file 1 document 1

### Requirement: A document records its type
Assigning a document type to a document SHALL record it. The type SHALL exist.

#### Scenario: Assign
- **WHEN** the type "Pay stubs" is assigned to a document
- **THEN** the document refers to "Pay stubs"

#### Scenario: Unknown type
- **WHEN** a type id that doesn't exist is assigned to a document
- **THEN** the assignment is refused and the document is unchanged

### Requirement: Callers can read their own records
Records SHALL be readable through `/records` endpoints by the caller who sent the batch they belong to, and by admins
for any batch. A record of another caller's batch SHALL return 404, the same as a missing one, so its existence isn't
revealed.

#### Scenario: Every record has a read endpoint
- **WHEN** the caller who sent a batch requests each of `GET /records/batches/{id}`,
  `GET /records/batches/{id}/source-files`, `GET /records/batches/{id}/documents`, `GET /records/source-files/{id}`,
  `GET /records/source-files/{id}/pages` and `GET /records/documents/{id}` for it
- **THEN** each response is 200 with the matching records

#### Scenario: Own batch
- **WHEN** caller `user-1` requests `GET /records/batches/{id}` for a batch they sent
- **THEN** the response is 200 with that batch

#### Scenario: Another caller's batch
- **WHEN** caller `user-2` requests `GET /records/batches/{id}` for a batch `user-1` sent
- **THEN** the response is 404 as a problem detail

#### Scenario: Another caller's document
- **WHEN** caller `user-2` requests `GET /records/documents/{id}` for a document in a batch `user-1` sent
- **THEN** the response is 404 as a problem detail

#### Scenario: Admin
- **WHEN** a caller with the `ADMIN` role requests that same batch
- **THEN** the response is 200

#### Scenario: Local profile
- **WHEN** the service runs with the `local` profile and a request without a token asks for `GET /records/batches`
- **THEN** the response is 200, as for an admin

### Requirement: Callers can list their batches
`GET /records/batches` SHALL return the caller's batches, newest first, at most `limit` of them (default 50, at most
200). Admins SHALL see every caller's batches.

#### Scenario: Only the caller's batches
- **GIVEN** `user-1` sent two batches and `user-2` sent one
- **WHEN** `user-1` requests `GET /records/batches`
- **THEN** the response lists only `user-1`'s two batches, newest first

#### Scenario: Limit out of range
- **WHEN** a caller requests `GET /records/batches?limit=500`
- **THEN** the response is 400 as a problem detail

### Requirement: Records can't be changed through the API
`POST`, `PUT`, `PATCH` and `DELETE` on any `/records` path SHALL return 405 for every caller, admins included,
because records are created and changed only by the pipeline.

#### Scenario: Admin tries to delete
- **WHEN** an admin sends `DELETE /records/batches/{id}`
- **THEN** the response is 405 and the batch is unchanged
