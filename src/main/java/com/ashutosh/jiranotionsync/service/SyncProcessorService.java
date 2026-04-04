package com.ashutosh.jiranotionsync.service;

import com.ashutosh.jiranotionsync.dto.JiraWebhookPayload;
import com.ashutosh.jiranotionsync.dto.NotionPageRequest;
import com.ashutosh.jiranotionsync.entity.SyncError;
import com.ashutosh.jiranotionsync.entity.SyncEvent;
import com.ashutosh.jiranotionsync.entity.SyncMapping;
import com.ashutosh.jiranotionsync.repository.SyncErrorRepository;
import com.ashutosh.jiranotionsync.repository.SyncEventRepository;
import com.ashutosh.jiranotionsync.repository.SyncMappingRepository;
import com.ashutosh.jiranotionsync.transform.JiraToNotionTransformer;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
@RequiredArgsConstructor
@Slf4j
public class SyncProcessorService {

    private final SyncEventRepository syncEventRepository;
    private final SyncMappingRepository syncMappingRepository;
    private final SyncErrorRepository syncErrorRepository;
    private final SyncQueueService syncQueueService;
    private final JiraToNotionTransformer transformer;
    private final NotionWriterService notionWriterService;
    private final AiSummaryService aiSummaryService;
    private final ObjectMapper objectMapper;

    @Value("${app.sync.max-retry-attempts:3}")
    private int maxRetryAttempts;

    @Value("${app.notion.database-id}")
    private String defaultNotionDatabaseId;

    @Value("${app.public-url:}")
    private String appPublicUrl;

    @Scheduled(fixedDelay = 5000)
    public void processPendingEvents() {
        try {
            String eventId = syncQueueService.dequeue();
            if (eventId == null) return;
            processEvent(UUID.fromString(eventId));
        } catch (Exception e) {
            log.debug("Queue processing skipped: {}", e.getMessage());
        }
    }

    @Transactional
    public void processEvent(UUID eventId) {
        SyncEvent event = syncEventRepository.findById(eventId)
                .orElseThrow(() -> new RuntimeException("Event not found: " + eventId));
        event.setStatus(SyncEvent.EventStatus.PROCESSING);
        syncEventRepository.save(event);
        try {
            JiraWebhookPayload payload = objectMapper.readValue(
                    event.getPayload(), JiraWebhookPayload.class);
            switch (event.getEventType()) {
                case ISSUE_CREATED -> handleIssueCreated(event, payload);
                case ISSUE_UPDATED,
                     STATUS_CHANGED -> handleIssueUpdated(event, payload);
                case ISSUE_DELETED  -> handleIssueDeleted(event, payload);
                case COMMENT_ADDED  -> handleCommentAdded(event, payload);
            }
            event.setStatus(SyncEvent.EventStatus.SUCCESS);
            event.setProcessedAt(LocalDateTime.now());
            syncEventRepository.save(event);
        } catch (Exception e) {
            handleProcessingFailure(event, e);
        }
    }

    private void handleIssueCreated(SyncEvent event, JiraWebhookPayload payload) throws Exception {
        String jiraIssueId = payload.getIssue().getId();
        String jiraIssueKey = payload.getIssue().getKey();

        if (syncMappingRepository.existsByJiraIssueId(jiraIssueId)) {
            log.info("Mapping already exists for {} — skipping", jiraIssueKey);
            return;
        }

        NotionPageRequest pageRequest = transformer.transform(payload, defaultNotionDatabaseId);
        String notionPageId = notionWriterService.createPage(pageRequest);

        Thread.sleep(2000);

        // Fetch ALL blocks and log them for debugging
        List<Map<String, Object>> blocks = notionWriterService.getPageBlocks(notionPageId);
        log.info("Page has {} blocks", blocks.size());
        for (int i = 0; i < blocks.size(); i++) {
            String type = (String) blocks.get(i).get("type");
            String id = (String) blocks.get(i).get("id");
            String text = extractBlockText(blocks.get(i), type);
            log.info("Block[{}] type={} id={} text={}", i, type, id, truncate(text, 50));
        }

        String descriptionBlockId = findParagraphAfterHeading(blocks, "Description");
        String stageTrackerBlockId = findParagraphAfterHeading(blocks, "Stage Tracker");

        log.info("desc block: {}, tracker block: {}", descriptionBlockId, stageTrackerBlockId);

        SyncMapping mapping = SyncMapping.builder()
                .jiraIssueId(jiraIssueId)
                .jiraIssueKey(jiraIssueKey)
                .notionPageId(notionPageId)
                .jiraProjectKey(payload.getIssue().getFields().getProject().getKey())
                .notionDatabaseId(defaultNotionDatabaseId)
                .descriptionBlockId(descriptionBlockId)
                .stageTrackerBlockId(stageTrackerBlockId)
                .lastSyncedAt(LocalDateTime.now())
                .syncStatus(SyncMapping.SyncStatus.ACTIVE)
                .updatedAt(LocalDateTime.now())
                .build();

        syncMappingRepository.save(mapping);
        log.info("Created Notion page {} for {}", notionPageId, jiraIssueKey);

        // Generate and write AI summary for the new page
        updateAiSummary(mapping, payload);
    }

    private void handleIssueUpdated(SyncEvent event, JiraWebhookPayload payload) throws Exception {
        String jiraIssueId = payload.getIssue().getId();
        SyncMapping mapping = syncMappingRepository.findByJiraIssueId(jiraIssueId).orElse(null);

        if (mapping == null) {
            handleIssueCreated(event, payload);
            return;
        }

        // Skip processing if the page has already been deleted/archived
        if (mapping.getSyncStatus() == SyncMapping.SyncStatus.DELETED) {
            log.info("Skipping update for deleted/archived mapping: {}", jiraIssueId);
            return;
        }

        if (payload.getChangelog() == null || payload.getChangelog().getItems() == null) return;

        String author = payload.getUser() != null ?
                payload.getUser().getDisplayName() : "Unknown";
        String timestamp = formatTimestamp(payload.getTimestamp());
        List<Map<String, Object>> historyChildren = new ArrayList<>();

        for (JiraWebhookPayload.JiraChangelogItem item : payload.getChangelog().getItems()) {
            String field = item.getField() != null ? item.getField().toLowerCase() : "";
            String from = item.getFromString() != null ? item.getFromString() : "—";
            String to = item.getToString() != null ? item.getToString() : "—";

            switch (field) {
                case "description" -> {
                    // Strip Jira wiki image markup (!filename!) before updating description
                    String cleanTo   = stripJiraImageMarkup(to);
                    String cleanFrom = stripJiraImageMarkup(from);
                    updateDescriptionInPlace(mapping, cleanTo, payload);
                    historyChildren.add(paragraph("📝  Description updated"));
                    historyChildren.add(paragraph("Before:  " + truncate(cleanFrom, 200)));
                    historyChildren.add(paragraph("After:  " + truncate(cleanTo, 200)));
                }
                case "status" -> {
                    updateStageTrackerInPlace(mapping, to);
                    historyChildren.add(paragraph("🔄  Status:  " + from + "  →  " + to));
                }
                case "assignee" ->
                    historyChildren.add(paragraph("👤  Assignee:  " + from + "  →  " + to));
                case "priority" ->
                    historyChildren.add(paragraph("🚨  Priority:  " + from + "  →  " + to));
                case "summary" ->
                    historyChildren.add(paragraph("✏️  Title:  " + from + "  →  " + to));
                case "attachment" -> {
                    String filename = item.getToString();
                    String attachmentId = item.getTo();
                    if (filename != null) {
                        String fnLower = filename.toLowerCase();
                        boolean isImage = fnLower.endsWith(".png") || fnLower.endsWith(".jpg")
                                || fnLower.endsWith(".jpeg") || fnLower.endsWith(".gif")
                                || fnLower.endsWith(".webp");
                        if (isImage) {
                            historyChildren.add(paragraph("🖼️  Attachment added:  " + filename));
                            String imageUrl = findAttachmentUrl(payload, attachmentId, filename);
                            if (imageUrl != null) {
                                try {
                                    List<Map<String, Object>> allBlocks =
                                            notionWriterService.getPageBlocks(mapping.getNotionPageId());
                                    String toggleId = findToggleContaining(allBlocks, "All Attachments");

                                    // If Attachments section doesn't exist yet, create it on the page
                                    if (toggleId == null) {
                                        log.info("No Attachments section found — creating it for page {}",
                                                mapping.getNotionPageId());
                                        List<String> newIds = notionWriterService.appendBlocksAndGetIds(
                                                mapping.getNotionPageId(), List.of(
                                                        divider(),
                                                        heading2("📎  Attachments"),
                                                        toggleBlockMap("📎  All Attachments")));
                                        // toggle is the 3rd block appended (divider, heading2, toggle)
                                        if (newIds.size() >= 3) toggleId = newIds.get(2);
                                    }

                                    if (toggleId != null) {
                                        notionWriterService.appendBlocks(toggleId, List.of(
                                                paragraph("🖼️  " + filename),
                                                imageBlock(imageUrl)));
                                        log.info("✅ Embedded image '{}' in Attachments toggle", filename);
                                    } else {
                                        notionWriterService.appendBlocks(mapping.getNotionPageId(),
                                                List.of(paragraph("🖼️  " + filename), imageBlock(imageUrl)));
                                    }
                                } catch (Exception e) {
                                    log.error("❌ Failed to embed attachment image: {}", e.getMessage());
                                }
                            }
                        } else {
                            historyChildren.add(paragraph("📎  Attachment added:  " + filename));
                        }
                    }
                }
                default -> {
                    if (!"resolution".equals(field)) {
                        historyChildren.add(paragraph("📌  " + capitalize(field)
                                + ":  " + from + "  →  " + to));
                    }
                }
            }
        }

        if (!historyChildren.isEmpty()) {
            String toggleTitle = "🕐  " + timestamp + "   ·   " + author;
            Map<String, Object> toggleBlock = Map.of(
                    "object", "block",
                    "type", "toggle",
                    "toggle", Map.of(
                            "rich_text", List.of(Map.of("text",
                                    Map.of("content", toggleTitle)))));
            List<String> ids = notionWriterService.appendBlocksAndGetIds(
                    mapping.getNotionPageId(), List.of(toggleBlock));
            if (!ids.isEmpty()) {
                notionWriterService.appendBlocks(ids.get(0), historyChildren);
            }
        }

        mapping.setLastSyncedAt(LocalDateTime.now());
        mapping.setJiraUpdatedAt(LocalDateTime.now());
        mapping.setUpdatedAt(LocalDateTime.now());
        syncMappingRepository.save(mapping);

        // Refresh AI summary whenever the issue changes
        updateAiSummary(mapping, payload);
    }

    private void updateDescriptionInPlace(SyncMapping mapping, String newDescription,
                                          JiraWebhookPayload payload) {
        try {
            // Always fetch fresh blocks — never rely on stale/corrupted stored ID
            List<Map<String, Object>> blocks =
                    notionWriterService.getPageBlocks(mapping.getNotionPageId());

            String blockId = findParagraphAfterHeading(blocks, "Description");
            if (blockId != null) {
                if (!blockId.equals(mapping.getDescriptionBlockId())) {
                    mapping.setDescriptionBlockId(blockId);
                    syncMappingRepository.save(mapping);
                }
                // Always strip any leftover Jira markup before saving
                String cleanDesc = stripJiraImageMarkup(newDescription).replace("\n", " ").trim();
                notionWriterService.updateBlock(blockId, "paragraph",
                        cleanDesc.isEmpty() ? "No description provided." : cleanDesc);
                log.info("✅ Updated description for {}", mapping.getJiraIssueKey());
            } else {
                log.error("❌ Could not find description block for {} — blocks returned: {}",
                        mapping.getJiraIssueKey(), blocks.size());
            }

            // Extract images from the raw (pre-strip) description and embed them
            if (payload != null) {
                List<String> imageFilenames = extractJiraImageFilenames(newDescription);
                for (String filename : imageFilenames) {
                    String imageUrl = findAttachmentUrl(payload, null, filename);
                    if (imageUrl != null) {
                        log.info("🖼️ Embedding image '{}' into description area for {}",
                                filename, mapping.getJiraIssueKey());
                        notionWriterService.appendBlocks(mapping.getNotionPageId(),
                                List.of(
                                    paragraph("🖼️  " + filename),
                                    imageBlock(imageUrl)
                                ));
                    } else {
                        log.warn("No attachment URL found for image '{}'", filename);
                    }
                }
            }
        } catch (Exception e) {
            log.error("❌ Exception updating description for {}: {}",
                    mapping.getJiraIssueKey(), e.getMessage(), e);
        }
    }

    private void updateAiSummary(SyncMapping mapping, JiraWebhookPayload payload) {
        try {
            JiraWebhookPayload.JiraIssueFields fields = payload.getIssue().getFields();

            // Collect attachment filenames for context
            List<String> attachmentNames = new ArrayList<>();
            if (fields.getAttachments() != null) {
                fields.getAttachments().forEach(a -> attachmentNames.add(a.getFilename()));
            }

            String description = extractDescriptionText(fields.getDescription());
            String status   = fields.getStatus()    != null ? fields.getStatus().getName()    : "Unknown";
            String type     = fields.getIssueType() != null ? fields.getIssueType().getName() : "Task";
            String priority = fields.getPriority()  != null ? fields.getPriority().getName()  : "Medium";
            String assignee = fields.getAssignee()  != null ? fields.getAssignee().getDisplayName() : null;

            String summary = aiSummaryService.generateSummary(
                    payload.getIssue().getKey(),
                    fields.getSummary(),
                    description,
                    status, type, priority, assignee,
                    attachmentNames);

            if (summary == null) return;

            // Find the callout block (first callout on the page) and update it
            List<Map<String, Object>> blocks =
                    notionWriterService.getPageBlocks(mapping.getNotionPageId());
            String calloutId = findCalloutBlock(blocks);
            if (calloutId != null) {
                notionWriterService.updateBlock(calloutId, "callout", summary);
                log.info("✅ AI Summary updated on Notion page for {}", mapping.getJiraIssueKey());
            } else {
                log.warn("No callout block found on page {} — appending AI summary",
                        mapping.getNotionPageId());
                notionWriterService.appendBlocks(mapping.getNotionPageId(),
                        List.of(Map.of("object", "block", "type", "callout",
                                "callout", Map.of(
                                        "rich_text", List.of(Map.of("text", Map.of("content", summary))),
                                        "icon", Map.of("type", "emoji", "emoji", "💡")))));
            }
        } catch (Exception e) {
            log.error("❌ Failed to update AI summary for {}: {}",
                    mapping.getJiraIssueKey(), e.getMessage());
        }
    }

    // Find the first callout block on the page
    private String findCalloutBlock(List<Map<String, Object>> blocks) {
        for (Map<String, Object> block : blocks) {
            if ("callout".equals(block.get("type"))) {
                return (String) block.get("id");
            }
        }
        return null;
    }

    // Extract plain text from ADF description object
    private String extractDescriptionText(Object desc) {
        if (desc == null) return "";
        if (desc instanceof String s) return s;
        StringBuilder sb = new StringBuilder();
        try {
            if (desc instanceof Map<?, ?> node) {
                Object content = node.get("content");
                if (content instanceof List<?> items) {
                    for (Object item : items) sb.append(extractDescriptionText(item)).append(" ");
                }
                Object text = node.get("text");
                if (text instanceof String t) sb.append(t);
            }
        } catch (Exception ignored) {}
        return sb.toString().trim();
    }

    private void updateStageTrackerInPlace(SyncMapping mapping, String newStatus) {
        try {
            // Always fetch fresh blocks — never rely on stale stored ID
            List<Map<String, Object>> blocks =
                    notionWriterService.getPageBlocks(mapping.getNotionPageId());

            // Update stage tracker paragraph
            String trackerBlockId = findParagraphAfterHeading(blocks, "Stage Tracker");
            if (trackerBlockId != null) {
                if (!trackerBlockId.equals(mapping.getStageTrackerBlockId())) {
                    mapping.setStageTrackerBlockId(trackerBlockId);
                    syncMappingRepository.save(mapping);
                }
                String newTracker = transformer.buildTracker(newStatus.toLowerCase());
                notionWriterService.updateBlock(trackerBlockId, "paragraph", newTracker);
                log.info("✅ Updated stage tracker to '{}' for {}", newStatus, mapping.getJiraIssueKey());
            } else {
                log.error("❌ Could not find stage tracker block for {} — blocks returned: {}", mapping.getJiraIssueKey(), blocks.size());
            }

            // Also update the Status bullet in the Details section
            String statusBulletId = findBulletContaining(blocks, "Status:");
            if (statusBulletId != null) {
                notionWriterService.updateBlock(statusBulletId, "bulleted_list_item",
                        "📌  Status:  " + newStatus);
                log.info("✅ Updated status bullet to '{}' for {}", newStatus, mapping.getJiraIssueKey());
            } else {
                log.error("❌ Could not find status bullet block for {}", mapping.getJiraIssueKey());
            }
        } catch (Exception e) {
            log.error("❌ Exception updating stage tracker for {}: {}", mapping.getJiraIssueKey(), e.getMessage(), e);
        }
    }

    // Find first bulleted_list_item block whose text contains the keyword
    private String findBulletContaining(List<Map<String, Object>> blocks, String keyword) {
        for (Map<String, Object> block : blocks) {
            String type = (String) block.get("type");
            if ("bulleted_list_item".equals(type)) {
                String text = extractBlockText(block, type);
                if (text.contains(keyword)) {
                    return (String) block.get("id");
                }
            }
        }
        return null;
    }

    // Find first toggle block whose text contains the keyword
    private String findToggleContaining(List<Map<String, Object>> blocks, String keyword) {
        for (Map<String, Object> block : blocks) {
            String type = (String) block.get("type");
            if ("toggle".equals(type)) {
                String text = extractBlockText(block, type);
                if (text != null && text.contains(keyword)) {
                    String id = (String) block.get("id");
                    log.info("Found toggle '{}' block: {}", keyword, id);
                    return id;
                }
            }
        }
        log.warn("Could not find toggle block containing: {}", keyword);
        return null;
    }

    // Find first paragraph block ID after a heading containing the keyword
    private String findParagraphAfterHeading(List<Map<String, Object>> blocks, String keyword) {
        boolean foundHeading = false;
        for (Map<String, Object> block : blocks) {
            String type = (String) block.get("type");
            String id = (String) block.get("id");

            if (type == null) continue;

            if (type.startsWith("heading_")) {
                String text = extractBlockText(block, type);
                if (text.contains(keyword)) {
                    foundHeading = true;
                    continue;
                } else if (foundHeading) {
                    break; // hit next heading without finding paragraph
                }
            }

            if (foundHeading && "paragraph".equals(type)) {
                log.info("Found {} paragraph block: {}", keyword, id);
                return id;
            }
        }
        log.warn("Could not find paragraph block after heading: {}", keyword);
        return null;
    }

    private void handleIssueDeleted(SyncEvent event, JiraWebhookPayload payload) throws Exception {
        String jiraIssueId = payload.getIssue().getId();
        syncMappingRepository.findByJiraIssueId(jiraIssueId).ifPresent(mapping -> {
            try {
                notionWriterService.archivePage(mapping.getNotionPageId());
                mapping.setSyncStatus(SyncMapping.SyncStatus.DELETED);
                syncMappingRepository.save(mapping);
            } catch (Exception e) {
                throw new RuntimeException(e);
            }
        });
    }

    private void handleCommentAdded(SyncEvent event, JiraWebhookPayload payload) throws Exception {
        if (payload.getComment() == null) return;
        String jiraIssueId = payload.getIssue().getId();
        SyncMapping mapping = syncMappingRepository.findByJiraIssueId(jiraIssueId).orElse(null);
        if (mapping == null) return;

        String author = payload.getComment().getAuthor() != null ?
                payload.getComment().getAuthor().getDisplayName() : "Unknown";
        String commentText = payload.getComment().getBody() != null ?
                payload.getComment().getBody().toString() : "";
        String timestamp = formatTimestamp(payload.getTimestamp());

        notionWriterService.appendBlocks(mapping.getNotionPageId(), List.of(
                divider(),
                callout("💬", "💬  " + author + "   ·   " + timestamp),
                paragraph(commentText)));
    }

    private String extractBlockText(Map<String, Object> block, String type) {
        try {
            if (type == null) return "";
            Map<String, Object> typeContent = (Map<String, Object>) block.get(type);
            if (typeContent == null) return "";
            Object richTextObj = typeContent.get("rich_text");
            if (!(richTextObj instanceof List)) return "";
            List<Map<String, Object>> richText = (List<Map<String, Object>>) richTextObj;
            if (richText.isEmpty()) return "";
            Object textObj = richText.get(0).get("text");
            if (!(textObj instanceof Map)) return "";
            return (String) ((Map<String, Object>) textObj).getOrDefault("content", "");
        } catch (Exception e) {
            return "";
        }
    }

    private Map<String, Object> paragraph(String text) {
        return Map.of("object", "block", "type", "paragraph",
                "paragraph", Map.of("rich_text", List.of(
                        Map.of("text", Map.of("content", truncate(text, 2000))))));
    }

    private Map<String, Object> divider() {
        return Map.of("object", "block", "type", "divider", "divider", Map.of());
    }

    private Map<String, Object> heading2(String text) {
        return Map.of("object", "block", "type", "heading_2",
                "heading_2", Map.of("rich_text", List.of(
                        Map.of("text", Map.of("content", text)))));
    }

    private Map<String, Object> toggleBlockMap(String title) {
        return Map.of("object", "block", "type", "toggle",
                "toggle", Map.of("rich_text", List.of(
                        Map.of("text", Map.of("content", title)))));
    }

    private Map<String, Object> callout(String emoji, String text) {
        return Map.of("object", "block", "type", "callout",
                "callout", Map.of(
                        "rich_text", List.of(Map.of("text", Map.of("content", text))),
                        "icon", Map.of("type", "emoji", "emoji", emoji)));
    }

    private String truncate(String s, int max) {
        if (s == null) return "";
        return s.length() > max ? s.substring(0, max) + "..." : s;
    }

    private String formatTimestamp(Long timestamp) {
        if (timestamp == null) return LocalDateTime.now()
                .format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm"));
        return LocalDateTime.ofInstant(Instant.ofEpochMilli(timestamp),
                ZoneId.of("Asia/Kolkata"))
                .format(DateTimeFormatter.ofPattern("MMM dd, yyyy HH:mm"));
    }

    private String capitalize(String s) {
        if (s == null || s.isEmpty()) return s;
        return Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    // Strip Jira wiki image markup:  !filename.png!  or  !filename.png|width=638,...!
    private String stripJiraImageMarkup(String text) {
        if (text == null) return "";
        return text.replaceAll("![^!]+!", "").replaceAll("\\s{2,}", " ").trim();
    }

    // Extract image filenames from Jira wiki markup  !filename.png!  or  !filename.png|opts!
    private List<String> extractJiraImageFilenames(String text) {
        List<String> filenames = new ArrayList<>();
        if (text == null) return filenames;
        Matcher m = Pattern.compile("!([^!|]+\\.(?:png|jpg|jpeg|gif|webp|PNG|JPG|JPEG|GIF|WEBP))(?:[^!]*)?!")
                .matcher(text);
        while (m.find()) filenames.add(m.group(1));
        return filenames;
    }

    private String findAttachmentUrl(JiraWebhookPayload payload, String attachmentId, String filename) {
        if (payload.getIssue() == null || payload.getIssue().getFields() == null) return null;
        var attachments = payload.getIssue().getFields().getAttachments();
        if (attachments == null) return null;

        return attachments.stream()
                .filter(a -> (attachmentId != null && attachmentId.equals(a.getId()))
                        || (filename != null && filename.equals(a.getFilename())))
                .findFirst()
                .map(a -> {
                    // Use our own proxy endpoint so Notion can load auth-protected Jira images
                    if (appPublicUrl != null && !appPublicUrl.isBlank()) {
                        // ngrok-skip-browser-warning bypasses the ngrok interstitial so Notion can load the image
                        String proxyUrl = appPublicUrl.stripTrailing() + "/api/attachments/" + a.getId()
                                + "?ngrok-skip-browser-warning=1";
                        log.info("🔗 Using proxy URL for image '{}': {}", a.getFilename(), proxyUrl);
                        return proxyUrl;
                    }
                    // Fall back to direct Jira URL (won't embed in Notion but logs the URL)
                    log.warn("⚠️  APP_PUBLIC_URL not set — image '{}' will not embed in Notion. " +
                             "Set APP_PUBLIC_URL to your ngrok URL to fix this.", a.getFilename());
                    return a.getContent();
                })
                .orElse(null);
    }

    private Map<String, Object> imageBlock(String url) {
        return Map.of("object", "block", "type", "image",
                "image", Map.of("type", "external", "external", Map.of("url", url)));
    }

    private void handleProcessingFailure(SyncEvent event, Exception e) {
        String msg = e.getMessage() != null ? e.getMessage() : "";

        // Archived page errors are permanent — no point retrying
        boolean isArchivedError = msg.contains("archived");

        if (isArchivedError) {
            log.warn("⏭️  Skipping retry for archived-page error on event {}", event.getId());
        } else {
            log.error("Error processing event {}: {}", event.getId(), msg);
        }

        event.setStatus(SyncEvent.EventStatus.FAILED);
        event.setErrorMessage(msg);
        // Force to max retries so it goes straight to dead letter
        event.setRetryCount(isArchivedError ? maxRetryAttempts : event.getRetryCount() + 1);
        syncEventRepository.save(event);

        String errorCode = e instanceof NotionWriterService.NotionApiException nae ?
                nae.getErrorCode() : "PROCESSING_FAILED";

        SyncError error = SyncError.builder()
                .event(event)
                .mapping(event.getMapping())
                .errorCode(errorCode)
                .errorMessage(msg)
                .stackTrace(isArchivedError ? "" : getStackTrace(e))
                .build();
        syncErrorRepository.save(error);

        if (!isArchivedError && event.getRetryCount() < maxRetryAttempts) {
            syncQueueService.enqueue(event.getId().toString());
        } else {
            syncQueueService.moveToDeadLetter(event.getId().toString());
        }
    }

    private String getStackTrace(Exception e) {
        java.io.StringWriter sw = new java.io.StringWriter();
        e.printStackTrace(new java.io.PrintWriter(sw));
        return sw.toString();
    }
}
