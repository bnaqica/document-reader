# WI-05: Inbox/outbox engine

| | |
|---|---|
| **Status** | Built, awaiting developer review |
| **Depends on** | [WI-01](WI-01-foundation.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §4, §13 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 6 |

**Goal:** Build the Postgres inbox/outbox: transactional publishing, dispatch, claiming with SKIP LOCKED, leases, backoff and retries.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `V8__inbox_outbox.sql`, `entity/OutboxMessage`, `InboxMessage`, `MessageType`, `InboxStatus`, their repositories, `service/OutboxMessageService(Impl)`, `InboxMessageService(Impl)`, `publisher/OutboxPublisher`, `processors/OutboxDispatcher`, `listener/InboxWorker`, `service/InboxMessageHandler`, `service/InboxProcessingService(Impl)`, `service/WorkerSignalService(Impl)`, `config/WorkerConfig`, and read-only controllers
- Test: `publisher/OutboxPublisherTests`, `processors/OutboxDispatcherTests`, `service/InboxProcessingServiceTests`, `service/WorkerSignalServiceTests`, `listener/InboxWorkerTests`

**Interfaces:**
- Produces:
  - `MessageType { SEPARATE_FILE, CLASSIFY_DOCUMENT, EXTRACT_DOCUMENT, DELIVER_WEBHOOK }`
  - `OutboxPublisher.publish(MessageType type, UUID aggregateId, Object payload)`: it must be called inside the caller's transaction (`Propagation.MANDATORY`) and sends `NOTIFY outbox_ready` after the commit.
  - `interface InboxMessageHandler { MessageType type(); void handle(InboxMessageRecord message); void onExhausted(InboxMessageRecord message, String lastError); }`. Each pipeline step's action service implementation (`SeparationServiceImpl`, `ClassificationServiceImpl`, `ExtractionServiceImpl`, `WebhookDeliveryServiceImpl`) implements it directly. There are no separate handler classes.
  - `InboxProcessingService.processDue(MessageType type) → int`: claims due messages, calls the matching `InboxMessageHandler`, and applies `markDone`, `markRetry` with backoff, or `markFailed` plus `onExhausted`. It returns how many it processed. All of the inbox logic lives here, not in the listener.
  - `WorkerSignalService`: `void signal(MessageType)`, `void signalDispatcher()`, `boolean awaitSignal(MessageType, Duration)` and `boolean awaitDispatcherSignal(Duration)`. It is an in-memory wake-up signal per message type, so one listener never calls another.
  - `InboxMessageService`:
    - `List<InboxMessageRecord> claimDue(MessageType, int limit, String instanceId, Duration lease)`
    - `void markDone(UUID id)`: it must be called inside the handler's final transaction.
    - `boolean markRetry(InboxMessageRecord claim, String error, Instant nextAttemptAt)` and `boolean markFailed(InboxMessageRecord claim, String error)`: they apply only to that claim (still `PROCESSING`, same holder and attempt), so a stale or finished message is left alone. *(Changed from `UUID` during WI-05's code review.)*
  - Backoff: `nextAttempt = now + min(backoffInitial × 2^(attempts−1), backoffMax)`.
  - `InboxWorker` (listener): one loop per message type on virtual threads. It waits on `WorkerSignalService.awaitSignal(type, outbox.pollInterval)`, then calls `InboxProcessingService.processDue(type)`. It has no other logic. Listeners follow `listener -> @Service to CRUD @Service interface -> ...` (`.claude/rules/service-architecture.md`).
  - `int maxAttempts(MessageType)`: returns `inbox.maxAttempts` for every type except `DELIVER_WEBHOOK`, which uses `webhook.maxAttempts`.

- [x] **Step 1: Write the failing tests.**
  - `publishRequiresTransaction()`: throws when called outside a transaction.
  - `publishRollsBackWithCaller()`
  - `dispatcherMovesOnceAcrossTwoDispatchers()`: two dispatchers run in parallel over 100 rows, giving 100 inbox rows and every outbox row dispatched.
  - `dispatcherIgnoresDuplicate()`: inserting the same id twice gives one inbox row.
  - `twoWorkersProcessEachMessageExactlyOnce()`: two `processDue` callers on separate threads, 50 messages, and the handler is invoked 50 times with distinct ids.
  - `failedHandleSchedulesRetryWithBackoff()`: `attempts=1` sets `nextAttemptAt = now+10s`, and `attempts=3` sets `now+40s`, using a fixed `Clock`.
  - `backoffCapsAtMax()`
  - `exhaustedAfterMaxAttemptsCallsOnExhausted()`: on the 5th failure the status is `FAILED` and `onExhausted` is called once.
  - `webhookUsesItsOwnMaxAttempts()`: a `DELIVER_WEBHOOK` message is exhausted on the 8th failure.
  - `staleLeaseIsReclaimed()`: a row left `PROCESSING` with `locked_until` in the past is claimed again.
  - `signalWakesWaiter()`: `awaitSignal` returns true straight away after `signal`, and false when the timeout passes without one.
  - `inboxWorkerOnlyDelegates()`: with a mocked `InboxProcessingService`, a signal makes the worker call `processDue(type)` once, and the worker touches no repository or CRUD service.
- [x] **Step 2: Run** the tests. Expected: FAIL.
- [x] **Step 3: Write the V8 migration** for the tables in §13, with an index on `inbox_message(message_type, status, next_attempt_at)` and a partial index on `outbox_message(dispatched_at) WHERE dispatched_at IS NULL`.
- [x] **Step 4: Implement the claim** as one short transaction: `SELECT … WHERE status='PENDING' AND next_attempt_at <= now OR (status='PROCESSING' AND locked_until < now) … FOR UPDATE SKIP LOCKED LIMIT n`, then update to `PROCESSING` and commit. `InboxProcessingService` dispatches each claimed message to the `InboxMessageHandler` bean whose `type()` matches. `InboxWorker` only waits on `WorkerSignalService` (or the poll interval) and calls `processDue`. Until WI-06 lands, nothing signals it, so it runs on polling alone.
- [x] **Step 5: Run** the tests and the build. Expected: PASS.

---

## Done when

- [x] Every step above is ticked.
- [x] `./gradlew spotlessApply build` passes, with all tests green.
- [ ] `git status` shows only the files this work item names, plus their tests and fixtures.
- [x] A code review (`superpowers:requesting-code-review`, using the `code-reviewer` agent) has no open findings.
- [ ] The developer has reviewed the diff.

## Commit

Claude never commits in this repo (`.claude/rules/git-workflow.md`). The developer commits this work item as one
commit, with this suggested message:

```text
feat: add Postgres inbox/outbox engine
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
