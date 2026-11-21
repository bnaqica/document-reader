# Spec Delta

## Purpose

Defines the settings that govern how the document reader processes batches: limits, run counts, timing and where
files are stored. It also defines the defaults those settings take, and how the service rejects settings that cannot
work.

## ADDED Requirements

### Requirement: Batch and file limits have defaults
The service SHALL default to at most 20 files and 200 MB per batch, and at most 50 MB and 100 pages per file, when
these limits are not configured.

#### Scenario: No limits configured
- **WHEN** the service starts with no batch or file limits configured
- **THEN** the batch limits are 20 files and 200 MB
- **AND** the file limits are 50 MB and 100 pages

#### Scenario: Limit overridden
- **WHEN** the service starts with the maximum files per batch configured as 5
- **THEN** the maximum files per batch is 5
- **AND** every other limit keeps its default

### Requirement: Image conversion has defaults
The service SHALL default to rendering pages at 200 DPI and to a longest image side of 2048 pixels.

#### Scenario: No conversion settings configured
- **WHEN** the service starts with no conversion settings configured
- **THEN** pages are rendered at 200 DPI
- **AND** the longest side of a page image is at most 2048 pixels

### Requirement: Separation, classification and extraction run counts have defaults
The service SHALL default to 3 separation runs in windows of 15 pages overlapping by 2. It SHALL default to 5
classification runs, 3 tie-break runs, 3 classification pages and 5 parallel classification calls, and to 3
extraction passes over at most 20 pages.

#### Scenario: No run counts configured
- **WHEN** the service starts with no run counts configured
- **THEN** separation uses 3 runs, a window of 15 pages and an overlap of 2
- **AND** classification uses 5 runs, 3 tie-break runs, the first 3 pages and up to 5 parallel calls
- **AND** extraction uses 3 passes over at most 20 pages

### Requirement: Minimum successful classification runs defaults to a majority
When the minimum number of successful classification runs is not configured, the service SHALL use a majority of
the configured classification runs.

#### Scenario: Default of five runs
- **WHEN** classification runs is 5 and the minimum successful runs is not configured
- **THEN** the minimum successful runs is 3

#### Scenario: Seven runs
- **WHEN** classification runs is 7 and the minimum successful runs is not configured
- **THEN** the minimum successful runs is 4

#### Scenario: Explicit minimum
- **WHEN** classification runs is 5 and the minimum successful runs is configured as 5
- **THEN** the minimum successful runs is 5

### Requirement: Voting run counts must be odd
The service SHALL refuse to start when the separation run count or the tie-break run count is even, so that a
majority always exists.

#### Scenario: Even separation runs
- **WHEN** the service starts with separation runs configured as 4
- **THEN** startup fails with a message naming the separation run setting

#### Scenario: Even tie-break runs
- **WHEN** the service starts with tie-break runs configured as 4
- **THEN** startup fails with a message naming the tie-break run setting

#### Scenario: Odd run counts
- **WHEN** the service starts with separation runs 5 and tie-break runs 3
- **THEN** startup succeeds

### Requirement: Numeric settings must be positive
The service SHALL refuse to start when any limit, run count, pass count, page count, attempt count or duration is
zero or negative. It SHALL also refuse to start when the minimum successful runs exceeds the classification runs.

#### Scenario: Zero extraction passes
- **WHEN** the service starts with extraction passes configured as 0
- **THEN** startup fails with a message naming the extraction pass setting

#### Scenario: Minimum above total
- **WHEN** the service starts with classification runs 5 and minimum successful runs 6
- **THEN** startup fails with a message naming the minimum successful runs setting

### Requirement: Timing and retry settings have defaults
The service SHALL default to:
- a 300-second wait for a batch, re-checked every 2 seconds;
- a 1-second outbox poll;
- 5 inbox attempts, with backoff starting at 10 seconds and capped at 10 minutes, and a 10-minute lease;
- 8 webhook attempts with a 10-second timeout.

#### Scenario: No timing configured
- **WHEN** the service starts with no timing or retry settings configured
- **THEN** a waiting call waits up to 300 seconds and re-checks every 2 seconds
- **AND** the outbox is polled every second
- **AND** inbox messages get 5 attempts, a backoff from 10 seconds to 10 minutes, and a 10-minute lease
- **AND** webhooks get 8 attempts with a 10-second timeout

### Requirement: Upload size limits follow the file and batch limits
The service SHALL refuse an HTTP upload whose single file exceeds the file size limit, or whose whole request exceeds
the batch size limit, before processing it.

#### Scenario: Default limits
- **WHEN** the service starts with default limits
- **THEN** a multipart upload may carry files of at most 50 MB and a request of at most 200 MB

#### Scenario: Changed file limit
- **WHEN** the file size limit is configured as 10 MB
- **THEN** a multipart upload with a 10.5 MB file is refused

### Requirement: Credentials and locations come from the environment
The service SHALL read the OpenAI API key, the OpenAI model and the S3 bucket from environment variables. Files
SHALL be stored under the `files/` prefix by default.

#### Scenario: Values supplied by the environment
- **WHEN** `OPENAI_API_KEY`, `OPENAI_MODEL` and `S3_BUCKET` are set
- **THEN** the service uses those values for OpenAI and S3

#### Scenario: Tests without real credentials
- **WHEN** the application starts under the test configuration with no real OpenAI key or bucket
- **THEN** startup succeeds using placeholder values, and no external service is contacted

### Requirement: Local development uses LocalStack for S3
Under the `local` profile, the service SHALL address S3 at the LocalStack endpoint `http://localhost:4566` with
path-style access, using the bucket `document-reader-local`.

#### Scenario: Local profile
- **WHEN** the service starts with the `local` profile
- **THEN** its S3 endpoint is `http://localhost:4566`, path-style access is on, and the bucket is `document-reader-local`

#### Scenario: Other profiles
- **WHEN** the service starts without the `local` profile
- **THEN** no S3 endpoint override is set, and the AWS default endpoint for the region is used

### Requirement: Requests run on virtual threads
The service SHALL run request handling on virtual threads, so waiting calls do not hold platform threads.

#### Scenario: Request thread
- **WHEN** an HTTP request is handled
- **THEN** it runs on a virtual thread
