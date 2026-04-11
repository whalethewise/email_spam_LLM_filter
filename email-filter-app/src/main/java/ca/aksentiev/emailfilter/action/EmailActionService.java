package ca.aksentiev.emailfilter.action;

import java.time.Instant;

import ca.aksentiev.emailfilter.config.AccountProperties;
import ca.aksentiev.emailfilter.config.DryRunProperties;
import ca.aksentiev.emailfilter.config.SpamFilterProperties;
import ca.aksentiev.emailfilter.email.imap.ImapConnectionFactory;
import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import ca.aksentiev.emailfilter.filter.EmailMessage;
import ca.aksentiev.emailfilter.scoring.ScoreCategory;
import ca.aksentiev.emailfilter.scoring.ScoreResult;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.FolderClosedException;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import jakarta.mail.search.MessageIDTerm;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;

/**
 * Executes email actions (move, delete, flag, tag) based on scoring results.
 * Respects dry-run mode — records decisions without executing when enabled.
 * Adds X-headers to every processed email for auditing.
 */
@Service
public class EmailActionService {

    private static final Logger log = LoggerFactory.getLogger(EmailActionService.class);

    private final SpamFilterProperties spamFilterProperties;
    private final DryRunProperties dryRunProperties;
    private final SubjectTagger subjectTagger;
    private final AuditService auditService;
    private final DryRunReportService dryRunReportService;
    private final ImapConnectionFactory connectionFactory;

    public EmailActionService(
            SpamFilterProperties spamFilterProperties,
            DryRunProperties dryRunProperties,
            SubjectTagger subjectTagger,
            AuditService auditService,
            DryRunReportService dryRunReportService,
            ImapConnectionFactory connectionFactory) {
        this.spamFilterProperties = spamFilterProperties;
        this.dryRunProperties = dryRunProperties;
        this.subjectTagger = subjectTagger;
        this.auditService = auditService;
        this.dryRunReportService = dryRunReportService;
        this.connectionFactory = connectionFactory;
    }

    /**
     * Executes the appropriate action on an email based on its score category.
     *
     * @param email   the parsed email
     * @param score   the scoring result
     * @param message the raw IMAP message (for IMAP operations)
     * @param account the account configuration (for folder paths)
     */
    public void execute(ParsedEmail email, ScoreResult score, Message message, AccountProperties.Account account) {
        String action = resolveAction(score.category());

        if (dryRunProperties.enabled()) {
            handleDryRun(email, score, action);
        } else {
            handleLive(email, score, message, account, action);
        }

        auditService.record(email, score, action, dryRunProperties.enabled());
    }

    /**
     * Executes the appropriate action using an EmailMessage and FilterResult metadata.
     * Respects forceDryRun flag from scan processing.
     *
     * @param emailMessage the filter-layer email representation
     * @param score        the scoring result
     * @param account      the account configuration
     * @param forceDryRun  if true, forces dry-run regardless of global setting
     */
    public void execute(EmailMessage emailMessage, ScoreResult score, AccountProperties.Account account, boolean forceDryRun) {
        String action = resolveAction(score.category());
        ParsedEmail email = toParsedEmail(emailMessage);

        if (dryRunProperties.enabled() || forceDryRun) {
            handleDryRun(email, score, action);
        } else {
            handleLive(email, score, emailMessage.rawMessage(), account, action);
        }

        auditService.record(email, score, action, dryRunProperties.enabled() || forceDryRun);
    }

    private ParsedEmail toParsedEmail(EmailMessage msg) {
        return new ParsedEmail(
                msg.messageId(), msg.subject(), msg.from(), msg.fromName(),
                msg.to(), msg.bodyText(), msg.headers(), Instant.now());
    }

    /**
     * Resolves the action string for a given score category using SpamFilterProperties.
     */
    String resolveAction(ScoreCategory category) {
        SpamFilterProperties.Actions actions = spamFilterProperties.getActions();
        return switch (category) {
            case SAFE -> actions.safe();
            case REVIEW -> actions.review();
            case SPAM -> actions.spam();
        };
    }

    private void handleDryRun(ParsedEmail email, ScoreResult score, String action) {
        String taggedSubject = subjectTagger.tag(email.subject(), score);
        String reason = buildReason(score);

        log.info("DRY RUN: would [{}] email [{}]", action, taggedSubject);
        dryRunReportService.recordDecision(email, score, action, reason);
    }

    private void handleLive(
            ParsedEmail email,
            ScoreResult score,
            Message message,
            AccountProperties.Account account,
            String action) {
        if ("leave".equals(action) || "none".equals(action)) {
            log.debug("Leaving email '{}' in inbox", email.subject());
            return;
        }

        Store store = null;
        Folder inbox = null;
        try {
            store = connectionFactory.connect(account);
            inbox = store.getFolder(account.getFolders().inbox());
            inbox.open(Folder.READ_WRITE);

            Message found = findByMessageId(inbox, email.messageId());
            if (found == null) {
                log.warn("Could not find email '{}' (Message-ID: {}) in inbox for action '{}'",
                        email.subject(), email.messageId(), action);
                return;
            }

            switch (action) {
                case "move-to-review" -> {
                    String tagged = subjectTagger.tag(email.subject(), score);
                    moveMessage(found, inbox, store, account.getFolders().review(), tagged, score, action);
                    log.info("Moved email '{}' to review folder", email.subject());
                }
                case "move-to-junk" -> {
                    String tagged = subjectTagger.tag(email.subject(), score);
                    moveMessage(found, inbox, store, account.getFolders().junk(), tagged, score, action);
                    log.info("Moved email '{}' to junk folder", email.subject());
                }
                case "delete" -> {
                    found.setFlag(Flags.Flag.DELETED, true);
                    inbox.expunge();
                    log.info("Deleted email '{}'", email.subject());
                }
                case "flag" -> {
                    found.setFlag(Flags.Flag.FLAGGED, true);
                    log.info("Flagged email '{}'", email.subject());
                }
                default -> log.warn("Unknown action '{}' for email '{}'", action, email.subject());
            }
        } catch (MessagingException e) {
            log.error("Failed to execute action '{}' on email '{}': {}", action, email.subject(), e.getMessage(), e);
        } finally {
            if (inbox != null && inbox.isOpen()) {
                try { inbox.close(false); } catch (MessagingException e) {
                    log.debug("Error closing IMAP folder: {}", e.getMessage());
                }
            }
            if (store != null) {
                try { store.close(); } catch (MessagingException e) {
                    log.debug("Error closing IMAP store: {}", e.getMessage());
                }
            }
        }
    }

    private Message findByMessageId(Folder folder, String messageId) throws MessagingException {
        if (messageId == null || messageId.isBlank()) {
            return null;
        }
        Message[] found = folder.search(new MessageIDTerm(messageId));
        return found.length > 0 ? found[0] : null;
    }

    /**
     * Creates a mutable copy with modified subject and X-headers,
     * appends it to the target folder, and deletes the original.
     */
    void moveMessage(Message original, Folder sourceFolder, Store store, String targetFolderName,
                     String newSubject, ScoreResult score, String action) throws MessagingException {
        MimeMessage modified = new MimeMessage((MimeMessage) original);
        modified.setSubject(newSubject);
        modified.setHeader("X-EmailFilter-Score", String.valueOf(Math.round(score.finalScore())));
        modified.setHeader("X-EmailFilter-Category", score.category().name());
        modified.setHeader("X-EmailFilter-LLM-Reason", sanitizeHeaderValue(score.llmReason()));
        modified.setHeader("X-EmailFilter-Action", action);
        modified.setHeader("X-EmailFilter-Filter", "spam-filter");
        modified.setHeader("X-EmailFilter-Processed", Instant.now().toString());
        modified.saveChanges();

        Folder targetFolder = store.getFolder(targetFolderName);
        if (!targetFolder.exists()) {
            boolean created = targetFolder.create(Folder.HOLDS_MESSAGES);
            log.info("Created IMAP folder '{}': {}", targetFolderName, created);
            targetFolder.setSubscribed(true);
        }
        targetFolder.open(Folder.READ_WRITE);

        try {
            targetFolder.appendMessages(new Message[] {modified});
            original.setFlag(Flags.Flag.DELETED, true);
            sourceFolder.expunge();
        } finally {
            if (targetFolder.isOpen()) {
                targetFolder.close(false);
            }
        }
    }

    private String sanitizeHeaderValue(String value) {
        if (value == null) {
            return "";
        }
        return value.replaceAll("[\\r\\n]", " ");
    }

    private String buildReason(ScoreResult score) {
        if (score.llmReason() != null && !score.llmReason().isEmpty()) {
            return score.llmReason();
        }
        return "Score " + score.finalScore() + " → " + score.category();
    }
}
