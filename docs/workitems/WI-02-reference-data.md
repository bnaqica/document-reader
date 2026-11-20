# WI-02: Reference data: type-name normalizer, loan purposes and document types

| | |
|---|---|
| **Status** | Not started |
| **Depends on** | [WI-01](WI-01-foundation.md) (each committed first) |
| **Design** | `docs/design/2026-11-20-document-reader-design.md` §7.1, §10 |
| **Source** | `docs/superpowers/plans/2026-11-20-document-reader.md`, Tasks 2 and 3 |

**Goal:** Seed the loan purposes and the 41 document types, and add the normalizer that keeps near-duplicate type names together.

**Before you start:** read the [global constraints](README.md#global-constraints), which apply to every work item.
Work on this item alone, and don't start the next one until this one is committed.

---

## Part A — Type-name normalizer

**Files:**
- Create: `processors/TypeNameNormalizer.java`
- Test: `processors/TypeNameNormalizerTests.java`

**Interfaces:**
- Produces: `Optional<String> TypeNameNormalizer.key(String name)`. It returns empty when nothing usable is left.

- [ ] **Step 1: Write the failing tests.**
  - `keyOf("Pay stubs") = keyOf("Paystub") = keyOf("pay-stub") = keyOf("PAY STUBS") = "paystub"`
  - `keyOf("W-2 forms") = keyOf("W2 form") = "w2form"`
  - `keyOf("1099 forms or K-1 forms") = "1099formork1form"`
  - `keyOf("Bank statements") = "bankstatement"`
  - `keyOf("Homeowners association documents") = "homeownersassociationdocument"`. Words ending in "ss", "us" and "is" are kept as they are, so "address" stays "address".
  - Review Focus #3: `key("—")`, `key("N/A")`, `key("  ")` and `key(null)` are all empty.
- [ ] **Step 2: Run** `./gradlew test --tests '*TypeNameNormalizerTests'`. Expected: FAIL.
- [ ] **Step 3: Implement** the normalization in §10.2: lowercase, replace punctuation and hyphens with spaces, singularize each word, then remove all spaces. Singularize with these rules in order: `ies→y`, `sses→ss`, then a trailing `s` is removed unless the word ends in `ss`, `us` or `is`. Treat `n/a`, `na`, `none` and `unknown` as empty.
- [ ] **Step 4: Run** the tests. Expected: PASS.

## Part B — Loan purposes and document types

**Files:**
- Create: `V2__reference_data.sql`, `V3__seed_loan_purposes.sql`, `V4__seed_document_types.sql`, the entities, repositories, CRUD services and controllers listed in the file structure
- Test: `repository/ReferenceDataSeedTests.java`, `api/LoanPurposeControllerTests.java`, `api/DocumentTypeControllerTests.java`

**Interfaces:**
- Consumes: `TypeNameNormalizer.key` (Part A).
- Produces:
  - `LoanPurposeService`: `Optional<LoanPurposeResponse> findActive(String code)`, `List<LoanPurposeResponse> findAllActive()`, plus CRUD.
  - `DocumentTypeService`: `List<DocumentTypeResponse> findAll()`, `Optional<DocumentTypeResponse> findByKey(String normalizedKey)` (it checks types and aliases), `DocumentTypeResponse create(NewDocumentType)` (throws `DuplicateDocumentTypeException` on a unique-key conflict), `void addAlias(UUID typeId, String alias)`, plus CRUD.
  - `DocumentTypeResponse(id, name, normalizedKey, description, dataSchema (JsonNode), schemaVersion, origin)`.

- [ ] **Step 1: Write the failing tests.**
  - `seedsEightLoanPurposes()`: the codes are exactly `CAR_PURCHASE`, `CAR_REFINANCE`, `HOME_PURCHASE`, `MORTGAGE_REFINANCE`, `BOAT_PURCHASE`, `BOAT_REFINANCE`, `RV_PURCHASE` and `RV_REFINANCE`, all active, each with a description.
  - `seedsFortyOneDocumentTypes()`: the count is 41. Every `normalized_key` is unique and equals `TypeNameNormalizer.key(name)`. Every `data_schema` is a valid JSON Schema object with at least one property. `origin` is `SEEDED`.
  - `findByKeyResolvesAlias()`: after `addAlias(payStubsId, "Paystub")`, `findByKey("paystub")` returns Pay stubs.
  - `createRejectsDuplicateKey()`: creating "Paystubs" throws `DuplicateDocumentTypeException`.
  - Controllers: a GET with a `jwt()` returns 200. A POST or PUT without the admin role returns 403, and with it returns 201 or 200. A request with no token returns 401.
- [ ] **Step 2: Run** the tests. Expected: FAIL.
- [ ] **Step 3: Write the V2 migration** with the `loan_purpose`, `document_type` and `document_type_alias` columns in §16, a unique `normalized_key` on both type tables, and `data_schema JSONB NOT NULL`.
- [ ] **Step 4: Write the V3 seed** for the eight purposes, with one-sentence descriptions that prompts can use as context.
- [ ] **Step 5: Write the V4 seed.** For each of the 41 types in the design conversation's list, use the model to draft the JSON Schema and **have a person review it** before it goes into the file. The schemas cover the fields lenders use (§10.1). `name` is the list wording, for example "Pay stubs" and "W-2 forms, typically the past two years" becoming "W-2 forms". Record the short-name choices in a comment at the top of V4.
- [ ] **Step 6: Implement** the entities (`@Getter`, a protected no-args constructor, `@Builder`, and no `@Data`), the repositories, the CRUD services that map to response records, and the controllers with `@PreAuthorize("hasRole('ADMIN')")` on writes.
- [ ] **Step 7: Run** the tests, then `./gradlew spotlessApply build`. Expected: PASS.

---

## Done when

- [ ] Every step above is ticked.
- [ ] `./gradlew spotlessApply build` passes, with all tests green.
- [ ] `git status` shows only the files this work item names, plus their tests and fixtures.
- [ ] A code review (`superpowers:requesting-code-review`, using the `code-reviewer` agent) has no open findings.
- [ ] The developer has reviewed the diff.

## Commit

Claude never commits in this repo (`.claude/rules/git-workflow.md`). The developer commits this work item as one
commit, with this suggested message:

```text
feat: add loan purposes, document type catalog and name normalizer
```

Then set **Status** above to `Done`, and do the same for this row in [README.md](README.md#order).
