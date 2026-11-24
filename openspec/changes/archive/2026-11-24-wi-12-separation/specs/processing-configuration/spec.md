# Spec Delta

## ADDED Requirements

### Requirement: Separation windows overlap by at least one page and less than a window
The service SHALL refuse to start when `separation.window-overlap` is less than 1 or not less than
`separation.window-size`, with a message naming the overlap setting. Every gap between two pages then lies inside some
window, and each window starts after the one before it.

#### Scenario: Overlap equal to the window
- **WHEN** the service starts with window size 15 and overlap 15
- **THEN** startup fails with a message naming the window overlap setting

#### Scenario: No overlap
- **WHEN** the service starts with overlap 0
- **THEN** startup fails with a message naming the window overlap setting

#### Scenario: Default windows
- **WHEN** the service starts with window size 15 and overlap 2
- **THEN** startup succeeds
