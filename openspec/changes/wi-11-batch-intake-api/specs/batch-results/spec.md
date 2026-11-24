# Spec Delta

## Purpose

Defines the batch result envelope (§7.3) that `GET /batches/{id}` and wait mode return: the batch's status, a short
status for each uploaded file, and one payload per document. It also defines who may read a batch's result.

## ADDED Requirements

### Requirement: The result describes the batch
`GET /batches/{id}` SHALL return the batch's result at any stage, with `batchId`, `status` (`RECEIVED`, `PROCESSING`,
`COMPLETED`, `PARTIALLY_COMPLETED` or `FAILED`), `loanPurpose`, `receivedAt`, `completedAt` (null until final) and
`error` (the failure reason, null unless `FAILED`).

#### Scenario: Processing batch
- **GIVEN** a batch that is `PROCESSING`
- **WHEN** its owner reads its result
- **THEN** the result has status `PROCESSING`, its loan purpose and received time, and null `completedAt` and `error`

#### Scenario: Failed batch
- **GIVEN** a batch completed as `FAILED` with the reason "No document completed"
- **WHEN** its owner reads its result
- **THEN** the result has status `FAILED`, its completion time, and `error` "No document completed"

### Requirement: The result lists every uploaded file
The result's `files` SHALL hold one entry per uploaded file, in upload order, each with `id`, `index`, `fileName`,
`pageCount`, `status`, `documentCount` (its documents so far) and `error` (its failure reason, or null). A file that
produced no documents SHALL still be listed.

#### Scenario: A failed file stays visible
- **GIVEN** a batch whose file 0 has 3 documents and whose file 1 failed with a reason
- **WHEN** its owner reads its result
- **THEN** `files` lists file 0 with `documentCount` 3, then file 1 with status `FAILED`, `documentCount` 0 and its
  reason as `error`

### Requirement: Documents are listed in file order, then page order
The result's `documents` SHALL list each document once, ordered by its file's index and then by its first page. Each
SHALL have `id`, `sourceFileId`, `pages` (`start` and `end`), `status`, `truncated` and `error`.

#### Scenario: Two files with several documents
- **GIVEN** file 0 with documents on pages 1–2 and 3–5, and file 1 with one document on page 1
- **WHEN** its owner reads the result, whatever order the documents were created in
- **THEN** `documents` lists file 0 pages 1–2, file 0 pages 3–5, then file 1 page 1

### Requirement: A document shows its type and votes once classified
A classified document SHALL show `documentType` with its `name` and `newType`, which is true for a type the model
created during classification. It SHALL also show `classification`, with `runs` (the successful first-round runs),
`votes` (count per type name), `agreement`, and `tieBreak`. `tieBreak` SHALL be null unless the vote was tied; then it
holds the tie-break `runs`, `votes` and `decidedBy`. Before classification, both SHALL be null.

#### Scenario: Not yet classified
- **WHEN** a document is still `PENDING`
- **THEN** its `documentType` and `classification` are null

#### Scenario: Classified by a clear vote
- **GIVEN** a document whose five classification runs voted 4 for "Pay stubs" and 1 for "W-2 forms"
- **WHEN** its owner reads the result
- **THEN** its `documentType` is "Pay stubs" with `newType` false
- **AND** its `classification` has `runs` 5, `votes` {"Pay stubs": 4, "W-2 forms": 1}, `agreement` 0.8 and a null
  `tieBreak`

#### Scenario: Classified by a tie-break
- **GIVEN** a document whose first-round vote tied, and whose three tie-break runs voted 2 to 1
- **WHEN** its owner reads the result
- **THEN** its `tieBreak` has `runs` 3, those votes and `decidedBy` `TIEBREAK`

#### Scenario: A type the model created
- **WHEN** a document is classified as a type the model created
- **THEN** its `documentType` has `newType` true

### Requirement: A document shows its summary and data once extracted
An extracted document SHALL show its `summary` and its `data`, as the extraction result recorded them. Before
extraction, both SHALL be null.

#### Scenario: Extracted document
- **GIVEN** a completed document whose extraction recorded a summary and data
- **WHEN** its owner reads the result
- **THEN** the document shows that summary and that data

#### Scenario: Not yet extracted
- **WHEN** a document is `CLASSIFIED`
- **THEN** its `summary` and `data` are null

### Requirement: Only the batch's owner or an admin reads its result
A caller SHALL read the result only of a batch whose recorded requester is their token subject. A caller with the
admin role SHALL read every batch. Any other batch, and an unknown id, SHALL get the same 404, so the answer doesn't
reveal that the batch exists.

#### Scenario: Another caller's batch
- **GIVEN** a batch sent by caller A
- **WHEN** caller B, who isn't an admin, reads its result
- **THEN** the answer is 404, the same as for an id that doesn't exist

#### Scenario: Admin
- **GIVEN** a batch sent by caller A
- **WHEN** a caller with the admin role reads its result
- **THEN** the result is returned
