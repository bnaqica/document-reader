# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-05 as one commit.

## 1. Configuration

- [x] 1.1 Extend `config/DocumentReaderPropertiesTests`: `inbox.concurrency` binds 4 and `workers.enabled` binds true by default; `inbox.concurrency: 0` fails startup naming the key. Verify it fails, add `concurrency` to `Inbox`, the `Workers` record, the `application.yml` defaults, `workers.enabled: false` in the test `config/application.yml`, and two README rows (design decision 7), then verify it passes along with every existing properties test.

## 2. Tables

- [x] 2.1 Write `repository/MessageTablesSchemaTests` (`JdbcClient`): an inbox status outside the four fails, a message type outside the four fails on both tables, and negative attempts fail. Verify it fails because the tables don't exist, write `V8__inbox_outbox.sql` (design decision 1), then verify it passes along with every WI-01 to WI-04 test.

## 3. Outbox and publishing

- [x] 3.1 Write `publisher/OutboxPublisherTests` (committing, with cleanup):
  - `publishRequiresTransaction()`: outside a transaction it throws `IllegalTransactionStateException` and no row exists;
  - `publishRollsBackWithCaller()`: a `TransactionTemplate` that publishes then rolls back leaves no row and sends no `outbox_ready`;
  - a commit leaves one undispatched row with the type, aggregate and payload, and a second connection listening on `outbox_ready` receives one notification;
  - a commit signals the dispatcher (`WorkerSignalService.awaitDispatcherSignal` returns true at once).

  Verify it fails. Implement `MessageType`, `OutboxMessage`, its repository, `OutboxMessageService(Impl)`, `OutboxMessageRecord`, `WorkerSignalService(Impl)` (enough for the dispatcher signal) and `OutboxPublisher`, then verify it passes.

## 4. Inbox CRUD

- [x] 4.1 Write `service/InboxMessageServiceTests` (fixed `Clock`):
  - `insertIfAbsent` inserts `PENDING` with 0 attempts and is due at the given time; the same id again leaves one row;
  - `claimDue` claims only its type, sets `PROCESSING`, `locked_by`, `locked_until = now + lease` and `attempts = 1`, in due order, and honours `limit`;
  - a message due in 30 s isn't claimed;
  - `staleLeaseIsReclaimed()`: `PROCESSING` with `locked_until` in the past is claimed again with `attempts` 2;
  - `markDone` sets `DONE` and `completed_at`, and a second call changes nothing; `markDone` outside a transaction throws;
  - `markRetry` and `markFailed` record the error, cut to 2,000 characters.

  Verify it fails. Implement `InboxStatus`, `InboxMessage`, its repository, `InboxMessageService(Impl)` and `InboxMessageRecord` (design decision 2), then verify it passes.

## 5. Dispatch

- [x] 5.1 Write `processors/OutboxDispatcherTests` (committing, with cleanup):
  - `dispatcherMovesOnceAcrossTwoDispatchers()`: 100 outbox rows, two threads calling `dispatchPending` together, give 100 inbox rows and every outbox row dispatched;
  - `dispatcherIgnoresDuplicate()`: an inbox row with id X already exists; dispatching outbox X leaves one inbox row and marks X dispatched;
  - a dispatch sends `inbox_ready` with the type as payload and signals that type in `WorkerSignalService`.

  Verify it fails, implement `OutboxDispatcher` (design decision 5), then verify it passes.

## 6. Processing

- [x] 6.1 Write `service/WorkerSignalServiceTests`: `signalWakesWaiter()` returns true at once after `signal`; with no signal it returns false after the timeout (200 ms in the test); three signals before one wait give one wake-up, then the next wait times out; the dispatcher signal behaves the same. Verify it fails where 3.1 left it short, complete `WorkerSignalServiceImpl`, then verify it passes.
- [x] 6.2 Write `service/InboxProcessingServiceTests` (committing, fixed `Clock`, stub handlers registered as beans):
  - `twoWorkersProcessEachMessageExactlyOnce()`: 50 due messages, two threads calling `processDue` until none remain, and the handler saw 50 distinct ids, each once;
  - a handler that succeeds leaves the message `DONE`;
  - `failedHandleSchedulesRetryWithBackoff()`: a failure at `attempts=1` sets `next_attempt_at = now + 10s`, and at `attempts=3` sets `now + 40s`;
  - `backoffCapsAtMax()`: at `attempts=11` it is `now + 10m`;
  - `exhaustedAfterMaxAttemptsCallsOnExhausted()`: the 5th failure gives `FAILED` and `onExhausted` called once with the last error;
  - `webhookUsesItsOwnMaxAttempts()`: a `DELIVER_WEBHOOK` message is retried on its 5th failure and exhausted on its 8th;
  - a reclaimed message whose attempts pass the maximum is exhausted without `handle` being called;
  - an `onExhausted` that throws leaves the message `PROCESSING`, not `FAILED`;
  - a type with no handler stays `PENDING` with 0 attempts after `processDue`;
  - two handlers for one type make construction fail naming the type;
  - `handle` runs with no transaction active (`TransactionSynchronizationManager.isActualTransactionActive()` is false inside the stub).

  Verify it fails. Implement `InboxMessageHandler`, `InboxProcessingService(Impl)` and `WorkerConfig` (design decisions 3 and 4), then verify it passes.

## 7. Worker loops

- [x] 7.1 Write `listener/InboxWorkerTests` (plain JUnit with Mockito, no Spring context): `inboxWorkerOnlyDelegates()`: with mocked `WorkerSignalService`, `InboxProcessingService` and `OutboxDispatcher`, a signal for `CLASSIFY_DOCUMENT` makes the worker call `processDue(CLASSIFY_DOCUMENT)` once, and the dispatcher signal calls `dispatchPending()` once; the worker's constructor takes no repository or CRUD service; loops start only for handled types; an exception from `processDue` is logged and the loop continues; `stop()` ends every loop; with `workers.enabled` false, `start()` starts nothing. Verify it fails, implement `InboxWorker` (design decision 6), then verify it passes.

## 8. Reading the queues

- [x] 8.1 Write `api/MessageRecordControllersTests` (`@WebMvcTest` over both controllers, `@Import(SecurityConfig.class)`, mocked CRUD services): each GET is 200 for an admin; `type` and `status` pass through and an unknown value gives 400; `limit=0` and `limit=500` give 400; a non-admin gets 403 as a problem detail; a missing id gives 404; POST, PUT, PATCH and DELETE give 405 for an admin; no token gives 401. Verify it fails, implement `OutboxMessageRecordController` and `InboxMessageRecordController` (design decision 8), then verify it passes.

## 9. Verification

- [x] 9.1 Update `README.md`: the two endpoints under the records API, and a short "Messages between steps" note on publish, dispatch, claim, retry and exhaustion. Verify it matches the design.
- [x] 9.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the WI-01 to WI-04 tests.
- [x] 9.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with `openspec/changes/wi-05-inbox-outbox/` as the requirements. Fix every Critical and Important finding, and verify the build again.
- [x] 9.4 Tick the boxes in `docs/workitems/WI-05-inbox-outbox-engine.md` (except the developer review and commit), and set its status.

## Workflow follow-up

- The developer reviews the diff and commits WI-05 as one commit.
- Then archive `wi-05-inbox-outbox`.
