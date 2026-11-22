# Spec Delta

## ADDED Requirements

### Requirement: Storage settings are checked at startup
`s3.bucket`, `s3.prefix` and `s3.region` SHALL NOT be blank, `s3.endpoint` SHALL be a valid URI when set, and
`s3.server-side-encryption` SHALL be `aws:kms` (the default) or `AES256`. A setting that breaks any of these SHALL stop
the application from starting, naming the setting.

#### Scenario: Blank bucket
- **WHEN** `S3_BUCKET` is set to an empty string
- **THEN** the application fails to start, naming `app.document-reader.s3.bucket`

#### Scenario: Malformed endpoint
- **WHEN** `app.document-reader.s3.endpoint` is `http://local host:4566`
- **THEN** the application fails to start, naming the setting

#### Scenario: Unknown encryption
- **WHEN** `app.document-reader.s3.server-side-encryption` is `none`
- **THEN** the application fails to start, naming the setting

#### Scenario: Default encryption
- **WHEN** the application starts without the `local` profile
- **THEN** objects are encrypted with `aws:kms`

### Requirement: The local profile needs no AWS credentials
When an S3 endpoint override is set, as in the `local` profile, the service SHALL use fixed placeholder credentials
that LocalStack accepts, so no AWS variables are needed. The `local` profile SHALL encrypt with `AES256`, because
LocalStack doesn't provide KMS. Without an override, credentials SHALL come from the AWS default provider chain.

#### Scenario: Local profile
- **WHEN** the service starts with the `local` profile and no AWS variables
- **THEN** it can store objects in LocalStack, encrypted with `AES256`
