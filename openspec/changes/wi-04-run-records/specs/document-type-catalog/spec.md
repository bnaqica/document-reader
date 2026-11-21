# Spec Delta

## ADDED Requirements

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
