# Design

## Context

WI-05 queues `pg_notify('outbox_ready', '')` and `pg_notify('inbox_ready', '<type>')` inside the publishing and
dispatching transactions, and wakes same-instance loops through `WorkerSignalService`, which also has `wakeAll()`.
WI-03 gives `BatchService.get` and `BatchStatus.isFinal()`. `wait.timeout` (300 s) and `wait.poll-interval` (2 s) are
bound in `DocumentReaderProperties`. Tests get Postgres from Testcontainers through `@ServiceConnection`, which
publishes a `JdbcConnectionDetails` bean rather than `spring.datasource.*` properties. See `proposal.md` for why, and
the specs for behaviour.

Rules that shape this:
- listeners receive an event and pass it to a service; parsing and decisions belong in the service;
- only a table's CRUD service touches its repository;
- logs carry ids and statuses only.

## Goals / Non-Goals

**Goals:**
- The interfaces in `docs/workitems/WI-06-notifications-and-wait-mode.md`, which WI-10 and WI-11 consume.
- A wait that is correct with no notifications at all; notifications only make it faster.

**Non-Goals:**
- The `POST /batches` wait and its 202 response (WI-11), and calling `notifyBatchCompleted` (WI-10).
- `DeferredResult`. The request thread is virtual, so a blocking wait is cheap (§14 leaves the choice to the builder).

## Decisions

### 1. The listening connection

- **`config/NotificationConnectionConfig`** provides a `NotificationConnectionFactory`, which opens a plain JDBC
  connection from `JdbcConnectionDetails` (URL, user, password) through `DriverManager`, outside the Hikari pool.
  *Alternative:* borrowing a pool connection forever would shrink the pool by one, and Hikari's max-lifetime would
  retire it under the listener. Using `JdbcConnectionDetails` means tests (Testcontainers) and deployments
  (`DB_URL`) get the same database the pool uses.
- The Postgres driver becomes `implementation`, because main code now uses `PGConnection.getNotifications`.

### 2. `listener/PgNotificationListener`

A `SmartLifecycle` that does nothing unless `workers.enabled`. When running, one virtual thread loops:
1. connect, run `LISTEN outbox_ready; LISTEN inbox_ready; LISTEN batch_completed`, then call
   `PgNotificationService.onConnected()`;
2. `getNotifications(500 ms)` and pass each one, as `(channel, payload)`, to `PgNotificationService.onNotification`;
3. on `SQLException`, close the connection, wait the backoff (1 s, doubling to 30 s, reset after a successful
   connect), and go back to 1.

After 60 empty reads (30 s) the loop runs `SELECT 1`, and the connection has TCP keepalive on, so a connection that
died without a reset fails and is reopened instead of leaving the instance deaf. `start()` refuses to start a second
loop while an earlier one is still alive, and the loop clears `running` however it ends. A failed connect and a lost
connection are logged differently, with the driver's message. *(Added after code review.)*

`stop()` sets `running` false and waits for the loop, which notices within one `getNotifications` timeout; it closes
the connection. A loop exception is logged at `error` with no payload and the loop carries on. The 500 ms timeout is a
constant: it only bounds how long shutdown waits.

### 3. `service/PgNotificationService(Impl)`: routing

The listener's only collaborator, so it holds no logic:
- `outbox_ready` → `WorkerSignalService.signalDispatcher()`;
- `inbox_ready` → `WorkerSignalService.signal(MessageType.valueOf(payload))`; an unknown name is logged at `debug` and
  ignored;
- `batch_completed` → `CompletionWaitService.onBatchCompleted(UUID.fromString(payload))`; an unparsable id is ignored
  the same way;
- `onConnected()` → `WorkerSignalService.wakeAll()`. Waiters need nothing, because they poll.

*Deviation:* the work item has the listener call the three services directly. Routing needs parsing (`valueOf`,
`fromString`) and a decision per channel, which the repo rules put in a service. The listener still talks to exactly
one service and never to another listener.

### 4. `service/CompletionWaitService(Impl)`

- **Registry:** `ConcurrentHashMap<UUID, CompletableFuture<Void>>`; `waitingCount()` reports its size (for the test,
  and later for metrics).
- **`await(batchId, timeout)`:**
  1. register (`computeIfAbsent`, so two waiters on one batch share a future);
  2. if `BatchService.get(batchId)` is already final, return true (this also refuses an unknown id with
     `ResourceNotFoundException`);
  3. until the deadline: `future.get(min(pollInterval, remaining))`; a completed future returns true; a slice timing
     out re-reads the status and returns true if final;
  4. at the deadline return false;
  5. on `InterruptedException`, restore the interrupt and return false, logging at `debug`;
  6. always, in `finally`, `remove(batchId, future)`.

  Registering before the first status read closes the gap where a completion lands between the read and the
  registration. Time is measured with `System.nanoTime`, as it bounds a real wait.
- **Changed after code review:**
  - `await` refuses to run inside a transaction (`IllegalStateException`): the batch might not be committed, so no
    worker could process it, and re-reads would come from the transaction's cache, holding a pool connection for the
    whole timeout.
  - A notification only prompts a re-read; the status decides. After the notification the wait just paces its
    re-reads, so a stray or early `NOTIFY` can't end it.
  - A `DataAccessException` while re-reading is logged at `warn` and the wait goes on, so a database blip doesn't fail
    the caller. An interrupt is checked before each re-read.
  - Tomcat doesn't interrupt a request thread when the client disconnects, so the spec no longer promises that a
    disconnect ends the wait: it holds only its own wait until the timeout. WI-11 decides whether to end it sooner.
- **`onBatchCompleted(batchId)`:** completes the registered future, if any.
- **`notifyBatchCompleted(batchId)`:** `@Transactional(propagation = MANDATORY)`. It calls
  `BatchService.notifyCompleted(batchId)`, which runs `SELECT pg_notify('batch_completed', :id)` through
  `BatchRepository`, so Postgres delivers it to every instance only on commit. It also registers an `afterCommit` that
  calls `onBatchCompleted` locally, so a waiter on this instance wakes even while the listener is reconnecting.

### 5. Configuration

No new settings. The README notes that the listener follows `workers.enabled`, and that it uses one connection per
instance outside the pool.

### 6. Tests

| Test | What it covers |
|---|---|
| `service/CompletionWaitServiceTests` (Postgres, committing) | `awaitReturnsWhenNotified` (`onBatchCompleted` from another thread, under 1 s); `awaitFallsBackToPolling` (no notification, the status set to `COMPLETED` through SQL, true within poll interval + 1 s, with `wait.poll-interval` 500 ms for speed); `awaitTimesOut` (3 s); already final; unknown batch; `waiterRemovedWhenCallerInterrupted` (registry back to 0, the batch unchanged, no ERROR log, checked with a Logback `ListAppender`); `notifyBatchCompleted` refused outside a transaction, delivered on commit (a `PgNotifications` listener receives the id), not on rollback, and completes a local waiter after commit |
| `service/PgNotificationServiceTests` (Mockito) | each channel routes to exactly one call; unknown type and bad id ignored; `onConnected` wakes all |
| `listener/PgNotificationListenerTests` (Postgres, with a real `NotificationConnectionFactory`) | `listenerOnlyDelegates`: a `NOTIFY` on each channel from another connection gives one matching call on a mocked `PgNotificationService`, and the listener's constructor takes only the factory, that service and the properties; `listenerReconnectsAfterConnectionDrop`: `pg_terminate_backend` on the listener's backend, then `onConnected` is called again and a later `NOTIFY` still arrives; disabled starts nothing |

## Risks / Trade-offs

- [Notifications sent while the listener is reconnecting are lost] → The reconnect wakes every loop, workers and the
  dispatcher poll every second, and waiters re-read every `wait.poll-interval`.
- [A disconnected sync caller keeps polling until `wait.timeout`] → One cheap read every `wait.poll-interval`;
  WI-11 can end it sooner with `DeferredResult` if that matters.
- [In-flight waits block graceful shutdown until they time out] → The listener stops before the web server's
  graceful phase; WI-11 decides whether to release waiters on shutdown.
- [`DB_URL` parameters override the listener's `ApplicationName`, and Hikari-only data source properties don't reach
  it] → Nothing is set that way today; set such options in `DB_URL`.
- [Two waiters on one batch share a future; the first to leave removes it] → The second still polls. Only one request
  waits on a batch in practice: the one that created it.
- [`pg_notify` payloads are limited to 8,000 bytes] → Payloads are a type name or a UUID.
- [One extra connection per instance] → Counted against the database's `max_connections`, outside the pool.

## Migration Plan

Nothing to migrate. Rolling back removes the listener; waits and workers keep working by polling.
