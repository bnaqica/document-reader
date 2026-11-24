# Spec Delta

## ADDED Requirements

### Requirement: Votes for a name the catalog lacked count under its name now
A vote from a run that answered a name not in the catalog when it ran SHALL be counted under the type that name
resolves to when the result is read, or else under the name as answered.

#### Scenario: Votes for a type the model created
- **GIVEN** a document whose runs voted 3 for "Moon rock appraisal", not then in the catalog, and 2 for "Pay stubs"
- **WHEN** it is classified as the new type and its owner reads the result
- **THEN** its `votes` are {"Moon rock appraisal": 3, "Pay stubs": 2}

#### Scenario: A losing name the catalog never got
- **GIVEN** a document whose runs voted 4 for "Pay stubs" and 1 for "Unicorn licence", which is not a type
- **WHEN** its owner reads the result
- **THEN** its `votes` are {"Pay stubs": 4, "Unicorn licence": 1}
