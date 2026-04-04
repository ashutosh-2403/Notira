-- V2__create_sync_events.sql
-- Logs every sync event that comes in from Jira webhook or Notion poll
-- Acts as an audit trail + queue log

CREATE TABLE sync_events (
    id              UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    mapping_id      UUID            REFERENCES sync_mappings(id) ON DELETE SET NULL,
    event_source    VARCHAR(16)     NOT NULL,   -- JIRA | NOTION
    event_type      VARCHAR(32)     NOT NULL,   -- ISSUE_CREATED | ISSUE_UPDATED | ISSUE_DELETED | COMMENT_ADDED
    payload         JSONB           NOT NULL,   -- raw webhook payload stored for debugging
    status          VARCHAR(16)     NOT NULL DEFAULT 'PENDING',  -- PENDING | PROCESSING | SUCCESS | FAILED
    retry_count     INT             NOT NULL DEFAULT 0,
    error_message   TEXT,
    processed_at    TIMESTAMP,
    created_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at      TIMESTAMP       NOT NULL DEFAULT NOW()
);

CREATE INDEX idx_sync_events_mapping_id     ON sync_events (mapping_id);
CREATE INDEX idx_sync_events_status         ON sync_events (status);
CREATE INDEX idx_sync_events_source         ON sync_events (event_source);
CREATE INDEX idx_sync_events_created_at     ON sync_events (created_at DESC);

COMMENT ON TABLE  sync_events               IS 'Audit log of every sync event processed by the system';
COMMENT ON COLUMN sync_events.event_source  IS 'JIRA=came from Jira webhook, NOTION=came from Notion poll';
COMMENT ON COLUMN sync_events.event_type    IS 'Type of event: ISSUE_CREATED, ISSUE_UPDATED, ISSUE_DELETED, COMMENT_ADDED';
COMMENT ON COLUMN sync_events.payload       IS 'Raw JSON payload from Jira webhook or Notion API for debugging';
COMMENT ON COLUMN sync_events.status        IS 'PENDING=queued, PROCESSING=in progress, SUCCESS=done, FAILED=error';
COMMENT ON COLUMN sync_events.retry_count   IS 'Number of retry attempts made for failed events';
