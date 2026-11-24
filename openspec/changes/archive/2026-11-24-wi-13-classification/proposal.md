# Proposal

## Why

WI-12 queues one `CLASSIFY_DOCUMENT` message per separated document, but nothing handles them yet, so every batch
stops after separation. Classification is process 2 of the pipeline (§10 and §11 of
`docs/design/2026-11-20-document-reader-design.md`). It decides each document's type by voting over several model
runs, breaks ties with a narrower round, keeps near-duplicate type names together, and lets the model add a type
the catalog doesn't have, exactly once even under concurrency. Extraction (WI-14) then knows which schema to fill.
This is WI-13 of `docs/workitems/`.

## What Changes

- **A `CLASSIFY_DOCUMENT` handler.** It sets the document to `CLASSIFYING` and sends its first
  `classification.max-pages` (3) pages, the loan purpose and the catalog to `classification.runs` (5) classification
  calls, at most `classification.parallelism` (5) at a time and outside any transaction.
  - Each run is saved as it finishes, with the type its answer resolves to. A retry reuses the runs that succeeded.
- **Answers are resolved before voting.** "Paystub" and "Pay stubs" count as votes for the same type. A name with no
  usable key, such as "N/A", is a failed run and isn't counted (Review Focus #3). A name that resolves to no type
  counts as a vote for that new name.
- **Too few successful runs:** fewer than `classification.min-successful-runs` (a majority) fails the attempt, so the
  inbox retries it.
- **The vote:**
  - The candidate with the most votes wins.
  - On a tie, `classification.tiebreak-runs` (3) calls may choose only among the tied candidates.
  - If the tie-break also ties, the tied candidate with the highest average confidence across every run wins.
  - The result records how it was decided: `VOTE`, `TIEBREAK` or `CONFIDENCE`.
- **A winning name that isn't in the catalog:**
  1. One confirmation call asks whether it is an existing type under another name. If it is, the name becomes that
     type's alias.
  2. Otherwise one call drafts its data schema, and a `MODEL` type is created, recording the document that created it.
  3. Two documents creating the same new type at once end up with one type. The loser re-reads the winner's.
- **One transaction then:**
  - saves the classification result (type, votes, agreement = winning votes ÷ counted first-round runs, tie-break,
    how it was decided);
  - assigns the type, sets the document to `CLASSIFIED`;
  - queues one `EXTRACT_DOCUMENT` message;
  - marks the inbox message done.
- **Locking:** the document is locked first and must still be `CLASSIFYING`, the same guard WI-12's review added for
  files.
- **A document whose message is exhausted** becomes `FAILED` ("Classification failed"), and the batch completion check
  runs.
- **A message for a document already classified, past classification or failed** does nothing.
- **The result envelope** counts a run that voted for a name not yet in the catalog under the type that name resolves
  to now, or under the name itself. So a type the model created shows its votes.

## Capabilities

### New Capabilities

- `document-classification`: how a document's type is decided. This covers the runs, resolving answers, the vote,
  the tie-break and confidence rounds, too few runs, the result and the next message, exhausted documents and
  repeated messages.

### Modified Capabilities

- `document-type-catalog`: a new requirement, that a type name the model proposes is either confirmed as an alias of
  an existing type or added once as a `MODEL` type with a drafted schema, even when two documents propose it at once.
- `batch-results`: a new requirement, that votes for a name that wasn't in the catalog are counted under the type it
  resolves to now, or under the name. The existing votes requirement is unchanged.

## Impact

- **New code:**
  - `processors/TypeVoteProcessor`;
  - `service/DocumentTypeCatalogService` and its implementation;
  - `service/ClassificationService` and its implementation (also the `CLASSIFY_DOCUMENT` `InboxMessageHandler`);
  - `dto/event/ExtractDocumentPayload`.
- **Changed code:**
  - `DocumentTypeService` gains `createFromModel` (origin `MODEL`, in its own transaction);
  - `DocumentService` gains `lockForUpdate`;
  - `BatchQueryServiceImpl` counts votes for names that weren't in the catalog.
- **Tests:**
  - `processors/TypeVoteProcessorTests`, `service/DocumentTypeCatalogServiceTests`, `service/ClassificationServiceTests`;
  - new cases in `DocumentTypeServiceTests`, `DocumentServiceTests` and `BatchQueryServiceTests`.
- **Dependencies and schema:** none new. The run, result and catalog tables already exist.
- **Later work items:** WI-14 handles `EXTRACT_DOCUMENT`. Until then, a classified batch stays `PROCESSING`.
