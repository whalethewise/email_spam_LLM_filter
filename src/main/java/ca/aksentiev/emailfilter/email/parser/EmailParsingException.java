package ca.aksentiev.emailfilter.email.parser;

/**
 * Wraps checked exceptions (e.g. {@link jakarta.mail.MessagingException})
 * that occur during email parsing into an unchecked exception.
 */
public class EmailParsingException extends RuntimeException {

    public EmailParsingException(String message, Throwable cause) {
        super(message, cause);
    }
}
