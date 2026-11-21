# Design

## Context

WI-01 bound `outbox.poll-interval`, `inbox.max-attempts`, `backoff-initial`, `backoff-max`, `lease` and
`webhook.max-attempts` in `DocumentReaderProperties`, and turned on virtual threads. WI-03 and WI-04 set the CRUD
service, insert-only entity, `/records` controller and test fixture patterns. No pipeline step exists yet, so this
change builds the engine with no real handler; tests supply stub handlers. See `proposal.md` for why, and the specs for
behaviour.

The design's §13 is the source for the tables and the flow. Repo rules that shape this:
- only a table's CRUD service touches its repository;
- listeners hold no logic and pass straight to a service;
- an action service calls CRUD services, never the reverse;
- OpenAI calls never run inside an open transaction.

## Goals / Non-Goals

**Goals:**
- The exact interfaces in `docs/workitems/WI-05-inbox-outbox-engine.md`, which WI-06 and WI-10 to WI-15 consume.
- At-least-once delivery with no double claim at any one time, and a bounded number of attempts even for a message
  that crashes its instance.
- Workers that run without WI-06, by polling.

**Non-Goals:**
- `LISTEN` and cross-instance wake-ups (WI-06). This change only sends the `NOTIFY`s.
- Exactly-once handling. A lease can expire while a slow handler still runs; handlers are idempotent (§13.8, owned by
  each step's work item).
- Cleaning up old `DONE` rows, and replaying `FAILED` ones.

## Decisions

### 1. Tables (V8)

| Table | Columns and constraints |
|---|---|
| `outbox_message` | `id UUID` pk, `message_type` (check: the four), `aggregate_id UUID`, `payload JSONB`, `created_at`, `dispatched_at` (nullable) |
| `inbox_message` | `id UUID` pk (the outbox id), `message_type` (check), `aggregate_id`, `payload JSONB`, `status` (check: `PENDING`, `PROCESSING`, `DONE`, `FAILED`), `attempts INT` (≥ 0, default 0), `next_attempt_at`, `locked_by VARCHAR(255)`, `locked_until`, `last_error TEXT`, `created_at`, `completed_at` |

- **Indexes:** `inbox_message(message_type, status, next_attempt_at)` for the claim, and a partial index
  `outbox_message(created_at) WHERE dispatched_at IS NULL` for dispatch. *(The work item says
  `outbox_message(dispatched_at) WHERE dispatched_at IS NULL`; indexing `created_at` under the same condition serves
  the same query and also its order.)*
- **`JSONB`, as §13 says.** Payloads are small id holders read by code, so key order doesn't matter, unlike extracted
  data.
- No foreign key from `aggregate_id`: it may be a batch, a file or a document.

### 2. Entities, enums and CRUD services

- `MessageType` and `InboxStatus` in `entity`. `OutboxMessage` and `InboxMessage` entities; the outbox row is
  insert-only (`InsertOnlyEntity`). The inbox row changes state, so it has no `Persistable` flag; rows are inserted by
  native SQL (below) and updated by small entity methods (`claim`, `done`, `retry`, `fail`).
- `OutboxMessageService(Impl)`:
  - `create(MessageType, UUID aggregateId, JsonNode payload)`, `@Transactional(propagation = MANDATORY)`. It inserts
    the row and runs `SELECT pg_notify('outbox_ready', '')` in the same transaction. Postgres delivers a `NOTIFY` only
    at commit and discards it on rollback, which gives "after commit" for free.
  - `lockUndispatched(int limit)` (`FOR UPDATE SKIP LOCKED`, oldest first, `MANDATORY`) and `markDispatched(ids, at)`.
  - `findRecent(int limit)`.
- `InboxMessageService(Impl)`:
  - `insertIfAbsent(List<OutboxMessageRecord>, Instant dueAt)`: `INSERT … ON CONFLICT (id) DO NOTHING`, then
    `pg_notify('inbox_ready', '<type>')` once per type inserted, in the caller's transaction (`MANDATORY`).
  - `claimDue(MessageType, int limit, String instanceId, Duration lease)`: its own transaction (`REQUIRES_NEW`), so it
    commits before any work starts. A native `SELECT … WHERE message_type=? AND ((status='PENDING' AND
    next_attempt_at <= ?) OR (status='PROCESSING' AND locked_until < ?)) ORDER BY next_attempt_at, created_at LIMIT ?
    FOR UPDATE SKIP LOCKED` locks the rows, and the entity's `claim` sets `PROCESSING`, the holder, the lease end and
    `attempts + 1`.
  - `markDone(UUID id)` (`MANDATORY`): a no-op when already `DONE`, and never revives a `FAILED` message.
  - `markRetry(InboxMessageRecord claim, String error, Instant nextAttemptAt)`, `markFailed(InboxMessageRecord claim,
    String error)`, `release(claim)` and `recordError(claim, error)` each return whether they applied. *(Changed after
    code review; the work item had `markRetry(UUID, …)` and `markFailed(UUID, …)`. Only the engine calls them.)*
  - **Every transition after the claim is one conditional `UPDATE`.** Those that end a claim apply only while the
    message is `PROCESSING` under that claim (same holder, same attempt). So a handler that marked its message done
    and then threw isn't retried or failed, a stale holder can't reopen a message another instance reclaimed, and a
    late `markDone` can't turn `FAILED` into `DONE`. A single statement also can't lose a concurrent claim's
    `attempts + 1`, as a read-then-write could. *(Added after code review.)*
  - `get(UUID)`, `find(MessageType, InboxStatus, int limit)`.
- **Records:** `OutboxMessageRecord` and `InboxMessageRecord` in `dto.response`, with the payload as a `JsonNode`.
- **Errors** are cut to 2,000 characters in the service, so a stack-trace-sized message can't bloat a row.
- **Times** come from the injected `Clock`, never `now()` in SQL, so backoff tests use a fixed clock.

### 3. Attempts count at claim time

`attempts` goes up when a message is claimed, not when it fails. So the first failure sees `attempts = 1` and backs
off `backoff-initial`, matching the work item's arithmetic, and a message whose handling kills its instance still
uses up an attempt each time its lease expires. `InboxProcessingService` checks `attempts > maxAttempts(type)` for a
claimed message: only a reclaim after a crash on the last attempt gets there, and it is exhausted without being
handled.
*Alternative:* counting on failure leaves a crash loop unbounded.

### 4. Processing (`InboxProcessingService(Impl)`, action service)

`processDue(MessageType type) → int`:
1. `claimDue(type, inbox.concurrency, instanceId, inbox.lease)`.
2. Handle each claimed message on a virtual thread, and wait for all of them. For each:
   - `attempts > maxAttempts`: exhaust (step 4 below), without handling;
   - otherwise call `handler.handle(message)` with **no transaction open**, so OpenAI calls run outside one;
   - success: `markDone` in a short transaction (a no-op if the handler already marked it inside its final
     transaction);
   - exception: if `attempts < maxAttempts`, `markRetry(id, error, now + backoff(attempts))`; otherwise exhaust.
3. Exhaust: `recordError` first, so the error survives a rollback; then one transaction runs `markFailed` and, only
   if that applied, `handler.onExhausted(message, error)`. If `onExhausted` throws, the transaction rolls back, the
   message stays `PROCESSING`, and it is exhausted again after its lease expires, with the recorded error, so a file
   or document always ends up failed.
4. **Interrupted handler** (shutdown cut it off): `release` puts the message back, due now, without counting the
   attempt, and never exhausts it. The interrupt is cleared while releasing, since an interrupted thread can't get a
   connection. *(Added after code review.)*
5. A full claim (`concurrency` messages) signals its own type, so a backlog drains without waiting for the poll.
   *(Added after code review.)*
6. Each task catches `Throwable` and logs the id and exception class only, so nothing escapes to the executor's
   default handler. Return the number of messages claimed.

- `backoff(attempts) = min(backoffInitial × 2^(attempts − 1), backoffMax)`, with the shift capped so it can't
  overflow.
- `maxAttempts(type)` is `webhook.max-attempts` for `DELIVER_WEBHOOK`, otherwise `inbox.max-attempts`.
- **Handlers:** the constructor collects the `InboxMessageHandler` beans into an `EnumMap` by `type()`, and throws
  `IllegalStateException` naming the type when two share one. `handledTypes()` lists the types with a handler.
- Logs carry the message id, type, attempts and status only, never the payload or the error text of a handler (which
  might quote document content).

### 5. Dispatch and publishing

- **`publisher/OutboxPublisher.publish(type, aggregateId, payload)`:** `@Transactional(propagation = MANDATORY)`.
  It converts the payload with `JsonMapper`, calls `OutboxMessageService.create`, and registers an `afterCommit`
  synchronization that calls `WorkerSignalService.signalDispatcher()`, so the same instance dispatches at once.
- **`processors/OutboxDispatcher.dispatchPending() → int`:** one transaction per round of up to 100 rows:
  `claimUndispatched(100)`, `insertIfAbsent(rows, now)`, `markDispatched`. Then `afterCommit` signals each type that
  was dispatched. It repeats until a round moves nothing, and returns the total.

### 6. Wake-ups and loops

- **`WorkerSignalService(Impl)`:** one binary signal per message type plus one for the dispatcher. Each is a
  `Semaphore` released only when it has no permit, so many signals before a wait collapse into one wake-up.
  `awaitSignal` and `awaitDispatcherSignal` are `tryAcquire(timeout)`, and return false on timeout. WI-06 calls
  `signal(type)` from `inbox_ready` and `signalDispatcher()` from `outbox_ready`.
- **`listener/InboxWorker`:** a `SmartLifecycle` that, when `workers.enabled`, starts one virtual-thread loop per
  handled type and one dispatcher loop:
  - type loop: `awaitSignal(type, pollInterval)` then `InboxProcessingService.processDue(type)`;
  - dispatcher loop: `awaitDispatcherSignal(pollInterval)` then `OutboxDispatcher.dispatchPending()`.

  A loop catches and logs an exception (at `error`, ids only) and keeps going. `stop()` sets `running` false and
  calls `WorkerSignalService.wakeAll()`, waits up to 20 s for rounds in flight, and interrupts only loops still
  running then. A loop checks `running` after each wait, so shutdown never starts a round. *(Changed after code
  review: interrupting at once cut off handlers mid-call, which used up an attempt and could fail a document.)* The
  dispatcher loop sits here rather than in its own class so all the background loops start and stop together; it
  still only waits and delegates.
- **`config/WorkerConfig`:** the instance id (`hostname:random-uuid`, so two instances on one host differ) as a
  `WorkerInstance` bean. `processDue` opens a virtual-thread executor per round.

### 7. Configuration

- `inbox.concurrency` (default 4, `@Positive`) on the existing `Inbox` record.
- A new `Workers(boolean enabled)` record, `app.document-reader.workers.enabled` (default true).
- The test `config/application.yml` sets `workers.enabled: false`, so background loops never race a test. Worker tests
  call `processDue` and `dispatchPending` directly, or start `InboxWorker` themselves.
- README environment table: two rows. These aren't secrets, so no `${ENV_VAR}` is needed beyond Spring's relaxed
  binding.

### 8. Read endpoints

`OutboxMessageRecordController` (`GET /records/outbox-messages?limit=`) and `InboxMessageRecordController`
(`GET /records/inbox-messages?type=&status=&limit=`, `GET /records/inbox-messages/{id}`), each method
`@PreAuthorize("hasRole('ADMIN')")`, `limit` `@Min(1) @Max(200)` defaulting to 50. A message has no single batch, so
ownership doesn't apply: only admins read the queues.

### 9. Tests

| Test | What it covers |
|---|---|
| `repository/MessageTablesSchemaTests` | status and type checks, attempts ≥ 0 |
| `publisher/OutboxPublisherTests` | `publishRequiresTransaction`, `publishRollsBackWithCaller`, commit notifies `outbox_ready` (a second JDBC connection runs `LISTEN`), rollback doesn't |
| `processors/OutboxDispatcherTests` | `dispatcherMovesOnceAcrossTwoDispatchers` (100 rows), `dispatcherIgnoresDuplicate`, `inbox_ready` notified |
| `service/InboxMessageServiceTests` | claim sets lease and attempts, not-due not claimed, `staleLeaseIsReclaimed`, due order, done is idempotent, error truncation |
| `service/InboxProcessingServiceTests` | `twoWorkersProcessEachMessageExactlyOnce`, `failedHandleSchedulesRetryWithBackoff`, `backoffCapsAtMax`, `exhaustedAfterMaxAttemptsCallsOnExhausted`, `webhookUsesItsOwnMaxAttempts`, crash on last attempt, `onExhausted` throwing leaves the message claimable, duplicate handlers refused, unhandled type stays pending |
| `service/WorkerSignalServiceTests` | `signalWakesWaiter`, timeout, signals collapse |
| `listener/InboxWorkerTests` | `inboxWorkerOnlyDelegates` (mocked services), loops only for handled types, nothing starts when disabled |
| `api/MessageRecordControllersTests` | 200 for admin, 403 otherwise, 404, 400 for `limit`, 405 for writes, 401 |
| `config/DocumentReaderPropertiesTests` (extended) | the two defaults, zero concurrency refused |

Database tests that commit (concurrency, `NOTIFY`) run outside the test transaction and clean up their rows, as WI-02
and WI-04 do.

## Risks / Trade-offs

- [A lease expires while a slow handler still runs, so two instances handle one message] → The lease (10 min) is far
  longer than any step; handlers check their state first (§13.8). The late finisher's `markDone` is harmless.
- [A stale holder's late `markDone` marks done a message another instance is still handling] → Its work did finish,
  and the handler is idempotent. Retry and failure are guarded by the claim, so only "done" can come late.
- [`processDue` waits for its slowest message before claiming more] → With `concurrency` 4 and steps of similar
  length this costs little. A per-type semaphore can replace it if extraction shows it.
- [Without WI-06, other instances only see new work on their next poll] → One second (`outbox.poll-interval`).

## Migration Plan

- Flyway applies V8 on startup.
- Before production, rolling back means dropping the two tables and deleting V8 from the Flyway history.
