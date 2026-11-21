# Spec Delta

## ADDED Requirements

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
