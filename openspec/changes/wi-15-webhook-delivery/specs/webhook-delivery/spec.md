# Spec Delta

## Purpose

Defines how an async batch's single webhook is delivered: the request it sends, what counts as delivered, how failed
attempts are retried and recorded, and what the batch keeps when delivery gives up.

## ADDED Requirements

### Requirement: A webhook carries the batch result
Delivering an async batch's webhook SHALL send one POST to its `webhookUrl` with the batch's result envelope as the
JSON body, the same envelope `GET /batches/{id}` returns. The request SHALL carry `X-Document-Reader-Batch` (the batch
id) and `X-Document-Reader-Delivery` (the delivery id, the same on every retry), and no signature header.

#### Scenario: Completed batch
- **GIVEN** an async batch completed with two documents
- **WHEN** its webhook is delivered
- **THEN** the receiver gets a POST whose body is the batch's result, with both documents
- **AND** the headers name the batch and the delivery, and no signature header is sent

### Requirement: A 2xx response delivers the webhook
A response with a 2xx status SHALL mark the batch's webhook `DELIVERED`, and no further attempt SHALL be made.

#### Scenario: Receiver accepts
- **WHEN** the receiver answers 204
- **THEN** the batch's webhook is `DELIVERED`

### Requirement: Any other outcome is retried
A non-2xx status, a redirect, no answer within `webhook.timeout`, or a failed connection SHALL fail the attempt, so
the message is retried through the inbox's backoff, up to `webhook.max-attempts` attempts in all. A redirect SHALL
never be followed.

#### Scenario: Server error
- **WHEN** the receiver answers 500
- **THEN** the attempt fails, and the webhook stays `PENDING`

#### Scenario: Timeout
- **WHEN** the receiver doesn't answer within `webhook.timeout`
- **THEN** the attempt fails, and the webhook stays `PENDING`

#### Scenario: Redirect
- **WHEN** the receiver answers 302 with a `Location` header
- **THEN** the location isn't requested, and the attempt fails

### Requirement: Every attempt is recorded
Each attempt SHALL be recorded with its number, the HTTP status if a response came, and a short error description
when it failed, as soon as the POST returns and in its own transaction. A response body SHALL never be recorded or
logged, and the URL and request body SHALL never be logged.

#### Scenario: Two failures then success
- **WHEN** a webhook fails with 500, then times out, then gets 200
- **THEN** attempts 1, 2 and 3 are recorded with statuses 500, none and 200, the first two with an error

### Requirement: A webhook that can't be delivered leaves the result in place
When a webhook's message is exhausted, the batch's webhook SHALL become `FAILED`, unless it was already delivered. The
batch's status and result SHALL be unchanged and still readable through `GET /batches/{id}`.

#### Scenario: Retries run out
- **GIVEN** a completed async batch whose receiver always answers 500
- **WHEN** its webhook message is exhausted
- **THEN** its webhook is `FAILED`, its status is still `COMPLETED`, and its owner can still read its result

### Requirement: A webhook is delivered at most once per batch
Delivery for a batch whose webhook isn't `PENDING` (already `DELIVERED` or `FAILED`, or a sync batch) SHALL send
nothing and change nothing.

#### Scenario: Message delivered again
- **GIVEN** a batch whose webhook is `DELIVERED`
- **WHEN** its message is handled again
- **THEN** no request is sent, and no attempt is recorded
