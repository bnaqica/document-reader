# WI-06: Notifications and wait mode

| | |
|---|---|
| **Status** | Not started |
| **Depends on** | [WI-03](WI-03-core-records.md), [WI-05](WI-05-inbox-outbox-engine.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §14 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 7 |

**Goal:** Add the LISTEN/NOTIFY listener that wakes workers and completes waiting API calls, with a polling fallback and a timeout.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `config/NotificationConnectionConfig`, `listener/PgNotificationListener`, `service/CompletionWaitService(Impl)`
- Test: `listener/PgNotificationListenerTests`, `service/CompletionWaitServiceTests`

**Interfaces:**
- Consumes: `BatchService.get` (WI-03) and `InboxWorker.wake(MessageType)` (WI-05).
- Produces:
  - `CompletionWaitService.await(UUID batchId, Duration timeout) → boolean`: true when the batch reached a final status.
  - `void notifyBatchCompleted(UUID batchId)`: sends `NOTIFY batch_completed`, and must be called inside the completing transaction.
  - `PgNotificationListener` listens on the `outbox_ready`, `inbox_ready` and `batch_completed` channels using one dedicated connection that reconnects with backoff.

- [ ] **Step 1: Write the failing tests.**
  - `awaitReturnsWhenNotified()`: a notification is sent from another connection, and `await` returns true in under 1 s.
  - `awaitFallsBackToPolling()`: with notifications suppressed, the batch status is set to `COMPLETED` and `await` returns true within `pollInterval + 1s`.
  - `awaitTimesOut()`: returns false after the timeout, set to 3 s for the test.
  - Review Focus #4: `waiterRemovedWhenCallerInterrupted()`. The waiting thread is interrupted, the registry size returns to 0, processing is not affected, and no ERROR log is written.
  - `listenerReconnectsAfterConnectionDrop()`
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** using the PgJDBC `PGConnection.getNotifications(timeoutMs)` loop on a virtual thread, and a `ConcurrentHashMap<UUID, CompletableFuture<Void>>` registry. `await` completes on whichever comes first: the future, or a status poll every `wait.pollInterval`.
- [ ] **Step 4: Run** the tests and the build. Expected: PASS.

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
feat: add Postgres notifications and batch wait support
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
