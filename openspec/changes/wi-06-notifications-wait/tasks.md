# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-06 as one commit.

## 1. Waiting for a batch

- [x] 1.1 Write `service/CompletionWaitServiceTests` (Postgres, committing, `wait.poll-interval` 500 ms):
  - `awaitReturnsWhenNotified()`: another thread calls `onBatchCompleted(B)` after 200 ms, and `await(B, 10s)` returns true in under 1 s;
  - `awaitFallsBackToPolling()`: with no notification, the batch status is set to `COMPLETED` through SQL after 200 ms, and `await` returns true within the poll interval plus 1 s;
  - `awaitTimesOut()`: a `PROCESSING` batch, `await(B, 3s)` returns false after at least 3 s, and B is still `PROCESSING`;
  - an already `FAILED` batch returns true at once; an unknown id gives `ResourceNotFoundException`;
  - `waiterRemovedWhenCallerInterrupted()`: the waiting thread is interrupted, `await` returns false with the interrupt kept, `waitingCount()` returns to 0, the batch is unchanged, and a Logback `ListAppender` saw no ERROR.

  Verify it fails. Implement `CompletionWaitService(Impl)` with `await`, `onBatchCompleted` and `waitingCount` (design decision 4), then verify it passes.
- [x] 1.2 Extend `service/CompletionWaitServiceTests`: `notifyBatchCompleted` outside a transaction throws `IllegalTransactionStateException`; inside a committed transaction a `PgNotifications` listener on `batch_completed` receives B's id, and a waiter on B returns true; inside a rolled-back transaction nothing is delivered. Verify it fails, add `BatchService.notifyCompleted` (and its repository query) and `notifyBatchCompleted`, then verify it passes along with every `BatchServiceTests` test.

## 2. Routing notifications

- [x] 2.1 Write `service/PgNotificationServiceTests` (Mockito): `outbox_ready` calls `signalDispatcher()` once; `inbox_ready` with `EXTRACT_DOCUMENT` calls `signal(EXTRACT_DOCUMENT)` once; `inbox_ready` with `PRINT_FILE` calls nothing; `batch_completed` with a UUID calls `onBatchCompleted` once; a bad UUID calls nothing; an unknown channel calls nothing; `onConnected()` calls `wakeAll()` once. Verify it fails, implement `PgNotificationService(Impl)` (design decision 3), then verify it passes.

## 3. The listener

- [x] 3.1 Write `listener/PgNotificationListenerTests` (Postgres, real `NotificationConnectionFactory`, mocked `PgNotificationService`, `workers.enabled` true for this test only):
  - `listenerOnlyDelegates()`: after `onConnected`, a `NOTIFY` on each of the three channels from another connection gives exactly one matching `onNotification(channel, payload)` call; the constructor takes only the factory, the service and the properties;
  - `listenerReconnectsAfterConnectionDrop()`: `pg_terminate_backend` on the listener's backend (found through `pg_stat_activity` and `LISTEN`'s connection), then `onConnected` is called a second time and a later `NOTIFY` still arrives;
  - `stop()` ends the loop and closes the connection;
  - with `workers.enabled` false, `start()` opens no connection.

  Verify it fails. Move the Postgres driver to `implementation`, and implement `NotificationConnectionFactory`, `NotificationConnectionConfig` and `PgNotificationListener` (design decisions 1 and 2), then verify it passes.

## 4. Verification

- [x] 4.1 Update `README.md`: under "Messages between steps", how notifications wake other instances and that the listener follows `workers.enabled` with one connection outside the pool; and a short "Waiting for a batch" note. Verify it matches the design.
- [x] 4.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the WI-01 to WI-05 tests.
- [x] 4.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with `openspec/changes/wi-06-notifications-wait/` as the requirements. Fix every Critical and Important finding, and verify the build again.
- [x] 4.4 Tick the boxes in `docs/workitems/WI-06-notifications-and-wait-mode.md` (except the developer review and commit), and set its status.

## Workflow follow-up

- The developer reviews the diff and commits WI-06 as one commit.
- Then archive `wi-06-notifications-wait`.
