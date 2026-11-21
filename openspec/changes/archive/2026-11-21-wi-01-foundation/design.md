# Design

## Context

The service is a freshly scaffolded Spring Boot 4.1.2 / Java 25 application. It has no processing settings yet:
`application.yml` holds only the datasource, Flyway, JWT issuer, actuator and springdoc. The approved design
(`docs/design/2026-11-20-document-reader-design.md` §17) lists every setting and its default, and WI-01
(`docs/workitems/WI-01-foundation.md`) owns them. See `proposal.md` for why this comes first, and
`specs/processing-configuration/spec.md` for the required behaviour.

Local infrastructure lives in the sibling `local-docker` repo. Its LocalStack container publishes ports 4565–4576
(the S3 edge port is 4566) and creates resources from `create-resources.sh`.

## Goals / Non-Goals

**Goals:**
- One immutable, validated settings object that every later work item injects, instead of scattered `@Value`s.
- Fail at startup, not at run time, for settings that cannot work.
- Add every library the later work items need now, so their diffs contain behaviour only.

**Non-Goals:**
- Using any of the new libraries. There is no S3 client, conversion or OpenAI code yet; those belong to WI-07,
  WI-08 and WI-09.
- Creating the LocalStack bucket, or changing the `local-docker` repo. The README documents the one command.
- Upgrading Spring Boot.

## Decisions

### 1. One `@ConfigurationProperties` record tree, `DocumentReaderProperties`, bound to `app.document-reader`

Nested records (`Batch`, `File`, `Conversion`, `Separation`, `Classification`, `Extraction`, `OpenAi`, `Wait`,
`Outbox`, `Inbox`, `Webhook`, `S3`) match the plan's interface exactly.
- Defaults are written in `application.yml`, not in Java, so one file shows every value and ops can see what an
  environment overrides.
- Sizes use `DataSize` and times use `Duration`.
- *Alternatives:* `@DefaultValue` on record components keeps the defaults in code, where they're invisible to anyone
  reading config, so it was rejected. Separate properties classes per area would mean twelve beans to inject and twelve
  prefixes to keep consistent, so that was rejected too.
- `@ConfigurationPropertiesScan` on the main class registers the record.

### 2. Validation with Jakarta constraints, plus `@AssertTrue` checks for rules that span fields

- `@Validated` on the record, and `@Valid` on each nested record so validation cascades.
- `@Positive` on counts. Durations and sizes are checked through `@AssertTrue` methods, because Jakarta has no
  positive-`Duration` constraint.
- "Run count is odd" and "minimum successful runs ≤ runs" are `@AssertTrue` methods named after the setting, such as
  `isRunsOdd()` on `Separation`. Spring's bind-validation failure then names the property path, which satisfies the
  spec's "message naming the setting" scenarios.
- Spring reports each failure under its Java path, in camelCase (`batch.maxFiles`), which it also accepts as the
  setting's key. A check method appears as the setting plus a suffix (`separation.runsOdd`). The exception is the
  `wait` record, whose component is `waitSettings`, so its messages name the real keys instead
  (`app.document-reader.wait.timeout must be positive`). A table-driven test checks that every positive and odd setting
  fails startup with its key.
- *Alternative:* a custom `@Odd` constraint annotation would be reusable, but there are only two uses. Under YAGNI, two
  small methods win.

### 3. Minimum successful runs is derived when absent

`Classification.minSuccessfulRuns` is a nullable `Integer` in the binding. The record's compact constructor replaces a
null with `runs / 2 + 1`, so the accessor never returns null. It stays a boxed `Integer`, because a record accessor
must match its component's type; callers can unbox it safely. The default isn't written in
YAML, because it depends on `runs`.

### 4. Multipart limits reference the same settings

`spring.servlet.multipart.max-file-size: ${app.document-reader.file.max-size}` and
`max-request-size: ${app.document-reader.batch.max-total-size}`. Overriding either `app.document-reader` value then
moves the HTTP limit with it, and the two can never disagree.

### 5. Environment variables

| Setting | Variable | Default |
|---|---|---|
| `spring.ai.openai.api-key` | `OPENAI_API_KEY` | required outside tests |
| `app.document-reader.open-ai.model` | `OPENAI_MODEL` | `gpt-4o`, chosen as a known vision-capable model; easy to change per environment |
| `app.document-reader.s3.bucket` | `S3_BUCKET` | required outside `local` and tests |

The test `config/application.yml` sets placeholders (`test-key` and `test-bucket`). Nothing contacts OpenAI or S3 in
WI-01, because no client exists yet.

The OpenAI auto-configuration stays on with the placeholder key; it doesn't call the API at startup. Spring AI does
need some key to start, which is why the test overlay sets `OPENAI_API_KEY`. *Alternative:* excluding the
auto-configuration until WI-09 would hide wiring problems until later, so it was rejected.

### 6. S3 settings gain `region`, `endpoint` and `pathStyleAccess`

The plan's `S3(bucket, prefix)` can't reach LocalStack, so the record adds:
- `region`, default `us-east-1`;
- `endpoint`, nullable, meaning the AWS default;
- `pathStyleAccess`, default `false`.

The `local` profile sets `endpoint: http://localhost:4566`, `path-style-access: true` and
`bucket: document-reader-local`.
- *Why local-docker's LocalStack and not a `compose.yaml` service:* Spring Boot's Docker Compose support only passes
  connection details for services it recognises, and it doesn't hand a LocalStack port to our own S3 settings. A fixed
  4566 mapping in this repo would also clash with `local-docker`'s 4565–4576 range. The README says to run
  `awslocal s3 mb s3://document-reader-local` once.

### 7. Virtual threads

`spring.threads.virtual.enabled: true` in `application.yml`. A test asserts a request runs on a virtual thread, using
a test-only controller in the test sources so no production endpoint is added.

### 8. Libraries and versions

| Library | Version | Why pinned |
|---|---|---|
| Spring AI BOM → `spring-ai-starter-model-openai` | 2.0.3 | Not in Boot's BOM. 2.0.x is the line built against Boot 4.1; 2.1 targets Boot 4.2 |
| AWS SDK v2 BOM → `s3` | 2.59.6 | Not in Boot's BOM |
| `org.apache.pdfbox:pdfbox` | 3.0.8 | Not in Boot's BOM |
| `com.twelvemonkeys.imageio:imageio-tiff`, `imageio-webp` | 3.15.3 | Not in Boot's BOM |
| `org.apache.tika:tika-core` | 4.2.0 | Not in Boot's BOM |
| `com.networknt:json-schema-validator` | 3.0.9 | Not in Boot's BOM. 3.x is built on Jackson 3 (`tools.jackson`), which matches Boot 4 |
| `org.testcontainers:testcontainers-localstack` | Boot BOM | Managed, so it isn't pinned |

BOMs are imported through the `io.spring.dependency-management` plugin's `mavenBom`, as the build already uses that
plugin.

## Risks / Trade-offs

- [Spring AI 2.0.3 was built against Boot 4.1.3, and we are on 4.1.2] → It is the same minor line. The full build and
  the context test catch any incompatibility. Upgrading Boot is a separate change.
- [Unused libraries enlarge the image and the dependency surface before they're needed] → This is accepted, so later
  work items stay behaviour-only. Dependabot tracks them all.
- [`gpt-4o` may be superseded] → It's only a default, and `OPENAI_MODEL` overrides it per environment.
- [Startup now fails on bad settings] → That's intended. The failure names the setting.

## Migration Plan

There is no data migration. The change deploys with the service. Rolling back means reverting the commit; the change
has no persisted state.
