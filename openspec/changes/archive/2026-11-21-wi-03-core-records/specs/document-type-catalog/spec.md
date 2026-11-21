# Spec Delta

## ADDED Requirements

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
