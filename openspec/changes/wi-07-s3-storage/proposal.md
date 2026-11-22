# Proposal

## Why

Intake (WI-11) stores each uploaded file in S3 under its source file's UUID, and separation (WI-12) stores each
rendered page next to it, so every later step reads the same pages (§8 and §16 of
`docs/design/2026-11-20-document-reader-design.md`). Nothing talks to S3 yet. This is WI-07 of `docs/workitems/`. It
needs only WI-01, and WI-08, WI-11 and WI-12 build on it. It also closes four points the WI-01 review left for this
work item: local credentials, one HTTP client, a typed endpoint, and blank S3 settings.

## What Changes

- **Storage contract:** `DocumentStorageClient` with `put`, `get` and `exists`. Every S3 failure, including a missing
  key, becomes one `StorageException`.
- **Key layout:** `S3Keys` builds `files/{sourceFileId}/original.{ext}` and `files/{sourceFileId}/pages/{nnnn}.png`,
  from the configured prefix.
- **Encryption:** every object is written with server-side encryption: SSE-KMS (`aws:kms`) by default, `AES256` in the
  `local` profile, because LocalStack doesn't do KMS.
- **Settings:**
  - a new `s3.server-side-encryption` setting (`aws:kms` or `AES256`);
  - `s3.endpoint` becomes a URI, so a malformed one fails at startup;
  - `s3.bucket`, `s3.prefix` and `s3.region` must not be blank;
  - with an endpoint override (LocalStack), fixed dummy credentials are used, so `local` needs no AWS variables.
- **Dependencies:** the S3 SDK uses its Apache HTTP client only; the Netty client it also pulls in is excluded.

## Capabilities

### New Capabilities

- `document-storage`: storing and reading file and page bytes in S3: the key layout, encryption, and how failures
  surface.

### Modified Capabilities

- `processing-configuration`: adds that storage settings are checked at startup and the encryption default, and
  that the `local` profile needs no AWS credentials. Written as ADDED; the existing LocalStack requirement still holds.

## Impact

- **Code:** `client/DocumentStorageClient`, `config/S3Config`, `config/S3DocumentStorageClient`,
  `processors/S3Keys`, `exception/StorageException`; `DocumentReaderProperties.S3` gains validation, a `URI`
  endpoint and the encryption setting.
- **Configuration:** `S3_SERVER_SIDE_ENCRYPTION` (optional, default `aws:kms`); the `local` profile sets `AES256`.
  README rows.
- **Build:** `netty-nio-client` excluded from `software.amazon.awssdk:s3`; `apache-client` declared.
- **Tests:** a LocalStack Testcontainer, pinned to a version, for the storage tests only.
- **Later work items:** WI-08 (conversion), WI-11 (intake) and WI-12 (separation) store and read through this
  contract.
