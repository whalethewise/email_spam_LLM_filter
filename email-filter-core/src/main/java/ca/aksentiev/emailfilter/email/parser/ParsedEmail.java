package ca.aksentiev.emailfilter.email.parser;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Immutable representation of a parsed email message,
 * carrying sender, recipients, subject, body, and headers.
 *
 * @param messageId  RFC 2822 Message-ID header
 * @param subject    email subject line (empty string if missing)
 * @param from       sender email address
 * @param fromName   sender display name (empty string if not present)
 * @param to         list of recipient email addresses
 * @param body       plain text body with HTML stripped
 * @param rawHeaders all headers as name→value (first occurrence wins for duplicates)
 * @param receivedAt timestamp when the email was received
 */
public record ParsedEmail(
        String messageId,
        String subject,
        String from,
        String fromName,
        List<String> to,
        String body,
        Map<String, String> rawHeaders,
        Instant receivedAt
) {
}
