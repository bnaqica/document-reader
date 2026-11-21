# Design

## Context

WI-01 left the service with settings, security (any valid JWT, plus an open health check), the audit filter and one
migration (`V1__create_audit_table.sql`). There are no domain tables yet. The approved design defines the reference
tables in §16 and the near-duplicate rules in §10.2 of `docs/design/2026-11-20-document-reader-design.md`. See
`proposal.md` for why, and the three specs for the required behaviour.

The constraints that shape this design come from `.claude/rules/`:
- every table gets a CRUD controller;
- the CRUD service is the only caller of its repository;
- controllers never see entities, and mapping uses builders;
- exceptions live in `exception`, and errors are `ProblemDetail`s;
- the main schema is `document_reader`.

## Goals / Non-Goals

**Goals:**
- Reference data that later work items read through two CRUD services, `LoanPurposeService` and
  `DocumentTypeService`, including name resolution.
- Key uniqueness across types and aliases that holds under concurrency, enforced by the database rather than by
  hoping two requests don't overlap.
- Data schemas that OpenAI strict structured output (WI-09, WI-14) will accept as they are.

**Non-Goals:**
- Model-created types, the confirmation call and schema drafting from pages. Those are WI-13; the `MODEL` origin
  exists now so WI-13 only adds behaviour.
- Linking types to documents (`created_by_document_id` stays a nullable UUID with no foreign key until WI-03 creates
  `document`).
- Caching the catalog. 41 rows read per classification call is cheap; revisit if WI-13 shows otherwise.

## Decisions

### 1. Tables (V2)

| Table | Columns |
|---|---|
| `loan_purpose` | `code VARCHAR(50)` pk, `name VARCHAR(100) NOT NULL`, `description TEXT NOT NULL`, `active BOOLEAN NOT NULL DEFAULT true` |
| `document_type` | `id UUID` pk, `name VARCHAR(200) NOT NULL`, `normalized_key VARCHAR(200) NOT NULL UNIQUE`, `description TEXT NOT NULL`, `data_schema JSON NOT NULL` (JSON, not JSONB, so the authored key order survives), `schema_version INT NOT NULL`, `origin VARCHAR(10) NOT NULL` (`SEEDED`, `MANUAL`, `MODEL`), `created_by_document_id UUID`, `created_at TIMESTAMPTZ NOT NULL`, `version BIGINT NOT NULL` (optimistic lock) |
| `document_type_alias` | `id UUID` pk, `document_type_id UUID NOT NULL` FK → `document_type` **ON DELETE CASCADE**, `alias VARCHAR(200) NOT NULL`, `normalized_key VARCHAR(200) NOT NULL UNIQUE` |

- Loan purposes keep their natural `code` key, because batches (WI-03) store the code, and it reads well in logs.
- `origin` gains `MANUAL` for admin-created types, alongside the design's `SEEDED` and `MODEL`.

### 2. Cross-table key uniqueness: unique indexes plus a trigger

Each table's unique index stops duplicates inside it, which covers the spec's concurrent "Lease agreement / Lease
agreements" case. A type and an alias with the same key are stopped by a `BEFORE INSERT OR UPDATE OF normalized_key`
trigger on each table:
1. It takes `pg_advisory_xact_lock(hashtext(NEW.normalized_key))`, so two transactions racing on one key serialize.
2. It raises `unique_violation` (SQLSTATE `23505`) if the other table already holds the key.

The service translates a `DataIntegrityViolationException` on a key into `DocumentTypeKeyConflictException`. It
resolves the key first, to name the owning type in the 409.
- *Alternatives:*
  - A shared `document_type_key` registry table is race-safe too, but it duplicates every key in a second place, and
    it adds a third table and CRUD controller.
  - A check in the service alone races.

  The trigger keeps the invariant in one place, the database, so it was chosen.
- A plain check in the service (resolve first) still runs before insert, so the common case returns a 409 with the
  owner's name without relying on exception parsing. The trigger is the guarantee.

### 3. Name normalization is a static, pure function in `processors`

It drops accents (NFD, then removes combining marks) and treats letters and digits of any alphabet as word
characters, so "Résumé" and "Resume" share a key. *(Added after code review; the seed's keys are ASCII and unaffected.)*

`TypeNameNormalizer.key(String name) → Optional<String>` implements the spec's rule exactly.
- It's static because it has no dependencies or state, and the V4 seed test and both services call it.
- The seed migration stores precomputed keys as literals. A test asserts each equals `TypeNameNormalizer.key(name)`,
  so the SQL and the Java can't drift.

### 4. Services and dependency direction

- `LoanPurposeService(Impl)` owns `LoanPurposeRepository`.
- `DocumentTypeService(Impl)` owns `DocumentTypeRepository`. It provides CRUD plus:
  - `Optional<DocumentTypeResponse> resolve(String name)`, which normalizes the name, then runs one repository query
    matching the type's key or any alias key (a JPQL `left join` on the alias entity);
  - `List<CatalogEntry> catalog()`, a lightweight name-and-description list for prompts (WI-09).
- `DocumentTypeAliasService(Impl)` owns `DocumentTypeAliasRepository`. It calls `DocumentTypeService` to check the
  parent type exists and to name a key's owner on conflict.

Dependencies point one way, from alias to type, so there are no cycles. Deleting a type removes its aliases through
the foreign key's `ON DELETE CASCADE`, so the type service never needs the alias service.

`DocumentTypeService.create` and `DocumentTypeAliasService.add` run with `Propagation.REQUIRES_NEW`. When a create
loses a race, its own transaction is aborted, not the caller's, so WI-13 can catch the conflict and re-resolve the
winner inside its transaction. *(Added after code review.)* Tests that call them clean up with `@AfterTransaction`,
because those rows commit.

The plan's `DuplicateDocumentTypeException` is renamed `DocumentTypeKeyConflictException(key, ownerName)`, because
aliases raise it too. WI-13 is updated to match.

### 5. Data schema storage and validation

*Changed after code review:*
- The column is `JSON`, not `JSONB`. JSONB reorders keys, and OpenAI strict output generates fields in schema order.
- The entity writes the column with `@ColumnTransformer(write = "?::json")` and `columnDefinition = "json"`, instead
  of `@JdbcTypeCode(SqlTypes.JSON)`, because Hibernate's JSON type casts to `jsonb` on PostgreSQL. The V4 seed casts
  with `::json` for the same reason.
- The rules now also require the root to be exactly `"type": "object"`, require `required` to match `properties`
  exactly, and reject `$ref`, `$defs`, `definitions`, `allOf` and `oneOf`.

- **Storage:** the entity holds `data_schema` as a `String`, with `@JdbcTypeCode(SqlTypes.JSON)`. DTOs carry it as a
  Jackson 3 `JsonNode` (`tools.jackson.databind`), and the service converts with the injected `JsonMapper`. This
  avoids depending on Hibernate's Jackson integration, which may still target Jackson 2.
- **Validation:** `DataSchemaRules.violations(JsonNode schema) → List<String>` in `processors`, which is empty when the
  schema is valid. It checks:
  1. the schema is valid JSON Schema draft 2020-12, using networknt `json-schema-validator`'s meta-schema;
  2. the root is `"type": "object"` with at least one property;
  3. the root and every nested object (inside `properties`, `items` and `anyOf`) have `additionalProperties: false`
     and a `required` list equal to their property names.

  Any violation becomes `InvalidDataSchemaException`, a 400 naming `dataSchema` with the violations listed.
- **Schema version:** it increases only when the stored schema changes, comparing parsed JSON trees (not strings), so
  reformatting the same schema doesn't bump it.

### 6. Seeded schemas (V4) are drafted during apply and reviewed by the developer

For each of the 41 types, the schema lists the fields a lender uses, in camelCase. Types are `string` (ISO dates as
`"format": "date"`), `number` or `integer`, each also allowing `null`, plus arrays of strict objects for repeating
data such as `deductions` and `transactions`.
- The 41 names are the spec's list. Each description comes from the original request's wording, including its
  parentheticals; for example, "Proof of address" says "utility bill, lease, or bank statement".
- Claude drafts the migration in apply. **The developer reviews V4 before the work item is done**, which is its own
  task so it can't be skipped.

### 7. API

| Method and path | Body / result | Role |
|---|---|---|
| `GET /loan-purposes` | active purposes, ordered by code | any |
| `GET /loan-purposes/{code}` | one purpose | any |
| `POST /loan-purposes` | `{code, name, description}` → 201 | `ADMIN` |
| `PUT /loan-purposes/{code}` | `{name, description, active}` → 200 | `ADMIN` |
| `DELETE /loan-purposes/{code}` | deactivates → 204 | `ADMIN` |
| `GET /document-types` | all types, ordered by name | any |
| `GET /document-types/{id}` | one type | any |
| `POST /document-types` | `{name, description, dataSchema}` → 201, origin `MANUAL` | `ADMIN` |
| `PUT /document-types/{id}` | `{name, description, dataSchema}` → 200 | `ADMIN` |
| `DELETE /document-types/{id}` | → 204 | `ADMIN` |
| `GET /document-types/{id}/aliases` | aliases of a type | any |
| `POST /document-types/{id}/aliases` | `{alias}` → 201 | `ADMIN` |
| `DELETE /document-types/{id}/aliases/{aliasId}` | → 204 | `ADMIN` |

- Request records in `dto.request` use Jakarta validation. The code pattern is `^[A-Z][A-Z0-9_]{0,49}$`.
- Response records live in `dto.response`.
- The controllers are `LoanPurposeController`, `DocumentTypeController` and `DocumentTypeAliasController`.

### 8. Error responses

`GlobalExceptionHandler` gains these handlers:

| Exception | Response |
|---|---|
| `ResourceNotFoundException` | 404 |
| `LoanPurposeExistsException`, `DocumentTypeKeyConflictException` | 409 |
| `InvalidDataSchemaException`, `UnusableNameException` | 400 |
| `AccessDeniedException` | 403 `ProblemDetail` |

- **400 field names:** it overrides `handleMethodArgumentNotValid` to add an `errors` property, mapping each field to
  its message, so a 400 names the field as the spec requires.
- **`OptimisticLockingFailureException`** (two admins saving one type at once) becomes 409, not 500.
- **Why an explicit 403 handler:** without it, a denied `@PreAuthorize` becomes Spring Security's empty 403 error page,
  not a problem detail. It's safe in the secured chain, because unauthenticated requests are stopped with a 401 before
  any controller runs. `AuthenticationException` is still rethrown, as the rule in `java-spring.md` requires.

### 9. Roles from Keycloak

- **Mapping:** `SecurityConfig` sets a `JwtAuthenticationConverter` whose authorities come from
  `realm_access.roles`, giving `ROLE_` plus the role in upper case. Scope-based authorities are dropped, since nothing
  uses them.
- **`local` profile:** the chain sets `.anonymous(a -> a.authorities("ROLE_ADMIN"))`, so `@PreAuthorize` passes
  without a token. The audit filter still records `anonymous` as the actor.

### 10. Tests

| Test | What it covers |
|---|---|
| `TypeNameNormalizerTests` (unit) | every normalization scenario |
| `DataSchemaRulesTests` (unit) | each schema rule, including the nested case |
| `ReferenceDataSeedTests` (`@DataJpaTest` with Testcontainers) | the seed counts, keys, origins and schema shapes, using `DataSchemaRules` |
| Service tests on real Postgres | resolution, conflicts (including the trigger's cross-table case and the concurrent case), schema versioning, deactivation and cascade delete |
| Controller tests (`@WebMvcTest`, with `@Import(SecurityConfig.class)`, mocked services, `jwt()` with and without `ROLE_ADMIN`) | status codes, problem details and role checks |
| `JwtRoleMappingTests` | realm roles to authorities |
| `LocalProfileAdminTests` | a local write without a token returns 201 |

## Risks / Trade-offs

- [The trigger is logic in the database, which is less visible than Java] → It's one small function, commented in
  V2, and covered by a test that would fail without it (alias versus type collision).
- [41 hand-reviewed schemas are a lot to review] → They're grouped by document family in V4, with a comment per type.
  The review is an explicit task, and schemas can still change later through `PUT` with a version bump.
- [Deleting a type is a hard delete] → It's acceptable while nothing references types. WI-03 adds the foreign key from
  `document`, and deleting a referenced type will then fail with 409, which is added there.
- [The 403 handler would turn an anonymous denial into 403 instead of 401] → This can't happen in the secured chain,
  which rejects anonymous requests at the filter. In `local`, everyone is admin.

## Migration Plan

- Flyway applies V2, V3 and V4 in order on startup.
- Rollback before production: drop the three tables and delete the V2–V4 rows from the Flyway history.
- After production: a forward migration only, never an edit to applied ones.
