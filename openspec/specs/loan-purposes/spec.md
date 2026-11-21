# loan-purposes Specification

## Purpose
Defines the loan purposes a batch can be submitted for, such as a car refinance or a home purchase. It covers the
purposes the service starts with, how callers read them, and how admins maintain them. Each purpose's description is
given to the model as context when it reads that batch's documents.

## Requirements

### Requirement: The service starts with eight loan purposes
On first start, the service SHALL provide exactly these active loan purposes, each with a name and a one-sentence
description: `CAR_PURCHASE`, `CAR_REFINANCE`, `HOME_PURCHASE`, `MORTGAGE_REFINANCE`, `BOAT_PURCHASE`,
`BOAT_REFINANCE`, `RV_PURCHASE` and `RV_REFINANCE`.

#### Scenario: Fresh database
- **WHEN** the service starts against an empty database
- **THEN** exactly those eight codes exist, all active
- **AND** each has a non-blank name and description

### Requirement: Callers can list the active loan purposes
`GET /loan-purposes` SHALL return the active loan purposes, ordered by code, each with its code, name, description
and active flag. An inactive purpose SHALL NOT be listed.

#### Scenario: List
- **WHEN** a caller with a valid token requests `GET /loan-purposes`
- **THEN** the response is 200 with the eight seeded purposes ordered by code

#### Scenario: Inactive purpose is not listed
- **GIVEN** `RV_REFINANCE` has been deactivated
- **WHEN** a caller requests `GET /loan-purposes`
- **THEN** `RV_REFINANCE` is not in the list

### Requirement: Callers can read one loan purpose
`GET /loan-purposes/{code}` SHALL return that purpose, whether or not it is active. An unknown code SHALL return 404
as a problem detail.

#### Scenario: Known code
- **WHEN** a caller requests `GET /loan-purposes/CAR_REFINANCE`
- **THEN** the response is 200 with code `CAR_REFINANCE`, its name, its description and `active: true`

#### Scenario: Unknown code
- **WHEN** a caller requests `GET /loan-purposes/SPACESHIP_PURCHASE`
- **THEN** the response is 404 as a problem detail

### Requirement: Admins can add a loan purpose
`POST /loan-purposes` SHALL create an active purpose from a code, name and description, and return 201 with it. The
code SHALL be upper-case letters, digits and underscores, starting with a letter, and at most 50 characters. The name
and description SHALL be non-blank. An invalid body SHALL return 400, and an existing code SHALL return 409.

#### Scenario: New purpose
- **WHEN** an admin posts code `MOTORCYCLE_PURCHASE` with a name and description
- **THEN** the response is 201 with the new purpose, active
- **AND** `GET /loan-purposes` now lists it

#### Scenario: Invalid code
- **WHEN** an admin posts code `motorcycle-purchase`
- **THEN** the response is 400 as a problem detail naming the `code` field

#### Scenario: Duplicate code
- **WHEN** an admin posts code `CAR_REFINANCE`
- **THEN** the response is 409 as a problem detail

### Requirement: Admins can change a loan purpose
`PUT /loan-purposes/{code}` SHALL replace the purpose's name, description and active flag and return 200 with the
result. The code SHALL NOT change. An unknown code SHALL return 404, and an invalid body SHALL return 400.

#### Scenario: Reactivate and rename
- **GIVEN** `RV_REFINANCE` is inactive
- **WHEN** an admin puts a new name, the same description and `active: true` to `/loan-purposes/RV_REFINANCE`
- **THEN** the response is 200 with the new name and `active: true`

### Requirement: Removing a loan purpose deactivates it
`DELETE /loan-purposes/{code}` SHALL mark the purpose inactive and return 204, instead of erasing it, because earlier
batches keep referring to its code. An unknown code SHALL return 404.

#### Scenario: Remove
- **WHEN** an admin deletes `/loan-purposes/BOAT_REFINANCE`
- **THEN** the response is 204
- **AND** `GET /loan-purposes` no longer lists it
- **AND** `GET /loan-purposes/BOAT_REFINANCE` still returns it with `active: false`
