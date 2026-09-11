package ca.aksentiev.emailfilter.lab.tuning;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import jakarta.mail.Flags;
import jakarta.mail.Folder;
import jakarta.mail.Message;
import jakarta.mail.MessagingException;
import jakarta.mail.Store;
import jakarta.mail.internet.MimeMessage;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

/**
 * Executes a reshuffle plan: copies each message with its rewritten subject
 * into its destination folder (which may be the review folder itself, to
 * rewrite the tag in place) and deletes the original. Only called when not
 * in dry-run mode.
 */
@Component
public class ReshuffleExecutor {

    private static final Logger log = LoggerFactory.getLogger(ReshuffleExecutor.class);

    public void execute(List<ReshufflePlan> plan, Store store, Folder sourceFolder) throws MessagingException {
        Map<String, Folder> otherDestFolders = new HashMap<>();
        int succeeded = 0;

        try {
            for (int i = 0; i < plan.size(); i++) {
                ReshufflePlan p = plan.get(i);
                try {
                    if (!(p.email().rawMessage() instanceof MimeMessage original)) {
                        log.warn("[LIVE] [{}/{}] No raw message available, skipping: \"{}\"",
                                i + 1, plan.size(), truncate(p.originalSubject(), 50));
                        continue;
                    }

                    Folder destFolder = p.destination().equals(sourceFolder.getFullName())
                            ? sourceFolder
                            : otherDestFolders.computeIfAbsent(p.destination(), name -> openDestination(store, name));

                    MimeMessage copy = new MimeMessage(original);
                    copy.setSubject(p.newSubject(), "UTF-8");
                    copy.saveChanges();

                    destFolder.appendMessages(new Message[]{copy});
                    original.setFlag(Flags.Flag.DELETED, true);
                    succeeded++;

                    log.info("[LIVE] [{}/{}] \"{}\" → {} ✓",
                            i + 1, plan.size(), truncate(p.newSubject(), 50), p.destination());
                } catch (Exception e) {
                    log.error("[LIVE] [{}/{}] Failed to process \"{}\": {}",
                            i + 1, plan.size(), truncate(p.originalSubject(), 50), e.getMessage());
                }
            }
        } finally {
            for (Folder folder : otherDestFolders.values()) {
                try {
                    folder.close(false);
                } catch (MessagingException e) {
                    log.warn("Failed to close folder {}: {}", folder.getName(), e.getMessage());
                }
            }
        }

        log.info("[LIVE] Expunging {}...", sourceFolder.getFullName());
        sourceFolder.close(true);

        log.info("[LIVE] Done. {}/{} emails reshuffled.", succeeded, plan.size());
    }

    private Folder openDestination(Store store, String name) {
        try {
            Folder f = store.getFolder(name);
            if (!f.exists()) {
                throw new IllegalStateException("Folder does not exist: " + name);
            }
            f.open(Folder.READ_WRITE);
            return f;
        } catch (MessagingException e) {
            throw new IllegalStateException("Failed to open folder: " + name, e);
        }
    }

    private String truncate(String s, int max) {
        if (s == null) {
            return "";
        }
        return s.length() <= max ? s : s.substring(0, max - 1) + "…";
    }
}
