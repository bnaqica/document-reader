# api-audit Specification

## Purpose

Defines what the audit trail (`audit.api_audit`) records for a request. This covers what a multipart upload's
audited payload holds, and the rule that every audited request gets a row, even one refused before its body was
read.

## Requirements

### Requirement: A multipart request's audit payload holds metadata, never file bytes
For a multipart request, the audited payload SHALL be a JSON object holding each form field's value and, under
`files`, one entry per uploaded file with its `name`, declared `contentType`, `size` in bytes and `sha256`. It SHALL
never hold any file's content.

#### Scenario: Batch upload
- **WHEN** a caller sends `POST /batches` with `loanPurpose` `CAR_REFINANCE` and two files
- **THEN** the audit row's payload holds `loanPurpose` `CAR_REFINANCE` and each file's name, type, size and SHA-256
- **AND** it holds none of either file's bytes

#### Scenario: Refused batch upload
- **WHEN** a batch upload is refused with 415 for an unsupported file
- **THEN** the audit row records status 415, `success` false, and the same metadata payload

### Requirement: An upload refused for its size is still audited
A multipart request refused because a file or the whole request is over the upload limits SHALL still get an audit
row, with the response status 413 and `success` false. Its payload SHALL be null, because the upload was never read.

#### Scenario: File over the upload limit
- **WHEN** a caller uploads a file over `file.max-size`
- **THEN** an audit row records the path, the method, the caller, status 413 and `success` false
