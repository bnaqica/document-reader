# Design

## Context

What already exists:
- **Records (WI-03, WI-04):**
  - `BatchService.get` and `updateWebhookStatus`. `WebhookStatus` is `NOT_APPLICABLE`, `PENDING`, `DELIVERED` or
    `FAILED`; WI-10 sets `PENDING` when an async batch completes.
  - `WebhookDeliveryAttemptService.record(batchId, attempt, httpStatus, error)` and `findByBatch`. Attempts are unique
    per `(batch_id, attempt)`, and `http_status` must be within 100–599.
- **Messaging (WI-05):**
  - `InboxMessageHandler`.
  - `InboxProcessingServiceImpl.maxAttempts` already gives `DELIVER_WEBHOOK` the `webhook.max-attempts` (8), and its
    backoff runs from `inbox.backoff-initial` to `backoff-max`.
  - The message's `aggregateId` is the batch id, and its `id` is a stable delivery id across retries.
- **The body (WI-11):** `BatchQueryService.getResult(batchId, caller)` builds the §7.3 envelope, and refuses a caller
  who may not read the batch.
- **Settings:** `DocumentReaderProperties.Webhook(maxAttempts, timeout)` defaults to 8 and 10 s.
- **Rule:** a client in `client/` is a declarative `@HttpExchange` contract only; the wiring goes in `config`
  (`.claude/rules/service-architecture.md`).

## Goals / Non-Goals

**Goals:** the delta spec, no request or response content in any log or record, and an attempt record that survives
anything the step does afterwards.

**Non-Goals:** signing, secrets and SSRF checks are deferred to §22, as is a per-receiver rate limit.

## Decisions

### 1. `WebhookClient` is a declarative interface; `WebhookClientConfig` wires it

The client:
```java
public interface WebhookClient {
    @PostExchange(contentType = MediaType.APPLICATION_JSON_VALUE)
    ResponseEntity<Void> post(URI webhookUrl,
                              @RequestHeader("X-Document-Reader-Batch") String batchId,
                              @RequestHeader("X-Document-Reader-Delivery") String deliveryId,
                              @RequestBody BatchResultResponse result);
}
```

A `URI` argument gives each call its own URL.

`WebhookClientConfig` builds a `RestClient`:
- The request factory is a `JdkClientHttpRequestFactory` over
  `HttpClient.newBuilder().followRedirects(Redirect.NEVER).connectTimeout(timeout)`, with `setReadTimeout(timeout)`.
- The client is built with `RestClient.builder()`, because `spring-boot-restclient` isn't a dependency, so no
  auto-configured builder exists (found while applying). Its JSON converter is set to the application's `JsonMapper`, so
  the body is serialized exactly as `GET` serializes it (Jackson 3, ISO instants).
- `defaultStatusHandler(status -> true, (request, response) -> {})` stops `RestClient` from throwing on 4xx and 5xx.
  So every response, a 3xx included, comes back as a `ResponseEntity` for the service to judge.
- The proxy comes from `HttpServiceProxyFactory`.

That keeps the contract in `client` and the wiring in `config`. Nothing needs translating in `exception`, because the
service reads the status and catches `ResourceAccessException` itself.

### 2. The step (`WebhookDeliveryServiceImpl.deliver(batchId, deliveryId)`, and `handle`)

1. **Check the batch.** It reads the batch. Unless `webhookStatus` is `PENDING`, it logs at `debug` level and returns.
2. **The body.** `query.getResult(batchId, new Caller(batch.requestedBy(), false))`. The owner may read their own
   batch, so the webhook gets exactly what its owner would see from `GET`. That needs no new, unchecked query method.
3. **The attempt number.** `findByBatch(batchId).size() + 1`.
4. **The POST**, with no transaction open:
   - A response gives its status code.
   - `ResourceAccessException` (a timeout or a refused connection) gives no status, with the error "No response: " and
     the simple class name of the root cause, for example `HttpTimeoutException`.
5. **Record the attempt straight away:**
   - On a 2xx: `record(batchId, n, status, null)`.
   - On any other status: `record(batchId, n, status, "HTTP " + status)`.
   - Without a response: `record(batchId, n, null, error)`.

   `record` runs in its own transaction, so nothing that happens afterwards can roll it back.
6. **The outcome:**
   - On a 2xx, one transaction sets `updateWebhookStatus(DELIVERED)` and `markDone(deliveryId)` (only when called from
     `handle`).
   - Otherwise it throws `IllegalStateException("Webhook attempt n for batch … failed: HTTP 500")`. That message
     becomes the inbox's `last_error`, which is ids and status only, and the inbox schedules the retry.

`handle(message)` calls `deliver(message.aggregateId(), message.id())`.

**Assumption:**
- The attempt number counts the recorded attempts, not the inbox's `attempts`, so it is correct even when `deliver` is
  called directly.
- One message per batch means the only race is a lease-expiry reclaim. If that happens, a duplicate attempt number
  makes `record` fail, and that attempt is retried later.

### 3. The exhausted path

`onExhausted` runs inside the inbox's `FAILED` transaction. Unless the batch's webhook is `DELIVERED`, it calls
`updateWebhookStatus(batchId, FAILED)`, and it changes nothing else. The batch's status and result stay as they are.

### 4. Logging

- `info`: the batch id, the attempt number and the status on each attempt; `DELIVERED`; `FAILED`.
- `debug`: skipped deliveries.
- Never the URL (it may carry a receiver's token), the body or the response.

### 5. Tests

`service/WebhookDeliveryServiceTests`:
- `@DataJpaTest`, non-transactional, with real batch, record, query and attempt services, the inbox and outbox
  services, and `WebhookClientConfig`. `JacksonAutoConfiguration` is imported for the `JsonMapper`.
- `webhook.timeout` is lowered to 1 s, so the timeout test is quick.
- A JDK `com.sun.net.httpserver.HttpServer` on a random port records each request's method, headers and body, and
  answers with a scripted status, delay or `Location`.
- The tests:
  - `postsBatchEnvelopeWithTwoHeaders()`
  - `2xxMarksDelivered()`
  - `500SchedulesRetry()`
  - `timeoutSchedulesRetry()`
  - `redirectIsNotFollowedAndCountsAsFailure()`
  - `unreachableReceiverCountsAsFailure()`
  - `everyAttemptRecorded()`
  - `idempotentWhenDelivered()`
  - `syncBatchSendsNothing()`
  - `handleDeliversAndMarksMessageDone()`
  - `exhaustedMarksWebhookFailedButKeepsResult()`
  - `exhaustedLeavesDeliveredWebhookAlone()`

### 6. Fixes from the code review

- **Any failure to send is recorded.** A request that can't even be sent (the URL validator accepts a port such as
  70000, which the HTTP client then refuses) is recorded as "Request failed: <type>", without the exception message,
  which could quote the URL.
- **Both outcomes lock the batch.** `onExhausted` and the success transaction both call `BatchService.lockForUpdate`,
  so a late 2xx and giving up can't overwrite each other.
- **One log line per attempt.** The attempt service already logs each attempt, so the service no longer logs it again.

## Risks / Trade-offs

- **[No SSRF protection]** The service POSTs to any absolute http or https URL a caller gives, including internal
  addresses. → This is accepted and documented for the proof of concept (§22). It must be fixed before real data.
- **[A slow receiver holds a worker]** Each attempt is capped at `webhook.timeout` (10 s), and the inbox runs messages
  on virtual threads.
- **[Receivers may see duplicates]** A reclaimed lease can POST twice. `X-Document-Reader-Delivery` is stable for the
  batch's one message, so receivers can de-duplicate.

## Open Questions

None. §15 settles the behaviour, and the work item names the mechanism.
