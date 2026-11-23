# batch-completion Specification

## Purpose

Defines when a batch is complete and what completing it does: the check that runs whenever a document finishes or a
file fails, the final status it chooses, the notice and webhook that follow, and how a batch is completed exactly once
when steps finish at the same time or are retried.

## Requirements

### Requirement: A batch completes when its last file and document finish
When a document finishes (completed or failed) or a file fails, the batch SHALL be checked. It SHALL complete only
when every one of its source files is `SEPARATED` or `FAILED` and every one of its documents is `COMPLETED` or
`FAILED`. Otherwise the check SHALL change nothing.

#### Scenario: Last document finishes
- **GIVEN** a batch with two separated files, whose documents are all completed except one
- **WHEN** that last document completes
- **THEN** the batch is completed

#### Scenario: A file is still being separated
- **GIVEN** a batch whose file 0 is separated with every document completed, and whose file 1 is `SEPARATING`
- **WHEN** file 0's last document completes
- **THEN** the batch stays `PROCESSING`

#### Scenario: Not the last document
- **GIVEN** a batch with two documents still being extracted
- **WHEN** one of them completes
- **THEN** the batch stays `PROCESSING`, with no notice and no webhook

#### Scenario: Last file fails
- **GIVEN** a batch whose file 0 is separated with every document completed, and whose file 1 is `SEPARATING`
- **WHEN** file 1 fails
- **THEN** the batch is completed

### Requirement: The final status reflects what finished
A completed batch SHALL be `COMPLETED` when every file was separated and every document completed, `FAILED` with the
reason "No document completed" when no document completed, and `PARTIALLY_COMPLETED` otherwise. The completion time
SHALL be recorded.

#### Scenario: Everything completed
- **WHEN** a batch completes with every file separated and every document completed
- **THEN** it is `COMPLETED` with a completion time

#### Scenario: A failed file beside completed documents
- **GIVEN** file 0 separated with its documents completed
- **WHEN** file 1 fails
- **THEN** the batch is `PARTIALLY_COMPLETED`

#### Scenario: A failed document beside completed ones
- **WHEN** a batch completes with every file separated, one document failed and the others completed
- **THEN** the batch is `PARTIALLY_COMPLETED`

#### Scenario: Nothing completed
- **WHEN** a batch completes with every document failed
- **THEN** it is `FAILED` with the reason "No document completed"

#### Scenario: Every file failed
- **WHEN** a batch's only files all fail before any document is found
- **THEN** it is `FAILED` with the reason "No document completed"

### Requirement: A completed batch is announced, and an async one gets its webhook
Completing a batch SHALL send the `batch_completed` notice when the transaction commits. An async batch SHALL also
get exactly one `DELIVER_WEBHOOK` message for the batch, and its webhook status SHALL become `PENDING`. A sync batch
SHALL get no webhook message, and its webhook status SHALL stay as it was.

#### Scenario: Async batch
- **WHEN** an async batch completes
- **THEN** one `DELIVER_WEBHOOK` message is written for the batch, its webhook status is `PENDING`, and the
  `batch_completed` notice is sent on commit

#### Scenario: Sync batch
- **WHEN** a sync batch completes
- **THEN** the `batch_completed` notice is sent on commit, no `DELIVER_WEBHOOK` message is written, and its webhook
  status is unchanged

### Requirement: A batch completes exactly once
The check SHALL lock the batch before reading its files and documents, so only one transaction can complete it.
When two steps finish a batch's last documents at the same time, the batch SHALL complete once, with one notice and
one webhook message. A check on a batch that is already complete SHALL change nothing and send nothing.

#### Scenario: Last two documents finish together
- **GIVEN** an async batch whose last two documents, from different files, are being finished in two transactions at
  the same time
- **WHEN** both transactions commit
- **THEN** the batch is completed once, with one `batch_completed` notice and one `DELIVER_WEBHOOK` message

#### Scenario: Retried step
- **GIVEN** a batch that is already `COMPLETED`
- **WHEN** the check runs again for one of its documents
- **THEN** nothing changes, and no notice or webhook message is sent

### Requirement: A whole batch can be failed
The pipeline SHALL be able to fail a whole batch with a reason, whatever its files and documents are doing. The
batch SHALL become `FAILED` with that reason and a completion time, and SHALL be announced and get its webhook as
any completed batch does. Failing a batch that is already complete SHALL change nothing and send nothing.

#### Scenario: Storage failure
- **GIVEN** an async batch that is `RECEIVED`
- **WHEN** it is failed with the reason "File could not be stored"
- **THEN** it is `FAILED` with that reason, the `batch_completed` notice is sent on commit, and one
  `DELIVER_WEBHOOK` message is written

#### Scenario: Already complete
- **GIVEN** a batch that is already `COMPLETED`
- **WHEN** it is failed
- **THEN** it stays `COMPLETED`, and no notice or webhook message is sent

### Requirement: The check runs only inside the caller's transaction
The check and failing a batch SHALL run inside the calling step's transaction, so the completion, the notice and the
webhook message commit or roll back with the step's own changes. Called outside a transaction, they SHALL be refused
and change nothing.

#### Scenario: No transaction
- **WHEN** the check is run outside any transaction
- **THEN** it is refused and the batch is unchanged

#### Scenario: Step rolls back
- **GIVEN** a step whose transaction completes the batch
- **WHEN** that transaction rolls back
- **THEN** the batch is not complete, no notice is sent and no webhook message exists
