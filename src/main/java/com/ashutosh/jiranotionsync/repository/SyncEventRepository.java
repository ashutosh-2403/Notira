package com.ashutosh.jiranotionsync.repository;

import com.ashutosh.jiranotionsync.entity.SyncEvent;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface SyncEventRepository extends JpaRepository<SyncEvent, UUID> {
    List<SyncEvent> findByStatusOrderByCreatedAtAsc(SyncEvent.EventStatus status);
    List<SyncEvent> findByMappingIdOrderByCreatedAtDesc(UUID mappingId);
    long countByStatus(SyncEvent.EventStatus status);
}
