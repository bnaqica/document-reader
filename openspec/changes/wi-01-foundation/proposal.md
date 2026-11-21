# Proposal

## Why

Every later work item reads its limits and run counts from one place (how many files a batch may hold, how often
vision is asked to classify or extract, how long a waiting call blocks). Without that foundation, each work item
would invent its own settings and defaults, and the values in the approved design (§17 of
`docs/design/2026-11-20-document-reader-design.md`) would drift. This is WI-01 of `docs/workitems/`, so it comes first.

## What Changes

- Add one typed settings object for the service under `app.document-reader.*`. It covers:
  - batch and file limits;
  - image conversion;
  - separation, classification and extraction run counts;
  - the OpenAI model and temperatures;
  - wait-mode timing;
  - inbox and outbox timing and retries;
  - webhook retries;
  - S3 location.

  Every value has the default from the design.
- Reject impossible settings at startup: even separation or tie-break run counts, and non-positive limits. A
  misconfigured instance fails fast instead of producing ties at run time.
- Derive the minimum successful classification runs from the run count (a majority) when it isn't set.
- Make the HTTP upload limits follow the file and batch size settings, so an oversized upload is refused before it
  is buffered.
- Read the OpenAI key, OpenAI model and S3 bucket from the environment. The application still starts in tests with
  placeholder values.
- Turn on virtual threads.
- Point the `local` profile's S3 at the shared `local-docker` LocalStack.
- Add the libraries later work items need (Spring AI with OpenAI, AWS SDK S3, PDFBox, TwelveMonkeys, Tika, a JSON
  Schema validator, Testcontainers LocalStack). Each is pinned, with a reason, where the Spring Boot BOM does not
  manage it.

No endpoints or database tables change.

## Capabilities

### New Capabilities

- `processing-configuration`: the service's processing settings, their defaults, how invalid settings are rejected
  at startup, and how upload limits follow them.

### Modified Capabilities

(none)

## Impact

- **Code:**
  - `config/DocumentReaderProperties` (new), and the main class enables it.
  - `application.yml`, `application-local.yml` and the test `config/application.yml`.
- **Build:** `build.gradle` gains the BOMs and libraries listed above, and nothing else changes.
- **Runtime:**
  - Startup now fails on invalid processing settings.
  - Requests run on virtual threads.
  - Multipart uploads are capped at 50 MB per file and 200 MB per request by default.
- **Local development:** the `local` profile expects LocalStack S3 on `localhost:4566`, with a `document-reader-local`
  bucket created there.
