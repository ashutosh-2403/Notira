package com.ashutosh.jiranotionsync.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.extern.slf4j.Slf4j;
import okhttp3.*;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

@Service
@Slf4j
public class AiSummaryService {

    private static final String GROQ_API_URL =
            "https://api.groq.com/openai/v1/chat/completions";
    private static final MediaType JSON = MediaType.get("application/json");

    @Value("${app.groq.api-key:}")
    private String apiKey;

    private final OkHttpClient httpClient;
    private final ObjectMapper objectMapper;

    public AiSummaryService(ObjectMapper objectMapper) {
        this.objectMapper = objectMapper;
        this.httpClient = new OkHttpClient.Builder()
                .connectTimeout(30, TimeUnit.SECONDS)
                .readTimeout(60, TimeUnit.SECONDS)
                .build();
    }

    /**
     * Generates a plain-English summary of a Jira issue for non-technical readers
     * using Google Gemini 1.5 Flash.
     * Returns null if API key is not configured or the call fails.
     */
    public String generateSummary(String issueKey, String title, String description,
                                   String status, String issueType, String priority,
                                   String assignee, List<String> attachmentNames) {
        if (apiKey == null || apiKey.isBlank()) {
            log.warn("GROQ_API_KEY not set — skipping AI summary for {}", issueKey);
            return null;
        }

        String prompt = buildPrompt(issueKey, title, description, status,
                issueType, priority, assignee, attachmentNames);

        try {
            Map<String, Object> body = Map.of(
                    "model", "llama-3.1-8b-instant",
                    "messages", List.of(Map.of("role", "user", "content", prompt)),
                    "max_tokens", 400,
                    "temperature", 0.7);

            String json = objectMapper.writeValueAsString(body);

            Request request = new Request.Builder()
                    .url(GROQ_API_URL)
                    .post(RequestBody.create(json, JSON))
                    .addHeader("Authorization", "Bearer " + apiKey)
                    .addHeader("Content-Type", "application/json")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                String responseBody = response.body() != null ? response.body().string() : "";
                if (!response.isSuccessful()) {
                    log.error("Groq API error: {} — {}", response.code(), responseBody);
                    return null;
                }

                // Parse: choices[0].message.content
                Map<String, Object> result = objectMapper.readValue(responseBody, Map.class);
                List<Map<String, Object>> choices = (List<Map<String, Object>>) result.get("choices");
                if (choices != null && !choices.isEmpty()) {
                    Map<String, Object> message = (Map<String, Object>) choices.get(0).get("message");
                    String summary = (String) message.get("content");
                    log.info("✅ AI summary generated for {}", issueKey);
                    return summary != null ? summary.trim() : null;
                }
            }
        } catch (Exception e) {
            log.error("❌ Error generating AI summary for {}: {}", issueKey, e.getMessage());
        }
        return null;
    }

    private String buildPrompt(String issueKey, String title, String description,
                                String status, String issueType, String priority,
                                String assignee, List<String> attachmentNames) {
        String attachmentsInfo = (attachmentNames != null && !attachmentNames.isEmpty())
                ? "Attachments: " + String.join(", ", attachmentNames)
                : "No attachments";

        String descPreview = (description != null && !description.isBlank())
                ? description.substring(0, Math.min(description.length(), 600))
                : "No description provided";

        return String.format("""
                You are writing a brief summary of a software task for someone non-technical — a fresher, a project manager, or someone new to the project who has no coding background.

                Task details:
                - Key: %s
                - Title: %s
                - Type: %s
                - Status: %s
                - Priority: %s
                - Assignee: %s
                - %s
                - Description: %s

                Write 3–4 sentences in plain, friendly English that explain:
                1. What this task is about (avoid technical jargon)
                2. What the developer did or is trying to do
                3. What the goal or outcome of this work is

                Do NOT use bullet points. Write in flowing paragraph form. Keep it concise and easy to understand for someone with no technical background.
                """,
                issueKey, title, issueType, status, priority,
                assignee != null ? assignee : "Unassigned",
                attachmentsInfo, descPreview);
    }
}
