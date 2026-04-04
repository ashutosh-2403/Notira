package com.ashutosh.jiranotionsync.repository;

import com.ashutosh.jiranotionsync.entity.SyncError;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.stereotype.Repository;
import java.util.List;
import java.util.UUID;

@Repository
public interface SyncErrorRepository extends JpaRepository<SyncError, UUID> {
    List<SyncError> findByResolvedFalseOrderByCreatedAtDesc();
    List<SyncError> findByErrorCode(String errorCode);
    long countByResolvedFalse();
}
