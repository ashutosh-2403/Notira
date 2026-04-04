package com.ashutosh.jiranotionsync.transform;

import com.ashutosh.jiranotionsync.dto.JiraWebhookPayload;
import com.ashutosh.jiranotionsync.dto.NotionPageRequest;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Component;

import java.util.*;

@Component
@Slf4j
public class JiraToNotionTransformer {

    public NotionPageRequest transform(JiraWebhookPayload payload, String databaseId) {
        JiraWebhookPayload.JiraIssue issue = payload.getIssue();
        JiraWebhookPayload.JiraIssueFields fields = issue.getFields();
        log.info("Transforming Jira issue {} to Notion page", issue.getKey());

        Map<String, Object> properties = new LinkedHashMap<>();
        properties.put("Name", titleProperty(issue.getKey() + " — " + nullSafe(fields.getSummary())));

        List<Map<String, Object>> blocks = new ArrayList<>();

        // ── AI Summary hint box ──
        blocks.add(callout("💡", "AI Summary — coming soon"));
        blocks.add(divider());

        // ── Details ──
        blocks.add(heading2("📋  Details"));
        blocks.add(bullet("🔑  Jira Key:  " + issue.getKey()));
        blocks.add(bullet("📌  Status:  " + (fields.getStatus() != null ? fields.getStatus().getName() : "—")));
        blocks.add(bullet("👤  Assignee:  " + (fields.getAssignee() != null ? fields.getAssignee().getDisplayName() : "Unassigned")));
        blocks.add(bullet("📢  Reporter:  " + (fields.getReporter() != null ? fields.getReporter().getDisplayName() : "—")));
        blocks.add(bullet("🚨  Priority:  " + (fields.getPriority() != null ? fields.getPriority().getName() : "—")));
        blocks.add(bullet("🏷️  Type:  " + (fields.getIssueType() != null ? fields.getIssueType().getName() : "—")));
        if (fields.getLabels() != null && !fields.getLabels().isEmpty())
            blocks.add(bullet("🔖  Labels:  " + String.join(", ", fields.getLabels())));
        blocks.add(divider());

        // ── Stage Tracker ──
        String status = fields.getStatus() != null ? fields.getStatus().getName().toLowerCase() : "";
        blocks.add(heading2("🚦  Stage Tracker"));
        blocks.add(paragraph(buildTracker(status)));
        blocks.add(divider());

        // ── Description ──
        blocks.add(heading2("📝  Description"));
        String desc = extractDescription(fields.getDescription());
        // Use a single paragraph so in-place updates always work reliably
        blocks.add(paragraph(desc.isEmpty() ? "No description provided." : desc.replace("\n", " ").trim()));
        blocks.add(divider());

        // ── Comments ──
        blocks.add(heading2("💬  Comments"));
        blocks.add(paragraph("Comments will appear here as they are added in Jira."));
        blocks.add(divider());

        // ── Attachments ──
        blocks.add(heading2("📎  Attachments"));
        blocks.add(toggleBlock("📎  All Attachments", "Images and files attached in Jira will appear here."));
        blocks.add(divider());

        // ── Change History ──
        blocks.add(heading2("🕐  Change History"));
        blocks.add(paragraph("All changes will be logged here automatically."));

        return NotionPageRequest.builder()
                .parent(Map.of("database_id", databaseId))
                .properties(properties)
                .children(blocks)
                .build();
    }

    public String buildTracker(String status) {
        boolean backlog    = status.contains("backlog");
        boolean inProgress = status.contains("progress");
        boolean inReview   = status.contains("review");
        boolean done       = status.contains("done");

        return (backlog    ? "🔵 Backlog"      : "⚪ Backlog")
             + "   →   "
             + (inProgress ? "🟡 In Progress"  : "⚪ In Progress")
             + "   →   "
             + (inReview   ? "🟠 In Review"    : "⚪ In Review")
             + "   →   "
             + (done       ? "✅ Done"         : "⚪ Done");
    }

    private Map<String, Object> titleProperty(String text) {
        return Map.of("title", List.of(Map.of("text", Map.of("content", nullSafe(text)))));
    }

    private Map<String, Object> heading2(String text) {
        return Map.of("object", "block", "type", "heading_2",
                "heading_2", Map.of("rich_text", List.of(
                        Map.of("text", Map.of("content", text)))));
    }

    private Map<String, Object> paragraph(String text) {
        return Map.of("object", "block", "type", "paragraph",
                "paragraph", Map.of("rich_text", List.of(
                        Map.of("text", Map.of("content", nullSafe(text))))));
    }

    private Map<String, Object> bullet(String text) {
        return Map.of("object", "block", "type", "bulleted_list_item",
                "bulleted_list_item", Map.of("rich_text", List.of(
                        Map.of("text", Map.of("content", nullSafe(text))))));
    }

    private Map<String, Object> divider() {
        return Map.of("object", "block", "type", "divider", "divider", Map.of());
    }

    private Map<String, Object> toggleBlock(String title, String placeholderText) {
        return Map.of("object", "block", "type", "toggle",
                "toggle", Map.of(
                        "rich_text", List.of(Map.of("text", Map.of("content", nullSafe(title)))),
                        "color", "default",
                        "children", List.of(
                                Map.of("object", "block", "type", "paragraph",
                                        "paragraph", Map.of("rich_text", List.of(
                                                Map.of("text", Map.of("content", nullSafe(placeholderText)))))))));
    }

    private Map<String, Object> callout(String emoji, String text) {
        return Map.of("object", "block", "type", "callout",
                "callout", Map.of(
                        "rich_text", List.of(Map.of("text", Map.of("content", nullSafe(text)))),
                        "icon", Map.of("type", "emoji", "emoji", emoji)));
    }

    private String extractDescription(Object desc) {
        if (desc == null) return "";
        if (desc instanceof String s) return s;
        StringBuilder sb = new StringBuilder();
        try {
            if (desc instanceof Map<?, ?> node) {
                Object content = node.get("content");
                if (content instanceof List<?> items) {
                    for (Object item : items) {
                        sb.append(extractDescription(item)).append("\n");
                    }
                }
                Object text = node.get("text");
                if (text instanceof String t) sb.append(t);
            }
        } catch (Exception e) {
            log.warn("Could not parse description: {}", e.getMessage());
        }
        return sb.toString().trim();
    }

    private String nullSafe(String v) { return v != null ? v : ""; }
}
