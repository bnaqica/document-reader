# Proposal

## Why

WI-10 queues one `DELIVER_WEBHOOK` message when an async batch completes, but nothing delivers it: an async caller is
told its result will come by webhook, and it never does. Webhook delivery is process 4 of the pipeline (§15 of
`docs/design/2026-11-20-document-reader-design.md`), the last piece of the async path. This is WI-15 of
`docs/workitems/`.

## What Changes

- **A `DELIVER_WEBHOOK` handler** POSTs the batch's result envelope (§7.3, the same body `GET /batches/{id}` returns)
  as JSON to the batch's `webhookUrl`. It sends two headers: `X-Document-Reader-Batch` (the batch id) and
  `X-Document-Reader-Delivery` (the message id, for the receiver's de-duplication).
  - There is no signature and no shared secret, for the proof of concept (§22).
- **Outcomes:**
  - Any 2xx response marks the batch's webhook `DELIVERED`.
  - Anything else fails the attempt, so the inbox retries it with its backoff, up to `webhook.max-attempts` (8). That
    covers a non-2xx status, a redirect (3xx, which is never followed), a timeout (`webhook.timeout`, 10 s) or a
    connection failure.
- **Every attempt is recorded** in `webhook_delivery_attempt`, with its number, HTTP status (if any) and a short error
  description. Each record is written as soon as the POST returns, in its own transaction, so a later failure in the
  step can't lose it. No response body is stored or logged.
- **After the last failed attempt** the batch's webhook becomes `FAILED`. The batch's status and result are untouched,
  so `GET /batches/{id}` still returns it.
- **A message for a batch whose webhook isn't `PENDING`** (already delivered or failed, or a sync batch) does nothing.
- **Logs** carry the batch id, the attempt number and the status only: never the URL, the body or the response.

## Capabilities

### New Capabilities

- `webhook-delivery`: what an async batch's webhook carries, when it counts as delivered, how failures are retried
  and recorded, and what happens once retries run out.

### Modified Capabilities

None. `batch-completion` already queues the message and sets the webhook `PENDING`. `batch-results` defines the body.

## Impact

- **New code:**
  - `client/WebhookClient` (a declarative `@HttpExchange` interface);
  - `config/WebhookClientConfig` (a `RestClient` on a JDK `HttpClient` with `Redirect.NEVER` and the
    `webhook.timeout`);
  - `service/WebhookDeliveryService` and `service/WebhookDeliveryServiceImpl` (also the `DELIVER_WEBHOOK`
    `InboxMessageHandler`).
- **Tests:** `service/WebhookDeliveryServiceTests`, against a local JDK `HttpServer` stub.
- **Dependencies and schema:** none new. `webhook_delivery_attempt` and `batch.webhook_status` already exist, and the
  inbox already gives `DELIVER_WEBHOOK` `webhook.max-attempts`.
- **Done:** with this, every message type has a handler, and the pipeline runs end to end.
