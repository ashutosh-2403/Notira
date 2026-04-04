package com.ashutosh.jiranotionsync.controller;

import lombok.extern.slf4j.Slf4j;
import okhttp3.Credentials;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.Response;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

/**
 * Proxies Jira attachment content so Notion can embed images.
 * Notion needs a public URL — Jira URLs require Basic auth, so we proxy them here.
 * Notion calls: GET /api/attachments/{attachmentId}  →  we fetch from Jira with auth → return bytes
 */
@RestController
@RequestMapping("/api/attachments")
@Slf4j
public class AttachmentProxyController {

    @Value("${app.jira.base-url}")
    private String jiraBaseUrl;

    @Value("${app.jira.email}")
    private String jiraEmail;

    @Value("${app.jira.api-token}")
    private String jiraApiToken;

    private final OkHttpClient httpClient = new OkHttpClient.Builder()
            .connectTimeout(15, java.util.concurrent.TimeUnit.SECONDS)
            .readTimeout(30, java.util.concurrent.TimeUnit.SECONDS)
            .build();

    @GetMapping("/{attachmentId}")
    public ResponseEntity<byte[]> proxyAttachment(@PathVariable String attachmentId) {
        try {
            String jiraUrl = jiraBaseUrl + "/rest/api/3/attachment/content/" + attachmentId;
            log.info("Proxying attachment {} from Jira", attachmentId);

            Request request = new Request.Builder()
                    .url(jiraUrl)
                    .get()
                    .addHeader("Authorization", Credentials.basic(jiraEmail, jiraApiToken))
                    .addHeader("Accept", "*/*")
                    .build();

            try (Response response = httpClient.newCall(request).execute()) {
                if (!response.isSuccessful() || response.body() == null) {
                    log.error("Failed to fetch attachment {}: HTTP {}", attachmentId, response.code());
                    return ResponseEntity.status(HttpStatus.NOT_FOUND).build();
                }
                byte[] bytes = response.body().bytes();
                String contentType = response.header("Content-Type", "image/png");
                log.info("✅ Proxied attachment {} ({} bytes, {})", attachmentId, bytes.length, contentType);
                return ResponseEntity.ok()
                        .contentType(MediaType.parseMediaType(contentType))
                        .body(bytes);
            }
        } catch (Exception e) {
            log.error("Error proxying attachment {}: {}", attachmentId, e.getMessage());
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR).build();
        }
    }
}
