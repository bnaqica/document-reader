# Spec Delta

## Purpose

Defines the stored record of every model run the pipeline makes and the results it decides from them:
- separation runs for each source file;
- classification runs and the classification result for each document;
- extraction runs and the extraction result for each document;
- each attempt to deliver a batch's webhook.

It covers what each record holds, the rules it must satisfy, and who can read it.

## ADDED Requirements

### Requirement: A separation run records one answer for a file
A separation run SHALL record its source file, its run number, the first and last page of the window it covered,
whether it succeeded, its boundaries (each a start page with a tentative label) and when it was saved. The window
SHALL start no later than it ends and stay within the file's pages. A failed run SHALL have no boundaries.

#### Scenario: Successful run
- **GIVEN** a 6-page file
- **WHEN** the pipeline saves run 1 over pages 1–6 with boundaries at page 1 "Paystub" and page 3 "W-2"
- **THEN** reading the file's runs returns that run with both boundaries, in that order, and a save time

#### Scenario: Failed run
- **WHEN** the pipeline saves a failed run 2 over pages 1–6
- **THEN** the run is stored as not succeeded, with no boundaries

#### Scenario: Window outside the file
- **GIVEN** a 6-page file
- **WHEN** the pipeline saves a run over pages 5–7, or over pages 4–3
- **THEN** the save is refused and no run is stored

### Requirement: Runs are numbered once
Within a file and window, a separation run number SHALL be used once. Within a document and round, a classification
run number SHALL be used once. Within a document and stage, an extraction pass number SHALL be used once. Run and pass
numbers SHALL start at 1. Saving a duplicate or a number below 1 SHALL be refused and change nothing.

#### Scenario: Duplicate classification run
- **GIVEN** a document has initial classification run 3
- **WHEN** the pipeline saves another initial run 3 for it
- **THEN** the save is refused and the document still has one run 3

#### Scenario: Same number in another round
- **GIVEN** a document has initial classification run 1
- **WHEN** the pipeline saves tie-break run 1 for it
- **THEN** both runs are stored

### Requirement: A file's separation runs list in window then run order
A file's separation runs SHALL list ordered by window start, then by run number.

#### Scenario: Two windows
- **GIVEN** a 20-page file with runs 1–3 over pages 1–15 and runs 1–3 over pages 14–20, saved in mixed order
- **WHEN** the file's runs are read
- **THEN** the three runs over pages 1–15 come first, each window in run order

### Requirement: A classification run records one answer for a document
A classification run SHALL record its document, its round (`INITIAL` or `TIEBREAK`), its run number, the type name the
model gave, the catalog type it resolved to (none if it failed or didn't resolve), its confidence, its reasoning,
whether it succeeded and when it was saved. Confidence SHALL be between 0 and 1.

#### Scenario: Resolved run
- **WHEN** the pipeline saves initial run 1 answering "Pay stub" with confidence 0.92, resolved to "Pay stubs"
- **THEN** reading the document's runs returns that answer, its resolved type, confidence and reasoning

#### Scenario: Failed run
- **WHEN** the pipeline saves a failed initial run 2 with no answer
- **THEN** the run is stored as not succeeded, with no resolved type

#### Scenario: Confidence out of range
- **WHEN** the pipeline saves a run with confidence 1.2
- **THEN** the save is refused and no run is stored

### Requirement: A document's classification runs list in round then run order
A document's classification runs SHALL list with every `INITIAL` run before every `TIEBREAK` run, each round in run
order.

#### Scenario: Tie-break after the initial round
- **GIVEN** a document with initial runs 1–5 and tie-break runs 1–3, saved in mixed order
- **WHEN** the document's runs are read
- **THEN** initial runs 1–5 come first, then tie-break runs 1–3

### Requirement: A document has at most one classification result
A classification result SHALL record its document, the winning type, the vote count per type, the agreement (between
0 and 1), whether a tie-break happened, how it was decided (`VOTE`, `TIEBREAK` or `CONFIDENCE`) and when. A document
SHALL have at most one; saving a second SHALL be refused and keep the first. Reading a document with none SHALL find
nothing.

#### Scenario: Decided by vote
- **WHEN** the pipeline saves a result of "Pay stubs" with votes "Pay stubs" 4 and "W-2" 1, agreement 0.8, decided by
  `VOTE`
- **THEN** reading the document's result returns that type, those votes, agreement 0.8 and no tie-break

#### Scenario: Second result
- **GIVEN** a document already has a classification result of "Pay stubs"
- **WHEN** the pipeline saves a result of "W-2" for it
- **THEN** the save is refused and the result stays "Pay stubs"

#### Scenario: Not classified yet
- **WHEN** the result of a document that has none is read
- **THEN** nothing is found

### Requirement: An extraction run records one stage's answer
An extraction run SHALL record its document, its stage (`INITIAL`, `CONSOLIDATED` or `FINAL`), its pass number, its
data, its summary and when it was saved. The data SHALL read back with its keys in the order they were saved. Reading a
document's runs for one stage SHALL return only that stage, in pass order.

#### Scenario: Initial passes
- **GIVEN** a document with initial passes 1–3 and a consolidated pass 1
- **WHEN** the document's `INITIAL` runs are read
- **THEN** passes 1, 2 and 3 are returned in that order, and not the consolidated pass

#### Scenario: Key order kept
- **WHEN** the pipeline saves a run whose data has keys `employerName`, `payDate`, `grossPay` in that order
- **THEN** reading it returns the keys in that order

### Requirement: A document has at most one extraction result
An extraction result SHALL record its document, the document type, the type's schema version, the final data (keys in
saved order) and the summary. A document SHALL have at most one; saving a second SHALL be refused and keep the first.
Reading a document with none SHALL find nothing.

#### Scenario: Final result
- **WHEN** the pipeline saves a result for a "Pay stubs" document at schema version 1
- **THEN** reading the document's result returns that type, version 1, the data and the summary

#### Scenario: Not extracted yet
- **WHEN** the result of a document that has none is read
- **THEN** nothing is found

### Requirement: Every webhook attempt is recorded
A webhook attempt SHALL record its batch, its attempt number (from 1), the HTTP status received (none if no response
came), the error if it failed, and when it was made. An attempt number SHALL be used once per batch. A batch's attempts
SHALL list in attempt order.

#### Scenario: Retry after a failure
- **WHEN** attempt 1 is recorded with no status and error "timeout", then attempt 2 with status 200
- **THEN** the batch's attempts list attempt 1 then attempt 2, with those statuses and errors

#### Scenario: Duplicate attempt
- **GIVEN** a batch has attempt 1
- **WHEN** attempt 1 is recorded again
- **THEN** the save is refused and the batch still has one attempt 1

### Requirement: Callers can read their own runs and results
The caller who sent a batch, and admins, SHALL be able to read these, each as JSON:
- `GET /records/source-files/{id}/separation-runs`;
- `GET /records/documents/{id}/classification-runs`, `/classification-result`, `/extraction-runs` (with an optional
  `stage`) and `/extraction-result`;
- `GET /records/batches/{id}/webhook-attempts`.

Another caller's file, document or batch, and a missing result, SHALL give 404 as a problem detail.

#### Scenario: Owner reads a document's runs
- **GIVEN** `user-1` sent a batch whose document has five classification runs
- **WHEN** `user-1` reads `/records/documents/{id}/classification-runs`
- **THEN** the response is 200 with the five runs

#### Scenario: Another caller
- **WHEN** `user-2` reads the same path
- **THEN** the response is 404 as a problem detail

#### Scenario: Admin
- **WHEN** a caller with the admin role reads `/records/batches/{id}/webhook-attempts` for `user-1`'s batch
- **THEN** the response is 200

#### Scenario: No result yet
- **GIVEN** `user-1`'s document has no extraction result
- **WHEN** `user-1` reads `/records/documents/{id}/extraction-result`
- **THEN** the response is 404 as a problem detail

#### Scenario: Unknown stage
- **WHEN** a caller reads `/records/documents/{id}/extraction-runs?stage=DRAFT`
- **THEN** the response is 400 as a problem detail

### Requirement: Runs and results can't be changed through the API
Only the pipeline SHALL create runs, results and webhook attempts. POST, PUT, PATCH and DELETE on the run and result
paths SHALL give 405, for admins too, and change nothing.

#### Scenario: Admin tries to delete a result
- **WHEN** a caller with the admin role sends `DELETE /records/documents/{id}/classification-result`
- **THEN** the response is 405 and the result is unchanged
