package ca.aksentiev.emailfilter.email.parser;

import java.util.Properties;

import ca.aksentiev.emailfilter.filter.EmailMessage;
import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailParsingServiceTest {

    private EmailParsingService service;
    private Session session;

    @BeforeEach
    void setUp() {
        service = new EmailParsingService();
        session = Session.getInstance(new Properties());
    }

    @Test
    void parsesSimplePlainTextEmail() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("alice@example.com", "Alice Smith"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("bob@example.com"));
        message.setSubject("Hello Bob");
        message.setText("This is a plain text email.", "UTF-8");
        message.setHeader("Message-ID", "<123@example.com>");

        EmailMessage result = service.parse(message);

        assertThat(result.messageId()).isEqualTo("<123@example.com>");
        assertThat(result.subject()).isEqualTo("Hello Bob");
        assertThat(result.from()).isEqualTo("alice@example.com");
        assertThat(result.fromName()).isEqualTo("Alice Smith");
        assertThat(result.to()).containsExactly("bob@example.com");
        assertThat(result.bodyText()).isEqualTo("This is a plain text email.");
        assertThat(result.bodyHtml()).isEmpty();
        assertThat(result.rawMessage()).isSameAs(message);
        assertThat(result.headers()).containsKey("Message-Id");
    }

    @Test
    void parsesMultipartAlternativePreferringPlainText() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.com"));
        message.setSubject("Multipart Test");

        MimeBodyPart plainPart = new MimeBodyPart();
        plainPart.setText("Plain text content", "UTF-8");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<html><body><b>HTML content</b></body></html>", "text/html; charset=UTF-8");
        htmlPart.setHeader("Content-Type", "text/html; charset=UTF-8");

        MimeMultipart multipart = new MimeMultipart("alternative");
        multipart.addBodyPart(plainPart);
        multipart.addBodyPart(htmlPart);
        message.setContent(multipart);

        EmailMessage result = service.parse(message);

        assertThat(result.bodyText()).isEqualTo("Plain text content");
        assertThat(result.bodyHtml()).contains("<b>HTML content</b>");
    }

    @Test
    void parsesHtmlOnlyEmailPreservingHtmlAndStrippingForPlainText() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.com"));
        message.setSubject("HTML Only");
        message.setContent("<html><body><h1>Title</h1><p>Paragraph text</p></body></html>",
                "text/html; charset=UTF-8");
        message.saveChanges();

        EmailMessage result = service.parse(message);

        assertThat(result.bodyText()).doesNotContain("<", ">");
        assertThat(result.bodyText()).contains("Title");
        assertThat(result.bodyText()).contains("Paragraph text");
        assertThat(result.bodyHtml()).contains("<h1>Title</h1>");
        assertThat(result.bodyHtml()).contains("<p>Paragraph text</p>");
    }

    @Test
    void stripHtmlDropsStyleAndScriptBlocksEntirely() {
        String html = "<html><head><style>.promo { color: #ff0000; font-size: 14px; }"
                + "</style><script>trackClick('open');</script></head>"
                + "<body><p>Real content here</p></body></html>";

        String text = service.stripHtml(html);

        assertThat(text).isEqualTo("Real content here");
        assertThat(text).doesNotContain("color", "font-size", "trackClick");
    }

    @Test
    void stripHtmlDecodesCommonEntities() {
        String html = "<p>Terms &amp; Conditions apply.&nbsp;It&#39;s &quot;final&quot; "
                + "&lt;no exceptions&gt;.</p>";

        String text = service.stripHtml(html);

        assertThat(text).isEqualTo("Terms & Conditions apply. It's \"final\" <no exceptions>.");
    }

    @Test
    void stripHtmlConvertsBlockTagsToLineBreaksNotSpaces() {
        String html = "<p>First paragraph</p><p>Second paragraph</p>";

        String text = service.stripHtml(html);

        assertThat(text).isEqualTo("First paragraph\nSecond paragraph");
    }

    @Test
    void handlesMissingSubjectGracefully() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.com"));
        message.setText("Body without subject", "UTF-8");

        EmailMessage result = service.parse(message);

        assertThat(result.subject()).isEmpty();
        assertThat(result.bodyText()).isEqualTo("Body without subject");
    }

    @Test
    void handlesMissingSenderGracefully() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.com"));
        message.setSubject("No sender");
        message.setText("Body text", "UTF-8");

        EmailMessage result = service.parse(message);

        assertThat(result.from()).isEmpty();
        assertThat(result.fromName()).isEmpty();
    }

    @Test
    void handlesMissingRecipientsGracefully() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setSubject("No recipients");
        message.setText("Body text", "UTF-8");

        EmailMessage result = service.parse(message);

        assertThat(result.to()).isEmpty();
    }

    @Test
    void parsesMultipartMixedWithAttachment() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.com", "Sender"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.com"));
        message.setSubject("With attachment");

        MimeBodyPart textPart = new MimeBodyPart();
        textPart.setText("Main body text", "UTF-8");

        MimeBodyPart attachmentPart = new MimeBodyPart();
        attachmentPart.setContent("file contents", "application/octet-stream");
        attachmentPart.setFileName("attachment.bin");

        MimeMultipart multipart = new MimeMultipart("mixed");
        multipart.addBodyPart(textPart);
        multipart.addBodyPart(attachmentPart);
        message.setContent(multipart);

        EmailMessage result = service.parse(message);

        assertThat(result.bodyText()).isEqualTo("Main body text");
        assertThat(result.bodyHtml()).isEmpty();
    }

    @Test
    void parsesNestedMultipartAlternativeInsideMixed() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.com"));
        message.setSubject("Nested multipart");

        MimeBodyPart plainPart = new MimeBodyPart();
        plainPart.setText("Nested plain text", "UTF-8");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<html><body><b>Nested HTML</b></body></html>", "text/html; charset=UTF-8");
        htmlPart.setHeader("Content-Type", "text/html; charset=UTF-8");

        MimeMultipart alternative = new MimeMultipart("alternative");
        alternative.addBodyPart(plainPart);
        alternative.addBodyPart(htmlPart);

        MimeBodyPart alternativeWrapper = new MimeBodyPart();
        alternativeWrapper.setContent(alternative);

        MimeBodyPart attachmentPart = new MimeBodyPart();
        attachmentPart.setContent("binary data", "application/pdf");
        attachmentPart.setFileName("report.pdf");

        MimeMultipart mixed = new MimeMultipart("mixed");
        mixed.addBodyPart(alternativeWrapper);
        mixed.addBodyPart(attachmentPart);
        message.setContent(mixed);

        EmailMessage result = service.parse(message);

        assertThat(result.bodyText()).isEqualTo("Nested plain text");
        assertThat(result.bodyHtml()).contains("<b>Nested HTML</b>");
    }

    @Test
    void multipleRecipientsAllExtracted() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipients(Message.RecipientType.TO, new InternetAddress[] {
            new InternetAddress("alice@example.com"),
            new InternetAddress("bob@example.com"),
            new InternetAddress("carol@example.com")
        });
        message.setSubject("Group email");
        message.setText("Hello everyone", "UTF-8");

        EmailMessage result = service.parse(message);

        assertThat(result.to()).containsExactly("alice@example.com", "bob@example.com", "carol@example.com");
    }

    @Test
    void retainsRawMessageReference() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.com"));
        message.setSubject("Reference check");
        message.setText("Body", "UTF-8");

        EmailMessage result = service.parse(message);

        assertThat(result.rawMessage()).isSameAs(message);
    }

    @Test
    void multipartHtmlOnlyFallsBackToStrippedText() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.com"));
        message.setSubject("HTML multipart only");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<html><body><p>Only HTML here</p></body></html>", "text/html; charset=UTF-8");
        htmlPart.setHeader("Content-Type", "text/html; charset=UTF-8");

        MimeMultipart multipart = new MimeMultipart("alternative");
        multipart.addBodyPart(htmlPart);
        message.setContent(multipart);

        EmailMessage result = service.parse(message);

        assertThat(result.bodyText()).contains("Only HTML here");
        assertThat(result.bodyText()).doesNotContain("<", ">");
        assertThat(result.bodyHtml()).contains("<p>Only HTML here</p>");
    }
}
