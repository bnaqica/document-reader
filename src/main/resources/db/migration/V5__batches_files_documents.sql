-- The record of everything the service processes: a batch (one request), its source files (one per upload), each
-- file's rendered pages, and the documents found in each file.

CREATE TABLE document_reader.batch (
    id                UUID          PRIMARY KEY,
    loan_purpose_code VARCHAR(50)   NOT NULL REFERENCES document_reader.loan_purpose (code),
    async             BOOLEAN       NOT NULL,
    webhook_url       VARCHAR(2048),
    status            VARCHAR(20)   NOT NULL
        CHECK (status IN ('RECEIVED', 'PROCESSING', 'COMPLETED', 'PARTIALLY_COMPLETED', 'FAILED')),
    webhook_status    VARCHAR(20)   NOT NULL
        CHECK (webhook_status IN ('NOT_APPLICABLE', 'PENDING', 'DELIVERED', 'FAILED')),
    failure_reason    TEXT,
    file_count        INTEGER       NOT NULL CHECK (file_count > 0),
    requested_by      VARCHAR(255)  NOT NULL,   -- the token's subject
    received_at       TIMESTAMPTZ   NOT NULL,
    completed_at      TIMESTAMPTZ,
    version           BIGINT        NOT NULL DEFAULT 0
);

CREATE INDEX idx_batch_requested_by_received ON document_reader.batch (requested_by, received_at DESC);

CREATE TABLE document_reader.source_file (
    id                UUID          PRIMARY KEY,
    batch_id          UUID          NOT NULL REFERENCES document_reader.batch (id),
    file_index        INTEGER       NOT NULL CHECK (file_index >= 0),   -- upload order within the batch
    original_filename VARCHAR(512)  NOT NULL,
    content_type      VARCHAR(255)  NOT NULL,
    size_bytes        BIGINT        NOT NULL CHECK (size_bytes >= 0),
    sha256            VARCHAR(64)   NOT NULL CHECK (char_length(sha256) = 64),
    s3_bucket         VARCHAR(255),                                   -- set once stored
    s3_key            VARCHAR(1024),
    page_count        INTEGER       NOT NULL CHECK (page_count > 0),
    status            VARCHAR(20)   NOT NULL
        CHECK (status IN ('RECEIVED', 'STORED', 'SEPARATING', 'SEPARATED', 'FAILED')),
    failure_reason    TEXT,
    version           BIGINT        NOT NULL DEFAULT 0,
    CONSTRAINT uq_source_file_index UNIQUE (batch_id, file_index),
    CONSTRAINT uq_source_file_batch UNIQUE (id, batch_id)   -- target of document's (file, batch) foreign key
);

CREATE TABLE document_reader.source_file_page (
    source_file_id UUID          NOT NULL REFERENCES document_reader.source_file (id) ON DELETE CASCADE,
    page_number    INTEGER       NOT NULL CHECK (page_number > 0),
    s3_key         VARCHAR(1024) NOT NULL,
    width          INTEGER       NOT NULL CHECK (width > 0),
    height         INTEGER       NOT NULL CHECK (height > 0),
    PRIMARY KEY (source_file_id, page_number)
);

CREATE TABLE document_reader.document (
    id               UUID        PRIMARY KEY,
    batch_id         UUID        NOT NULL REFERENCES document_reader.batch (id),   -- kept so a batch reads without joins
    source_file_id   UUID        NOT NULL,
    sequence         INTEGER     NOT NULL CHECK (sequence > 0),                    -- order within the file
    start_page       INTEGER     NOT NULL CHECK (start_page > 0),
    end_page         INTEGER     NOT NULL,
    status           VARCHAR(20) NOT NULL
        CHECK (status IN ('PENDING', 'CLASSIFYING', 'CLASSIFIED', 'EXTRACTING', 'COMPLETED', 'FAILED')),
    document_type_id UUID,
    truncated        BOOLEAN     NOT NULL DEFAULT FALSE,
    failure_reason   TEXT,
    completed_at     TIMESTAMPTZ,
    version          BIGINT      NOT NULL DEFAULT 0,
    CONSTRAINT ck_document_page_range CHECK (start_page <= end_page),
    CONSTRAINT uq_document_sequence UNIQUE (source_file_id, sequence),
    -- A document belongs to its file's batch, so ownership checks on batch_id can't be fooled
    CONSTRAINT fk_document_source_file FOREIGN KEY (source_file_id, batch_id)
        REFERENCES document_reader.source_file (id, batch_id),
    -- No cascade: a document type that documents use can't be deleted (DocumentTypeServiceImpl reads this name)
    CONSTRAINT fk_document_type_in_use FOREIGN KEY (document_type_id) REFERENCES document_reader.document_type (id)
);

CREATE INDEX idx_source_file_batch ON document_reader.source_file (batch_id);
CREATE INDEX idx_document_batch ON document_reader.document (batch_id);
CREATE INDEX idx_document_source_file ON document_reader.document (source_file_id);
CREATE INDEX idx_document_type_in_use ON document_reader.document (document_type_id);

-- The link WI-02 left for this migration: a type the model created points at the document that caused it.
ALTER TABLE document_reader.document_type
    ADD CONSTRAINT fk_document_type_created_by_document
        FOREIGN KEY (created_by_document_id) REFERENCES document_reader.document (id) ON DELETE SET NULL;
