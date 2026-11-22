# Design

## Context

WI-01 added the AWS SDK BOM (2.59.6) with `software.amazon.awssdk:s3`, test-only `testcontainers-localstack`, and
`DocumentReaderProperties.S3(bucket, prefix, region, endpoint, pathStyleAccess)`. `region` defaults to `us-east-1`,
`prefix` to `files/`, `bucket` comes from `S3_BUCKET`. The `local` profile points at LocalStack on
`http://localhost:4566` with path-style access. `LocalProfileTests` checks those values, with `endpoint` as a String.
Nothing builds an S3 client yet. See `proposal.md` for why, and the specs for behaviour.

Rules that shape this:
- `client` holds contracts only; wiring goes in `config`; error translation goes in `exception`;
- exceptions live in `exception`;
- logs carry ids and statuses only, never file names.

## Goals / Non-Goals

**Goals:**
- The contract in `docs/workitems/WI-07-s3-storage-client.md`, which WI-08, WI-11 and WI-12 use.
- The four WI-01 review notes, each with a test.

**Non-Goals:**
- Creating the bucket, bucket policy or KMS key: infrastructure owns those. The README already shows the one-off
  `awslocal s3 mb` for local use.
- Streaming or multipart uploads. Files are at most 50 MB (`file.max-size`) and already in memory at intake.
- Deleting objects. Nothing deletes yet.

## Decisions

### 1. Settings

`DocumentReaderProperties.S3` becomes:
`S3(@NotBlank bucket, @NotBlank prefix, @NotBlank region, URI endpoint, boolean pathStyleAccess,
@Pattern("aws:kms|AES256") serverSideEncryption)`.

- `URI` makes Spring's binder reject a malformed endpoint at startup, naming the key.
- `server-side-encryption` defaults to `aws:kms` (`${S3_SERVER_SIDE_ENCRYPTION:aws:kms}`); the `local` profile sets
  `AES256`. It stays a String with a pattern rather than the SDK's enum, because the SDK enum's constant names
  (`AWS_KMS`) don't match the values operators write. `S3Config` maps it with `ServerSideEncryption.fromValue`.
- With `aws:kms` and no key id, S3 uses the bucket's default KMS key (or the AWS-managed `aws/s3` key). A specific key
  id can come later with the infrastructure.

### 2. `config/S3Config`

- **`S3Client` bean:** region from settings, `forcePathStyle(pathStyleAccess)`, the Apache HTTP client
  (`ApacheHttpClient.builder()`), and:
  - with `endpoint` set: `endpointOverride(endpoint)` and `StaticCredentialsProvider` with the fixed pair
    `test`/`test`, which LocalStack accepts, so `local` needs no AWS variables;
  - without: `DefaultCredentialsProvider`, which resolves lazily, so contexts that never call S3 (most tests) start
    without credentials.
- **`DocumentStorageClient` bean:** `S3DocumentStorageClient(s3Client, bucket, serverSideEncryption)`.
- *Alternative for credentials:* documenting `AWS_ACCESS_KEY_ID=test` for local use. That is one more step for every
  developer, and the WI-01 note offered fixed credentials as the first choice.

### 3. `config/S3DocumentStorageClient` (implements `client/DocumentStorageClient`)

| Method | SDK call | Translation |
|---|---|---|
| `put(key, bytes, contentType)` | `putObject` with `contentType`, `serverSideEncryption` | any `SdkException` → `StorageException("put", key, cause)` |
| `get(key)` | `getObjectAsBytes` | `NoSuchKeyException` and any other `SdkException` → `StorageException("get", key, cause)` |
| `exists(key)` | `headObject` | `NoSuchKeyException` → false; any other `SdkException` → `StorageException("exists", key, cause)` |

- It sits in `config` because `client` holds contracts only, as the work item says.
- `put` logs at `info` with the key and the size; nothing else is logged. Keys hold ids only (decision 4).
- `StorageException(operation, key, cause)` is a `RuntimeException` in `exception`, with the message
  `"S3 {operation} failed for {key}"`. It never includes bytes. `GlobalExceptionHandler` needs nothing yet: no endpoint
  calls storage until WI-11, which decides its HTTP status.

### 4. `processors/S3Keys`

A bean declared in `S3Config`, built from `s3.prefix`, which must end with `/`:
- `original(UUID sourceFileId, String ext)` → `{prefix}{id}/original.{ext}`, with `ext` lower-cased and required to
  match `[a-z0-9]{1,10}`, so a file name can never leak into a key;
- `page(UUID sourceFileId, int page)` → `{prefix}{id}/pages/%04d.png`, with `page` 1–9999
  (`IllegalArgumentException` otherwise). `file.max-pages` is 100, so four digits are plenty.

It sits in `processors` (pure computation that isn't a service), because `client` is contracts only. The work item
names `S3Keys` without a package.

### 5. Dependencies

- `implementation('software.amazon.awssdk:s3') { exclude group: 'software.amazon.awssdk', module: 'netty-nio-client' }`
  and `implementation 'software.amazon.awssdk:apache-client'`, with a comment: the service uses the synchronous client
  only, on virtual threads; Java 25 no longer pins virtual threads in `synchronized`, so Apache's blocking client suits
  them.
- LocalStack tests use `localstack/localstack:4.4`, pinned so a new image can't change behaviour under the tests.

### 6. Tests

| Test | What it covers |
|---|---|
| `config/S3DocumentStorageClientTests` (plain JUnit, `@Testcontainers`, one static LocalStack container, the client built through `S3Config`) | `putThenGetRoundTrips`, `exists` true and false, replace, `putUsesServerSideEncryption` (`headObject` shows `AES256`), `missingKeyThrowsStorageException`, a missing bucket gives `StorageException` on `put` |
| `processors/S3KeysTests` | `originalKeyUsesSourceFileId`, `pageKeyIsZeroPadded`, page 0 and 10,000 refused, an extension with a dot or slash refused, a custom prefix used |
| `config/DocumentReaderPropertiesValidationTests` (extended) | blank `s3.bucket`, `s3.prefix` and `s3.region`, a malformed `s3.endpoint` and `s3.server-side-encryption=none` each fail startup naming the key |
| `config/DocumentReaderPropertiesTests` (extended) | `server-side-encryption` defaults to `aws:kms` |
| `config/LocalProfileTests` (updated) | the endpoint is the URI `http://localhost:4566`; `local` encrypts with `AES256` |
| `config/S3ConfigTests` | with an endpoint, the client's credentials provider is static (`test`); without, it is the default chain; the HTTP client is Apache's, and Netty isn't on the classpath |

### 7. Changes after code review

- **`exists` and missing buckets:** a HEAD request has no error body, so the SDK maps every 404 to
  `NoSuchKeyException`, including a missing bucket; `exists` then answers false and the next `put` fails. Without
  `s3:ListBucket`, S3 answers a missing key with 403, which `exists` reports as a `StorageException`. The contract's
  Javadoc and the README now state this and the IAM permissions the role needs.
- **Bean wiring tested:** a test builds `S3Config`'s beans with an `ApplicationContextRunner` bound to LocalStack, and
  checks that an object lands in the configured bucket with the configured encryption and that `S3Keys` uses the
  configured prefix. The bucket and the encryption are both Strings, so a swapped argument would otherwise compile
  and pass.
- **Fail fast:** `S3DocumentStorageClient` refuses an encryption value the SDK doesn't know, and `put` refuses null
  bytes with a clear message. `s3.prefix` must end with `/`.

## Risks / Trade-offs

- [LocalStack accepts `AES256` without really encrypting] → The test proves the header is sent and recorded, which is
  the part this code owns. Real encryption is S3's job.
- [`aws:kms` without a key id uses the account default key] → Acceptable for the proof of concept; the bucket policy
  can require a specific key later.
- [Whole objects in memory] → At most 50 MB per file and about 2 MB per page.

## Migration Plan

No data to migrate. Deployments set `S3_BUCKET`, and may set `S3_SERVER_SIDE_ENCRYPTION`; credentials come from the
AWS default chain (an IAM role in production).
