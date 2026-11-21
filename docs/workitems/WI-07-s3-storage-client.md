# WI-07: S3 storage client

| | |
|---|---|
| **Status** | Not started |
| **Depends on** | [WI-01](WI-01-foundation.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §8, §17 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Task 8 |

**Goal:** Add the S3 storage contract and implementation, with encrypted objects and the `files/{id}/…` key layout.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

**Files:**
- Create: `client/DocumentStorageClient`, `config/S3Config`, `config/S3DocumentStorageClient`, `exception/StorageException`
- Test: `config/S3DocumentStorageClientTests` (Testcontainers LocalStack)

**Interfaces:**
- Produces: `DocumentStorageClient`:
  - `void put(String key, byte[] bytes, String contentType)`
  - `byte[] get(String key)`
  - `boolean exists(String key)`
  - Key helpers on `S3Keys`: `original(UUID sourceFileId, String ext)` returns `files/{id}/original.{ext}`, and `page(UUID sourceFileId, int page)` returns `files/{id}/pages/{nnnn}.png`.
  - Every SDK error is translated to `StorageException`.

- [ ] **Step 1: Write the failing tests.**
  - `putThenGetRoundTrips()`
  - `putUsesServerSideEncryption()`: the head-object response shows SSE is set.
  - `pageKeyIsZeroPadded()`: `page(id, 7)` ends with `/pages/0007.png`.
  - `originalKeyUsesSourceFileId()`: `original(id, "pdf")` equals `files/{id}/original.pdf`.
  - `missingKeyThrowsStorageException()`
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Implement** with the AWS SDK v2 `S3Client`, an endpoint override from configuration for LocalStack, and `ServerSideEncryption.AWS_KMS`. In the local profile, where LocalStack lacks KMS, use `AES256`.
- [ ] **Step 4: Run** the tests and the build. Expected: PASS.

## Notes from the WI-01 review

The WI-01 code review raised these. Handle each in this work item, test-first.

- The `local` profile sets an S3 endpoint but no credentials, and the AWS SDK's default credentials provider fails without `AWS_ACCESS_KEY_ID` and `AWS_SECRET_ACCESS_KEY`. Use static dummy credentials when `app.document-reader.s3.endpoint` is set, or document the LocalStack values in the README.
- `software.amazon.awssdk:s3` brings in both `apache-client` and `netty-nio-client`. Pick one HTTP client explicitly and exclude the other.
- `S3.endpoint` is a `String`. Consider making it a `URI`, so a malformed URL fails at startup.
- `S3.bucket`, `S3.region` and `S3.prefix` aren't checked for blanks. An empty `S3_BUCKET` (for example, a missing Kubernetes secret key) binds `""`, and the service still starts. Add `@NotBlank`, with a test.

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
feat: add S3 document storage client
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
