package com.ashutosh.jiranotionsync.service;

import com.ashutosh.jiranotionsync.dto.JiraWebhookPayload;
import com.ashutosh.jiranotionsync.entity.SyncEvent;
import com.ashutosh.jiranotionsync.entity.SyncMapping;
import com.ashutosh.jiranotionsync.repository.SyncEventRepository;
import com.ashutosh.jiranotionsync.repository.SyncMappingRepository;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
@Slf4j
public class JiraWebhookService {

    private final SyncEventRepository syncEventRepository;
    private final SyncMappingRepository syncMappingRepository;
    private final SyncQueueService syncQueueService;
    private final SyncProcessorService syncProcessorService;
    private final ObjectMapper objectMapper;

    @Transactional
    public void processWebhookEvent(JiraWebhookPayload payload) throws Exception {

        String webhookEvent = payload.getWebhookEvent();
        log.info("Processing webhook event: {}", webhookEvent);

        SyncEvent.EventType eventType = mapWebhookEventToType(webhookEvent, payload);
        if (eventType == null) {
            log.info("Ignoring unhandled webhook event type: {}", webhookEvent);
            return;
        }

        SyncMapping mapping = null;
        if (payload.getIssue() != null) {
            mapping = syncMappingRepository
                    .findByJiraIssueId(payload.getIssue().getId())
                    .orElse(null);
        }

        String rawPayload = objectMapper.writeValueAsString(payload);

        SyncEvent event = SyncEvent.builder()
                .mapping(mapping)
                .eventSource(SyncEvent.EventSource.JIRA)
                .eventType(eventType)
                .payload(rawPayload)
                .status(SyncEvent.EventStatus.PENDING)
                .retryCount(0)
                .build();

        SyncEvent savedEvent = syncEventRepository.save(event);
        log.info("Saved sync event: {} with id: {}", eventType, savedEvent.getId());

        // Try Redis queue first, fall back to direct processing
        try {
            syncQueueService.enqueue(savedEvent.getId().toString());
            log.info("Event queued in Redis: {}", savedEvent.getId());
        } catch (Exception e) {
            log.warn("Redis unavailable, processing directly: {}", e.getMessage());
            syncProcessorService.processEvent(savedEvent.getId());
        }
    }

    private SyncEvent.EventType mapWebhookEventToType(
            String webhookEvent, JiraWebhookPayload payload) {

        return switch (webhookEvent) {
            case "jira:issue_created"   -> SyncEvent.EventType.ISSUE_CREATED;
            case "jira:issue_updated"   -> {
                if (isStatusChange(payload)) {
                    yield SyncEvent.EventType.STATUS_CHANGED;
                }
                yield SyncEvent.EventType.ISSUE_UPDATED;
            }
            case "jira:issue_deleted"   -> SyncEvent.EventType.ISSUE_DELETED;
            case "comment_created",
                 "comment_updated"      -> SyncEvent.EventType.COMMENT_ADDED;
            default                     -> null;
        };
    }

    private boolean isStatusChange(JiraWebhookPayload payload) {
        if (payload.getChangelog() == null || payload.getChangelog().getItems() == null) {
            return false;
        }
        return payload.getChangelog().getItems().stream()
                .anyMatch(item -> "status".equalsIgnoreCase(item.getField()));
    }
}
