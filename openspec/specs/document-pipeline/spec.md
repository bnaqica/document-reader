# document-pipeline Specification

## Purpose

Defines what the whole pipeline guarantees a batch, beyond each step on its own: the result and webhook a caller gets,
correct processing when several instances share one database, recovery when an instance dies mid-step, and an
on-demand measurement of accuracy and cost against the real model.

## Requirements

### Requirement: A sync batch returns one payload per document
A sync batch whose processing finishes within `wait.timeout` SHALL be answered with 200 and its result. The result
SHALL list one document per separated document, in file order and then page order, each with its type,
schema-valid `data` and a summary. Each file SHALL show how many documents it produced.

#### Scenario: Packet and licence
- **GIVEN** a `CAR_REFINANCE` batch of a six-page packet (a two-page pay stub, a W-2 and a three-page bank statement)
  and a one-page driver's licence photo
- **WHEN** it is sent with `async` false
- **THEN** the answer is 200 with status `COMPLETED` and four documents: Pay stubs pages 1–2, W-2 forms page 3 and Bank
  statements pages 4–6 of file 0, then Driver's license or government photo ID page 1 of file 1
- **AND** each document's data validates against its type's schema and its summary isn't empty
- **AND** file 0 shows 3 documents and file 1 shows 1

### Requirement: An async batch gets exactly one webhook with every payload
An async batch SHALL be answered with 202, and its webhook URL SHALL receive exactly one delivery once the batch
completes, carrying the same result as `GET /batches/{id}`.

#### Scenario: Packet and licence, async
- **WHEN** the same batch is sent with `async` true and a webhook URL
- **THEN** the receiver gets one POST, whose body has status `COMPLETED` and all four documents

### Requirement: Instances share the work without duplicating it
Several instances on one database SHALL together process every batch exactly once. Each message SHALL be handled to
completion by one instance, no run or result SHALL be written twice, and every batch SHALL complete once.

#### Scenario: Two instances, ten batches
- **GIVEN** two application instances running workers against one database
- **WHEN** ten batches are sent
- **THEN** all ten complete, each separation, classification and extraction run exists once, and each batch has one
  result per document

### Requirement: A crashed instance's work is finished by another
When an instance stops in the middle of a step, its claimed message SHALL become claimable again once its lease
expires, and another instance SHALL finish the step and the batch.

#### Scenario: Instance dies during extraction
- **GIVEN** an instance that stops while extracting a document, leaving its message claimed
- **WHEN** another instance runs and the lease expires
- **THEN** the other instance extracts the document, and the batch completes

### Requirement: Accuracy and cost are measured on demand against the real model
A golden suite SHALL run the pipeline against the real model only when explicitly requested and an API key is
present, never in the default build. It SHALL use only synthetic or sample documents, and SHALL report per-type
classification accuracy, separation accuracy and token totals per batch.

#### Scenario: Default build
- **WHEN** `./gradlew build` runs
- **THEN** the golden suite doesn't run

#### Scenario: Requested with a key
- **WHEN** `./gradlew goldenTest` runs with `OPENAI_API_KEY` set
- **THEN** the synthetic fixtures are processed by the real model, and the accuracy figures and token totals are
  printed
