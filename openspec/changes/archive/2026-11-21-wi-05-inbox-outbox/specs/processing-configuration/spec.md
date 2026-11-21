# Spec Delta

## ADDED Requirements

### Requirement: Worker settings have defaults
`app.document-reader.inbox.concurrency` SHALL default to 4 and be positive. `app.document-reader.workers.enabled`
SHALL default to true; when false, no dispatcher or worker loop runs, so nothing is dispatched or claimed in the
background.

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
