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
