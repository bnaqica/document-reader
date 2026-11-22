# document-storage Specification

## Purpose
Defines how the service stores and reads the bytes of uploaded files and rendered pages in S3: where each object
goes, how it is encrypted, and how a failure is reported.

## Requirements

### Requirement: Objects round-trip unchanged
Storing bytes under a key with a content type, then reading that key, SHALL return exactly the stored bytes. Whether
a key exists SHALL be answerable without reading the object. Storing under an existing key SHALL replace the object.

#### Scenario: Round trip
- **WHEN** 3 MB of PDF bytes are stored under a key with content type `application/pdf`
- **THEN** reading that key returns the same bytes, and the key exists

#### Scenario: Replace
- **GIVEN** a key holding page bytes
- **WHEN** different bytes are stored under the same key
- **THEN** reading it returns the new bytes

#### Scenario: Missing key
- **WHEN** whether a key that was never stored exists is asked
- **THEN** the answer is no

### Requirement: Files and pages follow one key layout
An original file SHALL be stored at `{prefix}{sourceFileId}/original.{ext}`, and page *n* of a file at
`{prefix}{sourceFileId}/pages/{nnnn}.png`, with *n* zero-padded to four digits. The prefix SHALL be
`s3.prefix`, `files/` by default. Keys SHALL hold only ids, numbers and the extension, never a file name.

#### Scenario: Original
- **WHEN** the key for source file `3f2…` with extension `pdf` is built
- **THEN** it is `files/3f2…/original.pdf`

#### Scenario: Page
- **WHEN** the key for page 7 of source file `3f2…` is built
- **THEN** it ends with `/pages/0007.png`

#### Scenario: Page out of range
- **WHEN** the key for page 0, or page 10,000, is built
- **THEN** it is refused

### Requirement: Every object is encrypted at rest
Every object SHALL be stored with server-side encryption: `aws:kms` by default, or `AES256` when
`s3.server-side-encryption` says so. Reading the object's metadata SHALL show the encryption used.

#### Scenario: Encryption recorded
- **GIVEN** `s3.server-side-encryption` is `AES256`
- **WHEN** an object is stored
- **THEN** its metadata shows `AES256` server-side encryption

### Requirement: Storage failures surface as one error
Any failure to store, read or check an object, including reading a key that doesn't exist, SHALL be reported as a
storage error naming the key and the operation, never as an SDK exception. The error SHALL NOT include the object's
bytes.

#### Scenario: Reading a missing key
- **WHEN** a key that was never stored is read
- **THEN** a storage error is raised, naming the key

#### Scenario: Bucket unavailable
- **GIVEN** the configured bucket doesn't exist
- **WHEN** an object is stored
- **THEN** a storage error is raised, naming the key
