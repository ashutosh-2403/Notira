package com.ashutosh.jiranotionsync.service;

import lombok.extern.slf4j.Slf4j;
import org.apache.commons.codec.digest.HmacAlgorithms;
import org.apache.commons.codec.digest.HmacUtils;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;

/**
 * Verifies the HMAC-SHA256 signature on incoming Jira webhooks.
 * Jira signs every webhook payload with your secret — we verify it
 * to make sure the request genuinely came from Jira and wasn't tampered with.
 */
@Service
@Slf4j
public class WebhookVerificationService {

    @Value("${app.jira.webhook-secret:}")
    private String webhookSecret;

    /**
     * Verifies the HMAC-SHA256 signature from Jira.
     *
     * @param rawBody   raw request body string
     * @param signature signature header value from Jira (format: "sha256=<hash>")
     * @return true if signature is valid
     */
    public boolean verifyJiraSignature(String rawBody, String signature) {
        if (webhookSecret == null || webhookSecret.isBlank()) {
            log.warn("Webhook secret not configured — skipping signature verification");
            return true;
        }

        try {
            // Jira sends signature as "sha256=<hex_hash>"
            String expectedPrefix = "sha256=";
            if (!signature.startsWith(expectedPrefix)) {
                log.warn("Signature format invalid — expected 'sha256=...' got: {}", signature);
                return false;
            }

            String receivedHash = signature.substring(expectedPrefix.length());
            String computedHash = new HmacUtils(HmacAlgorithms.HMAC_SHA_256, webhookSecret)
                    .hmacHex(rawBody);

            boolean isValid = computedHash.equals(receivedHash);
            if (!isValid) {
                log.warn("Webhook signature mismatch — possible tampered request");
            }
            return isValid;

        } catch (Exception e) {
            log.error("Error verifying webhook signature: {}", e.getMessage());
            return false;
        }
    }
}
