-- V3__create_sync_errors.sql
-- Tracks failed syncs for retry and alerting
-- Dead letter queue equivalent in DB

CREATE TABLE sync_errors (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    event_id        UUID            REFERENCES sync_events(id) ON DELETE CASCADE,
    mapping_id      UUID            REFERENCES sync_mappings(id) ON DELETE SET NULL,
    error_code      VARCHAR(64)     NOT NULL,   -- e.g. NOTION_API_TIMEOUT, JIRA_RATE_LIMIT
    error_message   TEXT            NOT NULL,
    stack_trace     TEXT,
    resolved        BOOLEAN         NOT NULL DEFAULT FALSE,
    resolved_at     TIMESTAMP,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sync_errors_event_id       ON sync_errors (event_id);
CREATE INDEX idx_sync_errors_resolved       ON sync_errors (resolved);
CREATE INDEX idx_sync_errors_error_code     ON sync_errors (error_code);
CREATE INDEX idx_sync_errors_created_at     ON sync_errors (created_at DESC);

COMMENT ON TABLE  sync_errors               IS 'Dead letter table — failed sync events with full error details';
COMMENT ON COLUMN sync_errors.error_code    IS 'Short error code e.g. NOTION_API_TIMEOUT, JIRA_RATE_LIMIT, TRANSFORM_FAILED';
COMMENT ON COLUMN sync_errors.resolved      IS 'TRUE once the error has been manually reviewed and resolved';
