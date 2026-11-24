# Spec Delta

## ADDED Requirements

### Requirement: A type name the model proposes is added once
When classification's winning name resolves to no type, the service SHALL first ask the model whether the name means
an existing type. If it does, the name SHALL become an alias of that type. Otherwise the model SHALL draft a strict
data schema, and a type with origin `MODEL`, that name, the drafted schema and the creating document SHALL be added.
When two documents propose the same name at once, exactly one type SHALL be created and both SHALL use it.

#### Scenario: Another name for an existing type
- **WHEN** the model proposes "Earnings statement" and confirms it is the existing "Pay stubs"
- **THEN** "Earnings statement" becomes an alias of Pay stubs, and no type is created

#### Scenario: A new kind of document
- **WHEN** the model proposes "Moon rock appraisal" and says it matches no existing type
- **THEN** a `MODEL` type "Moon rock appraisal" is created with the drafted schema and the creating document recorded

#### Scenario: Two documents at once
- **WHEN** two documents propose "Moon rock appraisal" at the same moment
- **THEN** one type exists, and both documents are classified as it
