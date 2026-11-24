# batch-intake Specification

## Purpose

Defines `POST /batches`: what a caller sends, how the whole batch is validated before anything is stored, the order
in which a valid batch is stored and queued for separation, and how the caller is answered in wait mode and in
webhook mode.

## Requirements

### Requirement: The request's fields are checked first
`POST /batches` SHALL take repeated `files`, `loanPurpose`, `async` (default false) and `webhookUrl` as multipart
parts. It SHALL check the fields before any file, and answer the first failure with a 400 problem whose detail is
that failure's message. The checks run in this order: the loan purpose must be an active code, there must be 1 to
`batch.max-files` files, `async` must be `true` or `false` (any case), and a non-blank `webhookUrl` must be given
exactly when `async` is true.

#### Scenario: Missing loan purpose
- **WHEN** a request has no `loanPurpose`
- **THEN** the answer is 400, and its detail lists every active loan purpose code

#### Scenario: Unknown loan purpose
- **WHEN** a request has `loanPurpose` `SPACESHIP_PURCHASE`
- **THEN** the answer is 400, and its detail lists every active loan purpose code

#### Scenario: No files
- **WHEN** a request has a valid loan purpose and no files
- **THEN** the answer is 400

#### Scenario: Too many files
- **WHEN** a request has 21 files and `batch.max-files` is 20
- **THEN** the answer is 400

#### Scenario: Async that isn't a boolean
- **WHEN** a request has `async` `maybe`
- **THEN** the answer is 400 with the detail "async must be true or false"

#### Scenario: Async without a webhook URL
- **WHEN** a request has `async` true and no `webhookUrl`
- **THEN** the answer is 400 with the detail "webhookUrl is required when async is true"

#### Scenario: Webhook URL without async
- **WHEN** a request has a `webhookUrl` and `async` false or missing
- **THEN** the answer is 400 with the detail "webhookUrl is only used when async is true"

#### Scenario: Empty webhook URL without async
- **WHEN** a sync request sends `webhookUrl` as an empty value
- **THEN** the field is treated as missing, and the batch is accepted

### Requirement: A webhook URL is an absolute http or https URL
A `webhookUrl` SHALL be accepted when it is an absolute URL with the `http` or `https` scheme and a host, and SHALL
otherwise get a 400 with the detail "webhookUrl must be an absolute http or https URL". No other check SHALL be
made: no DNS lookup, and no address or port restriction (proof of concept, §22).

#### Scenario: Relative URL
- **WHEN** an async request has `webhookUrl` `/hooks/done`
- **THEN** the answer is 400 with the detail "webhookUrl must be an absolute http or https URL"

#### Scenario: FTP URL
- **WHEN** an async request has `webhookUrl` `ftp://example.com/hook`
- **THEN** the answer is 400 with the detail "webhookUrl must be an absolute http or https URL"

#### Scenario: HTTP and HTTPS URLs
- **WHEN** an async request has `webhookUrl` `https://example.com/hook` or `http://localhost:9000/hook`
- **THEN** the URL is accepted

### Requirement: A batch over the total size limit is refused
When the request's fields pass, the files' combined size SHALL be checked against `batch.max-total-size` before any
file is read. A batch over the limit SHALL get a 413 problem.

#### Scenario: Batch over 200 MB
- **WHEN** a batch's files add up to more than 200 MB and `batch.max-total-size` is 200 MB
- **THEN** the answer is 413

### Requirement: Every file is checked before anything is stored
Each file SHALL be checked in this order, stopping at its first failure: its type from its content (415), its size
against `file.max-size` (413), and whether it can be read and has at most `file.max-pages` pages (422). Every file
SHALL be checked. If any fails, the answer SHALL be a problem listing every failing file in an `errors` array, each
entry with its `index`, `fileName`, `status` and `detail`, and nothing SHALL be stored in Postgres or S3.

#### Scenario: Unsupported type
- **WHEN** file 1 of a batch is a Word document named `notes.docx`
- **THEN** the answer is 415, and `errors` holds one entry with index 1 and file name `notes.docx`

#### Scenario: File over 50 MB
- **WHEN** a file is larger than `file.max-size` of 50 MB
- **THEN** the answer is 413, and `errors` names the file

#### Scenario: File over 100 pages
- **WHEN** a PDF has 101 pages and `file.max-pages` is 100
- **THEN** the answer is 422, and `errors` names the file

#### Scenario: One encrypted PDF among valid files
- **GIVEN** a batch of three files, whose file 2 is a password-protected PDF
- **WHEN** the batch is sent
- **THEN** the answer is 422, and `errors` names only file 2
- **AND** no batch, source file or outbox message is created, and nothing is put to S3

### Requirement: Several failing files give one status
When files fail with different statuses, the response status SHALL be the first of 415, 413 and 422 that any file
failed with. Every failing file SHALL still be listed in `errors` in index order, each with its own status.

#### Scenario: A type failure and a page failure
- **GIVEN** file 0 has 101 pages and file 1 is a Word document
- **WHEN** the batch is sent
- **THEN** the answer is 415
- **AND** `errors` lists file 0 with status 422, then file 1 with status 415

### Requirement: A valid batch is recorded before its files are stored
A valid batch SHALL first be committed with its source files, all `RECEIVED`. The batch SHALL record the loan
purpose, `async`, the webhook URL, the file count and the caller's token subject. Each file SHALL record its upload
index, its name, its type as detected from its content, its size, its SHA-256 and its page count. Only then SHALL each
original be put to S3.

#### Scenario: Recorded before any upload
- **WHEN** a valid batch of three files is sent
- **THEN** the batch and its three files exist as `RECEIVED` before the first object is put to S3

#### Scenario: Detected type is stored
- **WHEN** a PNG is uploaded with the declared type `application/octet-stream`
- **THEN** its source file records the type `image/png`

### Requirement: Stored files are queued for separation together
After every original is in S3, one transaction SHALL mark each file `STORED` with its bucket and key, set the batch
to `PROCESSING`, and queue one `SEPARATE_FILE` message per file. Each original SHALL be stored under
`{prefix}{fileId}/original.{ext}`, with the extension taken from the detected type.

#### Scenario: Three files queued
- **WHEN** a valid batch of three files has been stored
- **THEN** every file is `STORED` with its S3 bucket and key, and the batch is `PROCESSING`
- **AND** there are exactly three `SEPARATE_FILE` outbox messages, one for each file

#### Scenario: Key from the detected type
- **WHEN** a JPEG named `scan.final.JPEG` is stored
- **THEN** its key ends in `/original.jpg`

### Requirement: A storage failure fails the batch
If putting an original to S3 fails, every file of the batch and then the batch itself SHALL be failed with the reason
"Storing the files failed", and no `SEPARATE_FILE` message SHALL be queued. The caller SHALL be answered through the
batch's usual channel, as for any other final status.

#### Scenario: S3 is down
- **WHEN** a valid sync batch is sent while S3 refuses every upload
- **THEN** the batch and each of its files are `FAILED` with the reason "Storing the files failed"
- **AND** no `SEPARATE_FILE` message exists
- **AND** the answer is 200 with the result envelope, whose status is `FAILED`

### Requirement: An async batch is answered at once
An async batch SHALL be answered with 202 and `{ batchId, status, statusUrl }` as soon as it is queued, where
`statusUrl` is the absolute URL of `GET /batches/{batchId}`.

#### Scenario: Async batch
- **WHEN** a valid batch is sent with `async` true and a webhook URL
- **THEN** the answer is 202 with the batch id, status `PROCESSING` and the batch's status URL

### Requirement: A sync batch waits for its result
A sync batch SHALL wait for a final status for up to `wait.timeout`, with nothing in Postgres locked while it waits.
If the batch finishes in time, the answer SHALL be 200 with the result envelope. Otherwise it SHALL be 202 with
`{ batchId, status, statusUrl }`, and processing SHALL carry on.

#### Scenario: Finished in time
- **WHEN** a sync batch completes before `wait.timeout`
- **THEN** the answer is 200 with the batch's result envelope

#### Scenario: Timed out
- **WHEN** a sync batch is still processing when `wait.timeout` passes
- **THEN** the answer is 202 with the batch id, status `PROCESSING` and the batch's status URL
