# document-reader

Accepts multipart document uploads for lending workflows, records each request in PostgreSQL and stores the file in S3 under the request's UUID. Using Spring AI with OpenAI vision, it classifies each document by voting over a configurable number of classification calls (lender document types, extendable by the model), then extracts the document's information and a summary by consolidating several extraction passes into a final determination. Processing runs asynchronously through the inbox/outbox pattern on PostgreSQL.

| | |
|---|---|
| Port | 8084 |
| Stack | Java 25, Spring Boot 4.1, Spring AI (OpenAI), AWS S3, Gradle 9.9 (wrapper), PostgreSQL, Flyway, JUnit, Testcontainers, Spring Security (JWT), springdoc, Spotless, JaCoCo, Lombok, SLF4J (Logback), Jib |

## Prerequisites

- A JDK 17+ to run Gradle. The Java 25 toolchain is downloaded automatically by Gradle (foojay resolver)
  if you don't have it.
- In your IDE, the Lombok plugin with annotation processing enabled.
- For the feature workflow in Claude Code: Node.js 22.12+ and OpenSpec (`npm install -g @fission-ai/openspec@latest`).
- Docker running, for the local database and the Testcontainers tests.

## Run locally

```bash
./gradlew bootRun
```

This uses the `local` profile, which turns off authentication. Swagger UI is at
<http://localhost:8084/swagger-ui.html>.

The `local` profile still needs `OPENAI_API_KEY` (see below). It stores files in the LocalStack S3 from the sibling
`local-docker` repo, at `http://localhost:4566`. Start that LocalStack, then create the bucket once:

```bash
awslocal s3 mb s3://document-reader-local
```

## Environment variables

| Variable | Purpose | In the `local` profile |
|---|---|---|
| `DB_URL` | JDBC URL of the database | Not needed (`compose.yaml`) |
| `DB_USERNAME` | Database user | Not needed |
| `DB_PASSWORD` | Database password | Not needed |
| `JWT_ISSUER_URI` | Issuer whose JWTs this service accepts | Not needed (authentication is off) |
| `OPENAI_API_KEY` | OpenAI API key, used for vision calls | **Needed** |
| `OPENAI_MODEL` | OpenAI vision model; defaults to `gpt-4o` | Optional |
| `S3_BUCKET` | S3 bucket for uploaded files and page images | Not needed (`document-reader-local`) |

Processing settings (limits, run counts, timeouts and retries) are under `app.document-reader` in
`src/main/resources/application.yml`, with their defaults. The service refuses to start if one can't work, such as an
even vote count or a zero limit.

## Reference data API

Every call needs a bearer token. Reads are open to any valid token. Creating, changing or removing reference data
needs the Keycloak realm role `admin`; without it the call returns 403. In the `local` profile, every request acts
as an admin.

| Method and path | What it does | Needs `admin` |
|---|---|---|
| `GET /loan-purposes` | Lists active loan purposes, by code | No |
| `GET /loan-purposes/{code}` | One loan purpose, active or not | No |
| `POST /loan-purposes` | Adds one: `{code, name, description}` | Yes |
| `PUT /loan-purposes/{code}` | Replaces `{name, description, active}` | Yes |
| `DELETE /loan-purposes/{code}` | Deactivates it (batches keep referring to the code) | Yes |
| `GET /document-types` | Lists document types, by name | No |
| `GET /document-types/{id}` | One document type | No |
| `POST /document-types` | Adds one: `{name, description, dataSchema}` | Yes |
| `PUT /document-types/{id}` | Replaces `{name, description, dataSchema}`; a changed schema raises its version | Yes |
| `DELETE /document-types/{id}` | Removes it and its aliases | Yes |
| `GET /document-types/{id}/aliases` | Lists a type's other names | No |
| `POST /document-types/{id}/aliases` | Adds one: `{alias}` | Yes |
| `DELETE /document-types/{id}/aliases/{aliasId}` | Removes one | Yes |

Names are compared by a key that ignores case, punctuation and plurals, so "Pay stubs", "Paystub" and "pay-stub" are
the same name. A name or alias whose key is already taken gets 409, naming the type that owns it. A `dataSchema` must
be strict JSON Schema: every object lists all its properties as required (allowing `null` for absent values) and
sets `"additionalProperties": false`.

## Processing records API

Read-only views of what the pipeline has stored. A caller sees only the batches they sent (by token subject);
admins see every batch. A record of someone else's batch returns 404, the same as one that doesn't exist. Any method
other than GET returns 405, for admins too: only the pipeline creates or changes records.

| Path | Returns |
|---|---|
| `GET /records/batches?limit=50` | The caller's batches, newest first; `limit` is 1–200 |
| `GET /records/batches/{id}` | One batch |
| `GET /records/batches/{id}/source-files` | The batch's uploaded files, in upload order |
| `GET /records/batches/{id}/documents` | The batch's documents, by file then page order |
| `GET /records/source-files/{id}` | One uploaded file |
| `GET /records/source-files/{id}/pages` | The file's rendered pages, in page order |
| `GET /records/documents/{id}` | One document |

## Test and format

```bash
./gradlew build
```

The build runs the tests, writes a coverage report to `build/reports/jacoco/test/html/index.html`, and fails if
the code isn't formatted. To format it:

```bash
./gradlew spotlessApply
```

## Build a container (Jib)

```bash
./gradlew jibDockerBuild
```

To push to a registry instead:

```bash
IMAGE_REGISTRY=ghcr.io/my-org ./gradlew jib
```

## Contributing

Package layout and conventions are in `CLAUDE.md` and `.claude/rules/`.
