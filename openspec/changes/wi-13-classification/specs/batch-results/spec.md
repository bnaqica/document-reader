# Spec Delta

## MODIFIED Requirements

### Requirement: A document shows its type and votes once classified
A classified document SHALL show `documentType` with its `name` and `newType`, which is true for a type the model
created during classification. It SHALL also show `classification`, with `runs` (the successful first-round runs),
`votes` (count per type name), `agreement`, and `tieBreak`. `tieBreak` SHALL be null unless the vote was tied; then it
holds the tie-break `runs`, `votes` and `decidedBy`. Before classification, both SHALL be null. A run that voted for a
name not in the catalog at the time SHALL count under the type that name resolves to now, or else under the name.

#### Scenario: Not yet classified
- **WHEN** a document is still `PENDING`
- **THEN** its `documentType` and `classification` are null

#### Scenario: Classified by a clear vote
- **GIVEN** a document whose five classification runs voted 4 for "Pay stubs" and 1 for "W-2 forms"
- **WHEN** its owner reads the result
- **THEN** its `documentType` is "Pay stubs" with `newType` false
- **AND** its `classification` has `runs` 5, `votes` {"Pay stubs": 4, "W-2 forms": 1}, `agreement` 0.8 and a null
  `tieBreak`

#### Scenario: Classified by a tie-break
- **GIVEN** a document whose first-round vote tied, and whose three tie-break runs voted 2 to 1
- **WHEN** its owner reads the result
- **THEN** its `tieBreak` has `runs` 3, those votes and `decidedBy` `TIEBREAK`

#### Scenario: A type the model created
- **WHEN** a document is classified as a type the model created
- **THEN** its `documentType` has `newType` true

#### Scenario: Votes for a type the model created
- **GIVEN** a document whose runs voted 3 for "Moon rock appraisal", not then in the catalog, and 2 for "Pay stubs"
- **WHEN** it is classified as the new type and its owner reads the result
- **THEN** its `votes` are {"Moon rock appraisal": 3, "Pay stubs": 2}
