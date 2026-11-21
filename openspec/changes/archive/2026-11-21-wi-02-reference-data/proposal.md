# Proposal

## Why

Every batch carries a loan purpose, and every document gets a type from the catalog. Intake (WI-11) can't validate
`loanPurpose`, and classification (WI-13) can't vote, until both lists exist and near-duplicate type names resolve to
one type. This is WI-02 of `docs/workitems/`. It needs only WI-01, so it comes next.

## What Changes

- **Loan purposes:** add the loan purposes, seeded with eight codes:
  - `CAR_PURCHASE`, `CAR_REFINANCE`, `HOME_PURCHASE` and `MORTGAGE_REFINANCE`, which the product owner named;
  - `BOAT_PURCHASE`, `BOAT_REFINANCE`, `RV_PURCHASE` and `RV_REFINANCE`, which the design proposed.

  Each has a description that prompts use as context.
- **Document type catalog:** add it, seeded with the 41 lender document types. Each type has a short canonical name,
  a description and a JSON Schema describing the data that extraction returns for it.
- **Aliases:** a type can have other names that resolve to it.
- **Name normalization:** a name is reduced to a comparison key, so "Pay stubs", "Paystub" and "pay-stub" all resolve
  to the same type. A name with nothing usable in it, such as "N/A", resolves to nothing.
- **Endpoints:** add REST endpoints to read loan purposes, document types and aliases. Creating, changing and
  removing them is limited to admins.
- **Admin role:** map Keycloak realm roles from the token to application roles, so an `ADMIN` realm role grants
  admin access. Until now no endpoint needed a role, so tokens carried none the application could see.

## Capabilities

### New Capabilities

- `loan-purposes`: the loan purposes a batch can be for, their seed values, and how they are read and maintained.
- `document-type-catalog`: the document types, their aliases and data schemas, how names normalize so near-duplicates
  resolve to one type, the seed values, and how the catalog is read and maintained.
- `api-authorization`: how a caller's roles are taken from the token and which operations need the admin role.

### Modified Capabilities

(none)

## Impact

- **Database:** new tables `document_reader.loan_purpose`, `document_type` and `document_type_alias` (migration V2),
  plus seed migrations V3 (loan purposes) and V4 (document types). No existing table changes.
- **API:** new endpoints under `/loan-purposes` and `/document-types`, including `/document-types/{id}/aliases`.
  Writes are audited by the existing audit filter.
- **Security:** `SecurityConfig` gains a JWT role mapping. Existing endpoints keep their behaviour: any valid token,
  and the open health check.
- **Code:** new entities, repositories, CRUD services, controllers, request and response DTOs, exceptions, and the
  `TypeNameNormalizer` processor.
- **Later work items:** WI-11 validates `loanPurpose` against this table. WI-09 and WI-13 use the catalog's names,
  descriptions and schemas in prompts. WI-14 extracts against the schemas.
