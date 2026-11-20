# WI-15: Webhook delivery (process 4)

| | |
|---|---|
| **Status** | Not started |
| **Depends on** | [WI-03](WI-03-core-records.md), [WI-04](WI-04-run-and-result-records-plus-the-openai-call-log.md), [WI-05](WI-05-inbox-outbox-engine.md), [WI-10](WI-10-batch-completion.md), [WI-11](WI-11-batch-intake-api-validation-audit-and-result-query.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §15 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 15 |

**Goal:** Deliver one webhook per async batch, with retries and attempt records. Webhooks are unsigned for the proof of concept.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `client/WebhookClient`, `config/WebhookClientConfig`, `service/WebhookDeliveryService(Impl)`
- Test: `service/WebhookDeliveryServiceTests` (with a local HTTP server stub)

**Interfaces:**
- Consumes: Work items WI-03, WI-04, WI-05 and WI-10, and `BatchQueryService` (WI-11).
- Produces: `WebhookDeliveryService.deliver(UUID batchId, UUID deliveryId)`. There is no signing in the proof of concept (§15).

The service implementation also implements `InboxMessageHandler` (WI-05) for its message type: `handle` calls the step, and `onExhausted` runs the failure path. Listeners follow `listener -> @Service to CRUD @Service interface -> ...` (`.claude/rules/service-architecture.md`): the inbox listener calls `InboxProcessingService`, which calls this service, which changes data only through CRUD services.

- [ ] **Step 1: Write the failing tests.**
  - `postsBatchEnvelopeWithTwoHeaders()`: `X-Document-Reader-Batch` and `X-Document-Reader-Delivery`, and no signature header, with a body containing every document payload.
  - `2xxMarksDelivered()`
  - `500SchedulesRetry()` and `timeoutAfter10sSchedulesRetry()`
  - `redirectIsNotFollowedAndCountsAsFailure()`
  - `everyAttemptRecorded()`
  - `exhaustedMarksWebhookFailedButKeepsResult()`: `webhookStatus=FAILED`, the batch status is unchanged, and `GET` still returns the result.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** with `RestClient` built on a JDK `HttpClient` with `Redirect.NEVER` and a 10 s timeout. The body is the `BatchResultResponse` serialized with `JsonMapper`.
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
feat: add batch webhook delivery
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
