# Spec Delta

## ADDED Requirements

### Requirement: OpenAI settings are checked at startup
`open-ai.model` SHALL NOT be blank; a blank model SHALL stop the application from starting, naming the setting. The
service SHALL set up only the OpenAI chat model, and no embedding, image, audio or moderation model. A failed OpenAI
request SHALL be retried at most twice by the OpenAI client, because the inbox already retries whole steps.

#### Scenario: Blank model
- **WHEN** `OPENAI_MODEL` is set to an empty string
- **THEN** the application fails to start, naming `app.document-reader.open-ai.model`

#### Scenario: Only the chat model
- **WHEN** the application starts
- **THEN** it has a chat model, and no embedding, image, audio or moderation model

#### Scenario: Client retries
- **WHEN** the application starts with default settings
- **THEN** the OpenAI client retries a failed request at most twice
