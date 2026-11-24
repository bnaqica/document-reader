# Spec Delta

## Purpose

Defines how a separated document's type is decided: several classification runs over its first pages, answers
resolved to the catalog before voting, a tie-break round among tied candidates and a confidence fallback, a type the
model names added through the catalog, and what happens on success, on retry, and when the document fails for good.

## ADDED Requirements

### Requirement: A document gets several classification runs, saved as they finish
When classification starts, the document SHALL become `CLASSIFYING`. It SHALL get `classification.runs`
classification calls, at most `classification.parallelism` at once and outside any database transaction, each with
the document's first `classification.max-pages` pages, the loan purpose and the catalog. Each run SHALL be saved as
it finishes, and a retry SHALL make only the calls still needed.

#### Scenario: Runs saved
- **WHEN** a 2-page document is classified with 5 runs
- **THEN** five first-round runs are saved, each sent pages 1 and 2 of the document

#### Scenario: Long document
- **WHEN** a document covering file pages 4 to 10 is classified with `max-pages` 3
- **THEN** each call is sent file pages 4, 5 and 6

#### Scenario: Retry reuses successful runs
- **GIVEN** a document with three successful saved first-round runs out of 5
- **WHEN** classification runs again
- **THEN** two more first-round calls are made

### Requirement: Answers are resolved before they are counted
Each answer SHALL be resolved to the catalog by its name's key, so names reducing to the same key count for the same
type. Each run SHALL be saved with the type it resolved to, and SHALL keep that type on a retry. An answer whose name
has no usable key, or is longer than 200 characters, SHALL be saved as a failed run and not counted. An answer whose key matches no type SHALL count as a vote for that new name.

#### Scenario: Variant names
- **WHEN** the runs answer "Paystub" twice, "Pay stubs" twice and "W-2 forms" once
- **THEN** Pay stubs has 4 votes and W-2 forms 1

#### Scenario: A name with no key
- **WHEN** a run answers "N/A"
- **THEN** that run is saved as failed and has no vote

#### Scenario: A sentence instead of a name
- **WHEN** a run answers with a 300-character sentence
- **THEN** that run is saved as failed and has no vote, and no confirmation call is made for it

### Requirement: Too few successful runs is retried
When fewer than `classification.min-successful-runs` first-round runs succeed, the attempt SHALL fail so the message
is retried, and nothing beyond the saved runs SHALL be written.

#### Scenario: Two of five succeed
- **WHEN** three of five calls fail and the minimum is 3
- **THEN** the attempt fails, and the document stays `CLASSIFYING` with no result

### Requirement: The candidate with the most votes wins
When one candidate has more first-round votes than any other, it SHALL win, decided by `VOTE`.

#### Scenario: Clear plurality
- **WHEN** the votes are Pay stubs 4 and W-2 forms 1
- **THEN** Pay stubs wins by `VOTE`

### Requirement: A tie is broken by a round among the tied candidates
When the top first-round count is shared, `classification.tiebreak-runs` more calls SHALL be made, each offered only
the tied candidates, and saved as `TIEBREAK` runs. An answer outside the tied candidates SHALL be a failed run. The
candidate with the most tie-break votes SHALL win, decided by `TIEBREAK`.

#### Scenario: Two-way tie
- **GIVEN** first-round votes of Pay stubs 2, W-2 forms 2 and Bank statements 1
- **WHEN** the tie-break runs vote W-2 forms twice and Pay stubs once
- **THEN** exactly three tie-break calls were offered only Pay stubs and W-2 forms, and W-2 forms wins by `TIEBREAK`

### Requirement: A tie that remains is decided by confidence
When the tie-break round also ties, or none of its runs succeeds, the tied candidate whose runs have the highest
average confidence across both rounds SHALL win, decided by `CONFIDENCE`. Candidates with equal averages SHALL be
ordered by name key, so the outcome is repeatable.

#### Scenario: Three-way tie
- **GIVEN** three candidates tied in the first round
- **WHEN** the tie-break runs vote once for each
- **THEN** the candidate with the highest average confidence wins by `CONFIDENCE`

### Requirement: Classifying a document records its result and queues extraction
When the winner is known, and is a type in the catalog, one transaction SHALL save the result, assign the type to
the document, set it to `CLASSIFIED`, queue one `EXTRACT_DOCUMENT` message and mark the message done. The result SHALL
hold the first-round votes per type, the agreement (the winner's first-round votes ÷ the first-round runs counted),
whether a tie-break ran, and how it was decided.

#### Scenario: Four of five
- **WHEN** a document's runs vote Pay stubs 4 and W-2 forms 1
- **THEN** its result has Pay stubs, agreement 0.8, no tie-break and `VOTE`
- **AND** the document is `CLASSIFIED` with type Pay stubs, and one `EXTRACT_DOCUMENT` message exists for it

#### Scenario: The winning name is an existing type's other name
- **GIVEN** runs voting "Earnings statement" 3 and Pay stubs 2, where "Earnings statement" isn't in the catalog
- **WHEN** the model confirms it is Pay stubs
- **THEN** the result is Pay stubs with 5 votes and agreement 1.0

### Requirement: A document is classified at most once
Classification of a document that is already `CLASSIFIED`, `EXTRACTING`, `COMPLETED` or `FAILED`, or that has a
result, SHALL change nothing and make no call. A document that leaves `CLASSIFYING` while it is being classified SHALL
keep its status, and the late attempt SHALL save no result and queue nothing.

#### Scenario: Message delivered again
- **GIVEN** a classified document
- **WHEN** its message is handled again
- **THEN** no call is made, and no result or message is added

#### Scenario: Failed while being classified
- **GIVEN** an attempt still calling the model after another attempt exhausted the message
- **WHEN** the late attempt finishes its vote
- **THEN** the document stays `FAILED`, with no result and no `EXTRACT_DOCUMENT` message

### Requirement: A document that can't be classified fails without stopping its batch
When a document's classification message is exhausted, the document SHALL become `FAILED` with the reason
"Classification failed", unless it is already past classification, and the batch completion check SHALL run in the
same transaction.

#### Scenario: Exhausted message
- **WHEN** a document's classification message fails for the last time
- **THEN** the document is `FAILED` with "Classification failed", and the batch completion check runs for it
