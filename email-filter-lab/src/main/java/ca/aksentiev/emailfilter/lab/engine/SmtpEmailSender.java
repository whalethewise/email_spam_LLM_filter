package ca.aksentiev.emailfilter.lab.engine;

import ca.aksentiev.emailfilter.lab.config.LabProperties;
import jakarta.mail.MessagingException;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.mail.javamail.MimeMessageHelper;
import org.springframework.stereotype.Component;

/**
 * Sends outbound email via Spring's {@link JavaMailSender}.
 */
@Component
public class SmtpEmailSender {

    private static final Logger log = LoggerFactory.getLogger(SmtpEmailSender.class);

    private final JavaMailSender mailSender;
    private final LabProperties labProperties;

    public SmtpEmailSender(JavaMailSender mailSender, LabProperties labProperties) {
        this.mailSender = mailSender;
        this.labProperties = labProperties;
    }

    public void send(String to, String subject, String body) {
        try {
            MimeMessage message = mailSender.createMimeMessage();
            MimeMessageHelper helper = new MimeMessageHelper(message, false, "UTF-8");
            helper.setFrom(labProperties.getSmtp().fromAddress(), labProperties.getSmtp().fromName());
            helper.setTo(to);
            helper.setSubject(subject);
            helper.setText(body, false);
            mailSender.send(message);
            log.info("Email sent to '{}': {}", to, subject);
        } catch (MessagingException | java.io.UnsupportedEncodingException e) {
            log.error("Failed to send email to '{}': {}", to, e.getMessage());
        }
    }
}
