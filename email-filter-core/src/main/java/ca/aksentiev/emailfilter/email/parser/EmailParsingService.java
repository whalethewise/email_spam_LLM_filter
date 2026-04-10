package ca.aksentiev.emailfilter.email.parser;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Enumeration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import jakarta.mail.Address;
import jakarta.mail.Header;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Multipart;
import jakarta.mail.Part;
import jakarta.mail.internet.InternetAddress;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Extracts {@link EmailMessage} fields from a Jakarta Mail {@link Message}.
 * Handles MIME multipart (plain text, HTML, mixed), preserves raw HTML
 * separately, and retains the original {@link Message} reference for
 * downstream IMAP operations.
 */
@Service
public class EmailParsingService {

    private static final Logger log = LoggerFactory.getLogger(EmailParsingService.class);

    private static final Pattern HTML_TAG_PATTERN = Pattern.compile("<[^>]+>");
    private static final Pattern WHITESPACE_COLLAPSE_PATTERN = Pattern.compile("\\s{2,}");
    private static final int MAX_MIME_DEPTH = 10;
    private static final int MAX_MIME_PARTS = 100;

    /**
     * Parses a raw {@link Message} into an {@link EmailMessage} record.
     *
     * @param message the Jakarta Mail message to parse
     * @return parsed email message with all extracted fields
     * @throws EmailParsingException if the message cannot be parsed
     */
    public EmailMessage parse(Message message) {
        try {
            String messageId = extractHeader(message, "Message-ID");
            String subject = message.getSubject() != null ? message.getSubject() : "";
            String from = extractFromAddress(message);
            String fromName = extractFromName(message);
            List<String> to = extractRecipients(message);
            Map<String, String> headers = extractHeaders(message);

            BodyContent body = extractBody(message);

            return new EmailMessage(
                    messageId, from, fromName, to, subject,
                    body.plainText(), body.html(), message, headers);
        } catch (MessagingException | IOException e) {
            throw new EmailParsingException("Failed to parse email message", e);
        }
    }

    private String extractFromAddress(Message message) throws MessagingException {
        Address[] fromAddresses = message.getFrom();
        if (fromAddresses == null || fromAddresses.length == 0) {
            return "";
        }
        Address first = fromAddresses[0];
        if (first instanceof InternetAddress internetAddress) {
            return internetAddress.getAddress() != null ? internetAddress.getAddress() : "";
        }
        return first.toString();
    }

    private String extractFromName(Message message) throws MessagingException {
        Address[] fromAddresses = message.getFrom();
        if (fromAddresses == null || fromAddresses.length == 0) {
            return "";
        }
        Address first = fromAddresses[0];
        if (first instanceof InternetAddress internetAddress) {
            return internetAddress.getPersonal() != null ? internetAddress.getPersonal() : "";
        }
        return "";
    }

    private List<String> extractRecipients(Message message) throws MessagingException {
        Address[] recipients = message.getRecipients(Message.RecipientType.TO);
        if (recipients == null || recipients.length == 0) {
            return List.of();
        }
        List<String> result = new ArrayList<>(recipients.length);
        for (Address address : recipients) {
            if (address instanceof InternetAddress internetAddress) {
                result.add(internetAddress.getAddress() != null ? internetAddress.getAddress() : address.toString());
            } else {
                result.add(address.toString());
            }
        }
        return Collections.unmodifiableList(result);
    }

    private BodyContent extractBody(Message message) throws MessagingException, IOException {
        Object content = message.getContent();
        if (content instanceof Multipart multipart) {
            BodyContent body = extractBodyFromMultipart(multipart, message, 0, new int[]{0});
            if (body.plainText().isEmpty() && !body.html().isEmpty()) {
                log.warn("Email has no text/plain part, falling back to HTML (Message-ID: {})",
                        extractHeader(message, "Message-ID"));
                return new BodyContent(stripHtml(body.html()), body.html());
            }
            return body;
        }
        String contentType = message.getContentType();
        String text = contentToString(content);
        if (contentType != null && contentType.toLowerCase().contains("text/html")) {
            log.warn("Email has HTML-only body (Message-ID: {}), stripping tags for plain text",
                    extractHeader(message, "Message-ID"));
            return new BodyContent(stripHtml(text), text);
        }
        return new BodyContent(text, "");
    }

    private BodyContent extractBodyFromMultipart(Multipart multipart, Message message,
                                                  int depth, int[] partCount)
            throws MessagingException, IOException {
        if (depth > MAX_MIME_DEPTH) {
            log.warn("MIME nesting depth exceeded ({}) for Message-ID: {}",
                    MAX_MIME_DEPTH, extractHeader(message, "Message-ID"));
            return new BodyContent("", "");
        }

        String plainText = null;
        String htmlText = null;

        for (int i = 0; i < multipart.getCount(); i++) {
            partCount[0]++;
            if (partCount[0] > MAX_MIME_PARTS) {
                log.warn("MIME part count exceeded ({}) for Message-ID: {}",
                        MAX_MIME_PARTS, extractHeader(message, "Message-ID"));
                break;
            }

            Part part = multipart.getBodyPart(i);
            Object partContent = part.getContent();
            String contentType = part.getContentType();

            if (partContent instanceof Multipart nestedMultipart) {
                BodyContent nested = extractBodyFromMultipart(nestedMultipart, message, depth + 1, partCount);
                if (plainText == null && !nested.plainText().isEmpty()) {
                    plainText = nested.plainText();
                }
                if (htmlText == null && !nested.html().isEmpty()) {
                    htmlText = nested.html();
                }
            } else if (contentType != null && contentType.toLowerCase().contains("text/plain") && plainText == null) {
                plainText = contentToString(partContent);
            } else if (contentType != null && contentType.toLowerCase().contains("text/html") && htmlText == null) {
                htmlText = contentToString(partContent);
            }
        }

        return new BodyContent(plainText != null ? plainText : "", htmlText != null ? htmlText : "");
    }

    private Map<String, String> extractHeaders(Message message) throws MessagingException {
        Map<String, String> headers = new LinkedHashMap<>();
        Enumeration<Header> allHeaders = message.getAllHeaders();
        while (allHeaders.hasMoreElements()) {
            Header header = allHeaders.nextElement();
            headers.putIfAbsent(header.getName(), header.getValue());
        }
        return Collections.unmodifiableMap(headers);
    }

    private String extractHeader(Message message, String name) throws MessagingException {
        String[] values = message.getHeader(name);
        if (values == null || values.length == 0) {
            return "";
        }
        return values[0];
    }

    private String contentToString(Object content) {
        return content != null ? content.toString() : "";
    }

    String stripHtml(String html) {
        String noTags = HTML_TAG_PATTERN.matcher(html).replaceAll(" ");
        String collapsed = WHITESPACE_COLLAPSE_PATTERN.matcher(noTags.trim()).replaceAll(" ");
        return collapsed.trim();
    }

    /**
     * Internal holder for both plain text and HTML body content extracted from a message.
     */
    private record BodyContent(String plainText, String html) {}
}
