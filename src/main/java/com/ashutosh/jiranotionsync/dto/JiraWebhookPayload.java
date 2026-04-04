package com.ashutosh.jiranotionsync.dto;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonProperty;
import lombok.Data;

import java.util.List;

/**
 * Maps the incoming Jira webhook JSON payload.
 * Jira sends different shapes depending on the event type —
 * @JsonIgnoreProperties ensures unknown fields don't break parsing.
 */
@Data
@JsonIgnoreProperties(ignoreUnknown = true)
public class JiraWebhookPayload {

    // e.g. "jira:issue_created", "jira:issue_updated", "jira:issue_deleted"
    @JsonProperty("webhookEvent")
    private String webhookEvent;

    // timestamp of the event in epoch milliseconds
    @JsonProperty("timestamp")
    private Long timestamp;

    // the Jira issue that triggered the event
    @JsonProperty("issue")
    private JiraIssue issue;

    // user who triggered the event
    @JsonProperty("user")
    private JiraUser user;

    // populated when event is a comment add/update
    @JsonProperty("comment")
    private JiraComment comment;

    // populated when issue fields changed — contains before/after values
    @JsonProperty("changelog")
    private JiraChangelog changelog;

    // ==================== NESTED DTOs ====================

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiraIssue {

        @JsonProperty("id")
        private String id;                  // internal Jira issue ID e.g. "10001"

        @JsonProperty("key")
        private String key;                 // human-readable key e.g. "PROJ-123"

        @JsonProperty("fields")
        private JiraIssueFields fields;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiraIssueFields {

        @JsonProperty("summary")
        private String summary;             // issue title

        @JsonProperty("description")
        private Object description;         // ADF format (Atlassian Document Format)

        @JsonProperty("status")
        private JiraStatus status;

        @JsonProperty("priority")
        private JiraPriority priority;

        @JsonProperty("assignee")
        private JiraUser assignee;

        @JsonProperty("reporter")
        private JiraUser reporter;

        @JsonProperty("labels")
        private List<String> labels;

        @JsonProperty("project")
        private JiraProject project;

        @JsonProperty("issuetype")
        private JiraIssueType issueType;

        @JsonProperty("updated")
        private String updated;             // ISO 8601 datetime string

        @JsonProperty("created")
        private String created;

        @JsonProperty("attachment")
        private List<JiraAttachment> attachments;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiraStatus {
        @JsonProperty("name")
        private String name;                // e.g. "In Progress", "Done", "To Do"

        @JsonProperty("id")
        private String id;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiraPriority {
        @JsonProperty("name")
        private String name;                // e.g. "High", "Medium", "Low"
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiraUser {
        @JsonProperty("accountId")
        private String accountId;

        @JsonProperty("displayName")
        private String displayName;

        @JsonProperty("emailAddress")
        private String emailAddress;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiraProject {
        @JsonProperty("id")
        private String id;

        @JsonProperty("key")
        private String key;                 // e.g. "PROJ"

        @JsonProperty("name")
        private String name;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiraIssueType {
        @JsonProperty("name")
        private String name;                // e.g. "Story", "Bug", "Task"
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiraComment {
        @JsonProperty("id")
        private String id;

        @JsonProperty("body")
        private Object body;                // ADF format

        @JsonProperty("author")
        private JiraUser author;

        @JsonProperty("created")
        private String created;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiraChangelog {
        @JsonProperty("id")
        private String id;

        @JsonProperty("items")
        private List<JiraChangelogItem> items;
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiraChangelogItem {
        @JsonProperty("field")
        private String field;               // e.g. "status", "assignee", "summary"

        @JsonProperty("from")
        private String from;                // previous value ID

        @JsonProperty("fromString")
        private String fromString;          // previous value display name

        @JsonProperty("to")
        private String to;                  // new value ID (e.g. attachment ID)

        @JsonProperty("toString")
        private String toString;            // new value display name
    }

    @Data
    @JsonIgnoreProperties(ignoreUnknown = true)
    public static class JiraAttachment {
        @JsonProperty("id")
        private String id;

        @JsonProperty("filename")
        private String filename;

        @JsonProperty("content")
        private String content;             // URL to download attachment

        @JsonProperty("thumbnail")
        private String thumbnail;           // thumbnail URL (images only)

        @JsonProperty("mimeType")
        private String mimeType;
    }
}
