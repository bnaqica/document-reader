# batch-completion-wait Specification

## Purpose
Defines how a caller waits for a batch to finish: by a Postgres notification from whichever instance completed it,
or by re-reading the batch's status, with a timeout; and how a completion is announced.

## Requirements

### Requirement: A wait ends when the batch reaches a final status
Waiting for a batch SHALL return true as soon as the batch has a final status (`COMPLETED`, `PARTIALLY_COMPLETED` or
`FAILED`), found when the wait starts, after a `batch_completed` notification, or by a re-read every
`wait.poll-interval`. A notification for a batch that isn't final SHALL NOT end the wait. A wait SHALL run outside any
transaction, and SHALL be refused inside one. Waiting for an unknown batch SHALL be refused as not found.

#### Scenario: Notified
- **GIVEN** a caller waits up to 10 s for batch B
- **WHEN** another connection sends `NOTIFY batch_completed` with B's id
- **THEN** the wait returns true in under 1 s

#### Scenario: Notification missed
- **GIVEN** no notification is delivered and `wait.poll-interval` is 2 s
- **WHEN** batch B is set to `COMPLETED` while a caller waits
- **THEN** the wait returns true within 3 s

#### Scenario: Already complete
- **GIVEN** batch B is already `FAILED`
- **WHEN** a caller waits for it
- **THEN** the wait returns true at once

#### Scenario: Notification before the status is final
- **GIVEN** batch B is still `PROCESSING`
- **WHEN** a `batch_completed` notification for B arrives while a caller waits up to 2 s
- **THEN** the wait returns false at its timeout

#### Scenario: Inside a transaction
- **WHEN** a caller waits for batch B inside an open transaction
- **THEN** the wait is refused

#### Scenario: Unknown batch
- **WHEN** a caller waits for a batch id that doesn't exist
- **THEN** the wait is refused as not found

### Requirement: A wait gives up at its timeout
A wait SHALL return false when its timeout passes while the batch is still not final. Giving up SHALL change nothing
about the batch or its processing.

#### Scenario: Timeout
- **GIVEN** batch B stays `PROCESSING`
- **WHEN** a caller waits up to 3 s
- **THEN** the wait returns false after about 3 s, and B is still `PROCESSING`

### Requirement: An interrupted wait leaves nothing behind
When the waiting thread is interrupted, the wait SHALL end at once and return false, keep the thread's interrupt, and
remove its registration. Processing SHALL carry on, and nothing SHALL be logged at error level. A caller that
disconnects without its thread being interrupted SHALL hold only its own wait, which ends at its timeout and changes
nothing.

#### Scenario: Waiting thread interrupted
- **GIVEN** a caller waits for batch B
- **WHEN** its thread is interrupted
- **THEN** the wait ends, no waiter is registered any more, B is unchanged, and no error is logged

### Requirement: A completion is announced on commit
Announcing that a batch completed SHALL be possible only inside the completing transaction, and SHALL be refused
outside one. The `batch_completed` notification SHALL reach waiters on every instance only when that transaction
commits, and never for a rollback.

#### Scenario: Outside a transaction
- **WHEN** a completion is announced with no transaction open
- **THEN** it is refused

#### Scenario: Completing transaction rolls back
- **GIVEN** a transaction that announces batch B's completion
- **WHEN** it rolls back
- **THEN** no `batch_completed` notification is delivered

#### Scenario: Completing transaction commits
- **WHEN** a transaction that announces batch B's completion commits
- **THEN** a listener on `batch_completed` receives B's id
