# Spec Delta

## MODIFIED Requirements

### Requirement: Every type has a strict data schema
Every type SHALL have a data schema: a JSON Schema describing the data extraction returns. Its root SHALL be
exactly `"type": "object"` with at least one property. It and every nested object SHALL list exactly their
properties as required, allow `null` for values a document may lack, and disallow additional properties. `$ref`,
`allOf` and `oneOf` SHALL NOT appear. Only the keywords OpenAI strict structured output accepts SHALL appear, `anyOf`
and `items` SHALL NOT appear at the root, and objects SHALL nest at most 9 deep, the root counting as 1. A schema
breaking these rules SHALL be refused with 400.

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

#### Scenario: Keyword strict mode doesn't accept
- **WHEN** an admin creates a type whose schema gives a string property `minLength`
- **THEN** the response is 400 as a problem detail naming `dataSchema`

#### Scenario: Objects nested too deep
- **WHEN** an admin creates a type whose schema nests objects 10 deep
- **THEN** the response is 400 as a problem detail naming `dataSchema`
