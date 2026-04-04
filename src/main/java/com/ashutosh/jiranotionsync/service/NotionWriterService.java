package com.ashutosh.jiranotionsync.service;

import com.ashutosh.jiranotionsync.dto.NotionPageRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.retry.annotation.Backoff;
import org.springframework.retry.annotation.Retryable;
import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;

@Service
@Slf4j
public class NotionWriterService {

    private static final String NOTION_API_BASE = "https://api.notion.com/v1";
    private static final MediaType JSON = MediaType.get("application/json");

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    @Value("${app.notion.api-token}")
    private String notionApiToken;

    @Value("${app.notion.api-version}")
    private String notionApiVersion;

    public NotionWriterService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .writeTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
                .build();
    }

    @Retryable(retryFor = {IOException.class, NotionApiException.class},
            maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 2))
    public String createPage(NotionPageRequest request) throws Exception {
        log.info("Creating Notion page...");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("parent", request.getParent());
        body.put("properties", request.getProperties());
        if (request.getChildren() != null) body.put("children", request.getChildren());

        String json = objectMapper.writeValueAsString(body);
        Request httpRequest = new Request.Builder()
                .url(NOTION_API_BASE + "/pages")
                .post(RequestBody.create(json, JSON))
                .addHeader("Authorization", "Bearer " + notionApiToken)
                .addHeader("Notion-Version", notionApiVersion)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Notion API error creating page: {} - {}", response.code(), responseBody);
                handleNotionError(response.code(), responseBody);
            }
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            String pageId = (String) result.get("id");
            log.info("Created Notion page {}", pageId);
            return pageId;
        }
    }

    // Get all block IDs and types for a page
    public List<Map<String, Object>> getPageBlocks(String pageId) throws Exception {
        Request httpRequest = new Request.Builder()
                .url(NOTION_API_BASE + "/blocks/" + pageId + "/children?page_size=100")
                .get()
                .addHeader("Authorization", "Bearer " + notionApiToken)
                .addHeader("Notion-Version", notionApiVersion)
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("❌ getPageBlocks FAILED — pageId={} HTTP={} body={}", pageId, response.code(), responseBody);
                return List.of();
            }
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            List<Map<String, Object>> blocks = (List<Map<String, Object>>) result.getOrDefault("results", List.of());
            log.info("📄 getPageBlocks returned {} blocks for page {}", blocks.size(), pageId);
            return blocks;
        }
    }

    // Delete a block by ID
    public void deleteBlock(String blockId) throws Exception {
        Request httpRequest = new Request.Builder()
                .url(NOTION_API_BASE + "/blocks/" + blockId)
                .delete()
                .addHeader("Authorization", "Bearer " + notionApiToken)
                .addHeader("Notion-Version", notionApiVersion)
                .build();
        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                log.warn("Could not delete block {}: {}", blockId, response.code());
            }
        }
    }

    // Update text content of an existing block
    public void updateBlock(String blockId, String type, String newText) throws Exception {
        Map<String, Object> richText = Map.of("rich_text",
                List.of(Map.of("text", Map.of("content", newText))));
        Map<String, Object> body = Map.of(type, richText);
        String json = objectMapper.writeValueAsString(body);

        Request httpRequest = new Request.Builder()
                .url(NOTION_API_BASE + "/blocks/" + blockId)
                .patch(RequestBody.create(json, JSON))
                .addHeader("Authorization", "Bearer " + notionApiToken)
                .addHeader("Notion-Version", notionApiVersion)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String rb = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("❌ updateBlock FAILED — blockId={} type={} HTTP={} body={}", blockId, type, response.code(), rb);
            } else {
                log.info("✅ updateBlock SUCCESS — blockId={} type={}", blockId, type);
            }
        }
    }

    @Retryable(retryFor = {IOException.class, NotionApiException.class},
            maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 2))
    public void appendBlocks(String pageId, List<Map<String, Object>> blocks) throws Exception {
        log.info("Appending {} blocks to Notion page: {}", blocks.size(), pageId);
        Map<String, Object> body = Map.of("children", blocks);
        String json = objectMapper.writeValueAsString(body);

        Request httpRequest = new Request.Builder()
                .url(NOTION_API_BASE + "/blocks/" + pageId + "/children")
                .patch(RequestBody.create(json, JSON))
                .addHeader("Authorization", "Bearer " + notionApiToken)
                .addHeader("Notion-Version", notionApiVersion)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                log.error("Notion API error appending blocks: {} - {}", response.code(), responseBody);
                handleNotionError(response.code(), responseBody);
            }
        }
    }

    // Append blocks and return their IDs
    public List<String> appendBlocksAndGetIds(String pageId,
            List<Map<String, Object>> blocks) throws Exception {
        Map<String, Object> body = Map.of("children", blocks);
        String json = objectMapper.writeValueAsString(body);

        Request httpRequest = new Request.Builder()
                .url(NOTION_API_BASE + "/blocks/" + pageId + "/children")
                .patch(RequestBody.create(json, JSON))
                .addHeader("Authorization", "Bearer " + notionApiToken)
                .addHeader("Notion-Version", notionApiVersion)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            String responseBody = response.body() != null ? response.body().string() : "";
            if (!response.isSuccessful()) {
                handleNotionError(response.code(), responseBody);
            }
            Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
            List<Map<String, Object>> results =
                    (List<Map<String, Object>>) result.getOrDefault("results", List.of());
            return results.stream()
                    .map(b -> (String) b.get("id"))
                    .filter(Objects::nonNull)
                    .toList();
        }
    }

    @Retryable(retryFor = {IOException.class, NotionApiException.class},
            maxAttempts = 3, backoff = @Backoff(delay = 2000, multiplier = 2))
    public void archivePage(String pageId) throws Exception {
        log.info("Archiving Notion page: {}", pageId);
        String json = objectMapper.writeValueAsString(Map.of("archived", true));
        Request httpRequest = new Request.Builder()
                .url(NOTION_API_BASE + "/pages/" + pageId)
                .patch(RequestBody.create(json, JSON))
                .addHeader("Authorization", "Bearer " + notionApiToken)
                .addHeader("Notion-Version", notionApiVersion)
                .addHeader("Content-Type", "application/json")
                .build();

        try (Response response = httpClient.newCall(httpRequest).execute()) {
            if (!response.isSuccessful()) {
                String rb = response.body() != null ? response.body().string() : "";
                handleNotionError(response.code(), rb);
            }
            log.info("Notion page archived: {}", pageId);
        }
    }

    private void handleNotionError(int code, String body) throws NotionApiException {
        throw new NotionApiException("Notion API error " + code + ": " + body,
                extractErrorCode(body));
    }

    private String extractErrorCode(String body) {
        try {
            Map<String, Object> error = objectMapper.readValue(body, Map.class);
            return (String) error.getOrDefault("code", "UNKNOWN");
        } catch (Exception e) {
            return "UNKNOWN";
        }
    }

    public static class NotionApiException extends RuntimeException {
        private final String errorCode;
        public NotionApiException(String message, String errorCode) {
            super(message);
            this.errorCode = errorCode;
        }
        public String getErrorCode() { return errorCode; }
    }
}
