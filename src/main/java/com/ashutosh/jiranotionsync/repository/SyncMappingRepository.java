package com.ashutosh.jiranotionsync.repository;

import com.ashutosh.jiranotionsync.entity.SyncMapping;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.Optional;
import java.util.UUID;

@Repository
public interface SyncMappingRepository extends JpaRepository<SyncMapping, UUID> {
    Optional<SyncMapping> findByJiraIssueId(String jiraIssueId);
    Optional<SyncMapping> findByNotionPageId(String notionPageId);
    Optional<SyncMapping> findByJiraIssueKey(String jiraIssueKey);
    boolean existsByJiraIssueId(String jiraIssueId);
    boolean existsByNotionPageId(String notionPageId);
}