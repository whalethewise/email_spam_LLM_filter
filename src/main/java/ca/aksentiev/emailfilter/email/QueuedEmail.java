package ca.aksentiev.emailfilter.email;

import ca.aksentiev.emailfilter.config.AccountProperties;
import ca.aksentiev.emailfilter.filter.EmailMessage;

/**
 * An email paired with the account context it arrived on.
 * This is the unit of work placed on the processing queue.
 *
 * @param message      the parsed email message
 * @param account      the account configuration the email arrived on
 * @param forceDryRun  if true, forces dry-run processing regardless of global setting
 *                     (used by scan to ensure existing emails are never moved or deleted)
 */
public record QueuedEmail(EmailMessage message, AccountProperties.Account account, boolean forceDryRun) {

    /**
     * Creates a queue item with forceDryRun defaulting to false (normal IDLE processing).
     */
    public QueuedEmail(EmailMessage message, AccountProperties.Account account) {
        this(message, account, false);
    }
}
