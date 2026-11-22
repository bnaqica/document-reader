# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. New files are staged as they're
created; nothing is committed. The developer commits WI-07 as one commit.

## 1. Settings

- [x] 1.1 Extend `config/DocumentReaderPropertiesValidationTests` with blank `s3.bucket`, `s3.prefix` and `s3.region`, `s3.endpoint=http://local host:4566` and `s3.server-side-encryption=none`, each failing startup naming its key; extend `config/DocumentReaderPropertiesTests` so `server-side-encryption` defaults to `aws:kms`; update `config/LocalProfileTests` so the endpoint is `URI.create("http://localhost:4566")` and `local` sets `AES256`. Verify they fail, then change `DocumentReaderProperties.S3` (design decision 1), `application.yml` (`${S3_SERVER_SIDE_ENCRYPTION:aws:kms}`) and `application-local.yml` (`AES256`), and add the README row. Verify every properties test passes.

## 2. Keys

- [x] 2.1 Write `processors/S3KeysTests`: `originalKeyUsesSourceFileId()` (`original(id, "pdf")` is `files/{id}/original.pdf`, and `"PDF"` gives the same); `pageKeyIsZeroPadded()` (`page(id, 7)` ends with `/pages/0007.png`); pages 0 and 10,000 give `IllegalArgumentException`; extensions `"tar.gz"`, `"../x"` and `""` give `IllegalArgumentException`; a prefix of `docs/` is used. Verify it fails, implement `S3Keys` (design decision 4), then verify it passes.

## 3. The client

- [x] 3.1 Write `config/S3ConfigTests` (plain JUnit): with an endpoint, the built client resolves the static `test` credentials; without one, its credentials provider is `DefaultCredentialsProvider`; `Class.forName("software.amazon.awssdk.http.nio.netty.NettyNioAsyncHttpClient")` throws `ClassNotFoundException`. Verify it fails, adjust `build.gradle` (design decision 5) and implement `S3Config`'s `S3Client` bean (design decision 2), then verify it passes.
- [x] 3.2 Write `config/S3DocumentStorageClientTests` (`@Testcontainers`, static `localstack/localstack:4.4`, a bucket created in `@BeforeAll`, the client built through `S3Config` with the container's endpoint and `AES256`):
  - `putThenGetRoundTrips()` with 3 MB of random bytes and `application/pdf`;
  - `exists` is true after `put` and false for a key never stored;
  - storing again under a key replaces the bytes;
  - `putUsesServerSideEncryption()`: `headObject` shows `AES256` and the content type;
  - `missingKeyThrowsStorageException()`: `get` of a key never stored gives `StorageException` naming the key;
  - a client for a bucket that doesn't exist gives `StorageException` on `put`.

  Verify it fails. Implement `DocumentStorageClient`, `S3DocumentStorageClient`, `StorageException` and the `DocumentStorageClient` bean (design decision 3), and delete `client/package-info.java`, then verify it passes.

## 4. Verification

- [x] 4.1 Update `README.md`: the `S3_SERVER_SIDE_ENCRYPTION` row, that `local` needs no AWS variables, and the key layout. Verify it matches the design.
- [x] 4.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the WI-01 to WI-06 tests.
- [x] 4.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with `openspec/changes/wi-07-s3-storage/` as the requirements. Fix every Critical and Important finding, and verify the build again.
- [x] 4.4 Tick the boxes in `docs/workitems/WI-07-s3-storage-client.md` (except the developer review and commit), and set its status.

## Workflow follow-up

- The developer reviews the diff and commits WI-07 as one commit.
- Then archive `wi-07-s3-storage`.
