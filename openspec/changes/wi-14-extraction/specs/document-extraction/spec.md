# Spec Delta

## Purpose

Defines how a classified document's data and summary are extracted: first passes over its pages, a consolidated draft,
a final determination checked against the type's data schema, and then the document's completion and the batch
completion check, along with truncation, retries, repeated messages and exhausted documents.

## ADDED Requirements

### Requirement: A document gets several first passes, saved as they finish
When extraction starts on a classified document, it SHALL become `EXTRACTING`. It SHALL get `extraction.passes`
extraction calls, outside any database transaction, each with the document's pages, its type's data schema and the
loan purpose. Each SHALL be saved as an `INITIAL` run, and a retry SHALL make only the passes still missing.

#### Scenario: Three passes
- **WHEN** a classified document is extracted with 3 passes
- **THEN** three `INITIAL` runs are saved, each from a call sent the document's pages and its type's schema

#### Scenario: Retry reuses passes
- **GIVEN** a document with two saved `INITIAL` runs
- **WHEN** extraction runs again
- **THEN** one more extraction call is made

### Requirement: A long document is truncated
A document longer than `extraction.max-pages` SHALL be sent only its first `extraction.max-pages` pages in every call
that sends pages, and SHALL be recorded as `truncated`.

#### Scenario: 25 pages
- **WHEN** a 25-page document is extracted with `max-pages` 20
- **THEN** every call that sends pages is sent its first 20 pages, and the document is `truncated`

#### Scenario: Within the limit
- **WHEN** a 3-page document is extracted
- **THEN** every call that sends pages is sent all 3, and the document isn't `truncated`

### Requirement: The passes are consolidated into a draft
After the first passes, one text-only call SHALL receive every pass's data and summary, with the schema, and return a
merged draft and the fields where the passes disagreed. The draft SHALL be saved as a `CONSOLIDATED` run.

#### Scenario: Draft saved
- **WHEN** three passes have been saved
- **THEN** one consolidation call receives all three, and one `CONSOLIDATED` run is saved after them

### Requirement: A final determination settles the draft
One call SHALL receive the pages, the schema and the consolidated draft, with its disagreements, and return the final
data and summary. Final data that doesn't validate against the type's schema SHALL be retried once with the
validation errors. A second invalid answer SHALL fail the attempt, so the message is retried. The valid answer SHALL
be saved as a `FINAL` run.

#### Scenario: Valid the first time
- **WHEN** the final call returns data that matches the schema
- **THEN** one `FINAL` run is saved, and the runs are 3 `INITIAL`, 1 `CONSOLIDATED` and 1 `FINAL`, in that order

#### Scenario: Invalid once
- **WHEN** the first final answer misses a required field and the second matches the schema
- **THEN** the second final call receives the validation errors, and the second answer is the one saved

#### Scenario: Invalid twice
- **WHEN** both final answers fail validation
- **THEN** the attempt fails, no result is saved, and the document stays `EXTRACTING`

### Requirement: Extracting a document completes it and checks its batch
When the final data is valid, one transaction SHALL save the result (type, schema version, data and summary), set the
document to `COMPLETED`, run the batch completion check and mark the message done. The extracted values, summary and
validation messages SHALL never be logged.

#### Scenario: Document completed
- **WHEN** a document's final data is valid
- **THEN** it has a result with that data and summary and is `COMPLETED`
- **AND** the batch completion check runs once for it, inside the transaction that saved the result

### Requirement: A document is extracted at most once
Extraction of a document that is `COMPLETED` or `FAILED`, or that has a result, SHALL change nothing and make no call.
A document that leaves `EXTRACTING` while it is being extracted SHALL keep its status, and the late attempt SHALL save
no result.

#### Scenario: Message delivered again
- **GIVEN** a completed document
- **WHEN** its message is handled again
- **THEN** no call is made, and no result is added

#### Scenario: Failed while being extracted
- **GIVEN** an attempt still calling the model after another attempt exhausted the message
- **WHEN** the late attempt finishes
- **THEN** the document stays `FAILED`, with no result

### Requirement: A document that can't be extracted fails without stopping its batch
When a document's extraction message is exhausted, the document SHALL become `FAILED` with the reason
"Extraction failed", unless it is already `COMPLETED` or `FAILED`, and the batch completion check SHALL run in the
same transaction.

#### Scenario: Exhausted message
- **WHEN** a document's extraction message fails for the last time
- **THEN** the document is `FAILED` with "Extraction failed", and the batch completion check runs for it
