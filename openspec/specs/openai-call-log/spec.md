# openai-call-log Specification

## Purpose
Defines the log of every call the service makes to OpenAI: what each row records, where it is kept, what it must
never hold, and who can read it. Cost and latency reporting per batch come from this log.

## Requirements

### Requirement: Every OpenAI call is logged
Each OpenAI call SHALL be logged with its batch, its source file, its document (none for separation), its purpose, the
model, the latency in milliseconds, the prompt and completion tokens (none if unknown), the HTTP status (none if no
response came), the error if it failed, and when it was logged. The purpose SHALL be one of separate, classify,
tiebreak, confirm-type, draft-schema, extract, consolidate or final.

#### Scenario: Successful classification call
- **WHEN** a classify call for a document takes 1,840 ms with 2,100 prompt and 60 completion tokens and status 200
- **THEN** the log holds a row with that batch, file, document, purpose, model, latency, tokens and status, and no
  error

#### Scenario: Separation call that timed out
- **WHEN** a separate call for a file gets no response and fails with "timeout"
- **THEN** the log holds a row with no document, no status, no tokens and the error "timeout"

#### Scenario: Negative latency
- **WHEN** a call is logged with latency -5
- **THEN** the row is refused

### Requirement: The call log holds no prompt or response bodies
The call log SHALL be kept in the `document_reader_integration` schema, apart from the main schema. It SHALL have no
column for the prompt, the response or any request or response body, because those carry borrower data.

#### Scenario: Where the log lives
- **WHEN** the database schema is inspected
- **THEN** the log is the table `document_reader_integration.openai_call`

#### Scenario: No body columns
- **WHEN** the log table's columns are listed
- **THEN** none is named `prompt`, `response` or `body`

### Requirement: Callers can read their batch's call log
The caller who sent a batch, and admins, SHALL be able to read `GET /records/batches/{id}/openai-calls`, which lists
the batch's calls oldest first as JSON. Another caller's batch SHALL give 404 as a problem detail. POST, PUT, PATCH and
DELETE on that path SHALL give 405, for admins too.

#### Scenario: Owner
- **GIVEN** `user-1` sent a batch whose processing made 12 OpenAI calls
- **WHEN** `user-1` reads `/records/batches/{id}/openai-calls`
- **THEN** the response is 200 with the 12 calls, oldest first

#### Scenario: Another caller
- **WHEN** `user-2` reads the same path
- **THEN** the response is 404 as a problem detail

#### Scenario: Write attempt
- **WHEN** a caller with the admin role sends `POST /records/batches/{id}/openai-calls`
- **THEN** the response is 405
