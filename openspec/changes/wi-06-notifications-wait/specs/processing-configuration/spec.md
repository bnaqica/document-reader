# Spec Delta

## MODIFIED Requirements

### Requirement: Worker settings have defaults
`app.document-reader.inbox.concurrency` SHALL default to 4 and be positive. `app.document-reader.workers.enabled`
SHALL default to true; when false, no dispatcher, worker or notification listener runs, so nothing is dispatched or
claimed in the background, and a batch wait relies on polling alone.

#### Scenario: Defaults
- **WHEN** the application starts with no worker settings
- **THEN** worker concurrency is 4 and the workers run

#### Scenario: Zero concurrency
- **WHEN** `app.document-reader.inbox.concurrency` is 0
- **THEN** the application fails to start, naming the setting

#### Scenario: Workers off
- **GIVEN** `app.document-reader.workers.enabled` is false
- **WHEN** a message is published and committed
- **THEN** it stays undispatched in the outbox

#### Scenario: Listener off
- **GIVEN** `app.document-reader.workers.enabled` is false
- **WHEN** the application starts
- **THEN** no listening connection is opened
