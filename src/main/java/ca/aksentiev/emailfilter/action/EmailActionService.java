package ca.aksentiev.emailfilter.action;

import java.time.Instant;

import ca.aksentiev.emailfilter.config.AccountProperties;
import ca.aksentiev.emailfilter.config.DryRunProperties;
import ca.aksentiev.emailfilter.config.SpamFilterProperties;
import ca.aksentiev.emailfilter.email.parser.ParsedEmail;
import ca.aksentiev.emailfilter.filter.EmailMessage;
import ca.aksentiev.emailfilter.scoring.ScoreCategory;
import ca.aksentiev.emailfilter.scoring.ScoreResult;
import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
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

    public EmailActionService(
            SpamFilterProperties spamFilterProperties,
            DryRunProperties dryRunProperties,
            SubjectTagger subjectTagger,
            AuditService auditService,
            DryRunReportService dryRunReportService) {
        this.spamFilterProperties = spamFilterProperties;
        this.dryRunProperties = dryRunProperties;
        this.subjectTagger = subjectTagger;
        this.auditService = auditService;
        this.dryRunReportService = dryRunReportService;
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
        try {
            addXHeaders(message, score, action);

            switch (action) {
                case "leave", "none" -> {
                    log.debug("Leaving email '{}' in inbox", email.subject());
                }
                case "move-to-review" -> {
                    rewriteSubject(message, email.subject(), score);
                    moveMessage(message, account.getFolders().review());
                    log.info("Moved email '{}' to review folder", email.subject());
                }
                case "move-to-junk" -> {
                    rewriteSubject(message, email.subject(), score);
                    moveMessage(message, account.getFolders().junk());
                    log.info("Moved email '{}' to junk folder", email.subject());
                }
                case "delete" -> {
                    deleteMessage(message);
                    log.info("Deleted email '{}'", email.subject());
                }
                case "flag" -> {
                    flagMessage(message);
                    log.info("Flagged email '{}'", email.subject());
                }
                default -> log.warn("Unknown action '{}' for email '{}'", action, email.subject());
            }
        } catch (MessagingException e) {
            log.error("Failed to execute action '{}' on email '{}': {}", action, email.subject(), e.getMessage(), e);
        }
    }

    void addXHeaders(Message message, ScoreResult score, String action) throws MessagingException {
        message.setHeader("X-EmailFilter-Score", String.valueOf(Math.round(score.finalScore())));
        message.setHeader("X-EmailFilter-Category", score.category().name());
        message.setHeader("X-EmailFilter-LLM-Reason", score.llmReason());
        message.setHeader("X-EmailFilter-Action", action);
        message.setHeader("X-EmailFilter-Filter", "spam-filter");
        message.setHeader("X-EmailFilter-Processed", Instant.now().toString());
    }

    void rewriteSubject(Message message, String originalSubject, ScoreResult score) throws MessagingException {
        String tagged = subjectTagger.tag(originalSubject, score);
        message.setSubject(tagged);
    }

    void moveMessage(Message message, String targetFolderName) throws MessagingException {
        Folder sourceFolder = message.getFolder();
        Store store = sourceFolder.getStore();
        Folder targetFolder = store.getFolder(targetFolderName);

        if (!targetFolder.exists()) {
            targetFolder.create(Folder.HOLDS_MESSAGES);
        }
        if (!targetFolder.isOpen()) {
            targetFolder.open(Folder.READ_WRITE);
        }

        sourceFolder.copyMessages(new Message[] {message}, targetFolder);
        message.setFlag(Flags.Flag.DELETED, true);
        sourceFolder.expunge();

        if (targetFolder.isOpen()) {
            targetFolder.close(false);
        }
    }

    void deleteMessage(Message message) throws MessagingException {
        message.setFlag(Flags.Flag.DELETED, true);
        message.getFolder().expunge();
    }

    void flagMessage(Message message) throws MessagingException {
        message.setFlag(Flags.Flag.FLAGGED, true);
    }

    private String buildReason(ScoreResult score) {
        if (score.llmReason() != null && !score.llmReason().isEmpty()) {
            return score.llmReason();
        }
        return "Score " + score.finalScore() + " → " + score.category();
    }
}
