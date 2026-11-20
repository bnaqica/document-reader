# WI-05: Inbox/outbox engine

| | |
|---|---|
| **Status** | Not started |
| **Depends on** | [WI-01](WI-01-foundation.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §4, §13 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 6 |

**Goal:** Build the Postgres inbox/outbox: transactional publishing, dispatch, claiming with SKIP LOCKED, leases, backoff and retries.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `V8__inbox_outbox.sql`, `entity/OutboxMessage`, `InboxMessage`, `MessageType`, `InboxStatus`, their repositories, `service/OutboxMessageService(Impl)`, `InboxMessageService(Impl)`, `publisher/OutboxPublisher`, `processors/OutboxDispatcher`, `listener/InboxWorker`, `service/InboxMessageHandler`, `config/WorkerConfig`, and read-only controllers
- Test: `publisher/OutboxPublisherTests`, `processors/OutboxDispatcherTests`, `listener/InboxWorkerTests`

**Interfaces:**
- Produces:
  - `MessageType { SEPARATE_FILE, CLASSIFY_DOCUMENT, EXTRACT_DOCUMENT, DELIVER_WEBHOOK }`
  - `OutboxPublisher.publish(MessageType type, UUID aggregateId, Object payload)`: it must be called inside the caller's transaction (`Propagation.MANDATORY`) and sends `NOTIFY outbox_ready` after the commit.
  - `interface InboxMessageHandler { MessageType type(); void handle(InboxMessageRecord message); void onExhausted(InboxMessageRecord message, String lastError); }`
  - `InboxMessageService`:
    - `List<InboxMessageRecord> claimDue(MessageType, int limit, String instanceId, Duration lease)`
    - `void markDone(UUID id)`: it must be called inside the handler's final transaction.
    - `void markRetry(UUID id, String error, Instant nextAttemptAt)`
    - `void markFailed(UUID id, String error)`
  - Backoff: `nextAttempt = now + min(backoffInitial × 2^(attempts−1), backoffMax)`.
  - `InboxWorker.wake(MessageType)`: wakes that type's loop straight away. WI-06 calls it on `inbox_ready`; until then the worker relies on polling.
  - `int maxAttempts(MessageType)`: returns `inbox.maxAttempts` for every type except `DELIVER_WEBHOOK`, which uses `webhook.maxAttempts`.

- [ ] **Step 1: Write the failing tests.**
  - `publishRequiresTransaction()`: throws when called outside a transaction.
  - `publishRollsBackWithCaller()`
  - `dispatcherMovesOnceAcrossTwoDispatchers()`: two dispatchers run in parallel over 100 rows, giving 100 inbox rows and every outbox row dispatched.
  - `dispatcherIgnoresDuplicate()`: inserting the same id twice gives one inbox row.
  - `twoWorkersProcessEachMessageExactlyOnce()`: two workers, 50 messages, and the handler is invoked 50 times with distinct ids.
  - `failedHandleSchedulesRetryWithBackoff()`: `attempts=1` sets `nextAttemptAt = now+10s`, and `attempts=3` sets `now+40s`, using a fixed `Clock`.
  - `backoffCapsAtMax()`
  - `exhaustedAfterMaxAttemptsCallsOnExhausted()`: on the 5th failure the status is `FAILED` and `onExhausted` is called once.
  - `webhookUsesItsOwnMaxAttempts()`: a `DELIVER_WEBHOOK` message is exhausted on the 8th failure.
  - `staleLeaseIsReclaimed()`: a row left `PROCESSING` with `locked_until` in the past is claimed again.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Write the V8 migration** for the tables in §13, with an index on `inbox_message(message_type, status, next_attempt_at)` and a partial index on `outbox_message(dispatched_at) WHERE dispatched_at IS NULL`.
- [ ] **Step 4: Implement the claim** as one short transaction: `SELECT … WHERE status='PENDING' AND next_attempt_at <= now OR (status='PROCESSING' AND locked_until < now) … FOR UPDATE SKIP LOCKED LIMIT n`, then update to `PROCESSING` and commit. `InboxWorker` runs one loop per message type on virtual threads. It wakes on `outbox.pollInterval`, and exposes `InboxWorker.wake(MessageType)` so WI-06 can also wake it on `inbox_ready`, and dispatches to the handler bean whose `type()` matches.
- [ ] **Step 5: Run** the tests and the build. Expected: PASS.

---

## Done when

- [ ] Every step above is ticked.
- [ ] `./gradlew spotlessApply build` passes, with all tests green.
- [ ] `git status` shows only the files this work item names, plus their tests and fixtures.
- [ ] A code review (`superpowers:requesting-code-review`, using the `code-reviewer` agent) has no open findings.
- [ ] The developer has reviewed the diff.

## Commit

Claude never commits in this repo (`.claude/rules/git-workflow.md`). The developer commits this work item as one
commit, with this suggested message:

```text
feat: add Postgres inbox/outbox engine
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
