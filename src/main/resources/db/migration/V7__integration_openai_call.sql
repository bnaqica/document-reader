-- One row per OpenAI call, for cost and latency reporting per batch (design §16). It lives in the integration
-- schema, apart from the ERD, and has no foreign keys into it: logging a call must never fail because of a row
-- elsewhere. There is deliberately no column for prompt or response bodies, because those carry borrower data (§18).

CREATE TABLE document_reader_integration.openai_call (
    id                UUID         PRIMARY KEY,
    batch_id          UUID         NOT NULL,
    source_file_id    UUID         NOT NULL,
    document_id       UUID,                   -- null for separation
    purpose           VARCHAR(16)  NOT NULL CHECK (purpose IN ('SEPARATE', 'CLASSIFY', 'TIEBREAK', 'CONFIRM_TYPE',
                                                               'DRAFT_SCHEMA', 'EXTRACT', 'CONSOLIDATE', 'FINAL')),
    model             VARCHAR(255) NOT NULL,
    latency_ms        BIGINT       NOT NULL CHECK (latency_ms >= 0),
    prompt_tokens     INT          CHECK (prompt_tokens >= 0),
    completion_tokens INT          CHECK (completion_tokens >= 0),
    http_status       INT          CHECK (http_status BETWEEN 100 AND 599),
    error             TEXT,
    created_at        TIMESTAMPTZ  NOT NULL
);

CREATE INDEX ix_openai_call_batch ON document_reader_integration.openai_call (batch_id, created_at);
