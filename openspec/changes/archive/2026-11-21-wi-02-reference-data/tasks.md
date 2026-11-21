# Tasks

Every task with behaviour is test-first (`superpowers:test-driven-development`): write the failing test, run it and
watch it fail for the expected reason, implement, then run it and watch it pass. Nothing is committed; new files are
staged as they're created. The developer commits WI-02 as one commit.

## 1. Name normalization

- [x] 1.1 Write `processors/TypeNameNormalizerTests` covering every scenario in the "Names reduce to a comparison key" and "Names with nothing usable have no key" requirements, with literal expected keys. Verify it fails because `TypeNameNormalizer` doesn't exist.
- [x] 1.2 Implement `TypeNameNormalizer.key(String) → Optional<String>` (design decision 3). Verify every 1.1 test passes.

## 2. Data schema rules

- [x] 2.1 Write `processors/DataSchemaRulesTests`: a valid strict schema has no violations; a non-object root, an empty `properties`, a property missing from `required`, a missing `additionalProperties: false`, a nested object in `properties` that allows extra properties, a nested object in array `items` with an optional property, and a schema that isn't valid draft 2020-12 each give a violation. Verify it fails because `DataSchemaRules` doesn't exist.
- [x] 2.2 Implement `DataSchemaRules.violations(JsonNode) → List<String>` (design decision 5), using networknt's draft 2020-12 meta-schema plus the strictness walk. Verify every 2.1 test passes.

## 3. Tables and loan purposes

- [x] 3.1 Write `repository/ReferenceDataSeedTests.seedsEightLoanPurposes()` (`@DataJpaTest` plus `TestcontainersConfiguration`), asserting exactly the eight codes from the spec, all active, with non-blank names and descriptions. Verify it fails because the table doesn't exist.
- [x] 3.2 Write `V2__reference_data.sql` (all three tables, plus the cross-table key trigger with its advisory lock, design decisions 1 and 2), `V3__seed_loan_purposes.sql`, and the `LoanPurpose` entity and repository. Verify 3.1 passes, and that `DocumentReaderApplicationTests` still starts.
- [x] 3.3 Write `service/LoanPurposeServiceTests` (real Postgres) for the spec's scenarios: list active purposes ordered by code, get an inactive one by code, unknown code gives `ResourceNotFoundException`, create, duplicate code gives `LoanPurposeExistsException`, update (including reactivating), and delete deactivates. Verify it fails, implement `LoanPurposeService(Impl)` with its request and response records, then verify it passes.

## 4. Document type catalog

- [x] 4.1 Write `ReferenceDataSeedTests.seedsFortyOneDocumentTypes()`: exactly the spec's 41 names; origin `SEEDED`, version 1 and a non-blank description for each; every stored `normalized_key` equal to `TypeNameNormalizer.key(name)`; all keys distinct; and every `data_schema` with no `DataSchemaRules` violations. Add `payStubsSchemaCoversLenderFields()`, asserting the pay stub schema's properties include an employer, employee, pay period start and end, pay date, gross pay, net pay, year-to-date gross, deductions and hours. Verify both fail.
- [x] 4.2 Draft `V4__seed_document_types.sql` with the 41 types, descriptions and strict schemas (design decision 6), plus the `DocumentType` entity and repository. Verify that 4.1 passes.
- [x] 4.3 **Stop for developer review of `V4__seed_document_types.sql`.** Present the type list, with each type's fields in a compact table, and apply the developer's changes. Verify 4.1 still passes after the changes. Don't continue until the developer approves the seed.
- [x] 4.4 Write `service/DocumentTypeServiceTests` (real Postgres) for each scenario in "A name resolves to the type whose key matches" and the type-related scenarios in "Keys are unique across types and aliases": the duplicate type, a concurrent create in two threads giving exactly one row and one `DocumentTypeKeyConflictException`, and a rename to a taken name. Also cover "Admins can add a type" (origin `MANUAL`, version 1, `UnusableNameException` for "N/A", `InvalidDataSchemaException` for a bad schema), "Admins can change a type" (the version bumps only when the parsed schema changes), and the type-removal scenario. Verify it fails, implement `DocumentTypeService(Impl)` with `resolve`, `catalog` and CRUD (design decision 4), then verify it passes.
- [x] 4.5 Write `service/DocumentTypeAliasServiceTests` (real Postgres): add and list; an alias matching its own type's key gives 409; an alias colliding with another type ("W-2 form" on "Pay stubs") is refused by the trigger even when the service check is bypassed, by calling the repository directly; an alias with no key gives `UnusableNameException`; removal works; and deleting a type removes its aliases. Verify it fails, implement `DocumentTypeAliasEntity`, its repository and `DocumentTypeAliasService(Impl)`, then verify it passes.

## 5. API, errors and roles

- [x] 5.1 Write `config/JwtRoleMappingTests`: a token with `realm_access.roles: [admin, reader]` gives `ROLE_ADMIN` and `ROLE_READER`, and a token without `realm_access` gives no authorities. Verify it fails, add the `JwtAuthenticationConverter` to `SecurityConfig` (design decision 9), then verify it passes along with the existing `SecurityConfigTests`.
- [x] 5.2 Write `api/LoanPurposeControllerTests` (`@WebMvcTest`, `@Import(SecurityConfig.class)`, mocked service):
  - each endpoint's success status;
  - 404 and 409 as problem details;
  - a 400 that names `code` in `errors`;
  - 403 as a problem detail for a write with `jwt()` but no `ROLE_ADMIN`, with the service never called;
  - 401 for a write with no token;
  - 200 for a read with `jwt()` and no roles.

  Verify it fails. Implement `LoanPurposeController`, then add the exception handlers (the 404, 409 and 400 mappings, the `errors` property, and the 403 `ProblemDetail`) to `GlobalExceptionHandler` (design decision 8). Verify it passes.
- [x] 5.3 Write `api/DocumentTypeControllerTests` and `api/DocumentTypeAliasControllerTests` the same way, covering each endpoint in design decision 7, including a 400 naming `dataSchema` and a 409 naming the owning type. Verify they fail, implement both controllers, then verify they pass.
- [x] 5.4 Write `config/LocalProfileAdminTests` (`@SpringBootTest` with `@ActiveProfiles("local")` and Testcontainers): a POST to `/loan-purposes` with no token returns 201. Verify it fails (403), add the anonymous `ROLE_ADMIN` to the local chain, then verify it passes.

## 6. Verification

- [x] 6.1 Update `README.md` with the reference-data endpoints and the `admin` realm role needed to change them. Verify it matches design decision 7.
- [x] 6.2 Run `./gradlew spotlessApply build`, and verify BUILD SUCCESSFUL with every test green, including the WI-01 tests.
- [x] 6.3 Run a code review through `superpowers:requesting-code-review`, using the `code-reviewer` agent with `openspec/changes/wi-02-reference-data/` as the requirements. Fix every Critical and Important finding, and verify the build again.
- [x] 6.4 Tick the boxes in `docs/workitems/WI-02-reference-data.md` (except the developer review and commit), and verify that `git status` lists only WI-02 files.

## Workflow follow-up

- The developer reviews the diff and commits WI-02 as one commit.
- After the commit, run `/opsx:archive` for `wi-02-reference-data`. Also archive `wi-01-foundation` if it isn't yet.
