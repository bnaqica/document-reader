-- The outbox and inbox that carry work between pipeline steps (design §13). A step writes its next messages to the
-- outbox in its own transaction; a dispatcher copies them into the inbox (same id, so a copy is never made twice);
-- workers claim inbox rows under a lease. Payloads are small id holders read by code, so JSONB is fine here.
-- aggregate_id is a batch, file or document, so it has no foreign key.

CREATE TABLE document_reader.outbox_message (
    id            UUID        PRIMARY KEY,
    message_type  VARCHAR(32) NOT NULL CHECK (message_type IN
                      ('SEPARATE_FILE', 'CLASSIFY_DOCUMENT', 'EXTRACT_DOCUMENT', 'DELIVER_WEBHOOK')),
    aggregate_id  UUID        NOT NULL,
    payload       JSONB       NOT NULL,
    created_at    TIMESTAMPTZ NOT NULL,
    dispatched_at TIMESTAMPTZ
);

-- Only undispatched rows are read by the dispatcher, oldest first.
CREATE INDEX ix_outbox_message_undispatched ON document_reader.outbox_message (created_at)
    WHERE dispatched_at IS NULL;

CREATE TABLE document_reader.inbox_message (
    id              UUID         PRIMARY KEY,   -- the outbox id, which de-duplicates
    message_type    VARCHAR(32)  NOT NULL CHECK (message_type IN
                        ('SEPARATE_FILE', 'CLASSIFY_DOCUMENT', 'EXTRACT_DOCUMENT', 'DELIVER_WEBHOOK')),
    aggregate_id    UUID         NOT NULL,
    payload         JSONB        NOT NULL,
    status          VARCHAR(16)  NOT NULL CHECK (status IN ('PENDING', 'PROCESSING', 'DONE', 'FAILED')),
    attempts        INT          NOT NULL DEFAULT 0 CHECK (attempts >= 0),
    next_attempt_at TIMESTAMPTZ  NOT NULL,
    locked_by       VARCHAR(255),
    locked_until    TIMESTAMPTZ,
    last_error      TEXT,
    created_at      TIMESTAMPTZ  NOT NULL,
    completed_at    TIMESTAMPTZ
);

-- Serves the claim: one type, PENDING and due, or PROCESSING with an expired lease.
CREATE INDEX ix_inbox_message_claim ON document_reader.inbox_message (message_type, status, next_attempt_at);
