package com.ashutosh.jiranotionsync.dto;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.AllArgsConstructor;
import lombok.Builder;
import lombok.Data;
import lombok.NoArgsConstructor;

import java.util.List;
import java.util.Map;

/**
 * Represents a Notion page creation/update request.
 * Sent to Notion API POST /v1/pages
 */
@Data
@Builder
@NoArgsConstructor
@AllArgsConstructor
@JsonInclude(JsonInclude.Include.NON_NULL)
public class NotionPageRequest {

    // Parent database — where the page will be created
    private Map<String, Object> parent;

    // Page properties — title, status, assignee etc (metadata columns)
    private Map<String, Object> properties;

    // Page content blocks — the body of the page
    private List<Map<String, Object>> children;

    // Page icon (optional)
    private Map<String, Object> icon;

    // Page cover (optional)
    private Map<String, Object> cover;
}