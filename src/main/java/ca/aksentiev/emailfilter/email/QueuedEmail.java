package ca.aksentiev.emailfilter.email;

import ca.aksentiev.emailfilter.config.AccountProperties;
import ca.aksentiev.emailfilter.filter.EmailMessage;

/**
 * An email paired with the account context it arrived on.
 * This is the unit of work placed on the processing queue.
 *
 * @param message the parsed email message
 * @param account the account configuration the email arrived on
 */
public record QueuedEmail(EmailMessage message, AccountProperties.Account account) {}
