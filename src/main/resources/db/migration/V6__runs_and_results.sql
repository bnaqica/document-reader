-- Every model run the pipeline makes, the results it decides from them, and every webhook attempt (design §16).
-- Rows are written once and never changed. JSON (not JSONB) keeps key order, so extracted data reads back in schema
-- order. Foreign keys restrict deletes; the three to document_type are named so a catalog delete can tell
-- "type in use" from any other integrity error.

CREATE TABLE document_reader.separation_run (
    id             UUID        PRIMARY KEY,
    source_file_id UUID        NOT NULL REFERENCES document_reader.source_file (id),
    run_number     INT         NOT NULL CHECK (run_number >= 1),
    window_start   INT         NOT NULL,
    window_end     INT         NOT NULL,
    succeeded      BOOLEAN     NOT NULL,
    boundaries     JSON,
    created_at     TIMESTAMPTZ NOT NULL,
    CONSTRAINT ck_separation_run_window CHECK (1 <= window_start AND window_start <= window_end),
    CONSTRAINT ck_separation_run_boundaries CHECK ((boundaries IS NOT NULL) = succeeded),
    CONSTRAINT uq_separation_run_number UNIQUE (source_file_id, window_start, run_number)
);

CREATE TABLE document_reader.classification_run (
    id               UUID         PRIMARY KEY,
    document_id      UUID         NOT NULL REFERENCES document_reader.document (id),
    round            VARCHAR(16)  NOT NULL CHECK (round IN ('INITIAL', 'TIEBREAK')),
    run_number       INT          NOT NULL CHECK (run_number >= 1),
    raw_answer       TEXT,
    document_type_id UUID,
    confidence       NUMERIC(4, 3) CHECK (confidence BETWEEN 0 AND 1),
    reasoning        TEXT,
    succeeded        BOOLEAN      NOT NULL,
    created_at       TIMESTAMPTZ  NOT NULL,
    CONSTRAINT uq_classification_run_number UNIQUE (document_id, round, run_number),
    CONSTRAINT fk_classification_run_type_in_use FOREIGN KEY (document_type_id)
        REFERENCES document_reader.document_type (id)
);

CREATE TABLE document_reader.classification_result (
    document_id      UUID          PRIMARY KEY REFERENCES document_reader.document (id),
    document_type_id UUID          NOT NULL,
    votes            JSON          NOT NULL,
    agreement        NUMERIC(4, 3) NOT NULL CHECK (agreement BETWEEN 0 AND 1),
    tie_broken       BOOLEAN       NOT NULL,
    decided_by       VARCHAR(16)   NOT NULL CHECK (decided_by IN ('VOTE', 'TIEBREAK', 'CONFIDENCE')),
    decided_at       TIMESTAMPTZ   NOT NULL,
    CONSTRAINT fk_classification_result_type_in_use FOREIGN KEY (document_type_id)
        REFERENCES document_reader.document_type (id)
);

CREATE TABLE document_reader.extraction_run (
    id          UUID        PRIMARY KEY,
    document_id UUID        NOT NULL REFERENCES document_reader.document (id),
    stage       VARCHAR(16) NOT NULL CHECK (stage IN ('INITIAL', 'CONSOLIDATED', 'FINAL')),
    pass_number INT         NOT NULL CHECK (pass_number >= 1),
    data        JSON        NOT NULL,
    summary     TEXT,
    created_at  TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_extraction_run_pass UNIQUE (document_id, stage, pass_number)
);

CREATE TABLE document_reader.extraction_result (
    document_id      UUID        PRIMARY KEY REFERENCES document_reader.document (id),
    document_type_id UUID        NOT NULL,
    schema_version   INT         NOT NULL CHECK (schema_version >= 1),
    data             JSON        NOT NULL,
    summary          TEXT,
    created_at       TIMESTAMPTZ NOT NULL,
    CONSTRAINT fk_extraction_result_type_in_use FOREIGN KEY (document_type_id)
        REFERENCES document_reader.document_type (id)
);

CREATE TABLE document_reader.webhook_delivery_attempt (
    id           UUID        PRIMARY KEY,
    batch_id     UUID        NOT NULL REFERENCES document_reader.batch (id),
    attempt      INT         NOT NULL CHECK (attempt >= 1),
    http_status  INT         CHECK (http_status BETWEEN 100 AND 599),
    error        TEXT,
    attempted_at TIMESTAMPTZ NOT NULL,
    CONSTRAINT uq_webhook_delivery_attempt UNIQUE (batch_id, attempt)
);

-- The unique constraints lead with the parent id and serve every lookup. These keep a catalog delete's
-- foreign key check from scanning.
CREATE INDEX ix_classification_run_type ON document_reader.classification_run (document_type_id);
CREATE INDEX ix_classification_result_type ON document_reader.classification_result (document_type_id);
CREATE INDEX ix_extraction_result_type ON document_reader.extraction_result (document_type_id);
