-- V1__create_sync_mappings.sql
-- Maps Jira issue ID <-> Notion page ID
-- This is the core table — every synced item lives here

CREATE TABLE sync_mappings (
    id                  UUID            PRIMARY KEY DEFAULT gen_random_uuid(),
    jira_issue_id       VARCHAR(64)     NOT NULL,
    jira_issue_key      VARCHAR(32)     NOT NULL,       -- e.g. PROJ-123
    notion_page_id      VARCHAR(64)     NOT NULL,
    jira_project_key    VARCHAR(32)     NOT NULL,       -- e.g. PROJ
    notion_database_id  VARCHAR(64)     NOT NULL,
    last_synced_at      TIMESTAMP       NOT NULL DEFAULT NOW(),
    jira_updated_at     TIMESTAMP,
    notion_updated_at   TIMESTAMP,
    sync_status         VARCHAR(16)     NOT NULL DEFAULT 'ACTIVE',   -- ACTIVE | PAUSED | DELETED
    created_at          TIMESTAMP       NOT NULL DEFAULT NOW(),
    updated_at          TIMESTAMP       NOT NULL DEFAULT NOW(),

    CONSTRAINT uq_jira_issue_id     UNIQUE (jira_issue_id),
    CONSTRAINT uq_notion_page_id    UNIQUE (notion_page_id)
);

CREATE INDEX idx_sync_mappings_jira_key    ON sync_mappings (jira_issue_key);
CREATE INDEX idx_sync_mappings_project     ON sync_mappings (jira_project_key);
CREATE INDEX idx_sync_mappings_status      ON sync_mappings (sync_status);

COMMENT ON TABLE  sync_mappings                  IS 'Maps each Jira issue to its corresponding Notion page';
COMMENT ON COLUMN sync_mappings.jira_issue_id    IS 'Internal Jira issue ID (e.g. 10001)';
COMMENT ON COLUMN sync_mappings.jira_issue_key   IS 'Human-readable Jira key (e.g. PROJ-123)';
COMMENT ON COLUMN sync_mappings.notion_page_id   IS 'Notion page ID created for this issue';
COMMENT ON COLUMN sync_mappings.sync_status      IS 'ACTIVE=syncing, PAUSED=skipped, DELETED=soft deleted';
