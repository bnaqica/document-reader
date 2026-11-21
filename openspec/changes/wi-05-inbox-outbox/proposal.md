# Proposal

## Why

The pipeline runs asynchronously in four steps: separate a file, classify a document, extract it, deliver the webhook.
Each step must hand work to the next exactly when its own data commits, survive restarts and crashes, retry with
backoff, and give up cleanly so one bad document never leaves a batch hanging. The design chose its own Postgres
inbox and outbox for this (approach A, §4 and §13 of `docs/design/2026-11-20-document-reader-design.md`). This is
WI-05 of `docs/workitems/`. It needs only WI-01, and WI-06 and every pipeline step (WI-10 to WI-15) build on it.

## What Changes

- **Tables:** `outbox_message` and `inbox_message` in the main schema (V8), as in §13.
- **Publishing:** a step writes its next messages to the outbox inside its own transaction. They disappear if it rolls
  back, and Postgres sends `NOTIFY outbox_ready` only when it commits.
- **Dispatch:** a dispatcher on every instance moves undispatched outbox rows into the inbox once, ignores a message
  it has already moved, and wakes the workers with `NOTIFY inbox_ready`.
- **Claiming and processing:** a worker loop per message type claims due messages with `FOR UPDATE SKIP LOCKED` under
  a lease, hands each to the step that handles its type, and marks it done, schedules a retry with exponential backoff,
  or fails it and tells the step so it can fail its file or document.
- **Wake-ups:** an in-memory signal per message type wakes a worker straight away on the same instance. WI-06 adds the
  `LISTEN` side, which wakes workers on other instances; until then they poll.
- **Read endpoints:** admin-only, read-only endpoints for the two tables, as the repo rule requires a controller per
  table.
- **Configuration:** two new settings, the number of messages a worker handles at once and a switch that turns the
  background workers off (tests use it).

## Capabilities

### New Capabilities

- `message-processing`: how work passes between pipeline steps: publishing with the caller's transaction, dispatch
  from outbox to inbox, claiming under a lease, retries with backoff, exhaustion, wake-ups, and who can read the
  queues.

### Modified Capabilities

- `processing-configuration`: adds defaults for worker concurrency and the workers switch. Written as an ADDED
  requirement; the existing timing and retry defaults don't change.

## Impact

- **Database:** migration V8 adds the two tables and their indexes.
- **API:** new admin-only read endpoints under `/records/outbox-messages` and `/records/inbox-messages`.
- **Code:**
  - entities `OutboxMessage` and `InboxMessage`, enums `MessageType` and `InboxStatus`, repositories;
  - CRUD services `OutboxMessageService` and `InboxMessageService`;
  - `publisher/OutboxPublisher`, `processors/OutboxDispatcher`, `listener/InboxWorker`;
  - `service/InboxMessageHandler` (the interface each pipeline step implements), `InboxProcessingService` and
    `WorkerSignalService`;
  - `config/WorkerConfig` and two read-only controllers.
- **Configuration:** `app.document-reader.inbox.concurrency` and `app.document-reader.workers.enabled`, with README
  rows. The test configuration turns the workers off.
- **Later work items:** WI-06 signals the workers from `LISTEN`; WI-10 to WI-15 publish messages and implement
  `InboxMessageHandler`.
