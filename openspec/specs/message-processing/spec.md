# message-processing Specification

## Purpose
Defines how work passes from one pipeline step to the next through a Postgres outbox and inbox:
- a step publishes its next messages with its own data, in one transaction;
- a dispatcher moves each message from the outbox to the inbox once;
- workers claim due messages under a lease, process them, and retry or give up;
- wake-ups, and who can read the queues.

Message types are `SEPARATE_FILE`, `CLASSIFY_DOCUMENT`, `EXTRACT_DOCUMENT` and `DELIVER_WEBHOOK`.

## Requirements

### Requirement: Publishing joins the caller's transaction
Publishing a message SHALL be possible only inside a transaction, and SHALL be refused outside one. A published
message SHALL commit or roll back with the caller's data. `NOTIFY outbox_ready` SHALL reach other connections only
after that transaction commits, and never for a rollback.

#### Scenario: Outside a transaction
- **WHEN** a message is published with no transaction open
- **THEN** publishing is refused and no outbox row exists

#### Scenario: Caller rolls back
- **GIVEN** a transaction that publishes a `CLASSIFY_DOCUMENT` message
- **WHEN** the transaction rolls back
- **THEN** no outbox row exists for it and no `outbox_ready` notification was sent

#### Scenario: Caller commits
- **WHEN** a transaction that publishes a message commits
- **THEN** the outbox holds the message, undispatched, and a listener on `outbox_ready` is notified

### Requirement: Each outbox message reaches the inbox once
The dispatcher SHALL copy each undispatched outbox message into the inbox with the same id, type, aggregate and
payload, as `PENDING` and due now, and mark the outbox row dispatched, in one transaction. Dispatchers on several
instances SHALL never copy the same message twice, and a message whose id is already in the inbox SHALL be ignored.
After a dispatch commits, the workers SHALL be woken with `NOTIFY inbox_ready`.

#### Scenario: Two dispatchers at once
- **GIVEN** 100 undispatched outbox messages
- **WHEN** two dispatchers run in parallel
- **THEN** the inbox holds exactly 100 messages and every outbox message is dispatched

#### Scenario: Already in the inbox
- **GIVEN** an inbox message with id X
- **WHEN** an outbox message with id X is dispatched
- **THEN** the inbox still holds one message with id X, and the outbox message is dispatched

### Requirement: A due message is claimed by one worker at a time
A worker SHALL claim only messages of its type that are `PENDING` and due, or `PROCESSING` with an expired lease. A
claim SHALL set the message `PROCESSING`, record the claiming instance and a lease end, count one attempt, and commit
before any work starts. Workers on several threads or instances SHALL never hold the same message at once. Messages
SHALL be claimed in due order.

#### Scenario: Two workers, fifty messages
- **GIVEN** 50 due `CLASSIFY_DOCUMENT` messages
- **WHEN** two workers process them on separate threads
- **THEN** the step is given each of the 50 messages exactly once

#### Scenario: Not due yet
- **GIVEN** a `PENDING` message due in 30 seconds
- **WHEN** a worker claims
- **THEN** the message is not claimed

#### Scenario: Expired lease
- **GIVEN** a message left `PROCESSING` whose lease ended a minute ago, as when its instance crashed
- **WHEN** a worker claims
- **THEN** the message is claimed again under a new lease

### Requirement: A handled message is done
When the step handles a message without error, the message SHALL be `DONE` with a completion time. A step MAY mark it
done inside its own final transaction, so its result and the `DONE` status commit together; marking an already done
message done again SHALL change nothing.

#### Scenario: Step succeeds
- **WHEN** the step handles a claimed message without error
- **THEN** the message is `DONE` with a completion time, and is never claimed again

### Requirement: A failed message is retried with backoff
When the step fails on a message that has attempts left, the message SHALL go back to `PENDING` with its error
recorded and its next attempt at now plus `backoff-initial × 2^(attempts − 1)`, capped at `backoff-max`. The recorded
error SHALL be at most 2,000 characters.

#### Scenario: First failure
- **GIVEN** `backoff-initial` 10 s and a message on its first attempt
- **WHEN** the step fails with "timeout"
- **THEN** the message is `PENDING`, its error is "timeout", and it is due 10 s from now

#### Scenario: Third failure
- **WHEN** a message fails on its third attempt
- **THEN** it is due 40 s from now

#### Scenario: Backoff cap
- **GIVEN** `backoff-max` 10 min
- **WHEN** a message fails on an attempt whose backoff would be 160 min
- **THEN** it is due 10 min from now

### Requirement: An exhausted message fails and its step is told once
A message SHALL be exhausted when it fails on its last attempt: `inbox.max-attempts` for every type except
`DELIVER_WEBHOOK`, which uses `webhook.max-attempts`. An exhausted message SHALL become `FAILED` with its last error,
and its step SHALL be told once, in the same transaction, so it can fail its file or document. A message reclaimed
after an expired lease on its last attempt SHALL be exhausted without being handled again.

#### Scenario: Fifth failure
- **GIVEN** `inbox.max-attempts` 5
- **WHEN** a `CLASSIFY_DOCUMENT` message fails for the fifth time
- **THEN** it is `FAILED` and the step is told once, with the last error

#### Scenario: Webhook attempts
- **GIVEN** `webhook.max-attempts` 8
- **WHEN** a `DELIVER_WEBHOOK` message fails for the fifth time
- **THEN** it is scheduled for retry
- **AND** on its eighth failure it is `FAILED`

#### Scenario: Crashed on the last attempt
- **GIVEN** a message on its fifth attempt left `PROCESSING` with an expired lease
- **WHEN** a worker claims it
- **THEN** it is `FAILED` without being handled, and the step is told once

### Requirement: Each message type goes to its own step
Each message type SHALL be handled by exactly one step. Two steps for the same type SHALL stop the application from
starting. Messages of a type that no step handles yet SHALL stay `PENDING`, unclaimed.

#### Scenario: Two steps for one type
- **WHEN** the application starts with two handlers for `EXTRACT_DOCUMENT`
- **THEN** startup fails, naming the type

#### Scenario: No step yet
- **GIVEN** no handler for `DELIVER_WEBHOOK`
- **WHEN** a `DELIVER_WEBHOOK` message reaches the inbox
- **THEN** it stays `PENDING` with no attempts

### Requirement: Workers wake on a signal or a poll
Each message type's worker SHALL wait until it is signalled or `outbox.poll-interval` passes, whichever comes first,
then process its due messages. A message dispatched on the same instance SHALL signal its type's worker straight
away. The dispatcher SHALL wake the same way, on a publish or the poll interval. A worker SHALL handle up to
`inbox.concurrency` messages at once.

#### Scenario: Signal
- **WHEN** a type is signalled while its worker waits
- **THEN** the worker stops waiting at once

#### Scenario: No signal
- **WHEN** no signal comes
- **THEN** the worker stops waiting after the poll interval

### Requirement: Admins can read the queues
Admins SHALL be able to read:
- `GET /records/outbox-messages?limit=`, newest first;
- `GET /records/inbox-messages?type=&status=&limit=`, newest first, filtered by type and status when given;
- `GET /records/inbox-messages/{id}`.

`limit` SHALL be 1–200 and default to 50. Other callers SHALL get 403. POST, PUT, PATCH and DELETE SHALL give 405,
for admins too.

#### Scenario: Admin lists failed messages
- **WHEN** an admin reads `/records/inbox-messages?status=FAILED`
- **THEN** the response is 200 with only failed messages, newest first

#### Scenario: Not an admin
- **WHEN** a caller without the admin role reads `/records/inbox-messages`
- **THEN** the response is 403 as a problem detail

#### Scenario: Unknown message
- **WHEN** an admin reads `/records/inbox-messages/{id}` for an id that doesn't exist
- **THEN** the response is 404 as a problem detail

#### Scenario: Write attempt
- **WHEN** an admin sends `DELETE /records/inbox-messages/{id}`
- **THEN** the response is 405 and the message is unchanged

### Requirement: Notifications wake the loops on every instance
Each instance SHALL listen on `outbox_ready`, `inbox_ready` and `batch_completed` through one dedicated database
connection. An `outbox_ready` notification SHALL wake the dispatcher, and an `inbox_ready` notification SHALL wake
the worker for the message type it names; a payload that names no message type SHALL be ignored. If the connection
drops, the instance SHALL reconnect with backoff, and after each connect SHALL wake every loop once, because
notifications sent while disconnected are lost.

#### Scenario: Work published on another instance
- **GIVEN** instance A's dispatcher is waiting
- **WHEN** a transaction on instance B publishes a message and commits
- **THEN** instance A's dispatcher wakes without waiting for the poll

#### Scenario: Work dispatched for one type
- **WHEN** an `inbox_ready` notification names `EXTRACT_DOCUMENT`
- **THEN** the `EXTRACT_DOCUMENT` worker wakes, and no other type's worker does

#### Scenario: Unreadable payload
- **WHEN** an `inbox_ready` notification names `PRINT_FILE`
- **THEN** it is ignored and no worker wakes

#### Scenario: Connection drops
- **GIVEN** the listening connection is terminated by the database
- **WHEN** the database is reachable again
- **THEN** the instance reconnects, wakes every loop once, and receives later notifications
