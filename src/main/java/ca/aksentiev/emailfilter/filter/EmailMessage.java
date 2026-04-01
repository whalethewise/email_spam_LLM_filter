package ca.aksentiev.emailfilter.filter;

import java.util.List;
import java.util.Map;

import jakarta.mail.Message;

/**
 * Immutable representation of an email message as seen by filters.
 * Carries both parsed fields and the raw IMAP message for filters
 * that need to perform IMAP operations.
 *
 * @param messageId RFC 2822 Message-ID header
 * @param from      sender email address
 * @param fromName  sender display name (empty if not present)
 * @param to        list of recipient email addresses
 * @param subject   email subject line (empty if missing)
 * @param bodyText  plain text body
 * @param bodyHtml  original HTML body (empty if not available)
 * @param rawMessage the underlying Jakarta Mail message for IMAP operations
 * @param headers   all headers as name→value
 */
public record EmailMessage(
        String messageId,
        String from,
        String fromName,
        List<String> to,
        String subject,
        String bodyText,
        String bodyHtml,
        Message rawMessage,
        Map<String, String> headers) {}
