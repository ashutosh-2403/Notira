package com.ashutosh.jiranotionsync.entity;

import jakarta.persistence.*;
import lombok.*;
import org.hibernate.annotations.CreationTimestamp;
import org.hibernate.annotations.UpdateTimestamp;
import java.time.LocalDateTime;
import java.util.UUID;

@Entity
@Table(name = "sync_mappings")
@Getter
@Setter
@NoArgsConstructor
@AllArgsConstructor
@Builder
public class SyncMapping {

    @Id
    @GeneratedValue(strategy = GenerationType.UUID)
    private UUID id;

    @Column(name = "jira_issue_id", nullable = false, unique = true, length = 64)
    private String jiraIssueId;

    @Column(name = "jira_issue_key", nullable = false, length = 32)
    private String jiraIssueKey;

    @Column(name = "notion_page_id", nullable = false, unique = true, length = 64)
    private String notionPageId;

    @Column(name = "jira_project_key", nullable = false, length = 32)
    private String jiraProjectKey;

    @Column(name = "notion_database_id", nullable = false, length = 64)
    private String notionDatabaseId;

    @Column(name = "last_synced_at", nullable = false)
    private LocalDateTime lastSyncedAt;

    @Column(name = "jira_updated_at")
    private LocalDateTime jiraUpdatedAt;

    @Column(name = "notion_updated_at")
    private LocalDateTime notionUpdatedAt;

    @Enumerated(EnumType.STRING)
    @Column(name = "sync_status", nullable = false, length = 16)
    private SyncStatus syncStatus = SyncStatus.ACTIVE;

    @CreationTimestamp
    @Column(name = "created_at", nullable = false, updatable = false)
    private LocalDateTime createdAt;

    @Column(name = "description_block_id")
    private String descriptionBlockId;

    @Column(name = "stage_tracker_block_id")
    private String stageTrackerBlockId;

    @Column(name = "history_block_id")
    private String historyBlockId;

    @UpdateTimestamp
    @Column(name = "updated_at", nullable = false)
    private LocalDateTime updatedAt;

    public enum SyncStatus {
        ACTIVE, PAUSED, DELETED
    }
}
