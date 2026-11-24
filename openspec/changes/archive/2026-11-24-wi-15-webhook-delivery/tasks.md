# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-15 as one commit.

## 1. Contracts and wiring

- [x] 1.1 Add `client/WebhookClient`, `config/WebhookClientConfig` and the `service/WebhookDeliveryService` interface
  (`deliver(UUID batchId, UUID deliveryId)`), following design decision 1. These are checked by compiling.

## 2. Delivering

- [x] 2.1 Write `service/WebhookDeliveryServiceTests` (design decision 5 has the setup):
  - `postsBatchEnvelopeWithTwoHeaders()`: a POST with the result envelope (both documents), the two headers, and no
    signature header;
  - `2xxMarksDelivered()`.

  Watch them fail. Implement steps 1 to 6 of design decision 2 for the success path, and watch them pass.
- [x] 2.2 Add these tests first, then the failure path:
  - `500SchedulesRetry()`, `timeoutSchedulesRetry()`, `redirectIsNotFollowedAndCountsAsFailure()` (the `Location`
    target is never requested) and `unreachableReceiverCountsAsFailure()`: each throws, the webhook stays `PENDING`,
    and an attempt is recorded;
  - `everyAttemptRecorded()`: 500, then a timeout, then 200 give attempts 1, 2 and 3 with statuses 500, none and 200.
- [x] 2.3 Add these tests first, then the code for them:
  - `idempotentWhenDelivered()`;
  - `syncBatchSendsNothing()`.

## 3. Inbox handler and failure

- [x] 3.1 Add these tests first, then the `InboxMessageHandler` side (design decision 3):
  - `handleDeliversAndMarksMessageDone()`: `type()` is `DELIVER_WEBHOOK`, and the delivery header is the message id;
  - `exhaustedMarksWebhookFailedButKeepsResult()`: `FAILED`, the batch status unchanged, and `getResult` still returns
    the result;
  - `exhaustedLeavesDeliveredWebhookAlone()`.

## 4. Docs and verification

- [x] 4.1 Update `README.md` with webhook delivery: the request and headers, success, retries, attempt records, the
  `FAILED` outcome, and the missing signing and SSRF checks for the proof of concept.
- [x] 4.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the WI-01
  to WI-14 tests.
- [x] 4.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with
  `openspec/changes/wi-15-webhook-delivery/` as the requirements. Fix every Critical and Important finding, and verify
  the build again.
- [x] 4.4 Tick the boxes in `docs/workitems/WI-15-webhook-delivery.md` (except the developer review), set its status,
  and update its row in `docs/workitems/README.md`.

## Workflow follow-up

- The developer reviews the diff and commits WI-15 as one commit.
- Then archive `wi-15-webhook-delivery`.
