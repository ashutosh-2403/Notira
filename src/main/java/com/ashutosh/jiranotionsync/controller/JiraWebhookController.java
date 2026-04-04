package com.ashutosh.jiranotionsync.controller;

import com.ashutosh.jiranotionsync.dto.JiraWebhookPayload;
import com.ashutosh.jiranotionsync.service.JiraWebhookService;
import com.ashutosh.jiranotionsync.service.WebhookVerificationService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/webhooks")
@RequiredArgsConstructor
@Slf4j
public class JiraWebhookController {

    private final JiraWebhookService jiraWebhookService;
    private final WebhookVerificationService webhookVerificationService;

    /**
     * Receives incoming Jira webhook events.
     * Jira sends a POST request here whenever an issue is created, updated, or deleted.
     *
     * @param payload   parsed Jira webhook JSON body
     * @param signature HMAC-SHA256 signature from Jira (header: X-Hub-Signature)
     * @param rawBody   raw request body string for signature verification
     */
    @PostMapping("/jira")
    public ResponseEntity<String> handleJiraWebhook(
            @RequestBody JiraWebhookPayload payload,
            @RequestHeader(value = "X-Hub-Signature", required = false) String signature,
            @RequestAttribute("rawBody") String rawBody,
            HttpServletRequest request) {

        log.info("Received Jira webhook — event: {}, issueKey: {}",
                payload.getWebhookEvent(),
                payload.getIssue() != null ? payload.getIssue().getKey() : "N/A");

        // Step 1 — verify HMAC signature (skip in dev if secret not configured)
        if (signature != null) {
            boolean isValid = webhookVerificationService.verifyJiraSignature(rawBody, signature);
            if (!isValid) {
                log.warn("Invalid webhook signature received — rejecting request");
                return ResponseEntity.status(HttpStatus.UNAUTHORIZED)
                        .body("Invalid signature");
            }
        }

        // Step 2 — hand off to service layer for processing
        try {
            jiraWebhookService.processWebhookEvent(payload);
            return ResponseEntity.ok("Webhook received");
        } catch (Exception e) {
            log.error("Error processing Jira webhook event: {}", e.getMessage(), e);
            return ResponseEntity.status(HttpStatus.INTERNAL_SERVER_ERROR)
                    .body("Error processing webhook");
        }
    }

    /**
     * Health check endpoint — Jira pings this to verify webhook URL is alive.
     */
    @GetMapping("/jira/health")
    public ResponseEntity<String> health() {
        return ResponseEntity.ok("Jira webhook endpoint is active");
    }
}
