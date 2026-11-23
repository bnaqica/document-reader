# Spec Delta

## Purpose

Defines what each OpenAI vision call sends and returns: separation, classification, tie-break, new-type
confirmation, schema drafting, extraction, consolidation and final extraction. It also defines how a failed call or an
unusable answer is reported, and how every call is logged without keeping borrower data.

## ADDED Requirements

### Requirement: Calls send the pages and the loan purpose
Every call except consolidation SHALL send the given page images, read from storage by key, as PNG images in the
order given. Consolidation SHALL send no images. Every call SHALL include the loan purpose description.

#### Scenario: Images in order
- **GIVEN** page images for pages 1, 2 and 3
- **WHEN** an extraction call is made
- **THEN** the request holds the three images as PNG, in that order, and the purpose description

#### Scenario: Consolidation
- **WHEN** a consolidation call is made with three extraction passes
- **THEN** the request holds no images, and holds the schema and all three passes

### Requirement: Each call sends its step's inputs
Separation SHALL send the catalog's type names. Classification and new-type confirmation SHALL send every catalog
entry's name and description, and confirmation also the proposed name. Tie-break SHALL send only the tied candidates.
Schema drafting SHALL send the type's name. Extraction SHALL send the type's data schema. Final extraction SHALL send
the schema, the consolidated draft and any validation errors from an earlier attempt.

#### Scenario: Classification
- **GIVEN** three page images, a catalog of 41 types and the loan purpose "Home purchase"
- **WHEN** a classification call is made
- **THEN** the request holds the three images in page order, all 41 type names and descriptions, and the purpose
  description

#### Scenario: Tie-break
- **GIVEN** a tie between "Pay stub" and "W-2"
- **WHEN** a tie-break call is made
- **THEN** the request lists only those two types

#### Scenario: Final extraction
- **GIVEN** a consolidated draft and the validation error "data.grossPay: required"
- **WHEN** a final extraction call is made
- **THEN** the request holds the schema, the draft and that error

### Requirement: Calls use the configured model and the step's temperature
Every call SHALL name the configured OpenAI model. Separation SHALL use the separation temperature. Classification and
tie-break SHALL use the classification temperature. New-type confirmation, schema drafting, extraction, consolidation
and final extraction SHALL use the extraction temperature.

#### Scenario: Classification temperature
- **GIVEN** a classification temperature of 0.7 and a model of `gpt-4o`
- **WHEN** a classification call is made
- **THEN** the request names `gpt-4o` with temperature 0.7

#### Scenario: Changed model
- **GIVEN** the model is configured as `gpt-4.1`
- **WHEN** any call is made
- **THEN** the request names `gpt-4.1`

### Requirement: Answers come back as structured data
Every call SHALL ask for a JSON answer in a fixed shape and return it as typed values. Extraction and final
extraction SHALL ask for `data` in the document type's own schema plus a `summary`. Consolidation SHALL also return
the fields where the passes disagreed. A drafted schema SHALL follow the catalog's schema rules.

#### Scenario: Extraction
- **GIVEN** the pay stub schema
- **WHEN** an extraction call is made
- **THEN** the request asks for an answer whose `data` follows the pay stub schema, plus a `summary`

#### Scenario: Classification answer
- **WHEN** the model answers `{"documentType": "Pay stub", "confidence": 0.9, "reasoning": "..."}`
- **THEN** the call returns type "Pay stub" with confidence 0.9

#### Scenario: Separation answer
- **WHEN** the model answers `{"startPages": [1, 4], "labels": ["Pay stub", "W-2"]}`
- **THEN** the call returns start pages 1 and 4 with those labels

### Requirement: Failed calls and unusable answers raise one vision error
A call SHALL raise a vision error, naming the call's purpose and never the prompt or the answer, when:
- OpenAI returns an HTTP error or can't be reached;
- the answer is empty or isn't valid JSON;
- a required value is missing, or a confidence lies outside 0 to 1;
- a drafted schema breaks the catalog's schema rules;
- a page image can't be read from storage.

#### Scenario: HTTP error
- **WHEN** OpenAI answers a classification call with HTTP 429
- **THEN** the call raises a vision error naming classification and status 429

#### Scenario: Invalid JSON
- **WHEN** the model answers an extraction call with text that isn't JSON
- **THEN** the call raises a vision error

#### Scenario: Confidence out of range
- **WHEN** the model answers a classification call with a confidence of 1.5
- **THEN** the call raises a vision error

### Requirement: Every call is logged without its content
Each call that reaches OpenAI SHALL be logged once, after it returns or fails. The log holds the batch, the file, the
document (none for separation), the purpose, the model, the latency, the prompt and completion tokens, the HTTP status
and, for a failure, a short error that holds no prompt or answer text. If the log can't be written, the call's answer
SHALL still be returned.

#### Scenario: Successful call
- **GIVEN** a call for batch B, file F and document D
- **WHEN** a classification call succeeds using 1,200 prompt tokens and 80 completion tokens
- **THEN** one log row holds B, F, D, purpose classify, the model, the latency, 1,200 and 80 tokens and status 200
- **AND** the row holds none of the prompt or the answer

#### Scenario: Failed call
- **WHEN** a call fails with HTTP 500
- **THEN** one log row holds status 500 and an error, and the call raises a vision error

#### Scenario: Separation
- **WHEN** a separation call is made
- **THEN** its log row has no document

### Requirement: Calls never run inside a database transaction
A call SHALL be refused, before anything is sent, when a database transaction is open on the calling thread. A
refused call SHALL NOT be logged.

#### Scenario: Inside a transaction
- **WHEN** a classification call is made inside an open database transaction
- **THEN** it is refused, nothing is sent to OpenAI, and nothing is logged
