package ca.aksentiev.emailfilter.email.parser;

import java.util.Properties;

import jakarta.mail.Message;
import jakarta.mail.Session;
import jakarta.mail.internet.InternetAddress;
import jakarta.mail.internet.MimeBodyPart;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.internet.MimeMultipart;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EmailParserTest {

    private EmailParser parser;
    private Session session;

    @BeforeEach
    void setUp() {
        parser = new EmailParser();
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

        ParsedEmail result = parser.parse(message);

        assertThat(result.messageId()).isEqualTo("<123@example.com>");
        assertThat(result.subject()).isEqualTo("Hello Bob");
        assertThat(result.from()).isEqualTo("alice@example.com");
        assertThat(result.fromName()).isEqualTo("Alice Smith");
        assertThat(result.to()).containsExactly("bob@example.com");
        assertThat(result.body()).isEqualTo("This is a plain text email.");
        assertThat(result.rawHeaders()).containsKey("Message-Id");
        assertThat(result.receivedAt()).isNotNull();
    }

    @Test
    void parsesMultipartEmailPreferringPlainText() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.com"));
        message.setSubject("Multipart Test");

        MimeBodyPart plainPart = new MimeBodyPart();
        plainPart.setText("Plain text content", "UTF-8");

        MimeBodyPart htmlPart = new MimeBodyPart();
        htmlPart.setContent("<html><body><b>HTML content</b></body></html>", "text/html; charset=UTF-8");

        MimeMultipart multipart = new MimeMultipart("alternative");
        multipart.addBodyPart(plainPart);
        multipart.addBodyPart(htmlPart);
        message.setContent(multipart);

        ParsedEmail result = parser.parse(message);

        assertThat(result.body()).isEqualTo("Plain text content");
    }

    @Test
    void parsesHtmlOnlyEmailAndStripsTags() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.com"));
        message.setSubject("HTML Only");
        message.setContent("<html><body><h1>Title</h1><p>Paragraph text</p></body></html>",
                "text/html; charset=UTF-8");
        message.saveChanges();

        ParsedEmail result = parser.parse(message);

        assertThat(result.body()).doesNotContain("<", ">");
        assertThat(result.body()).contains("Title");
        assertThat(result.body()).contains("Paragraph text");
    }

    @Test
    void handlesMissingSubjectGracefully() throws Exception {
        MimeMessage message = new MimeMessage(session);
        message.setFrom(new InternetAddress("sender@example.com"));
        message.setRecipient(Message.RecipientType.TO, new InternetAddress("recipient@example.com"));
        message.setText("Body without subject", "UTF-8");
        // deliberately no setSubject()

        ParsedEmail result = parser.parse(message);

        assertThat(result.subject()).isEqualTo("");
        assertThat(result.body()).isEqualTo("Body without subject");
    }
}
