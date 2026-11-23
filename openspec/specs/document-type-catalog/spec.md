# document-type-catalog Specification

## Purpose
Defines the catalog of document types the service classifies documents into. It covers:
- how names are reduced to comparison keys, so near-duplicate names resolve to one type;
- the aliases a type can have, and the data schema that extraction fills for each type;
- the types the service starts with;
- how callers read the catalog and how admins maintain it.

## Requirements

### Requirement: Names reduce to a comparison key
The catalog SHALL compare names by a key. To build it, drop accents, lowercase the name, turn every character that
isn't a letter or digit (in any alphabet) into a space, and singularize each word. Then join the words with no spaces. A word is singularized by the
first rule that applies: `ies` becomes `y`, `sses` becomes `ss`, and a trailing `s` is dropped unless the word ends in
`ss`, `us` or `is`.

#### Scenario: Pay stub variants
- **WHEN** the names "Pay stubs", "Paystub", "pay-stub" and "PAY STUBS" are reduced
- **THEN** each key is `paystub`

#### Scenario: W-2 variants
- **WHEN** the names "W-2 forms" and "W2 form" are reduced
- **THEN** each key is `w2form`

#### Scenario: Words that keep their final s
- **WHEN** the names "Proof of address", "Break-even analysis" and "Bonus status" are reduced
- **THEN** the keys are `proofofaddress`, `breakevenanalysis` and `bonusstatus`

#### Scenario: Accented letters
- **WHEN** the names "Résumé" and "Déclaration de revenus" are reduced
- **THEN** the keys are `resume` and `declarationderevenus`

#### Scenario: Other plural endings
- **WHEN** the names "Homeowners association documents", "Insurance policies" and "Mailing addresses" are reduced
- **THEN** the keys are `homeownerassociationdocument`, `insurancepolicy` and `mailingaddress`

### Requirement: Names with nothing usable have no key
A name that is missing or blank, has no letters or digits, or reduces to `na`, `none` or `unknown` SHALL have no key.
Such a name SHALL never resolve to a type, and SHALL never become a type or alias.

#### Scenario: Unusable names
- **WHEN** the names "—", "N/A", "none", "Unknown", "   " and a missing name are reduced
- **THEN** none of them has a key

### Requirement: A name resolves to the type whose key matches
The catalog SHALL resolve a name to the type whose own key, or one of whose aliases' keys, equals the name's key. A
name whose key matches nothing, or that has no key, SHALL resolve to no type.

#### Scenario: Resolve by the type's own name
- **WHEN** the name "PAY STUB" is resolved
- **THEN** it resolves to the type "Pay stubs"

#### Scenario: Resolve by an alias
- **GIVEN** the type "Pay stubs" has the alias "Earnings statement"
- **WHEN** the name "earnings statements" is resolved
- **THEN** it resolves to the type "Pay stubs"

#### Scenario: No match
- **WHEN** the name "Boarding pass" is resolved
- **THEN** it resolves to no type

### Requirement: Keys are unique across types and aliases
No two types, no two aliases, and no type and alias SHALL share a key, so every key resolves to at most one type.
Creating or renaming a type, or adding an alias, whose key is already taken SHALL be refused with 409, naming the type
that owns the key.

#### Scenario: Duplicate type
- **WHEN** an admin creates a type named "Paystubs"
- **THEN** the response is 409 as a problem detail naming "Pay stubs"

#### Scenario: Alias that collides with another type
- **WHEN** an admin adds the alias "W-2 form" to the type "Pay stubs"
- **THEN** the response is 409 as a problem detail naming "W-2 forms"

#### Scenario: Concurrent creation of the same new name
- **WHEN** two requests create types named "Lease agreement" and "Lease agreements" at the same moment
- **THEN** exactly one type is created, and the other request gets 409

### Requirement: Every type has a strict data schema
Every type SHALL have a data schema: a JSON Schema describing the data extraction returns. Its root SHALL be
exactly `"type": "object"` with at least one property. It and every nested object SHALL list exactly their
properties as required, allow `null` for values a document may lack, and disallow additional properties. `$ref`,
`allOf` and `oneOf` SHALL NOT appear. A schema breaking these rules SHALL be refused with 400.

#### Scenario: Seeded schema shape
- **WHEN** any seeded type's data schema is read
- **THEN** it is an object schema with at least one property, every property is required, and additional properties
  are disallowed

#### Scenario: Schema that isn't an object
- **WHEN** an admin creates a type whose data schema is `{"type": "string"}`
- **THEN** the response is 400 as a problem detail naming `dataSchema`

#### Scenario: Schema with an optional property
- **WHEN** an admin creates a type whose schema has properties `a` and `b` but lists only `a` as required
- **THEN** the response is 400 as a problem detail naming `dataSchema`

#### Scenario: Reference to another definition
- **WHEN** an admin creates a type whose schema uses `$ref` for a property
- **THEN** the response is 400 as a problem detail naming `dataSchema`

#### Scenario: Nested object that allows extra properties
- **WHEN** an admin creates a type whose schema has an object property `deductions` that doesn't disallow additional
  properties
- **THEN** the response is 400 as a problem detail naming `dataSchema`

### Requirement: Data schemas use only what OpenAI strict mode accepts
A data schema SHALL use only the keywords OpenAI strict structured output accepts. `anyOf` and `items` SHALL NOT
appear at the root, and objects SHALL nest at most 9 deep, the root counting as 1, leaving one level for the
extraction wrapper. A schema breaking these rules SHALL be refused with 400.

#### Scenario: Keyword strict mode doesn't accept
- **WHEN** an admin creates a type whose schema gives a string property `minLength`
- **THEN** the response is 400 as a problem detail naming `dataSchema`

#### Scenario: Objects nested too deep
- **WHEN** an admin creates a type whose schema nests objects 10 deep
- **THEN** the response is 400 as a problem detail naming `dataSchema`

### Requirement: The service starts with the 41 lender document types
On first start, the catalog SHALL hold the 41 lender document types listed in the Fresh database scenario, and no
others. Each SHALL have origin `SEEDED`, schema version 1, a description, and a data schema covering the fields
lenders use from that document.

#### Scenario: Fresh database
- **WHEN** the service starts against an empty database
- **THEN** the catalog holds exactly these 41 names, all with origin `SEEDED` and schema version 1:
  1. Appraisal
  2. Authorization for credit check
  3. Bank statements
  4. Bill of sale
  5. Break-even or net tangible benefit analysis
  6. Birth certificate
  7. Closing disclosure
  8. Current loan or payoff statement
  9. Credit explanation letter
  10. Declaration page
  11. Divorce decree or court support order
  12. Driver's license or government photo ID
  13. Employment verification
  14. Flood determination
  15. Gift letter
  16. Homeowners association documents
  17. Hull number or serial number documentation
  18. Inspection or survey report
  19. Investment and retirement account statements
  20. Loan application
  21. Loan estimate
  22. Loan agreement or promissory note
  23. Marine survey
  24. Mileage or odometer reading
  25. Other income statements
  26. Pay stubs
  27. Profit-and-loss statement
  28. Proof of address
  29. Proof of down payment or trade-in
  30. Proof of insurance
  31. Proof of residency or citizenship
  32. Purchase agreement or buyer's order
  33. Registration
  34. Settlement statement
  35. Social Security number or taxpayer ID
  36. Tax returns
  37. Title or title insurance
  38. Valuation report
  39. VIN
  40. W-2 forms
  41. 1099 or K-1 forms
- **AND** every name has a key, and no two keys are equal

#### Scenario: Pay stub schema covers the lender's fields
- **WHEN** the data schema of "Pay stubs" is read
- **THEN** it has properties for the employer, the employee, the pay period start and end, the pay date, gross pay,
  net pay, year-to-date gross pay, deductions and hours worked

### Requirement: Callers can read the catalog
`GET /document-types` SHALL return every type ordered by name. Each type SHALL include its id, name, key, description,
data schema, schema version and origin. `GET /document-types/{id}` SHALL return one type, and an unknown id SHALL
return 404.

#### Scenario: List
- **WHEN** a caller with a valid token requests `GET /document-types`
- **THEN** the response is 200 with the 41 seeded types ordered by name

#### Scenario: Unknown id
- **WHEN** a caller requests `GET /document-types/{id}` with an id that doesn't exist
- **THEN** the response is 404 as a problem detail

### Requirement: Admins can add a type
`POST /document-types` SHALL create a type from a name, description and data schema, with origin `MANUAL` and schema
version 1, and return 201 with it. A name with no key, a blank description, or an invalid schema SHALL return 400.

#### Scenario: New type
- **WHEN** an admin creates a type "Lease agreement" with a description and a valid schema
- **THEN** the response is 201 with key `leaseagreement`, origin `MANUAL` and schema version 1

#### Scenario: Name with no key
- **WHEN** an admin creates a type named "N/A"
- **THEN** the response is 400 as a problem detail naming the `name` field

### Requirement: A type keeps its schema's property order
The catalog SHALL return a data schema with its properties in the order they were written, because extraction fills
fields in schema order.

#### Scenario: Order kept
- **WHEN** an admin creates a type whose schema lists properties `middle`, `zeta` and `alpha`, in that order
- **THEN** reading the type returns those properties in that order

### Requirement: Admins can change a type
`PUT /document-types/{id}` SHALL replace a type's name, description and data schema, and return 200 with the result.
- A new name SHALL get a new key, subject to the uniqueness rule.
- A changed data schema SHALL increase the schema version by one. An unchanged schema keeps its version.
- Origin SHALL NOT change.
- An unknown id SHALL return 404.
- A change that conflicts with another admin's save of the same type SHALL return 409.

#### Scenario: Schema change
- **GIVEN** "Gift letter" has schema version 1
- **WHEN** an admin puts the same name and description with a different valid schema
- **THEN** the response is 200 with schema version 2

#### Scenario: Description-only change
- **GIVEN** "Gift letter" has schema version 1
- **WHEN** an admin puts a new description with the same schema
- **THEN** the response is 200 with schema version 1

#### Scenario: Rename to a taken name
- **WHEN** an admin renames "Gift letter" to "Pay stub"
- **THEN** the response is 409 naming "Pay stubs"

### Requirement: Admins can remove a type
`DELETE /document-types/{id}` SHALL remove the type and its aliases, and return 204. An unknown id SHALL return 404.

#### Scenario: Remove
- **GIVEN** the type "Lease agreement" has the alias "Rental agreement"
- **WHEN** an admin deletes the type
- **THEN** the response is 204
- **AND** neither "Lease agreement" nor "Rental agreement" resolves to a type

### Requirement: Admins can maintain a type's aliases
`GET /document-types/{id}/aliases` SHALL list a type's aliases, each with its id, alias text and key, to any caller
with a valid token. `POST /document-types/{id}/aliases` SHALL add one and return 201. It SHALL return 400 for an alias
with no key, and 409 when the key is taken. `DELETE /document-types/{id}/aliases/{aliasId}` SHALL remove an alias and
return 204. An unknown type or alias SHALL return 404.

#### Scenario: Add and list
- **WHEN** an admin adds the alias "Earnings statement" to "Pay stubs"
- **THEN** the response is 201 with key `earningstatement`
- **AND** `GET /document-types/{id}/aliases` for "Pay stubs" lists it

#### Scenario: Alias that matches its own type
- **WHEN** an admin adds the alias "Paystub" to "Pay stubs"
- **THEN** the response is 409, because the key `paystub` already belongs to "Pay stubs"

#### Scenario: Remove
- **GIVEN** "Pay stubs" has the alias "Earnings statement"
- **WHEN** an admin deletes that alias
- **THEN** the response is 204, and "Earnings statement" no longer resolves to a type

### Requirement: A type that documents use can't be removed
`DELETE /document-types/{id}` SHALL be refused with 409 while any document refers to the type, and the type and its
aliases SHALL be left unchanged. The problem detail SHALL say the type is in use. A type no document refers to can
still be removed.

#### Scenario: Type in use
- **GIVEN** a document has been classified as "Pay stubs"
- **WHEN** an admin deletes "Pay stubs"
- **THEN** the response is 409 as a problem detail saying the type is in use
- **AND** "Pay stubs" still resolves

#### Scenario: Unused type
- **GIVEN** no document refers to "Lease agreement"
- **WHEN** an admin deletes it
- **THEN** the response is 204

### Requirement: A type that runs or results use can't be removed
`DELETE /document-types/{id}` SHALL be refused with 409 while any classification run, classification result or
extraction result refers to the type, the same as for a type a document refers to. The type and its aliases SHALL be
left unchanged, and the problem detail SHALL say the type is in use.

#### Scenario: Type only a classification run refers to
- **GIVEN** no document is typed "Lease agreement", but a classification run resolved to it
- **WHEN** an admin deletes "Lease agreement"
- **THEN** the response is 409 as a problem detail saying the type is in use
- **AND** "Lease agreement" still resolves

#### Scenario: Type an extraction result refers to
- **GIVEN** an extraction result refers to "Pay stubs"
- **WHEN** an admin deletes "Pay stubs"
- **THEN** the response is 409 and "Pay stubs" still resolves
