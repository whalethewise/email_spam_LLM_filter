package ca.aksentiev.emailfilter.lab.engine;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes a single resolved action against an IMAP message.
 * In dry-run mode, logs the action without performing it.
 */
@Component
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final SmtpEmailSender smtpEmailSender;

    public ActionExecutor(SmtpEmailSender smtpEmailSender) {
        this.smtpEmailSender = smtpEmailSender;
    }

    public void execute(ResolvedAction action, Message message, Store store, boolean dryRun) {
        if (action == null || action.type() == ActionType.LEAVE) {
            return;
        }

        String prefix = dryRun ? "[DRY-RUN]" : "[LIVE]";

        try {
            switch (action.type()) {
                case FLAG -> {
                    log.info("{} FLAG message", prefix);
                    if (!dryRun) {
                        message.setFlag(Flags.Flag.FLAGGED, true);
                    }
                }
                case MOVE_TO_JUNK -> {
                    log.info("{} MOVE to Junk", prefix);
                    if (!dryRun) {
                        moveToFolder(message, store, "Junk");
                    }
                }
                case MOVE_TO_FOLDER -> {
                    log.info("{} MOVE to '{}'", prefix, action.targetFolder());
                    if (!dryRun) {
                        moveToFolder(message, store, action.targetFolder());
                    }
                }
                case DELETE -> {
                    log.info("{} DELETE message", prefix);
                    if (!dryRun) {
                        message.setFlag(Flags.Flag.DELETED, true);
                        message.getFolder().expunge();
                    }
                }
                case SEND_EMAIL -> {
                    log.info("{} SEND email to '{}': {}", prefix, action.emailTo(), action.emailSubject());
                    if (!dryRun) {
                        smtpEmailSender.send(action.emailTo(), action.emailSubject(), action.emailBody());
                    }
                }
                default -> log.debug("{} No-op action: {}", prefix, action.type());
            }
        } catch (MessagingException e) {
            log.error("{} Action {} failed: {}", prefix, action.type(), e.getMessage());
        }
    }

    private void moveToFolder(Message message, Store store, String folderName) throws MessagingException {
        Folder targetFolder = store.getFolder(folderName);
        if (!targetFolder.exists()) {
            targetFolder.create(Folder.HOLDS_MESSAGES);
        }
        targetFolder.open(Folder.READ_WRITE);
        try {
            Folder sourceFolder = message.getFolder();
            sourceFolder.copyMessages(new Message[]{message}, targetFolder);
            message.setFlag(Flags.Flag.DELETED, true);
            sourceFolder.expunge();
        } finally {
            targetFolder.close(false);
        }
    }
}
