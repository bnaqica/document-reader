# Spec Delta

## MODIFIED Requirements

### Requirement: Upload size limits follow the file and batch limits
The service SHALL refuse an HTTP upload whose single file exceeds the file size limit, or whose whole request exceeds
the batch size limit, before processing it. A refused upload within the batch size limit SHALL be answered with a 413
problem response, rather than a dropped connection.

#### Scenario: Default limits
- **WHEN** the service starts with default limits
- **THEN** a multipart upload may carry files of at most 50 MB and a request of at most 200 MB

#### Scenario: Changed file limit
- **WHEN** the file size limit is configured as 10 MB
- **THEN** a multipart upload with a 10.5 MB file is refused

#### Scenario: Large refused upload gets an answer
- **WHEN** a client uploads a 60 MB file against the default 50 MB file limit
- **THEN** it receives a 413 problem response, and the connection isn't reset
