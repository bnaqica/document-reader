-- Reference data: the loan purposes a batch can be for, and the catalog of document types with their aliases.

CREATE TABLE document_reader.loan_purpose (
    code        VARCHAR(50)  PRIMARY KEY,
    name        VARCHAR(100) NOT NULL,
    description TEXT         NOT NULL,       -- given to the model as context for a batch's documents
    active      BOOLEAN      NOT NULL DEFAULT TRUE
);

CREATE TABLE document_reader.document_type (
    id                     UUID         PRIMARY KEY,
    name                   VARCHAR(200) NOT NULL,
    normalized_key         VARCHAR(200) NOT NULL,   -- TypeNameNormalizer.key(name)
    description            TEXT         NOT NULL,
    data_schema            JSON         NOT NULL,   -- strict JSON Schema; JSON (not JSONB) keeps the authored key order
    schema_version         INTEGER      NOT NULL,
    origin                 VARCHAR(10)  NOT NULL CHECK (origin IN ('SEEDED', 'MANUAL', 'MODEL')),
    created_by_document_id UUID,                    -- set for MODEL types; the foreign key arrives with `document`
    created_at             TIMESTAMPTZ  NOT NULL,
    version                BIGINT       NOT NULL DEFAULT 0,
    CONSTRAINT uq_document_type_key UNIQUE (normalized_key)
);

CREATE TABLE document_reader.document_type_alias (
    id               UUID         PRIMARY KEY,
    document_type_id UUID         NOT NULL REFERENCES document_reader.document_type (id) ON DELETE CASCADE,
    alias            VARCHAR(200) NOT NULL,
    normalized_key   VARCHAR(200) NOT NULL,
    CONSTRAINT uq_document_type_alias_key UNIQUE (normalized_key)
);

CREATE INDEX idx_document_type_alias_type ON document_reader.document_type_alias (document_type_id);

-- A key must belong to one type, whether as the type's own key or an alias. The unique constraints cover each table;
-- this trigger covers a type and an alias sharing a key. The advisory lock serializes transactions working on the
-- same key, and the check then runs on a fresh snapshot, so two racing inserts can't both pass. That relies on
-- READ COMMITTED (PostgreSQL's default): under REPEATABLE READ the snapshot would predate the other commit.
CREATE FUNCTION document_reader.reject_shared_document_type_key() RETURNS TRIGGER
    LANGUAGE plpgsql AS
$$
BEGIN
    PERFORM pg_advisory_xact_lock(hashtext(NEW.normalized_key));
    IF TG_TABLE_NAME = 'document_type' THEN
        IF EXISTS (SELECT 1 FROM document_reader.document_type_alias WHERE normalized_key = NEW.normalized_key) THEN
            RAISE EXCEPTION 'key % already belongs to a document type alias', NEW.normalized_key
                USING ERRCODE = 'unique_violation', CONSTRAINT = 'uq_document_type_shared_key';
        END IF;
    ELSIF EXISTS (SELECT 1 FROM document_reader.document_type
                  WHERE normalized_key = NEW.normalized_key AND id <> NEW.document_type_id) THEN
        RAISE EXCEPTION 'key % already belongs to a document type', NEW.normalized_key
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'uq_document_type_shared_key';
    ELSIF EXISTS (SELECT 1 FROM document_reader.document_type
                  WHERE normalized_key = NEW.normalized_key AND id = NEW.document_type_id) THEN
        RAISE EXCEPTION 'key % is already the name of its own document type', NEW.normalized_key
            USING ERRCODE = 'unique_violation', CONSTRAINT = 'uq_document_type_shared_key';
    END IF;
    RETURN NEW;
END
$$;

CREATE TRIGGER trg_document_type_shared_key
    BEFORE INSERT OR UPDATE OF normalized_key ON document_reader.document_type
    FOR EACH ROW EXECUTE FUNCTION document_reader.reject_shared_document_type_key();

CREATE TRIGGER trg_document_type_alias_shared_key
    BEFORE INSERT OR UPDATE OF normalized_key ON document_reader.document_type_alias
    FOR EACH ROW EXECUTE FUNCTION document_reader.reject_shared_document_type_key();
