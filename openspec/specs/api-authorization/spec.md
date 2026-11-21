# api-authorization Specification

## Purpose
Defines who may call which endpoints. It covers how a caller's roles are read from the Keycloak bearer token, which
operations need the admin role, and how the `local` profile behaves without an identity provider.

## Requirements

### Requirement: Roles come from the token's Keycloak realm roles
The service SHALL give a caller one application role for each entry in the token's `realm_access.roles` claim. The
role name is the entry in upper case. A token without that claim SHALL give the caller no roles. The caller is still
authenticated.

#### Scenario: Admin realm role
- **WHEN** a request carries a valid token whose `realm_access.roles` contains `admin`
- **THEN** the caller has the `ADMIN` role

#### Scenario: No realm roles
- **WHEN** a request carries a valid token with no `realm_access` claim
- **THEN** the caller is authenticated with no roles

### Requirement: Reading reference data needs only a valid token
Every `GET` on `/loan-purposes` and `/document-types` (including aliases) SHALL be allowed for any caller with a valid
token, with or without roles.

#### Scenario: Reader without roles
- **WHEN** a caller whose token has no roles requests `GET /document-types`
- **THEN** the response is 200

### Requirement: Changing reference data needs the admin role
Every `POST`, `PUT` and `DELETE` on `/loan-purposes` and `/document-types` (including aliases) SHALL require the
`ADMIN` role. A caller with a valid token but without that role SHALL get 403 as a problem detail, and the data SHALL
NOT change.

#### Scenario: Non-admin write
- **WHEN** a caller without the `ADMIN` role posts a new loan purpose
- **THEN** the response is 403 as a problem detail
- **AND** the purpose is not created

#### Scenario: Admin write
- **WHEN** a caller with the `ADMIN` role posts a valid new loan purpose
- **THEN** the response is 201

### Requirement: Requests without a valid token are refused
Outside the `local` profile, a request to any endpoint except the health check, without a valid bearer token, SHALL
get 401.

#### Scenario: Write without a token
- **WHEN** a request posts a new document type with no bearer token
- **THEN** the response is 401

### Requirement: The local profile acts as an admin
With the `local` profile active, authentication is off, so every request SHALL act as a caller with the `ADMIN` role.
That way, reference data can be maintained on a developer machine without an identity provider.

#### Scenario: Local write without a token
- **WHEN** the service runs with the `local` profile and a request without a token posts a valid new loan purpose
- **THEN** the response is 201
