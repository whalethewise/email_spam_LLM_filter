package ca.aksentiev.emailfilter.lab.engine;

import java.util.List;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes IMAP actions described by filter results.
 * In dry-run mode, logs actions without executing them.
 */
@Component
public class ActionExecutor {

    private static final Logger log = LoggerFactory.getLogger(ActionExecutor.class);

    private final SmtpEmailSender smtpEmailSender;

    public ActionExecutor(SmtpEmailSender smtpEmailSender) {
        this.smtpEmailSender = smtpEmailSender;
    }

    public void execute(FilterResult result, Message message, Store store, boolean dryRun) {
        if (result.action() == ActionType.LEAVE) {
            return;
        }

        String prefix = dryRun ? "[DRY-RUN]" : "[LIVE]";

        try {
            switch (result.action()) {
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
                    String folder = result.targetFolder();
                    log.info("{} MOVE to '{}'", prefix, folder);
                    if (!dryRun) {
                        moveToFolder(message, store, folder);
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
                    log.info("{} SEND email to '{}': {}", prefix, result.emailTo(), result.emailSubject());
                    if (!dryRun) {
                        smtpEmailSender.send(result.emailTo(), result.emailSubject(), result.emailBody());
                    }
                }
                default -> log.debug("{} No action for type {}", prefix, result.action());
            }
        } catch (MessagingException e) {
            log.error("{} Action failed for {}: {}", prefix, result.action(), e.getMessage());
        }
    }

    public void executeAll(List<ActionDefinition> actions, FilterResult result,
                           Message message, Store store, boolean dryRun,
                           VariableResolver resolver, java.util.Map<String, String> vars) {
        String prefix = dryRun ? "[DRY-RUN]" : "[LIVE]";
        for (ActionDefinition action : actions) {
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
                        String folder = resolver.resolve(action.folder(), vars);
                        log.info("{} MOVE to '{}'", prefix, folder);
                        if (!dryRun) {
                            moveToFolder(message, store, folder);
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
                        String to = resolver.resolve(action.to(), vars);
                        String subject = resolver.resolve(action.subject(), vars);
                        String body = resolver.resolve(action.body(), vars);
                        log.info("{} SEND email to '{}': {}", prefix, to, subject);
                        if (!dryRun) {
                            smtpEmailSender.send(to, subject, body);
                        }
                    }
                    default -> log.debug("{} No action for type {}", prefix, action.type());
                }
            } catch (MessagingException e) {
                log.error("{} Action {} failed: {}", prefix, action.type(), e.getMessage());
            }
        }
    }

    private void moveToFolder(Message message, Store store, String folderName) throws MessagingException {
        Folder targetFolder = store.getFolder(folderName);
        if (!targetFolder.exists()) {
            targetFolder.create(Folder.HOLDS_MESSAGES);
        }
        targetFolder.open(Folder.READ_WRITE);
        Folder sourceFolder = message.getFolder();
        sourceFolder.copyMessages(new Message[]{message}, targetFolder);
        message.setFlag(Flags.Flag.DELETED, true);
        sourceFolder.expunge();
        targetFolder.close(false);
    }
}
